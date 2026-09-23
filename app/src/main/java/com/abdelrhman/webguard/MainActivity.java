package com.abdelrhman.webguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.abdelrhman.webguard.admin.DeviceAdminReceiverImpl;
import com.abdelrhman.webguard.core.DomainBlocker;
import com.abdelrhman.webguard.vpn.BlockLog;
import com.abdelrhman.webguard.vpn.FilterVpnService;

import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 42;

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private android.content.SharedPreferences prefs;

    private TextView status;
    private TextView stats;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, DeviceAdminReceiverImpl.class);

        DomainBlocker.load(this);
        buildUi();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private TextView text(String value, float size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setPadding(16, 10, 16, 10);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 24, 20, 20);

        TextView title = text("🛡 حارس الويب", 28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = text(
                "حماية الهاتف من المواقع الإباحية عبر VPN محلي وقائمة نطاقات محدثة",
                16);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        startButton = button("تشغيل البرنامج");
        startButton.setTextSize(20);
        root.addView(startButton);

        stopButton = button("إيقاف الحماية");
        stopButton.setTextSize(18);
        root.addView(stopButton);

        status = text("", 16);
        root.addView(status);

        stats = text("", 14);
        root.addView(stats);

        Button accessibility = button("تفعيل حذف الرابط المحظور أثناء كتابته");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button owner = button("إعداد الحماية القوية (Device Owner)");
        owner.setOnClickListener(v -> showDeviceOwnerInstructions());
        root.addView(owner);

        Button update = button("تحديث قائمة المواقع المحظورة");
        update.setOnClickListener(v -> {
            update.setEnabled(false);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    String message = DomainBlocker.update(this);
                    runOnUiThread(() -> {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        update.setEnabled(true);
                        refreshUi();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(
                                this,
                                "فشل تحديث القائمة: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        update.setEnabled(true);
                    });
                }
            });
        });
        root.addView(update);

        TextView note = text(
                "في وضع Device Owner يستطيع النظام فرض Always-on VPN مع Lockdown ومنع تغيير إعدادات VPN وإلغاء تثبيت التطبيق. " +
                "بدون Device Owner تبقى القيود التي يفرضها نظام أندرويد على تطبيقات VPN العادية.",
                13);
        root.addView(note);

        setContentView(root);

        startButton.setOnClickListener(v -> enableProtection());
        stopButton.setOnClickListener(v -> disableProtection());
    }

    private void enableProtection() {
        try {
            prefs.edit().putBoolean("enabled", true).apply();

            if (isDeviceOwner()) {
                applyStrongOwnerPolicies();
                startVpnService();
                return;
            }

            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, VPN_REQUEST);
                return;
            }

            startVpnService();
        } catch (Exception e) {
            prefs.edit().putBoolean("enabled", false).apply();
            refreshUi();
            Toast.makeText(this, "تعذر تشغيل الحماية: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpnService();
        } else if (requestCode == VPN_REQUEST) {
            prefs.edit().putBoolean("enabled", false).apply();
            refreshUi();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, FilterVpnService.class);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, "تم تشغيل الحماية", Toast.LENGTH_SHORT).show();
        refreshUi();
    }

    private void disableProtection() {
        if (isDeviceOwner()) {
            new AlertDialog.Builder(this)
                    .setTitle("الحماية الإدارية مفعلة")
                    .setMessage(
                            "هذا الجهاز في وضع Device Owner. إيقاف الحماية من داخل التطبيق غير مسموح في الوضع المقيد. " +
                            "استخدم حساب الإدارة/إجراء إزالة Device Owner الموثق إذا أردت استعادة التحكم الكامل بالجهاز.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }

        prefs.edit().putBoolean("enabled", false).apply();
        stopService(new Intent(this, FilterVpnService.class));
        refreshUi();
        Toast.makeText(this, "تم إيقاف الحماية", Toast.LENGTH_SHORT).show();
    }

    private boolean isDeviceOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private void applyStrongOwnerPolicies() {
        if (!isDeviceOwner()) return;

        dpm.setAlwaysOnVpnPackage(admin, getPackageName(), true);
        dpm.setUninstallBlocked(admin, getPackageName(), true);

        addRestriction(UserManager.DISALLOW_CONFIG_VPN);
        addRestriction(UserManager.DISALLOW_APPS_CONTROL);
        addRestriction(UserManager.DISALLOW_SAFE_BOOT);
        addRestriction(UserManager.DISALLOW_FACTORY_RESET);

        if (Build.VERSION.SDK_INT >= 24) {
            addRestriction(UserManager.DISALLOW_ADD_USER);
            addRestriction(UserManager.DISALLOW_USER_SWITCH);
        }

        if (Build.VERSION.SDK_INT >= 29) {
            addRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS);
        }
    }

    private void addRestriction(String restriction) {
        try {
            dpm.addUserRestriction(admin, restriction);
        } catch (Exception ignored) {
        }
    }

    private void showDeviceOwnerInstructions() {
        String command =
                "adb shell dpm set-device-owner " +
                getPackageName() + "/" +
                DeviceAdminReceiverImpl.class.getName();

        String message =
                "للحصول على أقوى وضع حماية:

" +
                "1) ثبّت التطبيق على جهاز مخصص للحماية أو جهاز مؤهل لـ Device Owner.
" +
                "2) فعّل USB debugging.
" +
                "3) من الكمبيوتر نفّذ الأمر:

" +
                command +
                "

" +
                "بعد نجاح Device Owner اضغط «تشغيل البرنامج» ليتم تفعيل Always-on VPN + Lockdown ومنع إعدادات VPN وإلغاء تثبيت التطبيق.";

        new AlertDialog.Builder(this)
                .setTitle("Device Owner")
                .setMessage(message)
                .setPositiveButton("فتح إعدادات المطور", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void refreshUi() {
        if (status == null) return;

        boolean enabled = prefs.getBoolean("enabled", false);
        status.setText(enabled
                ? "الحالة: الحماية مفعلة"
                : "الحالة: الحماية متوقفة");

        stats.setText(
                "عدد النطاقات المحظورة: " + DomainBlocker.size(this) +
                "\nمحاولات الحظر: " + BlockLog.count(this) +
                "\nDevice Owner: " + (isDeviceOwner() ? "مفعّل" : "غير مفعّل"));

        if (startButton != null) {
            startButton.setEnabled(!enabled);
        }

        if (stopButton != null) {
            stopButton.setEnabled(enabled && !isDeviceOwner());
        }
    }
}
