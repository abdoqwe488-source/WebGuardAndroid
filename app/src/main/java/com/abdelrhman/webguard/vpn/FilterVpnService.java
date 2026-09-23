package com.abdelrhman.webguard.vpn;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import com.abdelrhman.webguard.MainActivity;
import com.abdelrhman.webguard.core.DomainBlocker;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

public class FilterVpnService extends VpnService {
    private ParcelFileDescriptor vpn; private volatile boolean running;
    private static final int MTU=1500; private static final String CH="webguard";
    @Override public int onStartCommand(Intent i,int flags,int id){
        startForeground(7,notification()); if(!running)start(); return START_STICKY;
    }
    private void start(){
        running=true;
        new Thread(()->{
            try{
                vpn=new Builder().setSession("WebGuard").setMtu(MTU).addAddress("10.0.0.1",32)
                    .addRoute("10.0.0.2",32).addDnsServer("10.0.0.2").establish();
                if(vpn==null)throw new IOException("VPN establish failed");
                FileInputStream in=new FileInputStream(vpn.getFileDescriptor());
                FileOutputStream out=new FileOutputStream(vpn.getFileDescriptor());
                byte[] packet=new byte[32767];
                while(running){
                    int n=in.read(packet); if(n<=0)continue;
                    DnsPacket.Query q=DnsPacket.parse(packet,n); if(q==null)continue;
                    if(DomainBlocker.isBlocked(this,q.host)){
                        BlockLog.record(this,q.host);out.write(DnsPacket.response(q,DnsPacket.nxdomain(q.dns)));out.flush();continue;
                    }
                    DatagramSocket s=new DatagramSocket();protect(s);
                    try{
                        s.setSoTimeout(2500);
                        DatagramPacket dp=new DatagramPacket(q.dns,q.dns.length,InetAddress.getByName("1.1.1.1"),53);
                        s.send(dp);byte[] b=new byte[4096];DatagramPacket rp=new DatagramPacket(b,b.length);s.receive(rp);
                        out.write(DnsPacket.response(q,java.util.Arrays.copyOf(rp.getData(),rp.getLength())));out.flush();
                    }catch(Exception ignored){}finally{s.close();}
                }
            }catch(Exception ignored){}finally{running=false;try{if(vpn!=null)vpn.close();}catch(Exception ignored){}}
        },"WebGuard-VPN").start();
    }
    private Notification notification(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CH,"حماية حارس الويب",NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm=getSystemService(NotificationManager.class);if(nm!=null)nm.createNotificationChannel(c);
        }
        Intent in=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,1,in,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("حارس الويب").setContentText("الحماية مفعلة").setOngoing(true).setContentIntent(pi).build();
    }
    @Override public void onDestroy(){running=false;try{if(vpn!=null)vpn.close();}catch(Exception ignored){}super.onDestroy();}
}
