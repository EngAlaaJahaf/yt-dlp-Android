package com.ytdlpgui.data.engine

object ErrorClassifier {

    private val rules = listOf(
        listOf("unsupported url", "not a valid url") to "رابط غير مدعوم أو غير صالح",
        listOf("unavailable", "removed") to "الفيديو غير متاح أو حُذف",
        listOf("sign in to confirm", "age gate") to "YouTube يطلب تسجيل دخول (بطاقة تجربة أعمار محتملة)",
        listOf("geo", "not available in your country") to "المحتوى محجوب جغرافياً في بلدك",
        listOf("403") to "الخادم رفض الطلب (403) — جرب من جديد أو حدّث المحرك",
        listOf("429") to "تجاوز الحد المسموح من الطلبات (429) — انتظر قليلاً ثم جرب",
        listOf("no space", "enospc") to "لا توجد مساحة تخزين كافية في الجهاز",
        listOf("permission denied", "eacces") to "رفض الوصول للمجلد — جرّب مجلد التنزيلات أو المجلد الافتراضي",
        listOf("http error 5", "502", "503", "504") to "خطأ من الخادم — جرب بعد فترة قصيرة",
        listOf("timed out", "timeout", "connection", "network error", "unable to resolve", "econn") to "مشكلة اتصال بالشبكة — تحقق من الإنترنت",
        listOf("ssl", "certificate") to "خطأ في شهادة الشبكة"
    )

    fun classify(raw: String?): String {
        if (raw.isNullOrBlank()) return "خطأ غير معروف"
        val lower = raw.lowercase()
        for ((patterns, message) in rules) {
            if (patterns.any { lower.contains(it) }) return "$message\n(تفاصيل تقنية: ${raw.take(180)})"
        }
        val trimmed = raw.trim().take(300)
        if (trimmed.length > 6) return "فشل التنزيل\n(تفاصيل تقنية: $trimmed)"
        return "فشل التنزيل: $raw"
    }

    fun classifyShort(raw: String?): String {
        if (raw.isNullOrBlank()) return "خطأ غير معروف"
        val lower = raw.lowercase()
        for ((patterns, message) in rules) {
            if (patterns.any { lower.contains(it) }) return message
        }
        return "فشل التنزيل"
    }
}
