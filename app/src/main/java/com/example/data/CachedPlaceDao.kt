package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedPlaceDao {
    @Query("SELECT * FROM cached_places ORDER BY distanceMeters ASC")
    suspend fun getAllCachedPlaces(): List<CachedPlaceEntity>

    @Query("SELECT * FROM cached_places WHERE category = :category ORDER BY distanceMeters ASC")
    suspend fun getCachedPlacesByCategory(category: String): List<CachedPlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaces(places: List<CachedPlaceEntity>)

    @Query("DELETE FROM cached_places")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM cached_places")
    suspend fun getCachedCount(): Int

    @Query("DELETE FROM cached_places WHERE placeId = :placeId")
    suspend fun deletePlaceById(placeId: String)
}
