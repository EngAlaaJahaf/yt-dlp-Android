package com.ytdlpgui

import android.R
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ytdlpgui.databinding.ActivityMainBinding

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val downloadProcessId = "yt_dlp_download_task"
    private var isDownloading = false
    private var isInitialized = false
    private val initReady = CompletableDeferred<Boolean>()

    // مستعرض اختيار المجلدات من النظام
    private val dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val path = getPathFromTreeUri(it)
            if (path != null) {
                binding.pathEdit.setText(path)
                Toast.makeText(this, "تم اختيار المجلد: $path", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "تم اختيار المجلد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getPathFromTreeUri(uri: Uri): String? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val relativePath = if (split.size > 1) split[1] else ""
            
            return if ("primary".equals(type, ignoreCase = true)) {
                if (relativePath.isNotEmpty()) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
                } else {
                    Environment.getExternalStorageDirectory().absolutePath
                }
            } else {
                // للبطاقة الخارجية SD Card
                "/storage/$type/$relativePath"
            }
        } catch (e: Exception) {
            return null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val qualities = arrayOf("الأفضل تلقائياً (best)", "1080p", "720p", "480p", "360p")
        val qualityAdapter = ArrayAdapter(this, R.layout.simple_spinner_dropdown_item, qualities)
        binding.qualitySpinner.adapter = qualityAdapter

        val formats = arrayOf(
            "فيديو MP4",
            "فيديو MKV",
            "فيديو WebM",
            "صوت MP3",
            "صوت M4A",
            "صوت OPUS",
            "صوت FLAC",
            "صوت WAV"
        )
        val formatAdapter = ArrayAdapter(this, R.layout.simple_spinner_dropdown_item, formats)
        binding.formatSpinner.adapter = formatAdapter

        // مسار التنزيل الافتراضي هو مجلد Downloads/yt-dlp
        val defaultDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "yt-dlp")
        binding.pathEdit.setText(defaultDir.absolutePath)

        // زر استعراض المجلدات
        binding.chooseFolderBtn.setOnClickListener {
            dirPickerLauncher.launch(null)
        }

        // زر إعادة ضبط المسار للافتراضي
        binding.resetPathBtn.setOnClickListener {
            binding.pathEdit.setText(defaultDir.absolutePath)
            Toast.makeText(this, "تمت استعادة المسار الافتراضي", Toast.LENGTH_SHORT).show()
        }

        binding.log.append("⏳ يتم تجهيز المحرك...\n")

        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                true
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.log.append("❌ فشل تهيئة المحرك: ${e.message}\n")
                }
                false
            }
            isInitialized = ok
            initReady.complete(ok)
            if (ok) {
                withContext(Dispatchers.Main) {
                    val ver = try { YoutubeDL.getInstance().versionName(applicationContext) ?: "جاهز" } catch(e: Exception) { "جاهز" }
                    binding.versionText.text = "النسخة: $ver"
                    binding.log.append("✅ محرك yt-dlp و FFmpeg جاهزان (الإصدار: $ver)\n")
                }
            }
        }

        binding.pasteBtn.setOnClickListener {
            val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            binding.urlEdit.setText(text)
        }

        binding.downloadBtn.setOnClickListener { startDownload() }
        binding.stopBtn.setOnClickListener { stopDownload() }
        binding.updateBtn.setOnClickListener { startUpdate() }

        binding.openFolderBtn.setOnClickListener {
            openFolder(getDownloadDir())
        }
    }

    private fun openFolder(folder: File) {
        if (!folder.exists()) folder.mkdirs()

        // 1. محاولة فتح المجلد بواسطة FileProvider عبر مدير ملفات النظام
        try {
            val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", folder)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (_: Exception) {}

        // 2. محاولة أخرى عبر intent فتح المجلدات العام
        try {
            val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", folder)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (_: Exception) {}

        // 3. فتح شاشة التنزيلات العامة
        try {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        } catch (_: Exception) {}

        Toast.makeText(this, "المسار: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun getDownloadDir(): File {
        val customPath = binding.pathEdit.text.toString().trim()
        val dir = if (customPath.isNotEmpty()) {
            File(customPath)
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "yt-dlp")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun startDownload() {
        val url = binding.urlEdit.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "الصق رابطاً أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.Main) {
            if (!isInitialized) {
                binding.log.append("⏳ انتظر التهيئة...\n")
                try { initReady.await() } catch (_: Exception) {}
            }
            if (!isInitialized || isDownloading) return@launch

            toggleUiState(downloading = true)
            binding.log.append("🚀 بدء التحميل: $url\n")

            withContext(Dispatchers.IO) {
                try {
                    val request = YoutubeDLRequest(url)
                    request.addOption("-o", "${getDownloadDir().absolutePath}/%(title)s.%(ext)s")
                    request.addOption("--no-mtime") // لحل مشاكل التواريخ على أندرويد

                    val quality = binding.qualitySpinner.selectedItem.toString()
                    val formatSelection = binding.formatSpinner.selectedItem.toString()

                    if (formatSelection.contains("صوت")) {
                        // وضع استخراج الصوت
                        request.addOption("-x")
                        when {
                            formatSelection.contains("MP3") -> request.addOption("--audio-format", "mp3")
                            formatSelection.contains("M4A") -> request.addOption("--audio-format", "m4a")
                            formatSelection.contains("OPUS") -> request.addOption("--audio-format", "opus")
                            formatSelection.contains("FLAC") -> request.addOption("--audio-format", "flac")
                            formatSelection.contains("WAV") -> request.addOption("--audio-format", "wav")
                        }
                    } else {
                        // وضع الفيديو
                        val resSelection = when {
                            quality.contains("1080") -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"
                            quality.contains("720") -> "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
                            quality.contains("480") -> "bestvideo[height<=480]+bestaudio/best[height<=480]/best"
                            quality.contains("360") -> "bestvideo[height<=360]+bestaudio/best[height<=360]/best"
                            else -> "bestvideo+bestaudio/best" // الأفضل تلقائياً
                        }
                        request.addOption("-f", resSelection)

                        when {
                            formatSelection.contains("MP4") -> request.addOption("--merge-output-format", "mp4")
                            formatSelection.contains("MKV") -> request.addOption("--merge-output-format", "mkv")
                            formatSelection.contains("WebM") -> request.addOption("--merge-output-format", "webm")
                        }
                    }

                    YoutubeDL.getInstance().execute(request, downloadProcessId) { progress, _, text ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            binding.progress.progress = progress.toInt()
                            if (text.isNotBlank() && !text.contains("[download]")) {
                                binding.log.append("$text\n")
                            } else if (text.isNotBlank() && progress.toInt() % 10 == 0) {
                                binding.log.append("التحميل: ${progress.toInt()}%\n")
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        binding.log.append("✅ اكتمل بنجاح.\n")
                        toggleUiState(false)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (e.message?.contains("destroy") == true) {
                            binding.log.append("⏹ تم الإلغاء.\n")
                        } else {
                            binding.log.append("❌ خطأ: ${e.message}\n")
                        }
                        toggleUiState(false)
                    }
                }
            }
        }
    }

    private fun stopDownload() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().destroyProcessById(downloadProcessId)
                withContext(Dispatchers.Main) { toggleUiState(false) }
            } catch (e: Exception) { }
        }
    }

    private fun startUpdate() {
        binding.updateBtn.isEnabled = false
        binding.log.append("⏳ جارٍ التحقق من تحديثات yt-dlp...\n")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                val ver = YoutubeDL.getInstance().versionName(applicationContext) ?: ""
                withContext(Dispatchers.Main) {
                    binding.versionText.text = "النسخة: $ver"
                    binding.log.append("✅ حالة التحديث: ${status?.name} — الإصدار: $ver\n")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.log.append("⚠️ لا يوجد تحديث أو فشل الاتصال: ${e.message}\n")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.updateBtn.isEnabled = true
                }
            }
        }
    }

    private fun toggleUiState(downloading: Boolean) {
        isDownloading = downloading
        binding.downloadBtn.isEnabled = !downloading
        binding.stopBtn.isEnabled = downloading
        binding.urlEdit.isEnabled = !downloading
        binding.qualitySpinner.isEnabled = !downloading
        binding.formatSpinner.isEnabled = !downloading
        binding.pathEdit.isEnabled = !downloading
        binding.chooseFolderBtn.isEnabled = !downloading
        binding.resetPathBtn.isEnabled = !downloading
    }
}
