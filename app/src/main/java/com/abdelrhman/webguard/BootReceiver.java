package com.abdelrhman.webguard;
import android.content.*;
import com.abdelrhman.webguard.vpn.FilterVpnService;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(i.getAction())) {
            try { c.startForegroundService(new Intent(c, FilterVpnService.class)); } catch (Exception ignored) {}
        }
    }
}
