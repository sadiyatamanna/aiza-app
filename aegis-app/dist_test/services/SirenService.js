"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SirenService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const { AegisHardwareModule } = nativeBridge_1.NativeModules;
let _mockSirenPlaying = false;
exports.SirenService = {
    /**
     * Starts emergency loud siren audio.
     */
    async playEmergencySiren() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisHardwareModule?.playEmergencySiren) {
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
    async stopEmergencySiren() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisHardwareModule?.stopEmergencySiren) {
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
    async isSirenPlaying() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisHardwareModule?.isSirenPlaying) {
            return await AegisHardwareModule.isSirenPlaying();
        }
        return _mockSirenPlaying;
    },
    /**
     * Backward-compatible alias for previous phases.
     */
    async startSiren() {
        await this.playEmergencySiren();
    },
    /**
     * Backward-compatible alias for previous phases.
     */
    async stopSiren() {
        await this.stopEmergencySiren();
    },
};
