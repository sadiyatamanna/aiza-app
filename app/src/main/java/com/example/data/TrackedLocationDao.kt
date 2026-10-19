package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedLocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: TrackedLocationEntity): Long

    @Query("SELECT * FROM tracked_locations ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLocationsFlow(limit: Int = 200): Flow<List<TrackedLocationEntity>>

    @Query("SELECT * FROM tracked_locations ORDER BY timestamp ASC")
    suspend fun getAllLocations(): List<TrackedLocationEntity>

    @Query("SELECT * FROM tracked_locations WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLocations(): List<TrackedLocationEntity>

    @Query("UPDATE tracked_locations SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM tracked_locations WHERE isSynced = 0")
    suspend fun getUnsyncedCount(): Int

    @Query("DELETE FROM tracked_locations WHERE timestamp < :beforeTimestamp")
    suspend fun clearOldLocations(beforeTimestamp: Long)
}
