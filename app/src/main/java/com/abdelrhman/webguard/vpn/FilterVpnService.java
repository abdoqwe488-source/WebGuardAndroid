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
import java.nio.charset.StandardCharsets;

public class FilterVpnService extends VpnService {
    private static final String CHANNEL_ID = "webguard_protection";
    private static final int NOTIFICATION_ID = 1001;

    private volatile boolean running;
    private ParcelFileDescriptor vpnInterface;
    private Socks5ProxyServer proxy;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

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

                running = true;
            } catch (Throwable error) {
                running = false;
                stopTunnel();
            }
        }, "WebGuard-Tunnel");

        starter.setDaemon(true);
        starter.start();
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
                "  network: '240.0.0.0'\n" +
                "  netmask: '240.0.0.0'\n" +
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
