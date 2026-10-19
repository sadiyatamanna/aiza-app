import { NativeModules, Platform } from './nativeBridge';

const { AegisHardwareModule } = NativeModules;

export interface SirenResult {
  success: boolean;
  status: 'PLAYING' | 'STOPPED' | 'SIREN_RESOURCE_UNAVAILABLE' | 'SIREN_INITIALIZATION_FAILED' | string;
  message?: string;
}

let _mockSirenPlaying = false;

export const SirenService = {
  /**
   * Starts emergency loud siren audio.
   */
  async playEmergencySiren(): Promise<SirenResult> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisHardwareModule?.playEmergencySiren) {
      return await AegisHardwareModule.playEmergencySiren();
    }
    _mockSirenPlaying = true;
    return {
      success: true,
      status: 'PLAYING',
    };
  },

  /**
   * Stops active siren audio.
   */
  async stopEmergencySiren(): Promise<SirenResult> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisHardwareModule?.stopEmergencySiren) {
      return await AegisHardwareModule.stopEmergencySiren();
    }
    _mockSirenPlaying = false;
    return {
      success: true,
      status: 'STOPPED',
    };
  },

  /**
   * Checks if siren is actively playing.
   */
  async isSirenPlaying(): Promise<boolean> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisHardwareModule?.isSirenPlaying) {
      return await AegisHardwareModule.isSirenPlaying();
    }
    return _mockSirenPlaying;
  },

  /**
   * Backward-compatible alias for previous phases.
   */
  async startSiren(): Promise<void> {
    await this.playEmergencySiren();
  },

  /**
   * Backward-compatible alias for previous phases.
   */
  async stopSiren(): Promise<void> {
    await this.stopEmergencySiren();
  },
};
