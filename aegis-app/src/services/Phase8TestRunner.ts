import { setMockPlatform, Platform } from './nativeBridge';
import { SosService } from './SosService';
import { TelephonyService } from './TelephonyService';
import { BluetoothService } from './BluetoothService';
import { HardwareService } from './HardwareService';
import { SirenService } from './SirenService';
import { BackgroundService } from './BackgroundService';
import { LocationService } from './LocationService';
import { MotionService } from './MotionService';
import { VoiceService } from './VoiceService';
import { PqcSecurityService } from './PqcSecurityService';
import { useSafetyStore } from '../store/safetyStore';

export async function runPhase8Verification(): Promise<boolean> {
  console.log('====================================================');
  console.log('  AIZA - PHASE 8: iOS FUNCTIONAL SAFETY PARITY TEST ');
  console.log('  Developer: Tammi                                  ');
  console.log('====================================================\n');

  let passed = 0;
  let failed = 0;

  function assert(condition: boolean, desc: string) {
    if (condition) {
      console.log(`  ✅ [PASS] ${desc}`);
      passed++;
    } else {
      console.error(`  ❌ [FAIL] ${desc}`);
      failed++;
    }
  }

  // ----------------------------------------------------
  // TEST SECTION 1: PLATFORM SWITCHING & CAPABILITY AUDIT
  // ----------------------------------------------------
  console.log('--- 1. iOS Platform Context & Capability Verification ---');
  setMockPlatform('ios');
  assert(Platform.OS === 'ios', 'Platform.OS successfully set to iOS');

  // ----------------------------------------------------
  // TEST SECTION 2: TELEPHONY PARITY & CONSTRAINTS
  // ----------------------------------------------------
  console.log('\n--- 2. iOS Telephony & Calling Verification ---');
  const callResult = await TelephonyService.initiateEmergencyCall('911');
  assert(
    callResult.status === 'CALL_INITIATED' && (callResult as any).platform === 'iOS',
    'Emergency call initiated on iOS via tel:// URL scheme'
  );
  assert(
    (callResult as any).note?.includes('user confirmation dialog'),
    'iOS dialer prompts user confirmation dialog (honoring Apple sandbox)'
  );

  const smsResult = await TelephonyService.sendDirectSms('911', 'EMERGENCY! Immediate assistance requested.');
  assert(
    smsResult.status === 'SMS_QUEUED' && (smsResult as any).platform === 'iOS',
    'iOS SMS queued via emergency dispatcher (no silent background SMS claimed)'
  );
  assert(
    (smsResult as any).platformLimitation?.includes('silent background SMS'),
    'Reports Apple sandbox policy limitation prohibiting silent background SMS'
  );

  // ----------------------------------------------------
  // TEST SECTION 3: COREBLUETOOTH & BLE WEARABLE SUPPORT
  // ----------------------------------------------------
  console.log('\n--- 3. iOS CoreBluetooth BLE GATT Verification ---');
  const btStatus = await BluetoothService.startBluetooth();
  assert(
    btStatus.available && (btStatus as any).protocol === 'CoreBluetooth BLE GATT',
    'BluetoothService on iOS initializes CoreBluetooth BLE GATT manager'
  );

  const btDevices = await BluetoothService.getBondedDevices();
  const sosWear = btDevices.find((d) => d.name === 'SOS-WEAR');
  assert(
    sosWear != null && (sosWear as any).type === 'BLE_GATT',
    'SOS-WEAR wearable identified via BLE GATT profile on iOS'
  );

  const connResult = await BluetoothService.connectDevice(sosWear?.address);
  assert(connResult.status === 'CONNECTED', 'Successfully connected to BLE wearable on iOS');

  let hwTriggerEmitted = false;
  const unsubHw = BluetoothService.onHardwareSosTrigger((ev) => {
    if (ev.trigger === 'S') hwTriggerEmitted = true;
  });
  BluetoothService._resetCooldown();
  BluetoothService._simulateHardwareTrigger('S');
  assert(hwTriggerEmitted, 'Hardware trigger event received from BLE GATT characteristic');
  unsubHw();

  // ----------------------------------------------------
  // TEST SECTION 4: HARDWARE PARITY (TORCH & SIREN)
  // ----------------------------------------------------
  console.log('\n--- 4. iOS Hardware Controls (AVAudioSession Siren & Torch) ---');
  const torchAvail = await HardwareService.isTorchAvailable();
  assert(torchAvail, 'AVCaptureDevice torch is available on iOS');

  const torchOn = await HardwareService.toggleTorch(true);
  assert(torchOn.enabled && torchOn.status === 'TORCH_ON', 'AVCaptureDevice torch activated');
  await HardwareService.toggleTorch(false);

  const sirenPlay = await SirenService.playEmergencySiren();
  assert(sirenPlay.success && sirenPlay.status === 'PLAYING', 'AVAudioSession emergency siren activated');

  const sirenStop = await SirenService.stopEmergencySiren();
  assert(sirenStop.success && sirenStop.status === 'STOPPED', 'AVAudioSession emergency siren stopped');

  // ----------------------------------------------------
  // TEST SECTION 5: COREDLOCATION PARITY
  // ----------------------------------------------------
  console.log('\n--- 5. iOS CoreLocation GPS Verification ---');
  const locPerm = await LocationService.requestLocationPermissions();
  assert(locPerm, 'CLLocationManager permission requested');

  const curPos = await LocationService.getCurrentPosition();
  assert(
    curPos != null && typeof curPos.latitude === 'number' && typeof curPos.longitude === 'number',
    'CLLocationManager returned high-precision GPS coordinates on iOS'
  );

  // ----------------------------------------------------
  // TEST SECTION 6: SHAKE SOS (COREMOTION ACCELERATION)
  // ----------------------------------------------------
  console.log('\n--- 6. iOS Shake SOS (CoreMotion) Verification ---');
  useSafetyStore.getState().setShakeSosEnabled(true);
  const shakeMonitoring = await MotionService.startShakeDetection();
  assert(shakeMonitoring.active, 'CMMotionManager accelerometer monitoring started on iOS');

  // Trigger simulation of CoreMotion shake >= 2.5G
  MotionService._simulateShake(2.9);
  // Allow promise resolution
  await new Promise((r) => setTimeout(r, 50));
  assert(useSafetyStore.getState().sosActive, 'Shake SOS dispatched emergency alert via CoreMotion trigger');
  useSafetyStore.getState().cancelSos();
  await MotionService.stopShakeDetection();

  // ----------------------------------------------------
  // TEST SECTION 7: VOICE SOS (SFSPEECHRECOGNIZER)
  // ----------------------------------------------------
  console.log('\n--- 7. iOS Voice SOS (SFSpeechRecognizer) Verification ---');
  useSafetyStore.getState().setVoiceSosEnabled(true);
  const voiceAuth = await VoiceService.requestSpeechAuthorization();
  assert(voiceAuth.granted, 'SFSpeechRecognizer permission granted');

  const voiceMonitoring = await VoiceService.startVoiceRecognition();
  assert(voiceMonitoring.active, 'SFSpeechRecognizer listening session started on iOS');

  VoiceService._simulateVoicePhrase('help');
  await new Promise((r) => setTimeout(r, 50));
  assert(useSafetyStore.getState().sosActive, 'Voice SOS dispatched emergency alert on keyword match ("help")');
  assert(
    useSafetyStore.getState().recentSosTrigger === 'VOICE',
    'SOS trigger source correctly recorded as VOICE without audio upload'
  );
  useSafetyStore.getState().cancelSos();
  await VoiceService.stopVoiceRecognition();

  // ----------------------------------------------------
  // TEST SECTION 8: BACKGROUND SERVICE POLICIES
  // ----------------------------------------------------
  console.log('\n--- 8. iOS Background Execution Policies ---');
  const bgService = await BackgroundService.startService();
  assert(bgService.running && (bgService as any).platform === 'iOS', 'UIBackgroundTask emergency session started');
  assert(
    (bgService as any).policy?.includes('Apple Background Task'),
    'App background execution clearly documents Apple Background Task constraints'
  );
  await BackgroundService.stopService();

  // ----------------------------------------------------
  // TEST SECTION 9: STRICT PQC NON-FAKING CONSTRAINTS
  // ----------------------------------------------------
  console.log('\n--- 9. Strict PQC Constraint Verification (NO FAKE PQC) ---');
  const pqcStatus = await PqcSecurityService.getPqcStatus();
  assert(
    pqcStatus.available === false,
    'PqcSecurityService on iOS reports available === false'
  );
  assert(
    pqcStatus.status === 'PQC_NATIVE_IMPLEMENTATION_PENDING',
    'PqcSecurityService on iOS reports status === PQC_NATIVE_IMPLEMENTATION_PENDING'
  );
  assert(
    pqcStatus.detail.includes('iOS native Post-Quantum Cryptography bridge pending'),
    'iOS PQC diagnostic clearly states native Swift bridge is pending'
  );

  let caughtPqcThrow = false;
  try {
    await PqcSecurityService.generatePqcKeyPair('ML-KEM-1024');
  } catch (e: any) {
    caughtPqcThrow = e.message.includes('PQC_UNAVAILABLE');
  }
  assert(caughtPqcThrow, 'Generating PQC keypair on iOS throws PQC_UNAVAILABLE (no fake keys)');

  // ----------------------------------------------------
  // TEST SECTION 10: UNIFIED SOS DISPATCH END-TO-END ON iOS
  // ----------------------------------------------------
  console.log('\n--- 10. Unified SOS Dispatch Pipeline on iOS ---');
  const dispatchRes = await SosService.executeSosDispatch('MANUAL');
  assert(dispatchRes.success, 'Unified SOS dispatch succeeded on iOS');
  assert(dispatchRes.sirenActive === true, 'Siren active in dispatch result on iOS');
  assert(dispatchRes.torchActive === true, 'Torch active in dispatch result on iOS');
  assert(useSafetyStore.getState().sosActive, 'Zustand safety store is in active SOS state');
  useSafetyStore.getState().cancelSos();

  // ----------------------------------------------------
  // TEST SECTION 11: CROSS-PLATFORM PARITY INTEGRITY
  // ----------------------------------------------------
  console.log('\n--- 11. Cross-Platform Android Regression Check ---');
  setMockPlatform('android');
  assert(Platform.OS === 'android', 'Switched back to Android platform context');

  const androidPqc = await PqcSecurityService.getPqcStatus();
  assert(
    androidPqc.available === true && androidPqc.status === 'PQC_ACTIVE',
    'Android continues to report real active PQC via Bouncy Castle 1.81'
  );

  const androidCall = await TelephonyService.initiateEmergencyCall('911');
  assert((androidCall as any).platform === 'Android', 'Android telephony remains intact');

  console.log('\n====================================================');
  console.log(`  PHASE 8 RESULTS: ${passed} PASSED, ${failed} FAILED`);
  console.log('====================================================\n');

  return failed === 0;
}

if (typeof require !== 'undefined' && require.main === module) {
  runPhase8Verification().then((ok) => {
    process.exit(ok ? 0 : 1);
  });
}
