package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_places")
data class CachedPlaceEntity(
    @PrimaryKey val placeId: String,
    val name: String,
    val category: String, // "POLICE", "HOSPITAL", "PHARMACY", "SHELTER"
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val phone: String = "",
    val isOpenNow: Boolean? = null,
    val distanceMeters: Int = 0,
    val isVerified: Boolean = true,
    val lastUpdated: Long = System.currentTimeMillis()
)
