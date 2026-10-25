# قواعد ClipCatch لـ R8

# ─── نماذج البيانات المسلسلة (JSON codec + Serializable إضافي) ───
-keep class com.ytdlpgui.data.model.** { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─── محرك yt-dlp من مكتبة io.github.junkfood02 ───
# تحتوي حالة خاصة (binaries في assets + إشارات عملية) — احفظ بالكامل
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# ─── Apache Commons Compress (يستخدمها ZipUtils عبر انعكاس عند unzip)
# قد يُهوى R8 الفئات فيفشل <clinit> ExtraFieldUtils بـ«not a concrete class»
-keep class org.apache.commons.compress.** { *; }
-keepclassmembers class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# ─── Room ───
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ─── ViewBinding لا يحتاج قواعد إضافية ───
