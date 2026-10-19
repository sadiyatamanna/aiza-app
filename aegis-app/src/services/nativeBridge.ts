/**
 * Safe Native Bridge layer for Aegis React Native.
 * Seamlessly resolves NativeModules, DeviceEventEmitter, and Platform
 * in Metro / React Native runtime, while providing an in-memory fallback
 * when executed in headless Node.js test environments.
 */

const isNodeEnvironment =
  typeof process !== 'undefined' &&
  process.versions != null &&
  process.versions.node != null &&
  typeof (globalThis as any).nativeCallSyncHook === 'undefined';

const eventListeners: Record<string, ((...args: any[]) => void)[]> = {};

let resolvedNativeModules: any = {};
let resolvedDeviceEventEmitter: any = {
  addListener(eventName: string, handler: (...args: any[]) => void) {
    if (!eventListeners[eventName]) {
      eventListeners[eventName] = [];
    }
    eventListeners[eventName].push(handler);
    return {
      remove() {
        if (eventListeners[eventName]) {
          eventListeners[eventName] = eventListeners[eventName].filter(cb => cb !== handler);
        }
      },
    };
  },
  emit(eventName: string, ...args: any[]) {
    if (eventListeners[eventName]) {
      eventListeners[eventName].forEach(cb => {
        try {
          cb(...args);
        } catch (e) {
          console.error(`Error in event listener for ${eventName}:`, e);
        }
      });
    }
  },
};

// Dynamic Platform object that can be queried or switched in test environments
let _currentPlatformOS = 'android';

let resolvedPlatform = {
  get OS() {
    return _currentPlatformOS;
  },
  set OS(val: string) {
    _currentPlatformOS = val;
  },
  select: (specifics: any) => specifics[_currentPlatformOS] || specifics.default,
};

export function setMockPlatform(os: 'android' | 'ios') {
  _currentPlatformOS = os;
}

let resolvedPermissionsAndroid: any = {
  PERMISSIONS: {
    ACCESS_FINE_LOCATION: 'android.permission.ACCESS_FINE_LOCATION',
  },
  RESULTS: {
    GRANTED: 'granted',
    DENIED: 'denied',
  },
  request: async () => 'granted',
};

// If in Node.js test environment, bridge AegisPqcModule to the native Java Bouncy Castle 1.81 engine
if (isNodeEnvironment) {
  function invokeJvmPqc(cmd: string, ...args: string[]): string {
    const cp = require('child_process');
    const path = require('path');
    const fs = require('fs');

    const jarPath = '/opt/gradle/gradle-9.3.1/lib/plugins/bcprov-jdk18on-1.81.jar';
    const classesDir = path.resolve(__dirname, '../../android/app/build/pqc_classes');
    const cpPath = `${jarPath}:${classesDir}`;

    if (!fs.existsSync(jarPath) || !fs.existsSync(classesDir)) {
      throw new Error('PQC_UNAVAILABLE: Bouncy Castle or compiled JVM classes not found.');
    }

    try {
      const stdout = cp.execFileSync(
        'java',
        ['-cp', cpPath, 'com.example.bridge.AegisPqcJvmCli', cmd, ...args],
        { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
      );
      return stdout.trim();
    } catch (e: any) {
      const stderr = e?.stderr?.toString()?.trim() || e?.message || 'Unknown PQC execution error';
      throw new Error(stderr);
    }
  }

  // Common and iOS/Android mock bridges for Node test environments
  let _mockBtConnected = false;
  let _mockBtLastTrigger = 0;
  let _mockTorchOn = false;
  let _mockSirenPlaying = false;
  let _mockBgServiceRunning = false;
  let _mockShakeActive = false;
  let _mockVoiceActive = false;
  let _mockLastShake = 0;
  let _mockLastVoice = 0;

  resolvedNativeModules.AegisTelephonyModule = {
    initiateEmergencyCall: async (phoneNumber: string) => {
      const isIos = _currentPlatformOS === 'ios';
      return {
        status: 'CALL_INITIATED',
        method: 'ACTION_CALL',
        phoneNumber,
        platform: isIos ? 'iOS' : 'Android',
        ...(isIos ? { note: 'iOS displays user confirmation dialog prior to dialer connection.' } : {}),
      };
    },
    sendDirectSms: async (phoneNumber: string, message: string) => {
      const isIos = _currentPlatformOS === 'ios';
      const parts = Math.ceil(message.length / 160) || 1;
      resolvedDeviceEventEmitter.emit('onAegisSmsStatus', {
        type: 'SENT_STATUS',
        status: 'SENT',
        phoneNumber,
        resultCode: 0,
        timestamp: Date.now(),
        platform: isIos ? 'iOS' : 'Android',
      });
      return {
        status: 'SMS_QUEUED',
        phoneNumber,
        parts,
        platform: isIos ? 'iOS' : 'Android',
        ...(isIos ? { platformLimitation: 'iOS sandbox security prohibits silent background SMS. Dispatched via emergency SMS queue.' } : {}),
      };
    },
  };

  resolvedNativeModules.AegisBluetoothModule = {
    startBluetooth: async () => ({
      available: true,
      enabled: true,
      platform: _currentPlatformOS === 'ios' ? 'iOS' : 'Android',
      protocol: _currentPlatformOS === 'ios' ? 'CoreBluetooth BLE GATT' : 'Bluetooth Classic SPP',
    }),
    getBondedDevices: async () => [
      {
        name: 'SOS-WEAR',
        address: '00:11:22:33:AA:BB',
        isSosWear: true,
        type: _currentPlatformOS === 'ios' ? 'BLE_GATT' : 'CLASSIC_SPP',
      },
      { name: 'Wireless Headset', address: 'AA:BB:CC:DD:EE:FF', isSosWear: false },
    ],
    connectDevice: async (macOrUuid?: string) => {
      _mockBtConnected = true;
      resolvedDeviceEventEmitter.emit('onAegisBluetoothStatus', {
        status: 'CONNECTED',
        details: 'Connected to SOS-WEAR',
        timestamp: Date.now(),
      });
      return {
        status: 'CONNECTED',
        device: 'SOS-WEAR',
        address: macOrUuid || '00:11:22:33:AA:BB',
      };
    },
    disconnectDevice: async () => {
      _mockBtConnected = false;
      resolvedDeviceEventEmitter.emit('onAegisBluetoothStatus', {
        status: 'DISCONNECTED',
        details: 'Disconnected from wearable',
        timestamp: Date.now(),
      });
      return { status: 'DISCONNECTED' };
    },
    isConnected: async () => _mockBtConnected,
  };

  resolvedNativeModules.AegisHardwareModule = {
    isTorchAvailable: async () => true,
    toggleTorch: async (enabled: boolean) => {
      _mockTorchOn = enabled;
      return { status: enabled ? 'TORCH_ON' : 'TORCH_OFF', enabled };
    },
    isTorchOn: async () => _mockTorchOn,
    playEmergencySiren: async () => {
      _mockSirenPlaying = true;
      return { success: true, status: 'PLAYING' };
    },
    stopEmergencySiren: async () => {
      _mockSirenPlaying = false;
      return { success: true, status: 'STOPPED' };
    },
    isSirenPlaying: async () => _mockSirenPlaying,
  };

  resolvedNativeModules.AegisBackgroundServiceModule = {
    startService: async () => {
      _mockBgServiceRunning = true;
      return {
        status: 'STARTED',
        running: true,
        platform: _currentPlatformOS === 'ios' ? 'iOS' : 'Android',
        policy: _currentPlatformOS === 'ios'
          ? 'Bounded by Apple Background Task & CoreLocation background policies.'
          : 'Android Foreground Service with sticky notification.',
      };
    },
    stopService: async () => {
      _mockBgServiceRunning = false;
      return { status: 'STOPPED', running: false };
    },
    isServiceRunning: async () => _mockBgServiceRunning,
  };

  resolvedNativeModules.AegisMotionModule = {
    isMotionAvailable: async () => true,
    startShakeDetection: async () => {
      _mockShakeActive = true;
      return { status: 'MONITORING_STARTED', active: true, platform: 'iOS CoreMotion' };
    },
    stopShakeDetection: async () => {
      _mockShakeActive = false;
      return { status: 'MONITORING_STOPPED', active: false };
    },
    isShakeDetectionActive: async () => _mockShakeActive,
    _simulateShake: (accelerationG: number = 2.8) => {
      const now = Date.now();
      if (now - _mockLastShake < 5000) return false;
      _mockLastShake = now;
      resolvedDeviceEventEmitter.emit('onAegisShakeSosTrigger', {
        source: 'SHAKE',
        triggerType: 'CORE_MOTION_ACCELERATION',
        accelerationG,
        timestamp: now,
        platform: 'iOS',
      });
      return true;
    },
    _resetCooldown: () => {
      _mockLastShake = 0;
    },
  };

  resolvedNativeModules.AegisSpeechModule = {
    requestSpeechAuthorization: async () => ({ granted: true, status: 'AUTHORIZED' }),
    startVoiceRecognition: async () => {
      _mockVoiceActive = true;
      return { status: 'LISTENING_STARTED', active: true, platform: 'iOS SFSpeechRecognizer' };
    },
    stopVoiceRecognition: async () => {
      _mockVoiceActive = false;
      return { status: 'LISTENING_STOPPED', active: false };
    },
    isVoiceRecognitionActive: async () => _mockVoiceActive,
    _simulateVoicePhrase: (phrase: string) => {
      const now = Date.now();
      if (now - _mockLastVoice < 5000) return false;
      _mockLastVoice = now;
      resolvedDeviceEventEmitter.emit('onAegisVoiceSosTrigger', {
        source: 'VOICE',
        triggerType: 'SPEECH_RECOGNITION',
        matchedPhrase: phrase,
        transcript: `Please ${phrase} right now!`,
        timestamp: now,
        platform: 'iOS',
      });
      return true;
    },
    _resetCooldown: () => {
      _mockLastVoice = 0;
    },
  };

  resolvedNativeModules.AegisLocationModule = {
    requestPermissions: async () => ({ status: 'REQUESTED' }),
    getCurrentPosition: async () => ({
      latitude: 37.7749,
      longitude: -122.4194,
      accuracy: 5.0,
      altitude: 12.0,
      speed: 0.0,
      timestamp: Date.now(),
      provider: 'iOS CoreLocation',
    }),
    startContinuousTracking: async () => ({ status: 'TRACKING_STARTED', active: true }),
    stopContinuousTracking: async () => ({ status: 'TRACKING_STOPPED', active: false }),
  };

  resolvedNativeModules.AegisPqcModule = {
    isPqcAvailable: async () => {
      if (_currentPlatformOS === 'ios') {
        // iOS native PQC is unavailable until future Swift/C NIST FIPS 203/204 integration
        return false;
      }
      try {
        const res = invokeJvmPqc('isPqcAvailable');
        return res === 'true';
      } catch {
        return false;
      }
    },
    getPqcStatus: async () => {
      if (_currentPlatformOS === 'ios') {
        return {
          available: false,
          status: 'PQC_NATIVE_IMPLEMENTATION_PENDING',
          detail: 'iOS native Post-Quantum Cryptography bridge pending (scheduled for future native Swift integration).',
          supportedAlgorithms: [],
        };
      }
      const jsonStr = invokeJvmPqc('getPqcStatus');
      return JSON.parse(jsonStr);
    },
    generatePqcKeyPair: async (algorithm: string) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: Native Post-Quantum Cryptography module is not available on iOS (PQC_NATIVE_IMPLEMENTATION_PENDING).');
      }
      const jsonStr = invokeJvmPqc('generatePqcKeyPair', algorithm);
      return JSON.parse(jsonStr);
    },
    kemEncapsulate: async (publicKeyBase64: string) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: ML-KEM-1024 encapsulation requires active native PQC module.');
      }
      const jsonStr = invokeJvmPqc('kemEncapsulate', publicKeyBase64);
      return JSON.parse(jsonStr);
    },
    kemDecapsulate: async (cipherTextBase64: string, privateKeyBase64: string) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: ML-KEM-1024 decapsulation requires active native PQC module.');
      }
      const jsonStr = invokeJvmPqc('kemDecapsulate', cipherTextBase64, privateKeyBase64);
      return JSON.parse(jsonStr);
    },
    dsaSign: async (message: string, privateKeyBase64: string) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: ML-DSA-87 signing requires active native PQC module.');
      }
      const msgB64 = Buffer.from(message, 'utf8').toString('base64');
      const jsonStr = invokeJvmPqc('dsaSign', msgB64, privateKeyBase64);
      return JSON.parse(jsonStr);
    },
    dsaVerify: async (message: string, signatureBase64: string, publicKeyBase64: string) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: ML-DSA-87 verification requires active native PQC module.');
      }
      const msgB64 = Buffer.from(message, 'utf8').toString('base64');
      const res = invokeJvmPqc('dsaVerify', msgB64, signatureBase64, publicKeyBase64);
      return res === 'true';
    },
    encrypt: async (plaintext: string, recipientPublicKeyBase64: string | null) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: Cannot encrypt payload without active native PQC module. Plaintext fallback is strictly prohibited.');
      }
      const plainB64 = Buffer.from(plaintext, 'utf8').toString('base64');
      return invokeJvmPqc('encrypt', plainB64, recipientPublicKeyBase64 || 'null');
    },
    decrypt: async (packetJson: string, recipientPrivateKeyBase64: string) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: Cannot decrypt payload without active native PQC module.');
      }
      const pktB64 = Buffer.from(packetJson, 'utf8').toString('base64');
      const resB64 = invokeJvmPqc('decrypt', pktB64, recipientPrivateKeyBase64);
      return Buffer.from(resB64, 'base64').toString('utf8');
    },
    sign: async (payload: string, signerPrivateKeyBase64: string | null) => {
      if (_currentPlatformOS === 'ios') {
        throw new Error('PQC_UNAVAILABLE: Cannot sign payload without active native PQC module. Empty signature fallback is strictly prohibited.');
      }
      const payloadB64 = Buffer.from(payload, 'utf8').toString('base64');
      return invokeJvmPqc('sign', payloadB64, signerPrivateKeyBase64 || 'null');
    },
  };
}

if (!isNodeEnvironment) {
  try {
    // Dynamic require so Metro resolves react-native at bundle time
    const rn = require('react-native');
    if (rn) {
      if (rn.NativeModules) resolvedNativeModules = rn.NativeModules;
      if (rn.DeviceEventEmitter) resolvedDeviceEventEmitter = rn.DeviceEventEmitter;
      if (rn.Platform) resolvedPlatform = rn.Platform;
      if (rn.PermissionsAndroid) resolvedPermissionsAndroid = rn.PermissionsAndroid;
    }
  } catch (_) {
    // Fallback active
  }
}

export const NativeModules = resolvedNativeModules;
export const DeviceEventEmitter = resolvedDeviceEventEmitter;
export const Platform = resolvedPlatform;
export const PermissionsAndroid = resolvedPermissionsAndroid;
