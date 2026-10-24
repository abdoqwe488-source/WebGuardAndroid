package com.fastdownload.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.graphics.Color;
import android.view.View;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import java.net.URL;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private EditText address;
    private WebView browser;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout root;
    private boolean dark = true;
    private final Set<String> blocked = new HashSet<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String[] domains = {"pornhub.com","xvideos.com","xnxx.com","xhamster.com","redtube.com","spankbang.com","brazzers.com","chaturbate.com","rule34.xxx","onlyfans.com","youporn.com","tube8.com","porn.com"};
        for (String d : domains) blocked.add(d);
        buildUi();
    }
    private TextView label(String text, int size) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setPadding(10,8,10,8); return t;
    }
    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); return b;
    }
    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(10,8,10,8);
        TextView title = label("⬇ Fast Download",25); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = label("متصفح + مدير تنزيلات | حجب نطاقات إباحية",14); sub.setGravity(Gravity.CENTER); root.addView(sub);
        address = new EditText(this); address.setSingleLine(true); address.setHint("الصق الرابط أو اكتب عنوان الموقع"); address.setTextSize(15); root.addView(address);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button go = button("فتح"); row.addView(go,new LinearLayout.LayoutParams(0,-2,1));
        Button dl = button("تنزيل الرابط"); row.addView(dl,new LinearLayout.LayoutParams(0,-2,1));
        Button theme = button("فاتح/داكن"); row.addView(theme,new LinearLayout.LayoutParams(0,-2,1)); root.addView(row);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setVisibility(View.GONE); root.addView(progress);
        status = label("جاهز. افتح صفحة أو الصق رابط ملف مباشر للتنزيل.",13); root.addView(status);
        browser = new WebView(this); browser.getSettings().setJavaScriptEnabled(true); browser.getSettings().setDomStorageEnabled(true);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view,int p) { progress.setVisibility(p<100?View.VISIBLE:View.GONE); progress.setProgress(p); }
        });
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                String url=request.getUrl().toString();
                if(isBlocked(url)){showBlocked();return true;} address.setText(url); return false;
            }
            @Override public void onPageFinished(WebView view,String url) { address.setText(url); status.setText("الصفحة: "+host(url)+" | اضغط روابط الملفات للتنزيل"); }
        });
        browser.setDownloadListener((url,userAgent,contentDisposition,mimeType,contentLength)->{
            if(isBlocked(url)){showBlocked();return;} enqueueDownload(url,userAgent,mimeType);
        });
        root.addView(browser,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root); applyTheme();
        go.setOnClickListener(v->openAddress());
        address.setOnEditorActionListener((v,actionId,event)->{openAddress();return true;});
        dl.setOnClickListener(v->startManualDownload());
        theme.setOnClickListener(v->{dark=!dark;applyTheme();});
    }
    private void openAddress() {
        String value=address.getText().toString().trim(); if(value.isEmpty())return;
        if(!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))value="https://"+value;
        if(isBlocked(value)){showBlocked();return;}
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);
        browser.loadUrl(value);
    }
    private void startManualDownload() {
        String value=address.getText().toString().trim(); if(value.isEmpty()){toast("أدخل رابط الملف أولًا");return;}
        if(!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))value="https://"+value;
        if(isBlocked(value)){showBlocked();return;} enqueueDownload(value,null,null);
    }
    private void enqueueDownload(String url,String userAgent,String mimeType) {
        if(isBlocked(url)){showBlocked();return;}
        try {
            URL parsed=new URL(url); String path=parsed.getPath();
            String name=(path==null||path.endsWith("/")||path.isEmpty())?"download_"+System.currentTimeMillis():path.substring(path.lastIndexOf('/')+1);
            name=name.replaceAll("[^a-zA-Z0-9._-]","_"); if(name.length()>100)name=name.substring(name.length()-100);
            if(name.isEmpty())name="download_"+System.currentTimeMillis();
            DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name); request.setDescription("Fast Download");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true); request.setAllowedOverRoaming(false);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,name);
            if(mimeType!=null&&!mimeType.isEmpty())request.setMimeType(mimeType);
            if(userAgent!=null&&!userAgent.isEmpty())request.addRequestHeader("User-Agent",userAgent);
            String cookie=android.webkit.CookieManager.getInstance().getCookie(url);
            if(cookie!=null&&!cookie.isEmpty())request.addRequestHeader("Cookie",cookie);
            long id=((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
            status.setText("بدأ التنزيل: "+name+"\nرقم التنزيل: "+id);
            toast("بدأ التنزيل. تابع التقدم من إشعارات الهاتف.");
        } catch(Exception e) { status.setText("تعذر بدء التنزيل: "+e.getMessage()); toast("الرابط غير صالح أو الموقع لا يسمح بالتنزيل"); }
    }
    private String host(String input) {
        try{return new URL(input).getHost().toLowerCase(Locale.US).replaceFirst("^www\\.","");}
        catch(Exception e){return "";}
    }
    private boolean isBlocked(String input) {
        String h=host(input); if(h.isEmpty())return false;
        for(String d:blocked)if(h.equals(d)||h.endsWith("."+d))return true;
        String[] words={"pornhub","xvideos","xnxx","xhamster","redtube","spankbang","hentai","adult"};
        for(String w:words)if(h.contains(w))return true; return false;
    }
    private void showBlocked() {
        status.setText("تم حظر الرابط لأنه يطابق قائمة النطاقات المحظورة.");
        new AlertDialog.Builder(this).setTitle("تم حظر الرابط").setMessage("الحجب هنا داخل متصفح Fast Download والتنزيلات التي يبدأها التطبيق فقط، وليس على بقية تطبيقات الهاتف.").setPositiveButton("حسنًا",null).show();
    }
    private void applyTheme() {
        int bg=dark?Color.rgb(18,24,33):Color.rgb(247,249,252), fg=dark?Color.WHITE:Color.rgb(25,30,38);
        root.setBackgroundColor(bg); setTextColors(root,fg); address.setTextColor(fg);
        address.setHintTextColor(dark?0xffaaaaaa:0xff777777); address.setBackgroundColor(dark?0xff263241:Color.WHITE);
    }
    private void setTextColors(android.view.ViewGroup group,int color) {
        for(int i=0;i<group.getChildCount();i++){View v=group.getChildAt(i);if(v instanceof TextView)((TextView)v).setTextColor(color);if(v instanceof android.view.ViewGroup)setTextColors((android.view.ViewGroup)v,color);}
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(browser!=null&&browser.canGoBack())browser.goBack();else super.onBackPressed();}
}
