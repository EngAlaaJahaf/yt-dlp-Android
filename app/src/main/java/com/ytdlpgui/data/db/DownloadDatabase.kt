package com.ytdlpgui.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

object DownloadStatus {
    const val QUEUED = 0
    const val RUNNING = 1
    const val DONE = 2
    const val FAILED = 3
    const val CANCELLED = 4
    const val PAUSED = 5

    private val labelRes = mapOf(
        QUEUED to com.ytdlpgui.R.string.status_queued,
        RUNNING to com.ytdlpgui.R.string.status_running,
        DONE to com.ytdlpgui.R.string.status_done,
        FAILED to com.ytdlpgui.R.string.status_failed,
        CANCELLED to com.ytdlpgui.R.string.status_cancelled,
        PAUSED to com.ytdlpgui.R.string.status_paused
    )

    fun label(context: Context, status: Int): String {
        val res = labelRes[status] ?: com.ytdlpgui.R.string.status_cancelled
        return context.getString(res)
    }

    fun color(status: Int): Int = when (status) {
        QUEUED -> 0xFF7C4DFF.toInt()
        RUNNING -> 0xFF1C63E1.toInt() // أزرق الهوية النشط
        DONE -> 0xFF00B36E.toInt() // أخضر الإنجاز المكتمل
        FAILED -> 0xFFE53935.toInt()
        PAUSED -> 0xFFF57C00.toInt()
        else -> 0xFF8A8A8E.toInt()
    }

    fun indicatorColor(status: Int): Int = when (status) {
        QUEUED -> 0xFF7C4DFF.toInt()
        RUNNING -> 0xFF1C63E1.toInt()
        DONE -> 0xFF00B36E.toInt()
        FAILED -> 0xFFE53935.toInt()
        PAUSED -> 0xFFF57C00.toInt()
        else -> 0xFF8A8A8E.toInt()
    }

    fun bgColor(status: Int): Int = when (status) {
        QUEUED -> 0x227C4DFF.toInt()
        RUNNING -> 0x241C63E1.toInt()
        DONE -> 0x2400B36E.toInt()
        FAILED -> 0x24E53935.toInt()
        PAUSED -> 0x24F57C00.toInt()
        else -> 0x1E8A8A8E.toInt()
    }
}

@Entity(tableName = "downloads")
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val dir: String,
    val qualityLabel: String,
    val formatLabel: String,
    val requestJson: String,
    val status: Int,
    val progress: Int = 0,
    val error: String? = null,
    val filePath: String? = null,
    @ColumnInfo(defaultValue = "0") val fileSize: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(entry: DownloadEntry): Long

    @Update
    suspend fun update(entry: DownloadEntry)

    @Query("SELECT * FROM downloads WHERE status = 0 ORDER BY id ASC LIMIT 1")
    suspend fun nextQueued(): DownloadEntry?

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 0")
    suspend fun queuedCount(): Int

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadEntry?

    @Query("SELECT * FROM downloads ORDER BY id DESC LIMIT 200")
    suspend fun recent(): List<DownloadEntry>

    @Query("SELECT * FROM downloads ORDER BY id DESC LIMIT 200")
    fun recentFlow(): kotlinx.coroutines.flow.Flow<List<DownloadEntry>>

    @Query("UPDATE downloads SET status = :status, progress = :progress, error = :error, updatedAt = :time WHERE id = :id")
    suspend fun updateStatus(
        id: Long,
        status: Int,
        progress: Int = 0,
        error: String? = null,
        time: Long = System.currentTimeMillis()
    )

    @Query("UPDATE downloads SET progress = :progress, updatedAt = :time WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int, time: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET filePath = :path, fileSize = :size, updatedAt = :time WHERE id = :id")
    suspend fun updateFileInfo(
        id: Long,
        path: String?,
        size: Long,
        time: Long = System.currentTimeMillis()
    )

    @Query("UPDATE downloads SET status = :status, updatedAt = :time WHERE status = 1")
    suspend fun cancelAllRunning(status: Int = DownloadStatus.CANCELLED, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads WHERE status IN (2, 3, 4)")
    suspend fun deleteFinished()

    /** إعادة محاولة عنصر فشل أو مُلغى فقط */
    @Query("UPDATE downloads SET status = 0, progress = 0, error = NULL, updatedAt = :time WHERE id = :id AND status IN (3, 4)")
    suspend fun markRetry(id: Long, time: Long = System.currentTimeMillis())

    /** إلغاء عنصر من الطابور (غير الجاري) */
    @Query("UPDATE downloads SET status = 4, updatedAt = :time WHERE id = :id AND status = 0")
    suspend fun cancelQueued(id: Long, time: Long = System.currentTimeMillis())

    @Query("SELECT * FROM downloads WHERE status = 1 ORDER BY id ASC")
    suspend fun allRunning(): List<DownloadEntry>

    @Query("SELECT * FROM downloads WHERE status = 0 ORDER BY id ASC")
    suspend fun allQueued(): List<DownloadEntry>

    @Query("SELECT * FROM downloads WHERE status = 5 ORDER BY id ASC")
    suspend fun allPaused(): List<DownloadEntry>

    /** استئناف صف متوقف مؤقتاً — يعود للطابور مع الحفاظ على التقدم الجزئي المحفوظ */
    @Query("UPDATE downloads SET status = 0, error = NULL, updatedAt = :time WHERE id = :id AND status = 5")
    suspend fun resumePaused(id: Long, time: Long = System.currentTimeMillis())
}

@Database(entities = [DownloadEntry::class], version = 3, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var instance: DownloadDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `filePath` TEXT")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `fileSize` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): DownloadDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "ytdlp_downloads.db"
                ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
