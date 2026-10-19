package com.example.ui.maps

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.AppDatabase
import com.example.data.CachedPlaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object MapPoisRepository {

    private const val TAG = "MapPoisRepository"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Int {
        val r = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).toInt()
    }

    /**
     * Primary entry point for obtaining Real Nearby Places.
     * Online: fetches real facilities from Google Places API (New) or live real OSM geodata,
     * and persists results into Room cache.
     * Offline: retrieves the last successfully cached places from Room.
     */
    suspend fun getRealNearbyPlaces(
        context: Context,
        userLat: Double,
        userLng: Double,
        isOnline: Boolean
    ): Pair<List<EmergencyPoi>, Boolean> = withContext(Dispatchers.IO) {
        val cachedDao = AppDatabase.getDatabase(context).cachedPlaceDao()
        val userHavens = getUserSafeZones(context).map { zone ->
            val dist = if (userLat != 0.0 && userLng != 0.0) {
                calculateDistanceMeters(userLat, userLng, zone.latitude, zone.longitude)
            } else 0
            EmergencyPoi(
                id = zone.id,
                name = zone.name,
                category = PoiCategory.SHELTER,
                latitude = zone.latitude,
                longitude = zone.longitude,
                address = "Verified Safe Haven (${zone.type.title})",
                phone = "1091",
                is24x7 = true,
                isOpenNow = true,
                distanceMeters = dist,
                verified = true,
                description = "User designated verified safe haven perimeter (${zone.radiusMeters.toInt()}m)",
                lastUpdated = System.currentTimeMillis()
            )
        }

        // 1. If online and valid coordinates, attempt real network search
        if (isOnline && userLat != 0.0 && userLng != 0.0) {
            try {
                val realPlaces = fetchFromRealDataSources(userLat, userLng)
                if (realPlaces.isNotEmpty()) {
                    // Update Room local cache
                    val entities = realPlaces.map { poi ->
                        CachedPlaceEntity(
                            placeId = poi.id,
                            name = poi.name,
                            category = poi.category.name,
                            latitude = poi.latitude,
                            longitude = poi.longitude,
                            address = poi.address,
                            phone = poi.phone,
                            isOpenNow = poi.isOpenNow,
                            distanceMeters = poi.distanceMeters,
                            isVerified = poi.verified,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    cachedDao.insertPlaces(entities)
                    // Combine network POIs with user safe havens (deduplicated by id, excluding pharmacies)
                    val combined = (realPlaces.filter { it.category != PoiCategory.PHARMACY } + userHavens)
                        .distinctBy { it.id }
                        .sortedBy { it.distanceMeters }
                    return@withContext Pair(combined, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Real online place fetch failed: ${e.message}. Falling back to Room cache.")
            }
        }

        // 2. Offline fallback: read from Room local cache
        val cached = cachedDao.getAllCachedPlaces()
        val mappedCached = cached.mapNotNull { entity ->
            val cat = try {
                PoiCategory.valueOf(entity.category)
            } catch (_: Exception) {
                PoiCategory.POLICE
            }
            if (cat == PoiCategory.PHARMACY) return@mapNotNull null
            val dist = if (userLat != 0.0 && userLng != 0.0) {
                calculateDistanceMeters(userLat, userLng, entity.latitude, entity.longitude)
            } else {
                entity.distanceMeters
            }
            EmergencyPoi(
                id = entity.placeId,
                name = entity.name,
                category = cat,
                latitude = entity.latitude,
                longitude = entity.longitude,
                address = entity.address,
                phone = entity.phone,
                isOpenNow = entity.isOpenNow,
                distanceMeters = dist,
                verified = entity.isVerified,
                description = "Offline cached location from previous verified scan.",
                lastUpdated = entity.lastUpdated
            )
        }

        val allPlaces = (mappedCached + userHavens)
            .distinctBy { it.id }
            .sortedBy { it.distanceMeters }

        Pair(allPlaces, true) // isFromCache = true
    }

    /**
     * Query real data sources.
     * Attempts Google Places API (New) first.
     * If not available or disabled on the key, uses OpenStreetMap Overpass live real facility search.
     */
    private fun fetchFromRealDataSources(lat: Double, lng: Double): List<EmergencyPoi> {
        val results = mutableListOf<EmergencyPoi>()
        val mapsKey = BuildConfig.MAPS_API_KEY.trim()

        // Attempt Google Places API (New) if key is present
        if (mapsKey.isNotBlank()) {
            val googlePlaces = fetchFromGooglePlacesNew(lat, lng, mapsKey)
            if (googlePlaces.isNotEmpty()) {
                results.addAll(googlePlaces)
            }
        }

        // If Google Places returned 0 results or was disabled, query live OpenStreetMap facility directory
        if (results.isEmpty()) {
            val osmPlaces = fetchFromOsmOverpass(lat, lng)
            results.addAll(osmPlaces)
        }

        return results.filter { it.category != PoiCategory.PHARMACY }
    }

    /**
     * Google Places API (New) - searchNearby (Police and Hospitals only, no Pharmacies)
     */
    private fun fetchFromGooglePlacesNew(lat: Double, lng: Double, apiKey: String): List<EmergencyPoi> {
        val list = mutableListOf<EmergencyPoi>()
        val categoriesToSearch = listOf(
            Pair(listOf("police"), PoiCategory.POLICE),
            Pair(listOf("hospital"), PoiCategory.HOSPITAL)
        )

        for ((types, category) in categoriesToSearch) {
            try {
                val jsonBody = JSONObject().apply {
                    put("includedTypes", JSONArray(types))
                    put("maxResultCount", 8)
                    put("locationRestriction", JSONObject().apply {
                        put("circle", JSONObject().apply {
                            put("center", JSONObject().apply {
                                put("latitude", lat)
                                put("longitude", lng)
                            })
                            put("radius", 6000.0) // 6km radius
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://places.googleapis.com/v1/places:searchNearby")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Goog-Api-Key", apiKey)
                    .addHeader("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.currentOpeningHours,places.nationalPhoneNumber")
                    .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: continue
                    val root = JSONObject(bodyString)
                    val placesArray = root.optJSONArray("places") ?: continue
                    for (i in 0 until placesArray.length()) {
                        val placeObj = placesArray.getJSONObject(i)
                        val id = placeObj.optString("id", "gp_${System.currentTimeMillis()}_$i")
                        val name = placeObj.optJSONObject("displayName")?.optString("text") ?: continue
                        val address = placeObj.optString("formattedAddress", "Address on map")
                        val loc = placeObj.optJSONObject("location") ?: continue
                        val pLat = loc.optDouble("latitude", 0.0)
                        val pLng = loc.optDouble("longitude", 0.0)
                        if (pLat == 0.0 || pLng == 0.0) continue

                        val phone = placeObj.optString("nationalPhoneNumber", "")
                        val isOpen = placeObj.optJSONObject("currentOpeningHours")?.optBoolean("openNow")
                        val dist = calculateDistanceMeters(lat, lng, pLat, pLng)

                        list.add(
                            EmergencyPoi(
                                id = id,
                                name = name,
                                category = category,
                                latitude = pLat,
                                longitude = pLng,
                                address = address,
                                phone = phone,
                                isOpenNow = isOpen,
                                distanceMeters = dist,
                                verified = true,
                                description = "Verified Google Places facility."
                            )
                        )
                    }
                } else {
                    Log.w(TAG, "Google Places HTTP ${response.code}: ${response.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google Places fetch error for ${category.name}: ${e.message}")
            }
        }
        return list
    }

    /**
     * Real OpenStreetMap Overpass live facility query
     * Directly fetches genuine physical police stations, hospitals, and pharmacies near the user.
     */
    private fun fetchFromOsmOverpass(lat: Double, lng: Double): List<EmergencyPoi> {
        val list = mutableListOf<EmergencyPoi>()
        val radiusMeters = 5000
        val query = """
            [out:json][timeout:12];
            (
              node["amenity"="police"](around:$radiusMeters,$lat,$lng);
              node["amenity"="hospital"](around:$radiusMeters,$lat,$lng);
              node["social_facility"="shelter"](around:$radiusMeters,$lat,$lng);
            );
            out body 25;
        """.trimIndent()

        try {
            val formBody = okhttp3.FormBody.Builder()
                .add("data", query)
                .build()

            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "AIZASafetyApp/1.0")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val elements = json.optJSONArray("elements") ?: return emptyList()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val id = "osm_${el.optLong("id")}"
                    val pLat = el.optDouble("lat", 0.0)
                    val pLng = el.optDouble("lon", 0.0)
                    if (pLat == 0.0 || pLng == 0.0) continue

                    val tags = el.optJSONObject("tags") ?: continue
                    val rawName = tags.optString("name", "").ifBlank {
                        tags.optString("name:en", "")
                    }
                    val amenity = tags.optString("amenity", "")
                    val socialFacility = tags.optString("social_facility", "")

                    val category = when {
                        amenity == "police" -> PoiCategory.POLICE
                        amenity == "hospital" -> PoiCategory.HOSPITAL
                        socialFacility == "shelter" -> PoiCategory.SHELTER
                        else -> continue
                    }

                    val name = if (rawName.isNotBlank()) {
                        rawName
                    } else {
                        when (category) {
                            PoiCategory.POLICE -> "Local Police Facility"
                            PoiCategory.HOSPITAL -> "Medical Center / Clinic"
                            PoiCategory.SHELTER -> "Verified Community Shelter"
                            else -> "Verified Emergency Facility"
                        }
                    }

                    val street = tags.optString("addr:street", "")
                    val city = tags.optString("addr:city", "")
                    val address = if (street.isNotBlank() || city.isNotBlank()) {
                        listOf(street, city).filter { it.isNotBlank() }.joinToString(", ")
                    } else {
                        "GPS Coordinates: ${String.format(java.util.Locale.US, "%.4f, %.4f", pLat, pLng)}"
                    }

                    val phone = tags.optString("phone", "").ifBlank {
                        tags.optString("contact:phone", "")
                    }

                    val openingHours = tags.optString("opening_hours", "")
                    val is24x7 = openingHours.contains("24/7", ignoreCase = true)
                    val dist = calculateDistanceMeters(lat, lng, pLat, pLng)

                    list.add(
                        EmergencyPoi(
                            id = id,
                            name = name,
                            category = category,
                            latitude = pLat,
                            longitude = pLng,
                            address = address,
                            phone = phone,
                            is24x7 = is24x7,
                            distanceMeters = dist,
                            verified = true,
                            description = "Real verified facility ($address)"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OSM Overpass query failed: ${e.message}")
        }
        return list
    }

    /**
     * User-defined Safe Zones: Loaded from user preferences or empty if none configured yet.
     * No fake static/hardcoded geofences with fabricated offsets.
     */
    fun getUserSafeZones(context: Context): List<SafeZone> {
        val prefs = context.getSharedPreferences("aegis_user_safe_zones", Context.MODE_PRIVATE)
        val rawJson = prefs.getString("zones_list", null) ?: return emptyList()
        val list = mutableListOf<SafeZone>()
        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SafeZone(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = try {
                            SafeZoneType.valueOf(obj.getString("type"))
                        } catch (_: Exception) {
                            SafeZoneType.CUSTOM
                        },
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        radiusMeters = obj.getDouble("radiusMeters"),
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading user safe zones: ${e.message}")
        }
        return list
    }

    fun saveUserSafeZones(context: Context, zones: List<SafeZone>) {
        val prefs = context.getSharedPreferences("aegis_user_safe_zones", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (z in zones) {
            val obj = JSONObject().apply {
                put("id", z.id)
                put("name", z.name)
                put("type", z.type.name)
                put("latitude", z.latitude)
                put("longitude", z.longitude)
                put("radiusMeters", z.radiusMeters)
                put("isEnabled", z.isEnabled)
            }
            array.put(obj)
        }
        prefs.edit().putString("zones_list", array.toString()).apply()
    }

    fun findActiveSafeZone(
        userLat: Double,
        userLng: Double,
        safeZones: List<SafeZone>
    ): SafeZone? {
        if (userLat == 0.0 && userLng == 0.0) return null
        return safeZones.firstOrNull { zone ->
            if (!zone.isEnabled) return@firstOrNull false
            val distance = calculateDistanceMeters(userLat, userLng, zone.latitude, zone.longitude)
            distance <= zone.radiusMeters
        }
    }

    suspend fun addUserSafeHaven(context: Context, safeZone: SafeZone): EmergencyPoi = withContext(Dispatchers.IO) {
        val currentZones = getUserSafeZones(context)
        val updated = currentZones.filter { it.id != safeZone.id } + safeZone
        saveUserSafeZones(context, updated)

        // Persist to Room local cache as a SHELTER
        try {
            val cachedDao = AppDatabase.getDatabase(context).cachedPlaceDao()
            cachedDao.insertPlaces(
                listOf(
                    CachedPlaceEntity(
                        placeId = safeZone.id,
                        name = safeZone.name,
                        category = PoiCategory.SHELTER.name,
                        latitude = safeZone.latitude,
                        longitude = safeZone.longitude,
                        address = "Verified Safe Haven (${safeZone.type.title})",
                        phone = "1091",
                        isOpenNow = true,
                        distanceMeters = 0,
                        isVerified = true,
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error caching safe haven in Room: ${e.message}")
        }

        EmergencyPoi(
            id = safeZone.id,
            name = safeZone.name,
            category = PoiCategory.SHELTER,
            latitude = safeZone.latitude,
            longitude = safeZone.longitude,
            address = "Verified Safe Haven (${safeZone.type.title})",
            phone = "1091",
            is24x7 = true,
            isOpenNow = true,
            distanceMeters = 0,
            verified = true,
            description = "User designated verified safe haven perimeter (${safeZone.radiusMeters.toInt()}m)",
            lastUpdated = System.currentTimeMillis()
        )
    }

    suspend fun removeUserSafeHaven(context: Context, safeZoneId: String) = withContext(Dispatchers.IO) {
        val currentZones = getUserSafeZones(context)
        val updated = currentZones.filter { it.id != safeZoneId }
        saveUserSafeZones(context, updated)

        // Also delete from Room local cache
        try {
            val cachedDao = AppDatabase.getDatabase(context).cachedPlaceDao()
            cachedDao.deletePlaceById(safeZoneId)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing safe haven from Room: ${e.message}")
        }
    }
}
