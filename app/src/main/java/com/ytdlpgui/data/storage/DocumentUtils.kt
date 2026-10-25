package com.ytdlpgui.data.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object DocumentUtils {

    /**
     * التحقق أن مستخدم المستندات (Tree URI) صالح وقابل للكتابة فعلياً،
     * وإلا نحتاج التراجع إلى مسار قابل للكتابة مباشرة.
     */
    fun canWriteTree(context: Context, treeUri: Uri?): Boolean {
        if (treeUri == null) return false
        return try {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            dir?.canWrite() == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * فحص حقيقي مباشر لاختبار ما إذا كان المسار الفعلي على القرص قابلاً لإنشاء الملفات والكتابة
     * من خلال عمليات الملفات المباشرة (التي يعتمد عليها محرك yt-dlp).
     */
    fun canWriteDirectPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val dir = java.io.File(path)
            if (!dir.exists() && !dir.mkdirs()) return false
            val testFile = java.io.File(dir, ".probe_${System.currentTimeMillis()}.tmp")
            if (testFile.createNewFile()) {
                testFile.delete()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun getPathFromTreeUri(uri: Uri): String? {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val relativePath = if (split.size > 1) split[1] else ""

            if ("primary".equals(type, ignoreCase = true)) {
                if (relativePath.isNotEmpty()) {
                    "${android.os.Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
                } else {
                    android.os.Environment.getExternalStorageDirectory().absolutePath
                }
            } else {
                "/storage/$type/$relativePath"
            }
        } catch (_: Exception) {
            null
        }
    }
}
