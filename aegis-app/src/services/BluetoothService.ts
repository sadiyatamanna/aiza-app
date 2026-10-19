import { NativeModules, DeviceEventEmitter, Platform } from './nativeBridge';

const { AegisBluetoothModule } = NativeModules;

export interface BluetoothDeviceInfo {
  name: string;
  address: string;
  isSosWear?: boolean;
}

export interface HardwareSosTriggerEvent {
  source: 'ESP32';
  trigger: 'S';
  triggerType: 'BLUETOOTH_HARDWARE';
  timestamp: number;
  deviceAddress?: string;
}

export interface BluetoothStatusEvent {
  status: 'CONNECTED' | 'DISCONNECTED' | 'CONNECTING' | 'ERROR';
  details?: string;
  timestamp: number;
}

export interface BluetoothAdapterStatus {
  available: boolean;
  enabled: boolean;
  error?: string;
}

// Memory store for headless/test environments
let _mockConnected = false;
let _mockLastTrigger = 0;
const TRIGGER_COOLDOWN_MS = 5000;

export const BluetoothService = {
  /**
   * Checks if Bluetooth hardware is available and turned on.
   */
  async startBluetooth(): Promise<BluetoothAdapterStatus> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBluetoothModule?.startBluetooth) {
      return await AegisBluetoothModule.startBluetooth();
    }
    return { available: true, enabled: true };
  },

  /**
   * Retrieves paired/known Bluetooth devices.
   * On Android: Queries bonded devices via Bluetooth Classic SPP adapter.
   * On iOS: Queries connected/discovered BLE peripherals with FFE0 service.
   */
  async getBondedDevices(): Promise<BluetoothDeviceInfo[]> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBluetoothModule?.getBondedDevices) {
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
  async connectDevice(macOrUuid?: string): Promise<{ status: string; device?: string; address?: string }> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBluetoothModule?.connectDevice) {
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
  async disconnectDevice(): Promise<{ status: string }> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBluetoothModule?.disconnectDevice) {
      return await AegisBluetoothModule.disconnectDevice();
    }
    _mockConnected = false;
    return { status: 'DISCONNECTED' };
  },

  /**
   * Checks if the device is currently connected to SOS-WEAR.
   */
  async isConnected(): Promise<boolean> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBluetoothModule?.isConnected) {
      return await AegisBluetoothModule.isConnected();
    }
    return _mockConnected;
  },

  /**
   * Subscribes to hardware SOS triggers emitted by the ESP32 wear device.
   * Enforces 5000ms cooldown across native Android, iOS, and fallback layers.
   */
  onHardwareSosTrigger(callback: (event: HardwareSosTriggerEvent) => void): () => void {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && DeviceEventEmitter) {
      const subscription = DeviceEventEmitter.addListener(
        'onAegisHardwareSosTrigger',
        callback
      );
      return () => subscription.remove();
    }

    // In-memory mock subscriber for testing environments
    const handler = (event: HardwareSosTriggerEvent) => {
      const now = Date.now();
      if (now - _mockLastTrigger >= TRIGGER_COOLDOWN_MS) {
        _mockLastTrigger = now;
        callback(event);
      }
    };

    const sub = DeviceEventEmitter?.addListener?.('onAegisHardwareSosTrigger', handler);
    return () => sub?.remove?.();
  },

  /**
   * Subscribes to Bluetooth status change events (CONNECTING, CONNECTED, DISCONNECTED, ERROR).
   */
  onBluetoothStatus(callback: (status: BluetoothStatusEvent) => void): () => void {
    if (DeviceEventEmitter) {
      const subscription = DeviceEventEmitter.addListener(
        'onAegisBluetoothStatus',
        callback
      );
      return () => subscription.remove();
    }
    return () => {};
  },

  /**
   * Helper method for testing or simulating hardware trigger events.
   */
  _simulateHardwareTrigger(triggerChar: string = 'S'): boolean {
    const now = Date.now();
    if (triggerChar !== 'S') return false;
    if (now - _mockLastTrigger < TRIGGER_COOLDOWN_MS) {
      return false; // Suppressed by cooldown
    }
    _mockLastTrigger = now;
    const event: HardwareSosTriggerEvent = {
      source: 'ESP32',
      trigger: 'S',
      triggerType: 'BLUETOOTH_HARDWARE',
      timestamp: now,
      deviceAddress: '00:11:22:33:AA:BB',
    };
    DeviceEventEmitter?.emit?.('onAegisHardwareSosTrigger', event);
    return true;
  },

  /**
   * Helper method for tests to reset the cooldown window.
   */
  _resetCooldown(): void {
    _mockLastTrigger = 0;
  },
};
