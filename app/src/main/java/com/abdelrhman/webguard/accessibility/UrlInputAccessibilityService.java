package com.abdelrhman.webguard.accessibility;
import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.*;
import android.widget.Toast;
import com.abdelrhman.webguard.core.DomainBlocker;
import com.abdelrhman.webguard.vpn.BlockLog;

public class UrlInputAccessibilityService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(e.getEventType()!=AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED&&e.getEventType()!=AccessibilityEvent.TYPE_VIEW_FOCUSED)return;
        AccessibilityNodeInfo n=e.getSource();if(n==null)return;
        try{
            if(!n.isEditable()||!n.isFocused()||n.getText()==null)return;
            String s=n.getText().toString();if(s.isEmpty()||s.length()>2048)return;
            if(DomainBlocker.isBlocked(this,s)){
                BlockLog.record(this,DomainBlocker.extractHost(s));
                Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"");
                n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a);
                Toast.makeText(this,"تم حذف الرابط المحظور",Toast.LENGTH_SHORT).show();
            }
        }finally{n.recycle();}
    }
    @Override public void onInterrupt(){}
}
