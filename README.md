# yt-dlp Android App

تطبيق أندرويد مفتوح المصدر لتحميل الفيديو مبني على مكتبة `youtubedl-android`.

## متطلبات التشغيل
- Android Studio (Hedgehog / Iguana / Jellyfish أو أحدث)
- JDK 17
- Android SDK 34 (Android 14)
- Min SDK: 24 (Android 7.0+)

## خطوات الفتح والبناء في Android Studio:
1. افتح Android Studio
2. اختر **File -> Open** وحدد مجلد المشروع: `<project-folder>`
3. انتظر حتى يكتمل مزامنة الـ Gradle (**Sync Project with Gradle Files**)
4. إذا طلب تنزيل SDK أو أدوات مفقودة، اضغط **Install missing platform(s) and sync project**
5. اضغط على زر **Run ▶** لتشغيل التطبيق على الهاتف أو المحاكي (Emulator)
6. لبناء ملف APK قابل للتثبيت: **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**
