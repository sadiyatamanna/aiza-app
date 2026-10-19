import { NativeModules, Platform } from './nativeBridge';

const { AegisBackgroundServiceModule } = NativeModules;

export interface BackgroundServiceResult {
  status: 'STARTED' | 'STOPPED' | 'FAILED';
  running: boolean;
  message?: string;
}

let _mockServiceRunning = false;

export const BackgroundService = {
  /**
   * Starts the Android foreground service with persistent notification.
   */
  async startService(): Promise<BackgroundServiceResult> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBackgroundServiceModule?.startService) {
      return await AegisBackgroundServiceModule.startService();
    }
    _mockServiceRunning = true;
    return {
      status: 'STARTED',
      running: true,
    };
  },

  /**
   * Stops the background foreground service.
   */
  async stopService(): Promise<BackgroundServiceResult> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBackgroundServiceModule?.stopService) {
      return await AegisBackgroundServiceModule.stopService();
    }
    _mockServiceRunning = false;
    return {
      status: 'STOPPED',
      running: false,
    };
  },

  /**
   * Checks if background foreground service is currently running.
   */
  async isServiceRunning(): Promise<boolean> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisBackgroundServiceModule?.isServiceRunning) {
      return await AegisBackgroundServiceModule.isServiceRunning();
    }
    return _mockServiceRunning;
  },
};
