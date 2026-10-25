package com.ytdlpgui.data.model

/**
 * تنقية الروابط الواردة من الحافظة/المشاركة:
 * - استخراج أول رابط صالح من نص فوضى
 * - إصلاح البروتوكول المزدوج (https://youtubehttps://...)
 */
object UrlSanitizer {

    private val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()

        val candidate = urlRegex.findAll(text).lastOrNull()?.value ?: return null

        // إذا تكرر البروتوكول داخل المرشّح خذ من آخر بروتوكول
        val lastProto = candidate.lastIndexOf("http", ignoreCase = true)
        val cleaned = if (lastProto > 0) candidate.substring(lastProto) else candidate

        val trimmed = cleaned.trim().trimEnd(',', ')', ']', '}', '"', '\'', '،', '.')
        return if (trimmed.matches(Regex("https?://\\S+", RegexOption.IGNORE_CASE))) trimmed else null
    }

    /**
     * استخراج معرّف فيديو يوتيوب من الرابط إن وُجد.
     */
    fun extractYoutubeId(raw: String?): String? {
        val url = sanitize(raw) ?: return null
        return Regex("[?&]v=([^&\\s]+)").find(url)?.groupValues?.get(1)?.trimEnd(',', '.', '؛')
            ?: Regex("youtu\\.be/([^/?&\\s]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.trimEnd(',', '.', '؛')
            ?: Regex("/(?:shorts|live|embed)/([^/?&\\s]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.trimEnd(',', '.', '؛')
    }

    /**
     * تنظيف رابط الفيديو الفردي من أي وسائط إضافية (مثل list= أو index=)
     * لضمان تنزيل الفيديو المستهدف فقط دون قائمة التشغيل بأكملها.
     */
    fun cleanSingleVideoUrl(raw: String?): String? {
        val url = sanitize(raw) ?: return null
        val ytVideoId = Regex("[?&]v=([^&\\s]+)").find(url)?.groupValues?.get(1)
        if (ytVideoId != null) {
            return "https://www.youtube.com/watch?v=${ytVideoId.trimEnd(',', '.', '؛')}"
        }
        val ytShortId = Regex("/shorts/([^/?&\\s]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        if (ytShortId != null) {
            return "https://www.youtube.com/shorts/${ytShortId.trimEnd(',', '.', '؛')}"
        }
        val ytBeId = Regex("youtu\\.be/([^/?&\\s]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        if (ytBeId != null) {
            return "https://youtu.be/${ytBeId.trimEnd(',', '.', '؛')}"
        }
        return url
    }

    /**
     * التحقق مما إذا كان الرابط يشير إلى قائمة تشغيل أو قناة/حساب مستخدم وليس فيديو فردي.
     */
    fun isPlaylistOrChannel(raw: String?): Boolean {
        val url = sanitize(raw) ?: return false

        // إذا كان رابط فيديو يوتيوب صريح (v= أو youtu.be أو shorts) فهو ليس قائمة/قناة
        if (Regex("[?&]v=[^&\\s]+").containsMatchIn(url) ||
            Regex("youtu\\.be/[^/?&\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
            Regex("/shorts/[^/?&\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
            Regex("/(?:video|reel|reels|p|tv|status)/[^/?&\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(url)
        ) {
            return false
        }

        // قائمة تشغيل يوتيوب
        if (url.contains("playlist?list=", ignoreCase = true) ||
            url.contains("/playlist", ignoreCase = true)
        ) {
            return true
        }

        // قناة / حساب يوتيوب
        if (Regex("youtube\\.com/(?:@[^/?&\\s]+|channel/[^/?&\\s]+|c/[^/?&\\s]+|user/[^/?&\\s]+)", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            return true
        }

        // حساب تيك توك
        if (Regex("tiktok\\.com/@[^/?&\\s]+/?$", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            return true
        }

        return false
    }

    /**
     * مفتاح موحّد لكشف "تم التحميل من قبل":
     * — روابط يوتيوب تُختزل إلى معرّف الفيديو (v=، youtu.be/، /shorts/، /live/، /embed/)
     * — تيك توك، إنستغرام، إكس/تويتر، فيسبوك تُختزل إلى معرّف المنشور الفريد
     * — غيرها يُختزل إلى الرابط الأساسي الصافي بأحرف صغيرة
     */
    fun normalizeKey(raw: String?): String? {
        val url = sanitize(raw) ?: return null

        // 1. YouTube video
        val isYouTube = url.contains("youtube.com", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true)
        if (isYouTube) {
            val ytId = Regex("[?&]v=([^&\\s]+)").find(url)?.groupValues?.get(1)
                ?: Regex("youtu\\.be/([^/?&\\s]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
                ?: Regex("/(?:shorts|live|embed)/([^/?&\\s]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            if (ytId != null) return "yt:${ytId.trimEnd(',', '.', '؛')}"
        }

        // 2. TikTok video
        val tiktokId = Regex("tiktok\\.com/@[^/]+/video/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: Regex("tiktok\\.com/.*?[?&]item_id=(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        if (tiktokId != null) return "tiktok:$tiktokId"

        // 3. Instagram reel/post
        val igId = Regex("instagram\\.com/(?:p|reel|reels|tv)/([^/?&\\s]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        if (igId != null) return "ig:$igId"

        // 4. Twitter / X status
        val xId = Regex("(?:twitter|x)\\.com/[^/]+/status/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        if (xId != null) return "x:$xId"

        // 5. Facebook video
        val fbId = Regex("(?:facebook\\.com/.*?(?:videos/|[?&]v=)|fb\\.watch/)([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        if (fbId != null) return "fb:$fbId"

        // Fallback: الرابط الصافي مع إزالة معلمات التعقب
        val base = url.substringBefore('?')
        return base.lowercase().trimEnd('/')
    }
}
