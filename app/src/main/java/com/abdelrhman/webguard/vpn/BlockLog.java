package com.abdelrhman.webguard.vpn;
import android.content.Context;
public final class BlockLog {
    private static final String P="blocked_count";
    public static void record(Context c,String host){
        if(host==null||host.isEmpty())return;
        var p=c.getSharedPreferences("stats",Context.MODE_PRIVATE);
        p.edit().putInt(P,p.getInt(P,0)+1).apply();
    }
    public static int count(Context c){return c.getSharedPreferences("stats",Context.MODE_PRIVATE).getInt(P,0);}
}
