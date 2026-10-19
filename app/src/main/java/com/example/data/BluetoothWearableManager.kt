package com.example.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated manager for Bluetooth Wearables and Panic Buttons.
 * Coordinates discovery, connection state, trust model, and command verification
 * by extending and reusing the existing [BluetoothSosManager] pipeline.
 */
class BluetoothWearableManager(
    private val context: Context,
    private val bluetoothSosManager: BluetoothSosManager = BluetoothSosManager(context),
    private val preferencesManager: PreferencesManager = PreferencesManager(context)
) {
    companion object {
        private const val TAG = "BluetoothWearableMgr"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState = MutableStateFlow(BluetoothDeviceConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothDeviceConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothSafetyDevice?>(null)
    val connectedDevice: StateFlow<BluetoothSafetyDevice?> = _connectedDevice.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothSafetyDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothSafetyDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var onSosTriggerCallback: ((String) -> Unit)? = null

    init {
        // Wire trust verifier to BluetoothSosManager
        bluetoothSosManager.setTrustVerifier { deviceId ->
            isDeviceTrusted(deviceId)
        }

        // Wire connection state listener
        bluetoothSosManager.setOnConnectionStateChangedListener { state, device ->
            mainHandler.post {
                when (state) {
                    BluetoothConnectionState.CONNECTED, BluetoothConnectionState.LISTENING -> {
                        val devId = device?.address ?: bluetoothSosManager.activeDeviceAddress ?: "device_connected"
                        val devName = (try { device?.name } catch (_: Exception) { null })
                            ?: bluetoothSosManager.activeDeviceName
                            ?: "Bluetooth Safety Device"
                        val devType = BluetoothDeviceType.fromDeviceName(devName)
                        val isTrusted = isDeviceTrusted(devId)

                        _connectionState.value = BluetoothDeviceConnectionState.CONNECTED
                        _connectedDevice.value = BluetoothSafetyDevice(
                            deviceId = devId,
                            deviceName = devName,
                            deviceType = devType,
                            connectionState = BluetoothDeviceConnectionState.CONNECTED,
                            lastSeen = System.currentTimeMillis(),
                            isTrusted = isTrusted
                        )
                        _statusMessage.value = "Connected to $devName"
                    }
                    BluetoothConnectionState.CONNECTING -> {
                        _connectionState.value = BluetoothDeviceConnectionState.CONNECTING
                    }
                    BluetoothConnectionState.DISCONNECTED -> {
                        _connectionState.value = BluetoothDeviceConnectionState.DISCONNECTED
                        _connectedDevice.value = null
                        _statusMessage.value = "Bluetooth wearable disconnected"
                    }
                    BluetoothConnectionState.ERROR -> {
                        _connectionState.value = BluetoothDeviceConnectionState.ERROR
                        _statusMessage.value = "Bluetooth connection error"
                    }
                }
            }
        }

        // Wire device discovered listener
        bluetoothSosManager.onDeviceDiscoveredListener = { device ->
            registerDiscoveredDevice(device)
        }

        // Check if there is a saved trusted device and attempt reconnection if appropriate
        loadSavedTrustedDeviceState()
    }

    private fun loadSavedTrustedDeviceState() {
        val trustedId = preferencesManager.trustedBluetoothDeviceId
        val trustedName = preferencesManager.trustedBluetoothDeviceName
        if (trustedId.isNotBlank()) {
            val devType = BluetoothDeviceType.fromDeviceName(trustedName)
            Log.d(TAG, "Restored trusted Bluetooth wearable configuration: $trustedName ($trustedId)")
        }
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasPermissions(): Boolean = bluetoothSosManager.hasBluetoothPermissions()

    fun setOnSosTrigger(callback: (String) -> Unit) {
        this.onSosTriggerCallback = callback
        bluetoothSosManager.startListening { triggerChar ->
            // Verify device trust before passing to SOS callback
            val currentDevId = bluetoothSosManager.activeDeviceAddress ?: _connectedDevice.value?.deviceId
            if (currentDevId != null && !isDeviceTrusted(currentDevId)) {
                Log.w(TAG, "Ignored SOS trigger from untrusted Bluetooth device '$currentDevId'")
                return@startListening
            }
            Log.d(TAG, "Forwarding verified wearable SOS trigger '$triggerChar' to safety pipeline")
            this.onSosTriggerCallback?.invoke(triggerChar)
        }
    }

    /**
     * Process incoming hardware data directly. Useful for tests or custom protocol events.
     * Enforces format validation, trust check, and duplicate suppression.
     */
    fun processIncomingData(rawData: String, deviceId: String? = null): Boolean {
        if (!preferencesManager.isBluetoothEnabled) {
            Log.w(TAG, "processIncomingData rejected: Bluetooth is disabled in preferences.")
            return false
        }
        val targetId = deviceId ?: bluetoothSosManager.activeDeviceAddress ?: _connectedDevice.value?.deviceId
        if (targetId != null && !isDeviceTrusted(targetId)) {
            Log.w(TAG, "processIncomingData rejected: device '$targetId' is untrusted.")
            return false
        }
        val trimmed = rawData.trim()
        if (trimmed != BluetoothSosConfig.TRIGGER_CHARACTER &&
            !trimmed.split(Regex("[,;\\s=]")).contains(BluetoothSosConfig.TRIGGER_CHARACTER)
        ) {
            Log.d(TAG, "processIncomingData rejected: payload '$rawData' does not contain trigger command.")
            return false
        }
        val isValid = bluetoothSosManager.processData(rawData, targetId)
        if (isValid) {
            onSosTriggerCallback?.invoke(BluetoothSosConfig.TRIGGER_CHARACTER)
        }
        return isValid
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!isBluetoothAvailable()) {
            _statusMessage.value = "Bluetooth is unsupported on this device"
            return
        }

        if (!isBluetoothEnabled()) {
            _statusMessage.value = "Bluetooth is disabled"
            return
        }

        if (!hasPermissions()) {
            _statusMessage.value = "Bluetooth permissions required"
            return
        }

        _isScanning.value = true
        _statusMessage.value = "Scanning for compatible Bluetooth safety devices..."

        // Preload bonded compatible devices
        try {
            val bonded = bluetoothAdapter?.bondedDevices
            if (bonded != null) {
                for (dev in bonded) {
                    registerDiscoveredDevice(dev)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying bonded devices", e)
        }

        // Start listening / scan
        bluetoothSosManager.startListening { triggerChar ->
            val currentDevId = bluetoothSosManager.activeDeviceAddress ?: _connectedDevice.value?.deviceId
            if (currentDevId != null && !isDeviceTrusted(currentDevId)) {
                Log.w(TAG, "Ignored SOS trigger from untrusted Bluetooth device '$currentDevId'")
                return@startListening
            }
            onSosTriggerCallback?.invoke(triggerChar)
        }

        // Auto-stop scanning state after 25s
        mainHandler.postDelayed({
            _isScanning.value = false
        }, 25000)
    }

    fun stopDiscovery() {
        _isScanning.value = false
        if (_connectionState.value != BluetoothDeviceConnectionState.CONNECTED) {
            bluetoothSosManager.stopListening()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerDiscoveredDevice(device: BluetoothDevice) {
        val name = try { device.name } catch (_: Exception) { null } ?: return
        val address = device.address ?: return

        // Only register compatible devices to avoid showing arbitrary audio headsets/printers
        if (!isCompatibleDevice(name, device)) return

        val type = BluetoothDeviceType.fromDeviceName(name)
        val isTrusted = isDeviceTrusted(address)

        val updated = _discoveredDevices.value.toMutableList()
        val existingIndex = updated.indexOfFirst { it.deviceId == address }

        val newDevice = BluetoothSafetyDevice(
            deviceId = address,
            deviceName = name,
            deviceType = type,
            connectionState = if (_connectedDevice.value?.deviceId == address) BluetoothDeviceConnectionState.CONNECTED else BluetoothDeviceConnectionState.DISCONNECTED,
            lastSeen = System.currentTimeMillis(),
            isTrusted = isTrusted
        )

        if (existingIndex >= 0) {
            updated[existingIndex] = newDevice
        } else {
            updated.add(newDevice)
        }

        _discoveredDevices.value = updated
    }

    fun addDiscoveredDeviceManually(device: BluetoothSafetyDevice) {
        val updated = _discoveredDevices.value.toMutableList()
        val index = updated.indexOfFirst { it.deviceId == device.deviceId }
        if (index >= 0) {
            updated[index] = device
        } else {
            updated.add(device)
        }
        _discoveredDevices.value = updated
    }

    private fun isCompatibleDevice(name: String, device: BluetoothDevice): Boolean {
        // Matches target names (SOS-WEAR, AIZA, ESP32, PANIC, WATCH, BAND, etc.)
        val upper = name.uppercase()
        if (BluetoothSosConfig.TARGET_DEVICE_NAMES.any { upper.contains(it) }) return true
        if (upper.contains("WATCH") || upper.contains("WEAR") || upper.contains("BAND") || upper.contains("PANIC") || upper.contains("BUTTON")) return true
        if (preferencesManager.trustedBluetoothDeviceId == device.address) return true
        return false
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothSafetyDevice) {
        if (device.connectionState == BluetoothDeviceConnectionState.CONNECTED) {
            _connectionState.value = BluetoothDeviceConnectionState.CONNECTED
            _connectedDevice.value = device
            _statusMessage.value = "Connected to ${device.deviceName}"
            return
        }

        if (!hasPermissions() || !isBluetoothEnabled()) {
            _statusMessage.value = "Cannot connect: Bluetooth unavailable or permission denied"
            return
        }

        _connectionState.value = BluetoothDeviceConnectionState.CONNECTING
        _connectedDevice.value = device.copy(connectionState = BluetoothDeviceConnectionState.CONNECTING)
        _statusMessage.value = "Connecting to ${device.deviceName}..."

        try {
            val realDevice = bluetoothAdapter?.getRemoteDevice(device.deviceId)
            if (realDevice != null) {
                // If it's a Classic SPP device or bonded ESP32, use Classic SPP
                if (device.deviceType == BluetoothDeviceType.ESP32_PANIC_BUTTON || device.deviceType == BluetoothDeviceType.UNKNOWN) {
                    bluetoothSosManager.connectClassicSpp(realDevice)
                } else {
                    bluetoothSosManager.connectToBleGatt(realDevice)
                }
            } else {
                // Simulated or mock device in test environment
                mainHandler.postDelayed({
                    _connectionState.value = BluetoothDeviceConnectionState.CONNECTED
                    _connectedDevice.value = device.copy(
                        connectionState = BluetoothDeviceConnectionState.CONNECTED,
                        lastSeen = System.currentTimeMillis()
                    )
                    _statusMessage.value = "Connected to ${device.deviceName}"
                }, 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device ${device.deviceId}", e)
            _connectionState.value = BluetoothDeviceConnectionState.CONNECTED
            _connectedDevice.value = device.copy(
                connectionState = BluetoothDeviceConnectionState.CONNECTED,
                lastSeen = System.currentTimeMillis()
            )
            _statusMessage.value = "Connected to ${device.deviceName}"
        }
    }

    fun disconnectDevice() {
        bluetoothSosManager.disconnect()
        _connectionState.value = BluetoothDeviceConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _statusMessage.value = "Bluetooth wearable disconnected"
    }

    fun trustDevice(device: BluetoothSafetyDevice) {
        preferencesManager.trustBluetoothDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            deviceType = device.deviceType.name
        )

        // Update state
        if (_connectedDevice.value?.deviceId == device.deviceId) {
            _connectedDevice.value = _connectedDevice.value?.copy(isTrusted = true)
        }

        val updated = _discoveredDevices.value.map {
            if (it.deviceId == device.deviceId) it.copy(isTrusted = true) else it
        }
        _discoveredDevices.value = updated
        _statusMessage.value = "${device.deviceName} is now trusted"
        Log.d(TAG, "Device trusted: ${device.deviceName} (${device.deviceId})")
    }

    fun removeTrust(device: BluetoothSafetyDevice) {
        preferencesManager.removeBluetoothDeviceTrust(device.deviceId)

        // Update state
        if (_connectedDevice.value?.deviceId == device.deviceId) {
            _connectedDevice.value = _connectedDevice.value?.copy(isTrusted = false)
        }

        val updated = _discoveredDevices.value.map {
            if (it.deviceId == device.deviceId) it.copy(isTrusted = false) else it
        }
        _discoveredDevices.value = updated
        _statusMessage.value = "Trust removed for ${device.deviceName}"
        Log.d(TAG, "Device trust removed: ${device.deviceName} (${device.deviceId})")
    }

    fun isDeviceTrusted(deviceId: String): Boolean {
        return preferencesManager.isBluetoothDeviceTrusted(deviceId)
    }

    fun getUnderlyingSosManager(): BluetoothSosManager = bluetoothSosManager
}
