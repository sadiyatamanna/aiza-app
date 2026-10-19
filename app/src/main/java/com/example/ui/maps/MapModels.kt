package com.example.ui.maps

import androidx.compose.ui.graphics.Color

enum class PoiCategory(
    val title: String,
    val shortLabel: String,
    val colorHex: String,
    val badgeColor: Color,
    val emergencyNumber: String
) {
    POLICE(
        title = "Police Stations",
        shortLabel = "Police",
        colorHex = "#2563EB",
        badgeColor = Color(0xFF2563EB),
        emergencyNumber = "100"
    ),
    HOSPITAL(
        title = "24/7 Hospitals & ER",
        shortLabel = "Hospital",
        colorHex = "#DC2626",
        badgeColor = Color(0xFFDC2626),
        emergencyNumber = "102"
    ),
    SHELTER(
        title = "Women Safe Havens",
        shortLabel = "Safe Haven",
        colorHex = "#9333EA",
        badgeColor = Color(0xFF9333EA),
        emergencyNumber = "1091"
    ),
    PHARMACY(
        title = "24/7 Pharmacies",
        shortLabel = "Pharmacy",
        colorHex = "#059669",
        badgeColor = Color(0xFF059669),
        emergencyNumber = "112"
    )
}

data class EmergencyPoi(
    val id: String,
    val name: String,
    val category: PoiCategory,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val phone: String,
    val is24x7: Boolean = false,
    val isOpenNow: Boolean? = null,
    val distanceMeters: Int = 0,
    val verified: Boolean = true,
    val description: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val walkingEtaMinutes: Int
        get() = ((distanceMeters / 80.0)).toInt().coerceAtLeast(1) // avg 4.8 km/h = 80m/min

    val drivingEtaMinutes: Int
        get() = ((distanceMeters / 400.0)).toInt().coerceAtLeast(1) // avg 24 km/h in city = 400m/min

    val formattedDistance: String
        get() = if (distanceMeters < 1000) {
            "${distanceMeters}m"
        } else {
            String.format(java.util.Locale.US, "%.1f km", distanceMeters / 1000.0)
        }
}

enum class SafeZoneType(val title: String, val defaultRadius: Double) {
    HOME("Home Safe Hub", 250.0),
    WORK("Workplace Zone", 300.0),
    CAMPUS("University / Campus", 500.0),
    CUSTOM("Custom Safe Point", 200.0)
}

data class SafeZone(
    val id: String,
    val name: String,
    val type: SafeZoneType,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val isEnabled: Boolean = true
)

enum class MapTileLayer(val title: String, val code: String) {
    STREETS("Streets", "streets"),
    DARK("Night Watch", "dark"),
    SATELLITE("Satellite", "satellite"),
    SAFETY_HEATMAP("Safety Heatmap", "heatmap")
}

data class CityPreset(
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)
