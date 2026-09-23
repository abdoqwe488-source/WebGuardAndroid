package com.abdelrhman.webguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.abdelrhman.webguard.vpn.FilterVpnService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        boolean enabled = context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);

        if (!enabled) {
            return;
        }

        Intent service = new Intent(context, FilterVpnService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {
        }
    }
}
