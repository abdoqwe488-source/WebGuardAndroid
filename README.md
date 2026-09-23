# حارس الويب — WebGuard Android

تطبيق حماية للهواتف التي تعمل بنظام Android 8 (API 26) أو أحدث.

## الوظائف
- VPN محلي بتمرير IPv4 وIPv6 عبر محرك tun2socks.
- فحص اسم النطاق قبل الاتصال وحظر المواقع الموجودة في قوائم الحظر.
- قوائم حظر مدمجة مع إمكانية التحديث من مصدرين عامين.
- حذف الرابط المحظور من حقول العنوان/البحث المدعومة بعد تفعيل Accessibility Service.
- تشغيل تلقائي بعد إعادة التشغيل عندما تكون الحماية مفعلة.
- وضع Device Owner اختياري للحماية الإدارية الأقوى.
- Always-on VPN + Lockdown عند تشغيل وضع Device Owner.
- منع تغيير إعدادات VPN ومنع إلغاء تثبيت التطبيق في وضع Device Owner.

## البناء

يتطلب JDK 17 وAndroid SDK:

    gradle --no-daemon assembleDebug

سيُنتج:

    app/build/outputs/apk/debug/app-debug.apk

## Device Owner

بعد تثبيت APK على جهاز مؤهل للإدارة، نفّذ من الكمبيوتر:

    adb shell dpm set-device-owner com.abdelrhman.webguard/.admin.DeviceAdminReceiverImpl

بعد نجاح Device Owner افتح التطبيق واضغط «تشغيل البرنامج».

## ملاحظات أندرويد

تطبيق Android عادي لا يستطيع أن يضمن أنه سيقاوم root أو bootloader غير مقفل أو جميع تطبيقات النظام. كما أن أندرويد يسمح بواجهة VPN واحدة نشطة في الوقت نفسه. لذلك يستخدم المشروع Device Owner + Always-on + Lockdown + قيود إعدادات VPN للوصول إلى أقوى وضع حماية متاح على جهاز مُدار.

حذف النص أثناء كتابته يعتمد على Accessibility Service، ولذلك يجب تمكين خدمة «حارس الويب» من إعدادات إمكانية الوصول. لا يمكن لتطبيق عادي تفعيل هذه الخدمة بصمت.
