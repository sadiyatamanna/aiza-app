package com.example.data

enum class BluetoothDeviceType {
    ESP32_PANIC_BUTTON,
    BLE_WEARABLE,
    WEAR_OS,
    UNKNOWN;

    fun getDisplayName(): String = when (this) {
        ESP32_PANIC_BUTTON -> "ESP32 Panic Button"
        BLE_WEARABLE -> "BLE Wearable"
        WEAR_OS -> "Wear OS"
        UNKNOWN -> "Unknown"
    }

    companion object {
        fun fromDeviceName(name: String?): BluetoothDeviceType {
            if (name.isNullOrBlank()) return UNKNOWN
            val upper = name.uppercase()
            return when {
                upper.contains("ESP32") || upper.contains("PANIC") || upper.contains("SOS_BUTTON") -> ESP32_PANIC_BUTTON
                upper.contains("WEAR_OS") || upper.contains("GALAXY WATCH") || upper.contains("PIXEL WATCH") -> WEAR_OS
                upper.contains("WEAR") || upper.contains("WATCH") || upper.contains("BAND") || upper.contains("BLE") || upper.contains("AIZA") || upper.contains("AEGIS") -> BLE_WEARABLE
                else -> UNKNOWN
            }
        }
    }
}

enum class BluetoothDeviceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR;

    fun getDisplayName(): String = when (this) {
        DISCONNECTED -> "Disconnected"
        CONNECTING -> "Connecting"
        CONNECTED -> "Connected"
        ERROR -> "Error"
    }
}

data class BluetoothSafetyDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: BluetoothDeviceType,
    val connectionState: BluetoothDeviceConnectionState = BluetoothDeviceConnectionState.DISCONNECTED,
    val lastSeen: Long = System.currentTimeMillis(),
    val isTrusted: Boolean = false
)
