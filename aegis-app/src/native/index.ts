/**
 * Aegis Native Bridge Specifications
 * 
 * Android Kotlin Modules to be created in Phase 4:
 * 1. AegisBluetoothModule (Bluetooth Classic SPP for ESP32 'S' trigger)
 * 2. AegisTelephonyModule (Direct background SMS via SmsManager and ACTION_CALL phone calls)
 * 3. AegisBackgroundService (Persistent Android Foreground Service for Shake-to-SOS & continuous GPS)
 * 4. AegisHardwareModule (High-decibel MediaPlayer siren & Camera2 torch)
 * 5. AegisPqcModule (Bouncy Castle Kyber-1024 encapsulation & Dilithium-5 signatures)
 * 
 * iOS Swift Modules:
 * 1. AegisTelephonyBridge (tel:// scheme and MFMessageComposeViewController)
 * 2. AegisBluetoothBridge (CoreBluetooth BLE bridge)
 * 3. AegisHardwareBridge (AVAudioSession high volume siren)
 * 4. AegisSecurityBridge (Apple Keychain Services)
 */

export * from '../services/BluetoothService';
export * from '../services/TelephonyService';
export * from '../services/BackgroundService';
export * from '../services/HardwareService';
export * from '../services/SirenService';
export * from '../services/PqcSecurityService';
export * from '../services/MotionService';
export * from '../services/VoiceService';
