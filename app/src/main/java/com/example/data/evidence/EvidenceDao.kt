package com.example.data.evidence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: EvidenceRecordEntity)

    @Update
    suspend fun update(record: EvidenceRecordEntity)

    @Delete
    suspend fun delete(record: EvidenceRecordEntity)

    @Query("DELETE FROM evidence_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM evidence_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EvidenceRecordEntity?

    @Query("SELECT * FROM evidence_records ORDER BY captureTimestamp DESC")
    fun observeAll(): Flow<List<EvidenceRecordEntity>>

    @Query("SELECT * FROM evidence_records WHERE userId = :userId ORDER BY captureTimestamp DESC")
    fun observeAllByUser(userId: String): Flow<List<EvidenceRecordEntity>>

    @Query("SELECT * FROM evidence_records WHERE sosEventId = :sosEventId ORDER BY captureTimestamp DESC")
    fun observeBySosEvent(sosEventId: String): Flow<List<EvidenceRecordEntity>>

    @Query("SELECT * FROM evidence_records WHERE userId = :userId AND syncState != 'Backed Up' ORDER BY captureTimestamp ASC")
    suspend fun getPendingSync(userId: String): List<EvidenceRecordEntity>

    @Query("UPDATE evidence_records SET syncState = :state, storageObjectPath = :remotePath, lastSyncError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSyncState(id: String, state: String, remotePath: String?, error: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE evidence_records SET verificationState = :state, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateVerificationState(id: String, state: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM evidence_records WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}
