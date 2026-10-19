"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HardwareService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const { AegisHardwareModule } = nativeBridge_1.NativeModules;
let _mockTorchOn = false;
exports.HardwareService = {
    /**
     * Checks if device has a camera flash unit available for torch.
     */
    async isTorchAvailable() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisHardwareModule?.isTorchAvailable) {
            return await AegisHardwareModule.isTorchAvailable();
        }
        return true;
    },
    /**
     * Toggles camera torch / flashlight state.
     */
    async toggleTorch(enabled) {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisHardwareModule?.toggleTorch) {
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
    async isTorchOn() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisHardwareModule?.isTorchOn) {
            return await AegisHardwareModule.isTorchOn();
        }
        return _mockTorchOn;
    },
};
