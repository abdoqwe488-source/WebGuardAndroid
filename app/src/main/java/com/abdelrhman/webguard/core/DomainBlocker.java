package com.abdelrhman.webguard.core;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

public final class DomainBlocker {
    private static final Set<String> domains = new CopyOnWriteArraySet<>();
    private static final String PREF="blocked_domains";
    private static final String LIST_URL="https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts";
    private static final String[] KEYWORDS={"porn","xxx","xvideo","xnxx","redtube","pornhub","hentai","rule34","xhamster","spankbang","chaturbate","brazzers","onlyfans","sexcam","sexvideo","sexchat"};
    private DomainBlocker(){}

    public static void load(Context c){
        domains.clear();
        try(InputStream in=c.openFileInput(PREF); BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String s; while((s=r.readLine())!=null){s=s.trim().toLowerCase(Locale.US); if(!s.isEmpty()&&!s.startsWith("#")) domains.add(s);}
        }catch(Exception ignored){}
        if(domains.isEmpty()){
            String[] seed={"pornhub.com","www.pornhub.com","xvideos.com","www.xvideos.com","xnxx.com","www.xnxx.com","xhamster.com","redtube.com","spankbang.com","brazzers.com","chaturbate.com","rule34.xxx","hentaihaven.xxx","onlyfans.com"};
            domains.addAll(Arrays.asList(seed));
        }
    }

    public static boolean isBlocked(Context c,String input){
        if(domains.isEmpty()) load(c);
        String host=extractHost(input);
        if(host.isEmpty()) return false;
        for(String d:domains) if(host.equals(d)||host.endsWith("."+d)) return true;
        for(String k:KEYWORDS) if(host.contains(k)) return true;
        return false;
    }

    public static String extractHost(String input){
        if(input==null) return "";
        String s=input.trim().toLowerCase(Locale.US);
        try{
            if(!s.matches("^[a-z][a-z0-9+.-]*://.*$")) s="http://"+s;
            URL u=new URL(s);
            String h=u.getHost();
            return h==null?"":h.toLowerCase(Locale.US);
        }catch(Exception e){
            s=s.replaceFirst("^[a-z][a-z0-9+.-]*://","");
            int slash=s.indexOf('/'); if(slash>=0)s=s.substring(0,slash);
            int q=s.indexOf('?'); if(q>=0)s=s.substring(0,q);
            return s.replaceAll("^www\\.","").trim();
        }
    }

    public static int size(Context c){ if(domains.isEmpty()) load(c); return domains.size(); }

    public static String update(Context c) throws Exception {
        HttpURLConnection con=(HttpURLConnection)new URL(LIST_URL).openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(30000); con.setRequestProperty("User-Agent","WebGuard/1.0");
        try(InputStream in=con.getInputStream(); BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
            FileOutputStream out=c.openFileOutput(PREF,Context.MODE_PRIVATE);
            BufferedWriter w=new BufferedWriter(new OutputStreamWriter(out,StandardCharsets.UTF_8))){
            String line; int count=0;
            while((line=r.readLine())!=null){
                line=line.trim();
                if(line.startsWith("#")||line.isEmpty()) continue;
                String[] p=line.split("\\s+");
                if(p.length>=2 && !p[1].equals("localhost")&&!p[1].equals("broadcasthost")){
                    w.write(p[1].toLowerCase(Locale.US)); w.newLine(); count++;
                }
            }
            domains.clear();
            load(c);
            return "تم تحديث القائمة: "+count+" نطاقًا";
        }finally{con.disconnect();}
    }
}
