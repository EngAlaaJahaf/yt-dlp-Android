package com.ytdlpgui.data.db

import android.content.Context
import androidx.room.RoomDatabase

/**
 * عقد التخزين الذي يحتاجه الـViewModel فقط — يسهل اختباره.
 */
interface DownloadStore {
    suspend fun insert(entry: DownloadEntry): Long
    suspend fun cancelAllRunning()
    suspend fun recent(): List<DownloadEntry>
    suspend fun allPaused(): List<DownloadEntry>
    suspend fun resume(id: Long)
    suspend fun updateStatus(id: Long, status: Int)
}

class RoomDownloadStore(context: Context) : DownloadStore {
    private val db: DownloadDatabase = DownloadDatabase.get(context)
    override suspend fun insert(entry: DownloadEntry): Long = db.downloadDao().insert(entry)
    override suspend fun cancelAllRunning() = db.downloadDao().cancelAllRunning()
    override suspend fun recent(): List<DownloadEntry> = db.downloadDao().recent()
    override suspend fun allPaused(): List<DownloadEntry> = db.downloadDao().allPaused()
    override suspend fun resume(id: Long) = db.downloadDao().resumePaused(id)
    override suspend fun updateStatus(id: Long, status: Int) = db.downloadDao().updateStatus(id, status)
}
