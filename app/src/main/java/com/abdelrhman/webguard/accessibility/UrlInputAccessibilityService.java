package com.abdelrhman.webguard.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.abdelrhman.webguard.core.DomainBlocker;
import com.abdelrhman.webguard.vpn.BlockLog;

import java.util.Locale;

public class UrlInputAccessibilityService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                type != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return;
        }

        AccessibilityNodeInfo node = event.getSource();
        if (node == null) return;

        try {
            if (!node.isEditable() || !node.isFocused() || node.getText() == null) {
                return;
            }

            String text = node.getText().toString().trim();
            if (text.isEmpty() || text.length() > 2048) return;

            if (!looksLikeBrowserAddressField(node, text)) {
                return;
            }

            if (DomainBlocker.isBlocked(this, text)) {
                String host = DomainBlocker.extractHost(text);
                BlockLog.record(this, host);

                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

                Toast.makeText(
                        this,
                        "تم حذف الرابط المحظور: " + host,
                        Toast.LENGTH_SHORT).show();
            }
        } finally {
            node.recycle();
        }
    }

    private boolean looksLikeBrowserAddressField(
            AccessibilityNodeInfo node, String value) {

        String className = node.getClassName() == null
                ? ""
                : node.getClassName().toString().toLowerCase(Locale.US);

        String viewId = node.getViewIdResourceName() == null
                ? ""
                : node.getViewIdResourceName().toLowerCase(Locale.US);

        String hint = node.getHintText() == null
                ? ""
                : node.getHintText().toString().toLowerCase(Locale.US);

        boolean fieldLooksLikeAddress =
                viewId.contains("url") ||
                viewId.contains("address") ||
                viewId.contains("omnibox") ||
                viewId.contains("search") ||
                hint.contains("url") ||
                hint.contains("address") ||
                hint.contains("search") ||
                className.contains("edittext");

        boolean looksLikeHost =
                value.contains(".") ||
                value.startsWith("http://") ||
                value.startsWith("https://") ||
                value.startsWith("www.");

        return fieldLooksLikeAddress && looksLikeHost;
    }

    @Override
    public void onInterrupt() {
    }
}
