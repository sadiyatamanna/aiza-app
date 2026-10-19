import { BluetoothService, HardwareSosTriggerEvent } from './BluetoothService';
import { TelephonyService, SmsStatusEvent } from './TelephonyService';
import { SirenService } from './SirenService';
import { HardwareService } from './HardwareService';
import { BackgroundService } from './BackgroundService';
import { PqcSecurityService } from './PqcSecurityService';

let passed = 0;
let failed = 0;

function assert(condition: boolean, testName: string, detail?: string) {
  if (condition) {
    console.log(`[PASS] ${testName}`);
    passed++;
  } else {
    console.error(`[FAIL] ${testName}${detail ? ` - ${detail}` : ''}`);
    failed++;
  }
}

async function runPhase4Tests() {
  console.log('--- Starting Aegis Phase 4 Native Modules & Hardware Bridges Tests ---');

  // Test 1: Bluetooth Service Initialization & Adapter Check
  try {
    const btStatus = await BluetoothService.startBluetooth();
    assert(
      typeof btStatus.available === 'boolean' && typeof btStatus.enabled === 'boolean',
      '1. Bluetooth adapter check contract'
    );
  } catch (err: any) {
    assert(false, '1. Bluetooth adapter check contract', err.message);
  }

  // Test 2: Bluetooth Bonded Devices Query
  try {
    const devices = await BluetoothService.getBondedDevices();
    assert(
      Array.isArray(devices) && devices.some(d => d.name === 'SOS-WEAR'),
      '2. Bonded devices query finds paired SOS-WEAR hardware'
    );
  } catch (err: any) {
    assert(false, '2. Bonded devices query finds paired SOS-WEAR hardware', err.message);
  }

  // Test 3: Bluetooth Connect / Disconnect Lifecycle
  try {
    const connectRes = await BluetoothService.connectDevice();
    const isConn = await BluetoothService.isConnected();
    const disconnectRes = await BluetoothService.disconnectDevice();
    const isConnAfter = await BluetoothService.isConnected();

    assert(
      connectRes.status === 'CONNECTED' &&
        isConn === true &&
        disconnectRes.status === 'DISCONNECTED' &&
        isConnAfter === false,
      '3. Bluetooth Classic SPP connect and disconnect lifecycle'
    );
  } catch (err: any) {
    assert(false, '3. Bluetooth Classic SPP connect and disconnect lifecycle', err.message);
  }

  // Test 4: ESP32 Hardware Trigger Contract Verification
  try {
    let capturedEvent: HardwareSosTriggerEvent | null = null;
    const unsubscribe = BluetoothService.onHardwareSosTrigger(event => {
      capturedEvent = event;
    });

    // Simulate 'S' press
    const triggered = BluetoothService._simulateHardwareTrigger('S');

    const event = capturedEvent as HardwareSosTriggerEvent | null;
    assert(
      triggered &&
        event !== null &&
        event.source === 'ESP32' &&
        event.trigger === 'S' &&
        event.triggerType === 'BLUETOOTH_HARDWARE' &&
        typeof event.timestamp === 'number',
      '4. ESP32 ASCII "S" hardware trigger event format verification'
    );
    unsubscribe();
  } catch (err: any) {
    assert(false, '4. ESP32 ASCII "S" hardware trigger event format verification', err.message);
  }

  // Test 5: 5000ms Native Cooldown Verification
  try {
    let triggerCount = 0;
    const unsubscribe = BluetoothService.onHardwareSosTrigger(() => {
      triggerCount++;
    });

    BluetoothService._resetCooldown();
    // Immediate second trigger within 5000ms must be suppressed
    const firstTrigger = BluetoothService._simulateHardwareTrigger('S');
    const secondTrigger = BluetoothService._simulateHardwareTrigger('S');
    const thirdTrigger = BluetoothService._simulateHardwareTrigger('S');

    assert(
      firstTrigger === true &&
        secondTrigger === false &&
        thirdTrigger === false &&
        triggerCount === 1,
      '5. Hardware SOS 5-second cooldown suppresses duplicate rapid triggers'
    );
    unsubscribe();
  } catch (err: any) {
    assert(false, '5. Hardware SOS 5-second cooldown suppresses duplicate rapid triggers', err.message);
  }

  // Test 6: Telephony Service - Emergency Call Dispatch
  try {
    const callResult = await TelephonyService.initiateEmergencyCall('+15551234567');
    assert(
      (callResult.status === 'CALL_INITIATED' || callResult.status === 'DIAL_FALLBACK_INITIATED') &&
        callResult.phoneNumber === '+15551234567',
      '6. Telephony emergency call initiation'
    );
  } catch (err: any) {
    assert(false, '6. Telephony emergency call initiation', err.message);
  }

  // Test 7: Telephony Service - Direct SMS & Multi-part calculation
  try {
    const shortSms = await TelephonyService.sendDirectSms('+15551234567', 'Help me! I am at safe location.');
    const longMessage = 'EMERGENCY ALERT: '.repeat(20);
    const longSms = await TelephonyService.sendDirectSms('+15551234567', longMessage);

    assert(
      shortSms.status === 'SMS_QUEUED' &&
        shortSms.parts === 1 &&
        longSms.status === 'SMS_QUEUED' &&
        longSms.parts > 1,
      '7. Telephony direct SMS with single and multipart partitioning'
    );
  } catch (err: any) {
    assert(false, '7. Telephony direct SMS with single and multipart partitioning', err.message);
  }

  // Test 8: Telephony SMS Status Listener
  try {
    let receivedStatus: SmsStatusEvent | null = null;
    const unsubSms = TelephonyService.onSmsStatus(evt => {
      receivedStatus = evt;
    });
    assert(typeof unsubSms === 'function', '8. Telephony SMS receipt status listener subscription');
    unsubSms();
  } catch (err: any) {
    assert(false, '8. Telephony SMS receipt status listener subscription', err.message);
  }

  // Test 9: Background Foreground Service Lifecycle
  try {
    const startRes = await BackgroundService.startService();
    const isRunningAfterStart = await BackgroundService.isServiceRunning();
    const stopRes = await BackgroundService.stopService();
    const isRunningAfterStop = await BackgroundService.isServiceRunning();

    assert(
      startRes.status === 'STARTED' &&
        isRunningAfterStart === true &&
        stopRes.status === 'STOPPED' &&
        isRunningAfterStop === false,
      '9. Background foreground service start, status, and stop controls'
    );
  } catch (err: any) {
    assert(false, '9. Background foreground service start, status, and stop controls', err.message);
  }

  // Test 10: Siren Service Audio Control
  try {
    const startSirenRes = await SirenService.playEmergencySiren();
    const isPlaying = await SirenService.isSirenPlaying();
    const stopSirenRes = await SirenService.stopEmergencySiren();
    const isPlayingAfter = await SirenService.isSirenPlaying();

    assert(
      startSirenRes.success === true &&
        isPlaying === true &&
        stopSirenRes.status === 'STOPPED' &&
        isPlayingAfter === false,
      '10. Emergency loud siren playback and audio lifecycle'
    );
  } catch (err: any) {
    assert(false, '10. Emergency loud siren playback and audio lifecycle', err.message);
  }

  // Test 11: Hardware Torch Control
  try {
    const hasTorch = await HardwareService.isTorchAvailable();
    const torchOnRes = await HardwareService.toggleTorch(true);
    const isTorchOn = await HardwareService.isTorchOn();
    const torchOffRes = await HardwareService.toggleTorch(false);

    assert(
      hasTorch === true &&
        torchOnRes.status === 'TORCH_ON' &&
        isTorchOn === true &&
        torchOffRes.status === 'TORCH_OFF',
      '11. Hardware torch / strobe toggle controls'
    );
  } catch (err: any) {
    assert(false, '11. Hardware torch / strobe toggle controls', err.message);
  }

  // Test 12: PQC Security Audit Compliance (No fake crypto, reports valid status and rejects unsupported algorithms)
  try {
    const isPqcAvail = await PqcSecurityService.isPqcAvailable();
    const pqcStatus = await PqcSecurityService.getPqcStatus();

    let threwPendingOrUnsupported = false;
    try {
      await PqcSecurityService.generatePqcKeyPair('ML-KEM-768');
    } catch (err: any) {
      threwPendingOrUnsupported =
        err.message.includes('PQC_NATIVE_IMPLEMENTATION_PENDING') ||
        err.message.includes('PQC_UNAVAILABLE') ||
        err.message.includes('Unsupported PQC algorithm') ||
        err.message.includes('PQC_UNSUPPORTED_ALGORITHM');
    }

    assert(
      ((isPqcAvail === true && pqcStatus.status === 'PQC_ACTIVE') ||
        (isPqcAvail === false && pqcStatus.status === 'PQC_NATIVE_IMPLEMENTATION_PENDING')) &&
        threwPendingOrUnsupported === true,
      '12. PQC Security Audit compliance: Reports active native PQC or pending without crypto fabrication'
    );
  } catch (err: any) {
    assert(false, '12. PQC Security Audit compliance', err.message);
  }

  console.log(`\nAll ${passed}/${passed + failed} Phase 4 Native Modules & Hardware Bridges Tests finished.`);
  if (failed > 0) {
    process.exit(1);
  }
}

runPhase4Tests().catch(err => {
  console.error('Fatal error during Phase 4 tests:', err);
  process.exit(1);
});
