package com.ytdlpgui.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ImageLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    suspend fun loadBitmap(urlStr: String?): Bitmap? {
        if (urlStr.isNullOrBlank()) return null
        memoryCache.get(urlStr)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        memoryCache.put(urlStr, bmp)
                    }
                    bmp
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun loadInto(
        imageView: ImageView,
        urlStr: String?,
        coroutineScope: CoroutineScope,
        onLoaded: ((Bitmap) -> Unit)? = null
    ) {
        if (urlStr.isNullOrBlank()) return

        val cached = memoryCache.get(urlStr)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            onLoaded?.invoke(cached)
            return
        }

        coroutineScope.launch {
            val bmp = loadBitmap(urlStr)
            if (bmp != null) {
                imageView.alpha = 0f
                imageView.setImageBitmap(bmp)
                imageView.animate().alpha(1f).setDuration(200L).start()
                onLoaded?.invoke(bmp)
            }
        }
    }

    suspend fun loadVideoThumbnail(file: java.io.File?): Bitmap? {
        if (file == null || !file.exists()) return null
        val cacheKey = "file_thumb_${file.absolutePath}_${file.lastModified()}"
        memoryCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(file.absolutePath)
                val bmp = mmr.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: mmr.frameAtTime
                mmr.release()
                if (bmp != null) {
                    memoryCache.put(cacheKey, bmp)
                }
                bmp
            } catch (_: Exception) {
                null
            }
        }
    }

    fun loadVideoThumbnailInto(
        imageView: ImageView,
        file: java.io.File?,
        coroutineScope: CoroutineScope,
        onLoaded: ((Bitmap) -> Unit)? = null
    ) {
        if (file == null || !file.exists()) return
        val cacheKey = "file_thumb_${file.absolutePath}_${file.lastModified()}"
        val cached = memoryCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            onLoaded?.invoke(cached)
            return
        }

        coroutineScope.launch {
            val bmp = loadVideoThumbnail(file)
            if (bmp != null) {
                imageView.alpha = 0f
                imageView.setImageBitmap(bmp)
                imageView.animate().alpha(1f).setDuration(200L).start()
                onLoaded?.invoke(bmp)
            }
        }
    }

    suspend fun getMediaDurationSeconds(file: java.io.File): Int? {
        return withContext(Dispatchers.IO) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(file.absolutePath)
                val durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                mmr.release()
                durStr?.toLongOrNull()?.let { (it / 1000).toInt() }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun formatDuration(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
