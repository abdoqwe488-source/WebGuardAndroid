package com.abdelrhman.webguard;

import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.abdelrhman.webguard.admin.DeviceAdminReceiverImpl;
import com.abdelrhman.webguard.core.DomainBlocker;
import com.abdelrhman.webguard.vpn.BlockLog;
import com.abdelrhman.webguard.vpn.FilterVpnService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private DevicePolicyManager dpm; private ComponentName admin; private Switch sw; private TextView status,stats;
    private android.content.SharedPreferences prefs;
    @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("settings",0);dpm=(DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);admin=new ComponentName(this,DeviceAdminReceiverImpl.class);DomainBlocker.load(this);ui();refresh();}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(16,12,16,12);return t;}
    private Button btn(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private void ui(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(18,24,18,18);
        TextView title=text("🛡 حارس الويب",28);title.setGravity(Gravity.CENTER);root.addView(title);
        root.addView(text("حماية الهاتف من المواقع الإباحية",16));
        sw=new Switch(this);sw.setText("تشغيل الحماية");sw.setTextSize(17);root.addView(sw);
        status=text("",15);root.addView(status);stats=text("",14);root.addView(stats);
        Button acc=btn("تفعيل فحص شريط العنوان");acc.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));root.addView(acc);
        Button owner=btn("تعليمات Device Owner");owner.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("إدارة الجهاز").setMessage("لأقصى حماية، اجعل التطبيق Device Owner باستخدام ADB. بعد ذلك يمكن للنظام فرض Always-on VPN ومنع إلغاء التثبيت.").setPositiveButton("حسنًا",null).show());root.addView(owner);
        Button update=btn("تحديث قائمة الحظر");update.setOnClickListener(v->{
            update.setEnabled(false); Executors.newSingleThreadExecutor().execute(()->{try{String m=DomainBlocker.update(this);runOnUiThread(()->{Toast.makeText(this,m,Toast.LENGTH_LONG).show();update.setEnabled(true);refresh();});}catch(Exception e){runOnUiThread(()->{Toast.makeText(this,"تعذر التحديث: "+e.getMessage(),Toast.LENGTH_LONG).show();update.setEnabled(true);});}});
        });root.addView(update);
        Button disable=btn("إيقاف الحماية / طلب الإزالة");disable.setOnClickListener(v->challenge());root.addView(disable);
        root.addView(text("مهم: لا يمكن لتطبيق عادي ضمان تجاوز كل VPN آخر. وضع Device Owner + Always-on/Lockdown هو أقوى وضع متاح للنظام.",13));
        setContentView(root);
        sw.setOnClickListener(v->{if(sw.isChecked())enable();else challenge();});
    }
    private void enable(){
        try{
            Intent p=VpnService.prepare(this);if(p!=null){startActivityForResult(p,42);return;}startVpn();
        }catch(Exception e){sw.setChecked(false);Toast.makeText(this,"تعذر بدء VPN",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==42)startVpn();}
    private void startVpn(){
        if(isOwner())try{dpm.setAlwaysOnVpnPackage(admin,getPackageName(),false);if(Build.VERSION.SDK_INT>=21)dpm.setUninstallBlocked(admin,getPackageName(),true);}
        catch(Exception ignored){}
        prefs.edit().putBoolean("enabled",true).apply();
        Intent i=new Intent(this,FilterVpnService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);refresh();
    }
    private boolean isOwner(){return dpm!=null&&dpm.isDeviceOwnerApp(getPackageName());}
    private void challenge(){
        EditText e=new EditText(this);e.setHint("اكتب 200 كلمة بالضبط");e.setGravity(Gravity.TOP);e.setMinLines(8);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this).setTitle("تأكيد إيقاف الحماية").setMessage("لن يتم تنفيذ الإجراء إلا إذا كان النص 200 كلمة بالضبط.").setView(e).setNegativeButton("إلغاء",null).setPositiveButton("متابعة",(d,w)->{
            int n=e.getText().toString().trim().isEmpty()?0:e.getText().toString().trim().split("\\s+").length;
            if(n!=200){Toast.makeText(this,"عدد الكلمات = "+n+"، المطلوب 200 بالضبط.",Toast.LENGTH_LONG).show();return;}
            disableAndOfferUninstall();
        }).show();
    }
    private void disableAndOfferUninstall(){
        prefs.edit().putBoolean("enabled",false).apply();stopService(new Intent(this,FilterVpnService.class));
        if(isOwner()){
            try{dpm.setAlwaysOnVpnPackage(admin,null,false);}catch(Exception ignored){}
            try{dpm.setUninstallBlocked(admin,getPackageName(),false);}catch(Exception ignored){}
            try{dpm.clearDeviceOwnerApp(getPackageName());}catch(Exception ignored){}
        }
        sw.setChecked(false);refresh();
        new AlertDialog.Builder(this).setTitle("تم اجتياز الشرط").setMessage("تم إيقاف الحماية. يمكنك الآن إلغاء تثبيت التطبيق من إعدادات Android.").setPositiveButton("فتح شاشة الإزالة",(d,w)->{
            Intent i=new Intent(Intent.ACTION_DELETE,android.net.Uri.parse("package:"+getPackageName()));startActivity(i);
        }).setNegativeButton("إغلاق",null).show();
    }
    private void refresh(){
        boolean en=prefs.getBoolean("enabled",false);sw.setChecked(en);
        status.setText(en?"الحالة: الحماية مفعلة":"الحالة: الحماية متوقفة");
        stats.setText("النطاقات المحظورة في القائمة: "+DomainBlocker.size(this)+"\nمحاولات محظورة: "+BlockLog.count(this)+(isOwner()?"\nDevice Owner: مفعّل":"\nDevice Owner: غير مفعّل"));
    }
}
