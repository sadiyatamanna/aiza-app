"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BluetoothService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const { AegisBluetoothModule } = nativeBridge_1.NativeModules;
// Memory store for headless/test environments
let _mockConnected = false;
let _mockLastTrigger = 0;
const TRIGGER_COOLDOWN_MS = 5000;
exports.BluetoothService = {
    /**
     * Checks if Bluetooth hardware is available and turned on.
     */
    async startBluetooth() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBluetoothModule?.startBluetooth) {
            return await AegisBluetoothModule.startBluetooth();
        }
        return { available: true, enabled: true };
    },
    /**
     * Retrieves paired/known Bluetooth devices.
     * On Android: Queries bonded devices via Bluetooth Classic SPP adapter.
     * On iOS: Queries connected/discovered BLE peripherals with FFE0 service.
     */
    async getBondedDevices() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBluetoothModule?.getBondedDevices) {
            return await AegisBluetoothModule.getBondedDevices();
        }
        return [
            { name: 'SOS-WEAR', address: '00:11:22:33:AA:BB', isSosWear: true },
            { name: 'Wireless Headset', address: 'AA:BB:CC:DD:EE:FF', isSosWear: false },
        ];
    },
    /**
     * Connects to the ESP32 SOS-WEAR device.
     * On Android: Connects via Bluetooth Classic RFCOMM SPP or BLE GATT.
     * On iOS: Connects via CoreBluetooth BLE GATT (FFE0 service, FFE1 characteristic).
     */
    async connectDevice(macOrUuid) {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBluetoothModule?.connectDevice) {
            return await AegisBluetoothModule.connectDevice(macOrUuid || null);
        }
        _mockConnected = true;
        return {
            status: 'CONNECTED',
            device: 'SOS-WEAR',
            address: macOrUuid || '00:11:22:33:AA:BB',
        };
    },
    /**
     * Disconnects current Bluetooth connection.
     */
    async disconnectDevice() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBluetoothModule?.disconnectDevice) {
            return await AegisBluetoothModule.disconnectDevice();
        }
        _mockConnected = false;
        return { status: 'DISCONNECTED' };
    },
    /**
     * Checks if the device is currently connected to SOS-WEAR.
     */
    async isConnected() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBluetoothModule?.isConnected) {
            return await AegisBluetoothModule.isConnected();
        }
        return _mockConnected;
    },
    /**
     * Subscribes to hardware SOS triggers emitted by the ESP32 wear device.
     * Enforces 5000ms cooldown across native Android, iOS, and fallback layers.
     */
    onHardwareSosTrigger(callback) {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && nativeBridge_1.DeviceEventEmitter) {
            const subscription = nativeBridge_1.DeviceEventEmitter.addListener('onAegisHardwareSosTrigger', callback);
            return () => subscription.remove();
        }
        // In-memory mock subscriber for testing environments
        const handler = (event) => {
            const now = Date.now();
            if (now - _mockLastTrigger >= TRIGGER_COOLDOWN_MS) {
                _mockLastTrigger = now;
                callback(event);
            }
        };
        const sub = nativeBridge_1.DeviceEventEmitter?.addListener?.('onAegisHardwareSosTrigger', handler);
        return () => sub?.remove?.();
    },
    /**
     * Subscribes to Bluetooth status change events (CONNECTING, CONNECTED, DISCONNECTED, ERROR).
     */
    onBluetoothStatus(callback) {
        if (nativeBridge_1.DeviceEventEmitter) {
            const subscription = nativeBridge_1.DeviceEventEmitter.addListener('onAegisBluetoothStatus', callback);
            return () => subscription.remove();
        }
        return () => { };
    },
    /**
     * Helper method for testing or simulating hardware trigger events.
     */
    _simulateHardwareTrigger(triggerChar = 'S') {
        const now = Date.now();
        if (triggerChar !== 'S')
            return false;
        if (now - _mockLastTrigger < TRIGGER_COOLDOWN_MS) {
            return false; // Suppressed by cooldown
        }
        _mockLastTrigger = now;
        const event = {
            source: 'ESP32',
            trigger: 'S',
            triggerType: 'BLUETOOTH_HARDWARE',
            timestamp: now,
            deviceAddress: '00:11:22:33:AA:BB',
        };
        nativeBridge_1.DeviceEventEmitter?.emit?.('onAegisHardwareSosTrigger', event);
        return true;
    },
    /**
     * Helper method for tests to reset the cooldown window.
     */
    _resetCooldown() {
        _mockLastTrigger = 0;
    },
};
