package com.abdelrhman.webguard.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.abdelrhman.webguard.MainActivity;
import com.abdelrhman.webguard.core.DomainBlocker;
import com.wgtunnel.hevtunnel.TProxyService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FilterVpnService extends VpnService {
    private static final String CHANNEL_ID = "webguard_protection";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_STOP = "com.abdelrhman.webguard.action.STOP_PROTECTION";

    private volatile boolean running;
    private ParcelFileDescriptor vpnInterface;
    private Socks5ProxyServer proxy;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putBoolean("enabled", false).apply();
            clearOwnerPoliciesForRecovery();
            stopTunnel();
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!running) {
            startTunnel();
        }

        return START_STICKY;
    }

    private synchronized void startTunnel() {
        if (running) return;

        Thread starter = new Thread(() -> {
            try {
                DomainBlocker.load(this);

                proxy = new Socks5ProxyServer(this, this);
                proxy.start();

                // Test the protected upstream before taking over the phone's
                // default route. If it fails, exit without black-holing traffic.
                proxy.verifyUpstream();

                VpnService.Builder builder = new VpnService.Builder()
                        .setSession("حارس الويب")
                        .setMtu(1500)
                        .addAddress("198.18.0.1", 24)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("198.18.0.2");

                if (Build.VERSION.SDK_INT >= 21) {
                    builder.addAddress("fc00::1", 64)
                           .addRoute("::", 0);
                }

                vpnInterface = builder.establish();

                if (vpnInterface == null) {
                    throw new IllegalStateException("تعذر إنشاء واجهة VPN");
                }

                File config = writeHevConfig();
                boolean started = TProxyService.TProxyStartService(
                        config.getAbsolutePath(),
                        vpnInterface.getFd());

                if (!started) {
                    throw new IllegalStateException("تعذر تشغيل محرك الشبكة");
                }

                // The JNI call reports thread creation, not tunnel readiness.
                // Check that the native worker survived configuration parsing.
                Thread.sleep(900);
                if (!TProxyService.TProxyIsRunning()) {
                    throw new IllegalStateException("توقف محرك الشبكة أثناء البدء");
                }

                // A live native worker does not prove that packets are being
                // forwarded. Test a real app-owned TCP connection through the
                // newly established VPN before enabling any owner policies.
                verifyTrafficThroughTunnel();

                running = true;
                applyOwnerPoliciesAfterTunnelIsHealthy();
                startHealthMonitor();
            } catch (Throwable error) {
                running = false;
                getSharedPreferences("settings", MODE_PRIVATE)
                        .edit().putBoolean("enabled", false)
                        .putString("last_error", error.getMessage() == null
                                ? "تعذر بدء نفق الشبكة" : error.getMessage())
                        .apply();
                stopTunnel();
                clearOwnerPoliciesForRecovery();
                stopForeground(true);
                stopSelf();
            }
        }, "WebGuard-Tunnel");

        starter.setDaemon(true);
        starter.start();
    }

    private void verifyTrafficThroughTunnel() throws IOException {
        IOException last = null;
        String[] targets = {"1.1.1.1", "8.8.8.8", "9.9.9.9"};

        for (String target : targets) {
            try (Socket probe = new Socket()) {
                // Intentionally do NOT call VpnService.protect(): this probe
                // must travel through the TUN -> Hev -> local SOCKS path.
                probe.setTcpNoDelay(true);
                probe.connect(new InetSocketAddress(
                        InetAddress.getByName(target), 443), 2500);
                return;
            } catch (IOException error) {
                last = error;
            }
        }

        throw new IOException(
                "اختبار مرور الإنترنت عبر VPN فشل؛ أوقفت الحماية لتجنب قطع اتصال الهاتف",
                last);
    }

    private void applyOwnerPoliciesAfterTunnelIsHealthy() {
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager)
                        getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;

        android.content.ComponentName admin = new android.content.ComponentName(
                this, com.abdelrhman.webguard.admin.DeviceAdminReceiverImpl.class);

        try {
            // Keep Always-on restart behavior, but never enable Lockdown:
            // a tunnel problem must not turn into a device-wide internet outage.
            dpm.setAlwaysOnVpnPackage(admin, getPackageName(), false);
        } catch (Exception e) {
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("last_error",
                            "تعذر تفعيل Always-on VPN: " + e.getMessage()).apply();
        }

        try { dpm.setUninstallBlocked(admin, getPackageName(), true); }
        catch (Exception ignored) {}
        try { dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CONFIG_VPN); }
        catch (Exception ignored) {}
        try { dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS); }
        catch (Exception ignored) {}
    }

    /**
     * Fail-open recovery: never leave an always-on Lockdown pointing at a
     * tunnel that stopped working. Unblock the app and remove restrictions so
     * the device owner can recover the phone without a factory reset.
     */
    private void clearOwnerPoliciesForRecovery() {
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager)
                        getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;

        android.content.ComponentName admin = new android.content.ComponentName(
                this, com.abdelrhman.webguard.admin.DeviceAdminReceiverImpl.class);

        try { dpm.setAlwaysOnVpnPackage(admin, null, false); }
        catch (Exception ignored) {}
        try { dpm.setUninstallBlocked(admin, getPackageName(), false); }
        catch (Exception ignored) {}

        String[] restrictions = {
                android.os.UserManager.DISALLOW_CONFIG_VPN,
                android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                android.os.UserManager.DISALLOW_APPS_CONTROL,
                android.os.UserManager.DISALLOW_SAFE_BOOT,
                android.os.UserManager.DISALLOW_FACTORY_RESET,
                android.os.UserManager.DISALLOW_ADD_USER,
                android.os.UserManager.DISALLOW_USER_SWITCH
        };
        for (String restriction : restrictions) {
            try { dpm.clearUserRestriction(admin, restriction); }
            catch (Exception ignored) {}
        }
    }

    private void startHealthMonitor() {
        Thread monitor = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (!TProxyService.TProxyIsRunning()) {
                    running = false;
                    getSharedPreferences("settings", MODE_PRIVATE)
                            .edit().putBoolean("enabled", false)
                            .putString("last_error", "توقف محرك الشبكة؛ تم إيقاف VPN لاستعادة الإنترنت")
                            .apply();
                    stopTunnel();
                    clearOwnerPoliciesForRecovery();
                    stopForeground(true);
                    stopSelf();
                    return;
                }
            }
        }, "WebGuard-Tunnel-Monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private File writeHevConfig() throws Exception {
        String configText =
                "misc:\n" +
                "  task-stack-size: 24576\n" +
                "  log-level: 'error'\n" +
                "tunnel:\n" +
                "  mtu: 1500\n" +
                "  ipv4: '198.18.0.1'\n" +
                "  ipv6: 'fc00::1'\n" +
                "socks5:\n" +
                "  address: '127.0.0.1'\n" +
                "  port: 1080\n" +
                "  username: ''\n" +
                "  password: ''\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: '198.18.0.2'\n" +
                "  port: 53\n" +
                "  network: '100.64.0.0'\n" +
                "  netmask: '255.192.0.0'\n" +
                "  cache-size: 10000\n";

        File file = new File(getFilesDir(), "hev-tunnel.yml");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(configText.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        return file;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "حماية حارس الويب",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT |
                        PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("حارس الويب")
                .setContentText("الحماية مفعلة")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build();
    }

    private synchronized void stopTunnel() {
        running = false;

        try {
            TProxyService.TProxyStopService();
        } catch (Throwable ignored) {
        }

        try {
            if (proxy != null) {
                proxy.stop();
            }
        } catch (Throwable ignored) {
        }

        proxy = null;

        try {
            if (vpnInterface != null) {
                vpnInterface.close();
            }
        } catch (Throwable ignored) {
        }

        vpnInterface = null;
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }
}
