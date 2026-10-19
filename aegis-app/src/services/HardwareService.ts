import { NativeModules, Platform } from './nativeBridge';

const { AegisHardwareModule } = NativeModules;

export interface TorchResult {
  status: 'TORCH_ON' | 'TORCH_OFF' | 'FAILED';
  enabled: boolean;
  message?: string;
}

let _mockTorchOn = false;

export const HardwareService = {
  /**
   * Checks if device has a camera flash unit available for torch.
   */
  async isTorchAvailable(): Promise<boolean> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisHardwareModule?.isTorchAvailable) {
      return await AegisHardwareModule.isTorchAvailable();
    }
    return true;
  },

  /**
   * Toggles camera torch / flashlight state.
   */
  async toggleTorch(enabled: boolean): Promise<TorchResult> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisHardwareModule?.toggleTorch) {
      return await AegisHardwareModule.toggleTorch(enabled);
    }
    _mockTorchOn = enabled;
    return {
      status: enabled ? 'TORCH_ON' : 'TORCH_OFF',
      enabled,
    };
  },

  /**
   * Returns current torch state.
   */
  async isTorchOn(): Promise<boolean> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisHardwareModule?.isTorchOn) {
      return await AegisHardwareModule.isTorchOn();
    }
    return _mockTorchOn;
  },
};
