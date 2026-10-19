package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig

class PreferencesManager(context: Context) {
    val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences = context.getSharedPreferences("aegis_safety_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PHONE = "user_phone"
        private const val KEY_USER_PASSWORD = "user_password"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_SHAKE_ENABLED = "shake_enabled"
        private const val KEY_DEVICE_PUBLIC_KEY = "device_public_key"
        private const val KEY_DEVICE_PRIVATE_KEY = "device_private_key"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_SUPABASE_USER_ID = "supabase_user_id"
    }

    var supabaseUserId: String
        get() = prefs.getString(KEY_SUPABASE_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUPABASE_USER_ID, value).apply()

    var isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var userName: String
        get() = SecureStorageHelper.decrypt(prefs.getString(KEY_USER_NAME, "") ?: "")
        set(value) = prefs.edit().putString(KEY_USER_NAME, SecureStorageHelper.encrypt(value)).apply()

    var userPhone: String
        get() {
            val raw = SecureStorageHelper.decrypt(prefs.getString(KEY_USER_PHONE, "") ?: "")
            if (raw.contains("@")) {
                if (userEmail.isBlank()) {
                    userEmail = raw.trim()
                }
                prefs.edit().remove(KEY_USER_PHONE).apply()
                return ""
            }
            return raw
        }
        set(value) {
            val cleaned = value.trim()
            if (cleaned.contains("@")) {
                if (userEmail.isBlank()) {
                    userEmail = cleaned
                }
                return
            }
            prefs.edit().putString(KEY_USER_PHONE, SecureStorageHelper.encrypt(cleaned)).apply()
        }

    var userEmail: String
        get() = SecureStorageHelper.decrypt(prefs.getString(KEY_USER_EMAIL, "") ?: "")
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, SecureStorageHelper.encrypt(value.trim())).apply()

    var userPassword: String
        get() = SecureStorageHelper.decrypt(prefs.getString(KEY_USER_PASSWORD, "") ?: "")
        set(value) = prefs.edit().putString(KEY_USER_PASSWORD, SecureStorageHelper.encrypt(value)).apply()

    var isShakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHAKE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SHAKE_ENABLED, value).apply()

    var isLocationEnabled: Boolean
        get() = prefs.getBoolean("location_enabled", true)
        set(value) = prefs.edit().putBoolean("location_enabled", value).apply()

    var isBluetoothEnabled: Boolean
        get() = prefs.getBoolean("bluetooth_enabled", false)
        set(value) = prefs.edit().putBoolean("bluetooth_enabled", value).apply()

    var isVoiceSosEnabled: Boolean
        get() = prefs.getBoolean("voice_sos_enabled", false)
        set(value) = prefs.edit().putBoolean("voice_sos_enabled", value).apply()

    var isWifiEnabled: Boolean
        get() = prefs.getBoolean("wifi_enabled", false)
        set(value) = prefs.edit().putBoolean("wifi_enabled", value).apply()

    val shakeSensitivity: Float = 12.0f

    var devicePublicKey: String
        get() = prefs.getString(KEY_DEVICE_PUBLIC_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_PUBLIC_KEY, value).apply()

    var devicePrivateKey: String
        get() = prefs.getString(KEY_DEVICE_PRIVATE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_PRIVATE_KEY, value).apply()

    var isDarkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, true)
        set(value) = prefs.edit().putBoolean(KEY_DARK_THEME, value).apply()

    var appTheme: String
        get() = prefs.getString("app_theme", "light") ?: "light"
        set(value) = prefs.edit().putString("app_theme", value).apply()

    var accentColor: String
        get() = prefs.getString("accent_color", "magenta") ?: "magenta"
        set(value) = prefs.edit().putString("accent_color", value).apply()

    var profileImageUri: String
        get() = prefs.getString("profile_image_uri", "") ?: ""
        set(value) = prefs.edit().putString("profile_image_uri", value).apply()

    var medicalNotes: String
        get() = prefs.getString("medical_notes", "") ?: ""
        set(value) = prefs.edit().putString("medical_notes", value).apply()

    var bloodGroup: String
        get() = prefs.getString("blood_group", "") ?: ""
        set(value) = prefs.edit().putString("blood_group", value).apply()

    var allergies: String
        get() = prefs.getString("allergies", "") ?: ""
        set(value) = prefs.edit().putString("allergies", value).apply()

    var medicalConditions: String
        get() = prefs.getString("medical_conditions", "") ?: ""
        set(value) = prefs.edit().putString("medical_conditions", value).apply()

    var isSendVoiceClipWithSos: Boolean
        get() = prefs.getBoolean("send_voice_clip_with_sos", true)
        set(value) = prefs.edit().putBoolean("send_voice_clip_with_sos", value).apply()

    var shareGpsTracking: Boolean
        get() = prefs.getBoolean("share_gps_tracking", true)
        set(value) = prefs.edit().putBoolean("share_gps_tracking", value).apply()

    var shareBatteryStatus: Boolean
        get() = prefs.getBoolean("share_battery_status", true)
        set(value) = prefs.edit().putBoolean("share_battery_status", value).apply()

    var shareMedicalInfo: Boolean
        get() = prefs.getBoolean("share_medical_info", true)
        set(value) = prefs.edit().putBoolean("share_medical_info", value).apply()

    val isMedicalSharingExplicitlyConfigured: Boolean
        get() = prefs.contains("share_medical_info")

    var isHardwareLinkEnabled: Boolean
        get() = prefs.getBoolean("hardware_link_enabled", false)
        set(value) = prefs.edit().putBoolean("hardware_link_enabled", value).apply()

    var trustedBluetoothDeviceId: String
        get() = prefs.getString("trusted_bt_device_id", "") ?: ""
        set(value) = prefs.edit().putString("trusted_bt_device_id", value).apply()

    var trustedBluetoothDeviceName: String
        get() = prefs.getString("trusted_bt_device_name", "") ?: ""
        set(value) = prefs.edit().putString("trusted_bt_device_name", value).apply()

    var trustedBluetoothDeviceType: String
        get() = prefs.getString("trusted_bt_device_type", "UNKNOWN") ?: "UNKNOWN"
        set(value) = prefs.edit().putString("trusted_bt_device_type", value).apply()

    fun isBluetoothDeviceTrusted(deviceId: String): Boolean {
        val saved = trustedBluetoothDeviceId
        return saved.isNotBlank() && saved.equals(deviceId, ignoreCase = true)
    }

    fun trustBluetoothDevice(deviceId: String, deviceName: String, deviceType: String) {
        prefs.edit()
            .putString("trusted_bt_device_id", deviceId)
            .putString("trusted_bt_device_name", deviceName)
            .putString("trusted_bt_device_type", deviceType)
            .apply()
    }

    fun removeBluetoothDeviceTrust(deviceId: String? = null) {
        prefs.edit()
            .remove("trusted_bt_device_id")
            .remove("trusted_bt_device_name")
            .remove("trusted_bt_device_type")
            .apply()
    }

    var countryCode: String
        get() = prefs.getString("user_country_code", "+91") ?: "+91"
        set(value) = prefs.edit().putString("user_country_code", value.trim()).apply()

    val googleMapsApiKey: String = BuildConfig.MAPS_API_KEY
    val supabaseUrl: String = BuildConfig.SUPABASE_URL
    val supabaseAnonKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    var lastLatitude: Float
        get() = if (hasAcquiredRealGpsFix) prefs.getFloat("last_latitude", 0f) else 0f
        set(value) = prefs.edit().putFloat("last_latitude", value).apply()

    var lastLongitude: Float
        get() = if (hasAcquiredRealGpsFix) prefs.getFloat("last_longitude", 0f) else 0f
        set(value) = prefs.edit().putFloat("last_longitude", value).apply()

    var lastLocationTimestamp: Long
        get() = prefs.getLong("last_location_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_location_timestamp", value).apply()

    var hasAcquiredRealGpsFix: Boolean
        get() = prefs.getBoolean("has_acquired_real_gps_fix", false)
        set(value) = prefs.edit().putBoolean("has_acquired_real_gps_fix", value).apply()

    var isContinuousLocationSharingEnabled: Boolean
        get() = prefs.getBoolean("continuous_location_sharing_enabled", false)
        set(value) = prefs.edit().putBoolean("continuous_location_sharing_enabled", value).apply()

    var lastLocationSyncTimestamp: Long
        get() = prefs.getLong("last_location_sync_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_location_sync_timestamp", value).apply()

    fun clearProfile() {
        prefs.edit()
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_PHONE)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_PASSWORD)
            .remove(KEY_IS_LOGGED_IN)
            .remove(KEY_ONBOARDING_COMPLETE)
            .remove("profile_image_uri")
            .apply()
    }
}
