import { NativeModules, DeviceEventEmitter, Platform } from './nativeBridge';
import { useSafetyStore } from '../store/safetyStore';
import { SosService } from './SosService';

const { AegisMotionModule } = NativeModules;

export interface ShakeTriggerEvent {
  source: 'SHAKE';
  triggerType: 'CORE_MOTION_ACCELERATION' | 'SENSOR_ACCELEROMETER';
  accelerationG: number;
  timestamp: number;
  platform: string;
}

let _isMonitoring = false;
let _subscription: any = null;

export const MotionService = {
  /**
   * Checks if accelerometer hardware is available on the device.
   */
  async isMotionAvailable(): Promise<boolean> {
    if (AegisMotionModule?.isMotionAvailable) {
      return await AegisMotionModule.isMotionAvailable();
    }
    return true;
  },

  /**
   * Starts monitoring for emergency shake gestures.
   */
  async startShakeDetection(): Promise<{ status: string; active: boolean }> {
    if (_isMonitoring) {
      return { status: 'ALREADY_MONITORING', active: true };
    }

    if (AegisMotionModule?.startShakeDetection) {
      await AegisMotionModule.startShakeDetection();
    }

    if (DeviceEventEmitter) {
      _subscription = DeviceEventEmitter.addListener(
        'onAegisShakeSosTrigger',
        async (event: ShakeTriggerEvent) => {
          const store = useSafetyStore.getState();
          // Check if shake SOS feature is enabled in user settings
          if (store.shakeSosEnabled && !store.sosActive) {
            console.log('[MotionService] Valid emergency shake detected. Triggering unified SOS dispatch.');
            await SosService.executeSosDispatch('SHAKE');
          }
        }
      );
    }

    _isMonitoring = true;
    return { status: 'MONITORING_STARTED', active: true };
  },

  /**
   * Stops shake monitoring.
   */
  async stopShakeDetection(): Promise<{ status: string; active: boolean }> {
    if (_subscription) {
      _subscription.remove();
      _subscription = null;
    }

    if (AegisMotionModule?.stopShakeDetection) {
      await AegisMotionModule.stopShakeDetection();
    }

    _isMonitoring = false;
    return { status: 'MONITORING_STOPPED', active: false };
  },

  /**
   * Checks if shake detection is actively monitoring.
   */
  async isShakeDetectionActive(): Promise<boolean> {
    if (AegisMotionModule?.isShakeDetectionActive) {
      return await AegisMotionModule.isShakeDetectionActive();
    }
    return _isMonitoring;
  },

  /**
   * Testing simulator for shake trigger.
   */
  _simulateShake(accelerationG: number = 2.8): boolean {
    if (AegisMotionModule?._simulateShake) {
      return AegisMotionModule._simulateShake(accelerationG);
    }
    return false;
  },
};
