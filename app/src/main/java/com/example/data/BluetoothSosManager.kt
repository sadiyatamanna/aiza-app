package com.example.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.util.UUID

object BluetoothSosConfig {
    const val TRIGGER_CHARACTER = "S"
    
    // Target device names configured on ESP32 firmware
    val TARGET_DEVICE_NAMES = listOf(
        "SOS-WEAR",
        "SOS_WEAR",
        "SOSWEAR",
        "AIZA",
        "AEGIS",
        "ESP32",
        "PANIC",
        "SOS_BUTTON",
        "ESP32_SOS"
    )

    // Standard SPP UUID for Classic Bluetooth Serial Port Profile (BluetoothSerial.h on ESP32)
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    // Standard GATT Service & Characteristic UUIDs for BLE ESP32 firmware
    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

enum class BluetoothConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    ERROR
}

class BluetoothSosManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothSosManager"
        const val TRIGGER_CHARACTER = BluetoothSosConfig.TRIGGER_CHARACTER
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // Bluetooth Classic SPP Socket & Thread
    private var classicSocket: BluetoothSocket? = null
    private var classicReadThread: Thread? = null
    private var isClassicConnected = false

    // BLE GATT
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private var currentState = BluetoothConnectionState.DISCONNECTED
    private var isListening = false
    private var onSosTriggerCallback: ((String) -> Unit)? = null
    private var lastTriggerTime = 0L

    var activeDeviceAddress: String? = null
        private set
    var activeDeviceName: String? = null
        private set

    private var trustVerifier: ((String) -> Boolean)? = null
    private var onConnectionStateChangedListener: ((BluetoothConnectionState, BluetoothDevice?) -> Unit)? = null
    var onDeviceDiscoveredListener: ((BluetoothDevice) -> Unit)? = null

    fun setTrustVerifier(verifier: ((String) -> Boolean)?) {
        this.trustVerifier = verifier
    }

    fun setOnConnectionStateChangedListener(listener: ((BluetoothConnectionState, BluetoothDevice?) -> Unit)?) {
        this.onConnectionStateChangedListener = listener
    }

    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    // Broadcast receiver for Bluetooth Classic Discovery
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val deviceName = try { device?.name } catch (e: Exception) { null }
                Log.d(TAG, "Bluetooth Classic Discovery found device: $deviceName (${device?.address})")
                if (device != null) {
                    onDeviceDiscoveredListener?.invoke(device)
                    if (isTargetDeviceName(deviceName)) {
                        Log.d(TAG, "Matching ESP32 device discovered via Classic Bluetooth: $deviceName. Connecting SPP...")
                        stopClassicDiscovery()
                        connectClassicSpp(device)
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                Log.d(TAG, "Bluetooth Classic Discovery finished.")
            }
        }
    }
    private var isDiscoveryReceiverRegistered = false

    fun getState(): BluetoothConnectionState = currentState

    fun processData(rawData: String, deviceId: String? = activeDeviceAddress): Boolean {
        val trimmed = rawData.trim()
        Log.d(TAG, "[AEGIS][BLE] Processing incoming hardware data from '$deviceId': '$rawData' -> trimmed: '$trimmed'")
        
        if (trimmed.contains(TRIGGER_CHARACTER) || trimmed == TRIGGER_CHARACTER) {
            // Verify device trust if verifier is configured
            if (trustVerifier != null && deviceId != null && !trustVerifier!!.invoke(deviceId)) {
                Log.w(TAG, "[AEGIS][BLE] REJECTED SOS TRIGGER: Device '$deviceId' is UNTRUSTED.")
                return false
            }

            val now = System.currentTimeMillis()
            if (now - lastTriggerTime > 5000) {
                lastTriggerTime = now
                Log.d(TAG, "[AEGIS][BLE] VALID HARDWARE SOS TRIGGER RECEIVED ('$TRIGGER_CHARACTER') from device '$deviceId'!")
                return true
            } else {
                Log.d(TAG, "[AEGIS][BLE] Duplicate trigger suppressed within cooldown window (${now - lastTriggerTime}ms).")
            }
        } else {
            Log.d(TAG, "[AEGIS][BLE] Ignored non-trigger / malformed data: '$rawData'")
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun startListening(onTrigger: (String) -> Unit) {
        this.onSosTriggerCallback = onTrigger
        this.isListening = true

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "[AEGIS][BLE] Bluetooth adapter is null or disabled.")
            currentState = BluetoothConnectionState.ERROR
            return
        }

        if (!hasPermissions()) {
            Log.w(TAG, "[AEGIS][BLE] Missing required Bluetooth permissions.")
            currentState = BluetoothConnectionState.ERROR
            return
        }

        currentState = BluetoothConnectionState.CONNECTING
        Log.d(TAG, "[AEGIS][BLE] Starting connection search for SOS-WEAR / AEGIS ESP32 hardware...")

        // Step 1: Check Paired/Bonded Devices (highest priority for BluetoothSerial ESP32 like SOS-WEAR)
        try {
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
            val matchedPairedDevice = pairedDevices?.firstOrNull { device ->
                isTargetDeviceName(device.name)
            }

            if (matchedPairedDevice != null) {
                Log.d(TAG, "[AEGIS][BLE] Found paired target device: ${matchedPairedDevice.name} (${matchedPairedDevice.address}). Attempting Classic SPP...")
                connectClassicSpp(matchedPairedDevice)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Error querying paired devices", e)
        }

        // Step 2: Start Bluetooth Classic Discovery
        startClassicDiscovery()

        // Step 3: Concurrently start BLE Scan in case firmware is running BLE GATT
        startBleScan()
    }

    private fun isTargetDeviceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return BluetoothSosConfig.TARGET_DEVICE_NAMES.any { target ->
            name.contains(target, ignoreCase = true)
        }
    }

    // ==========================================
    // BLUETOOTH CLASSIC (SPP / BluetoothSerial)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        try {
            if (!isDiscoveryReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(discoveryReceiver, filter)
                isDiscoveryReceiverRegistered = true
            }
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
            Log.d(TAG, "[AEGIS][BLE] Bluetooth Classic Discovery started.")
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Failed to start classic discovery", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopClassicDiscovery() {
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Error cancelling discovery", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectClassicSpp(device: BluetoothDevice) {
        stopClassicDiscovery()
        stopBleScan()
        activeDeviceAddress = device.address
        activeDeviceName = device.name ?: "Bluetooth Device"

        Thread {
            try {
                Log.d(TAG, "[AEGIS][BLE] Opening SPP RFCOMM socket to ${device.name ?: "Unknown"} (${device.address})...")
                classicSocket?.close()

                // Try secure socket first, fallback to insecure socket if needed
                val socket = try {
                    device.createRfcommSocketToServiceRecord(BluetoothSosConfig.SPP_UUID)
                } catch (e: Exception) {
                    device.createInsecureRfcommSocketToServiceRecord(BluetoothSosConfig.SPP_UUID)
                }
                classicSocket = socket
                socket.connect()

                isClassicConnected = true
                currentState = BluetoothConnectionState.LISTENING
                Log.d(TAG, "[AEGIS][BLE] SPP RFCOMM Socket CONNECTED to ${device.name}! Listening for 'S' panic triggers...")
                handler.post {
                    onConnectionStateChangedListener?.invoke(BluetoothConnectionState.CONNECTED, device)
                }

                // Start read loop thread
                classicReadThread = Thread {
                    readFromClassicSocket(socket.inputStream)
                }.apply {
                    name = "Aegis-ClassicBT-Reader"
                    isDaemon = true
                    start()
                }

            } catch (e: Exception) {
                Log.e(TAG, "[AEGIS][BLE] Failed to connect SPP socket to ${device.address}: ${e.message}")
                isClassicConnected = false
                try { classicSocket?.close() } catch (_: Exception) {}
                classicSocket = null

                // If listening is still desired, schedule a reconnect attempt
                scheduleReconnect()
            }
        }.start()
    }

    private fun readFromClassicSocket(inputStream: InputStream) {
        val buffer = ByteArray(256)
        while (isListening && isClassicConnected) {
            try {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val receivedString = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    Log.d(TAG, "[AEGIS][BLE] Classic SPP data received: '$receivedString'")
                    if (processData(receivedString, activeDeviceAddress)) {
                        handler.post {
                            onSosTriggerCallback?.invoke(TRIGGER_CHARACTER)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "[AEGIS][BLE] SPP Socket read interrupted: ${e.message}")
                isClassicConnected = false
                break
            }
        }
        Log.d(TAG, "[AEGIS][BLE] Classic SPP Reader thread exiting.")
        scheduleReconnect()
    }

    // ==========================================
    // BLUETOOTH LOW ENERGY (BLE / GATT)
    // ==========================================

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning || bluetoothAdapter == null || isClassicConnected) return

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "[AEGIS][BLE] BLE Scanner unavailable.")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BluetoothSosConfig.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            scanner.startScan(filters, settings, bleScanCallback)
            Log.d(TAG, "[AEGIS][BLE] BLE Scanner active. Searching for SOS-WEAR / AEGIS panic hardware...")

            // Timeout scan after 30 seconds
            handler.postDelayed({
                stopBleScan()
            }, 30000)
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Failed to start BLE scan", e)
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Error stopping BLE scan", e)
        }
        isScanning = false
    }

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val devName = device.name ?: "Unknown"
            Log.d(TAG, "[AEGIS][BLE] BLE Device Discovered: $devName (${device.address})")
            onDeviceDiscoveredListener?.invoke(device)
            if (isTargetDeviceName(devName) || result.scanRecord?.serviceUuids?.any { it.uuid == BluetoothSosConfig.SERVICE_UUID } == true) {
                stopBleScan()
                connectToBleGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "[AEGIS][BLE] BLE Scan failed with error code: $errorCode")
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToBleGatt(device: BluetoothDevice) {
        stopBleScan()
        activeDeviceAddress = device.address
        activeDeviceName = device.name ?: "Bluetooth Device"
        currentState = BluetoothConnectionState.CONNECTING
        Log.d(TAG, "[AEGIS][BLE] Connecting GATT to device ${device.address}...")
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Failed to connect GATT to device", e)
            currentState = BluetoothConnectionState.ERROR
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                currentState = BluetoothConnectionState.CONNECTED
                Log.d(TAG, "[AEGIS][BLE] GATT Connected. Discovering services...")
                gatt?.discoverServices()
                handler.post {
                    onConnectionStateChangedListener?.invoke(BluetoothConnectionState.CONNECTED, gatt?.device)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                currentState = BluetoothConnectionState.DISCONNECTED
                activeDeviceAddress = null
                activeDeviceName = null
                Log.d(TAG, "[AEGIS][BLE] GATT Disconnected.")
                handler.post {
                    onConnectionStateChangedListener?.invoke(BluetoothConnectionState.DISCONNECTED, null)
                }
                bluetoothGatt?.close()
                bluetoothGatt = null
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(BluetoothSosConfig.SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BluetoothSosConfig.CHARACTERISTIC_UUID)

                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(BluetoothSosConfig.CLIENT_CHARACTERISTIC_CONFIG)
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                    currentState = BluetoothConnectionState.LISTENING
                    Log.d(TAG, "[AEGIS][BLE] AEGIS Hardware Panic Button LISTENING over BLE GATT notifications!")
                } else {
                    Log.w(TAG, "[AEGIS][BLE] Required characteristic not found on device.")
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val bytes = characteristic?.value ?: return
            handleCharacteristicBytes(bytes)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicBytes(value)
        }
    }

    private fun handleCharacteristicBytes(bytes: ByteArray) {
        val receivedString = String(bytes, Charsets.UTF_8)
        Log.d(TAG, "[AEGIS][BLE] Incoming BLE bytes: '$receivedString'")
        if (processData(receivedString, activeDeviceAddress)) {
            handler.post {
                onSosTriggerCallback?.invoke(TRIGGER_CHARACTER)
            }
        }
    }

    fun disconnect() {
        stopListening()
        activeDeviceAddress = null
        activeDeviceName = null
        handler.post {
            onConnectionStateChangedListener?.invoke(BluetoothConnectionState.DISCONNECTED, null)
        }
    }

    // ==========================================
    // RECONNECT & LIFECYCLE MANAGEMENT
    // ==========================================

    private fun scheduleReconnect() {
        if (!isListening) return
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            if (isListening && !isClassicConnected && bluetoothGatt == null) {
                Log.d(TAG, "[AEGIS][BLE] Reconnecting to SOS-WEAR / AEGIS hardware...")
                onSosTriggerCallback?.let { startListening(it) }
            }
        }
        handler.postDelayed(reconnectRunnable!!, 10000)
    }

    @SuppressLint("MissingPermission")
    fun stopListening() {
        isListening = false
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null

        stopClassicDiscovery()
        if (isDiscoveryReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {}
            isDiscoveryReceiverRegistered = false
        }

        isClassicConnected = false
        try {
            classicSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Error closing Classic socket", e)
        }
        classicSocket = null
        classicReadThread = null

        stopBleScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "[AEGIS][BLE] Error closing GATT connection", e)
        }
        bluetoothGatt = null

        currentState = BluetoothConnectionState.DISCONNECTED
        Log.d(TAG, "[AEGIS][BLE] BluetoothSosManager stopped listening.")
    }

    private fun hasPermissions(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            return hasScan && hasConnect
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothHardwareEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun hasBluetoothPermissions(): Boolean = hasPermissions()

    fun isConnected(): Boolean =
        currentState == BluetoothConnectionState.CONNECTED || currentState == BluetoothConnectionState.LISTENING
}
