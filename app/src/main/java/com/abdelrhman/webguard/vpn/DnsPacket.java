package com.abdelrhman.webguard.vpn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class DnsPacket {
    static final class Query {
        final int srcIp,dstIp,srcPort,dstPort; final byte[] dns; final String host;
        Query(int a,int b,int c,int d,byte[] e,String f){srcIp=a;dstIp=b;srcPort=c;dstPort=d;dns=e;host=f;}
    }
    static Query parse(byte[] p,int len){
        if(len<28 || (p[0]&0xf0)!=0x40 || (p[9]&255)!=17)return null;
        int ihl=(p[0]&15)*4; if(len<ihl+8)return null;
        int sp=u16(p,ihl), dp=u16(p,ihl+2); if(dp!=53)return null;
        int dl=u16(p,ihl+4); int ds=ihl+8; if(dl<12||ds+dl>len)return null;
        byte[] dns=new byte[dl];System.arraycopy(p,ds,dns,0,dl);
        return new Query(i32(p,12),i32(p,16),sp,dp,dns,host(dns));
    }
    static byte[] response(Query q,byte[] dns){
        int n=20+8+dns.length; byte[] p=new byte[n];
        p[0]=0x45;p[2]=(byte)(n>>>8);p[3]=(byte)n;p[6]=0x40;p[8]=64;p[9]=17;
        put32(p,12,q.dstIp);put32(p,16,q.srcIp);put16(p,10,checksum(p,0,20));
        put16(p,20,q.dstPort);put16(p,22,q.srcPort);put16(p,24,8+dns.length);
        System.arraycopy(dns,0,p,28,dns.length);
        put16(p,26,udpChecksum(q.dstIp,q.srcIp,p,20,8+dns.length));
        return p;
    }
    static byte[] nxdomain(byte[] q){
        int end=questionEnd(q); if(end<16)return q;
        byte[] r=java.util.Arrays.copyOf(q,end+4); int f=u16(q,2)|0x8000; f=(f&0xfff0)|3;
        put16(r,2,f);put16(r,6,0);put16(r,8,0);put16(r,10,0); return r;
    }
    private static String host(byte[] d){
        int o=12;StringBuilder s=new StringBuilder();int guard=0;
        while(o<d.length){int n=d[o++]&255;if(n==0)break;if(n>63||o+n>d.length)return "";
            if(s.length()>0)s.append('.');s.append(new String(d,o,n,StandardCharsets.US_ASCII));o+=n;if(++guard>100)return "";}
        return s.toString().toLowerCase(java.util.Locale.US);
    }
    private static int questionEnd(byte[] d){int o=12,g=0;while(o<d.length){int n=d[o++]&255;if(n==0)return o;if(n>63||o+n>d.length)return -1;o+=n;if(++g>100)return -1;}return -1;}
    private static int u16(byte[] p,int o){return ((p[o]&255)<<8)|(p[o+1]&255);}
    private static int i32(byte[] p,int o){return ((p[o]&255)<<24)|((p[o+1]&255)<<16)|((p[o+2]&255)<<8)|(p[o+3]&255);}
    private static void put16(byte[] p,int o,int v){p[o]=(byte)(v>>>8);p[o+1]=(byte)v;}
    private static void put32(byte[] p,int o,int v){p[o]=(byte)(v>>>24);p[o+1]=(byte)(v>>>16);p[o+2]=(byte)(v>>>8);p[o+3]=(byte)v;}
    private static int checksum(byte[] p,int o,int n){long s=0;for(int i=o;i<o+n;i+=2){s+=((p[i]&255)<<8)|(i+1<o+n?p[i+1]&255:0);s=(s&65535)+(s>>>16);}return (int)(~s)&65535;}
    private static int udpChecksum(int src,int dst,byte[] p,int o,int n){
        byte[] x=new byte[12+n+(n&1)];put32(x,0,src);put32(x,4,dst);x[9]=17;put16(x,10,n);System.arraycopy(p,o,x,12,n);return checksum(x,0,x.length)==0?65535:checksum(x,0,x.length);
    }
}
