package com.example.bridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Android Native Bluetooth Classic SPP Bridge for ESP32 Wearable ("SOS-WEAR").
 * Listens for hardware button press ('S' character) and enforces a 5-second cooldown natively.
 */
class AegisBluetoothModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "AegisBluetoothModule"
        const val EVENT_HARDWARE_TRIGGER = "onAegisHardwareSosTrigger"
        const val EVENT_BLUETOOTH_STATUS = "onAegisBluetoothStatus"

        // Standard Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEFAULT_DEVICE_NAME = "SOS-WEAR"
        const val HARDWARE_TRIGGER_CHAR = 'S'.code.toByte() // ASCII 83
        const val TRIGGER_COOLDOWN_MS = 5000L // 5-second native cooldown
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedDevice: BluetoothDevice? = null
    private var readThread: Thread? = null

    @Volatile
    private var isConnectedState = false

    @Volatile
    private var lastTriggerTimestamp = 0L

    override fun getName(): String = MODULE_NAME

    private fun sendEvent(eventName: String, params: WritableMap?) {
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN built-in EventEmitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN built-in EventEmitter
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                reactContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                reactContext,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks Bluetooth adapter availability and current state.
     */
    @ReactMethod
    fun startBluetooth(promise: Promise) {
        try {
            if (bluetoothAdapter == null) {
                val map = Arguments.createMap().apply {
                    putBoolean("available", false)
                    putBoolean("enabled", false)
                    putString("error", "BLUETOOTH_NOT_SUPPORTED")
                }
                promise.resolve(map)
                return
            }

            val isEnabled = bluetoothAdapter.isEnabled
            val map = Arguments.createMap().apply {
                putBoolean("available", true)
                putBoolean("enabled", isEnabled)
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("BLUETOOTH_CHECK_FAILED", e.message, e)
        }
    }

    /**
     * Returns list of paired/bonded Bluetooth devices.
     */
    @ReactMethod
    fun getBondedDevices(promise: Promise) {
        try {
            if (bluetoothAdapter == null) {
                promise.reject("BLUETOOTH_UNAVAILABLE", "Bluetooth is not supported on this device")
                return
            }

            if (!hasBluetoothConnectPermission()) {
                promise.reject("PERMISSION_DENIED", "BLUETOOTH_CONNECT permission not granted")
                return
            }

            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
            val deviceArray: WritableArray = Arguments.createArray()

            pairedDevices?.forEach { device ->
                val deviceMap: WritableMap = Arguments.createMap().apply {
                    putString("name", device.name ?: "Unknown")
                    putString("address", device.address)
                    putBoolean(
                        "isSosWear",
                        device.name.equals(DEFAULT_DEVICE_NAME, ignoreCase = true)
                    )
                }
                deviceArray.pushMap(deviceMap)
            }

            promise.resolve(deviceArray)
        } catch (e: Exception) {
            promise.reject("GET_BONDED_FAILED", e.message, e)
        }
    }

    /**
     * Connects to the ESP32 SOS-WEAR device via RFCOMM SPP.
     * Searches paired devices if macAddress is null or empty.
     */
    @ReactMethod
    fun connectDevice(macAddress: String?, promise: Promise) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            promise.reject("BLUETOOTH_DISABLED", "Bluetooth adapter is null or disabled")
            return
        }

        if (!hasBluetoothConnectPermission()) {
            promise.reject("PERMISSION_DENIED", "BLUETOOTH_CONNECT permission not granted")
            return
        }

        // Close any existing connection first
        disconnectInternal()

        Thread {
            try {
                emitStatus("CONNECTING", null)

                var targetDevice: BluetoothDevice? = null

                if (!macAddress.isNullOrBlank()) {
                    targetDevice = bluetoothAdapter.getRemoteDevice(macAddress)
                } else {
                    val bonded = bluetoothAdapter.bondedDevices
                    targetDevice = bonded?.find {
                        it.name.equals(DEFAULT_DEVICE_NAME, ignoreCase = true)
                    }
                }

                if (targetDevice == null) {
                    emitStatus("ERROR", "Target device '$DEFAULT_DEVICE_NAME' not found in bonded devices")
                    promise.reject(
                        "DEVICE_NOT_FOUND",
                        "Device '$DEFAULT_DEVICE_NAME' not found. Please pair it in Android Bluetooth Settings first."
                    )
                    return@Thread
                }

                // Discovery slows down connection; cancel discovery before connect
                try {
                    bluetoothAdapter.cancelDiscovery()
                } catch (_: Exception) {}

                val socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                bluetoothSocket = socket
                connectedDevice = targetDevice
                isConnectedState = true

                // Start background reader thread
                startReaderThread(socket.inputStream)

                emitStatus("CONNECTED", targetDevice.address)

                val result = Arguments.createMap().apply {
                    putString("status", "CONNECTED")
                    putString("device", targetDevice.name ?: DEFAULT_DEVICE_NAME)
                    putString("address", targetDevice.address)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                disconnectInternal()
                emitStatus("ERROR", e.message)
                promise.reject("CONNECTION_FAILED", "Failed to connect to SPP device: ${e.message}", e)
            }
        }.start()
    }

    /**
     * Disconnects the current RFCOMM socket.
     */
    @ReactMethod
    fun disconnectDevice(promise: Promise) {
        try {
            disconnectInternal()
            emitStatus("DISCONNECTED", null)
            val result = Arguments.createMap().apply {
                putString("status", "DISCONNECTED")
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("DISCONNECT_FAILED", e.message, e)
        }
    }

    /**
     * Checks if RFCOMM socket is currently open and connected.
     */
    @ReactMethod
    fun isConnected(promise: Promise) {
        val connected = isConnectedState && (bluetoothSocket?.isConnected == true)
        promise.resolve(connected)
    }

    private fun startReaderThread(inputStream: InputStream) {
        readThread = Thread {
            val buffer = ByteArray(1024)
            while (isConnectedState && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        break
                    }
                    for (i in 0 until bytesRead) {
                        val byte = buffer[i]
                        if (byte == HARDWARE_TRIGGER_CHAR) {
                            handleHardwareTrigger()
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
            if (isConnectedState) {
                disconnectInternal()
                emitStatus("DISCONNECTED", "Socket connection closed unexpectedly")
            }
        }.apply {
            name = "AegisSppReadThread"
            isDaemon = true
            start()
        }
    }

    /**
     * Handles hardware SOS trigger with strict 5000ms native cooldown.
     */
    private fun handleHardwareTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimestamp < TRIGGER_COOLDOWN_MS) {
            // Ignored within 5-second cooldown window
            return
        }
        lastTriggerTimestamp = now

        val event = Arguments.createMap().apply {
            putString("source", "ESP32")
            putString("trigger", "S")
            putString("triggerType", "BLUETOOTH_HARDWARE")
            putDouble("timestamp", now.toDouble())
            putString("deviceAddress", connectedDevice?.address ?: "")
        }
        sendEvent(EVENT_HARDWARE_TRIGGER, event)
    }

    private fun emitStatus(status: String, details: String?) {
        val map = Arguments.createMap().apply {
            putString("status", status)
            if (details != null) {
                putString("details", details)
            }
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }
        sendEvent(EVENT_BLUETOOTH_STATUS, map)
    }

    @Synchronized
    private fun disconnectInternal() {
        isConnectedState = false
        try {
            readThread?.interrupt()
            readThread = null
        } catch (_: Exception) {}

        try {
            bluetoothSocket?.close()
            bluetoothSocket = null
        } catch (_: Exception) {}

        connectedDevice = null
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        disconnectInternal()
    }
}
