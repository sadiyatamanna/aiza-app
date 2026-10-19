package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SosLogDao {
    @Query("SELECT * FROM sos_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<SosLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SosLogEntity): Long

    @Query("DELETE FROM sos_logs")
    suspend fun clearAllLogs()

    @Query("SELECT * FROM sos_logs WHERE status = 'Pending_Sync' OR status = 'Pending' ORDER BY timestamp ASC")
    suspend fun getUnsyncedSosLogs(): List<SosLogEntity>

    @Query("UPDATE sos_logs SET status = :status WHERE id = :id")
    suspend fun updateLogStatus(id: Int, status: String)

    @Query("UPDATE sos_logs SET voiceClipUrl = :url, status = :status WHERE id = :id")
    suspend fun updateVoiceClipUrl(id: Int, url: String, status: String)
}
