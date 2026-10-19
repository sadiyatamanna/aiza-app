"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MotionService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const safetyStore_1 = require("../store/safetyStore");
const SosService_1 = require("./SosService");
const { AegisMotionModule } = nativeBridge_1.NativeModules;
let _isMonitoring = false;
let _subscription = null;
exports.MotionService = {
    /**
     * Checks if accelerometer hardware is available on the device.
     */
    async isMotionAvailable() {
        if (AegisMotionModule?.isMotionAvailable) {
            return await AegisMotionModule.isMotionAvailable();
        }
        return true;
    },
    /**
     * Starts monitoring for emergency shake gestures.
     */
    async startShakeDetection() {
        if (_isMonitoring) {
            return { status: 'ALREADY_MONITORING', active: true };
        }
        if (AegisMotionModule?.startShakeDetection) {
            await AegisMotionModule.startShakeDetection();
        }
        if (nativeBridge_1.DeviceEventEmitter) {
            _subscription = nativeBridge_1.DeviceEventEmitter.addListener('onAegisShakeSosTrigger', async (event) => {
                const store = safetyStore_1.useSafetyStore.getState();
                // Check if shake SOS feature is enabled in user settings
                if (store.shakeSosEnabled && !store.sosActive) {
                    console.log('[MotionService] Valid emergency shake detected. Triggering unified SOS dispatch.');
                    await SosService_1.SosService.executeSosDispatch('SHAKE');
                }
            });
        }
        _isMonitoring = true;
        return { status: 'MONITORING_STARTED', active: true };
    },
    /**
     * Stops shake monitoring.
     */
    async stopShakeDetection() {
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
    async isShakeDetectionActive() {
        if (AegisMotionModule?.isShakeDetectionActive) {
            return await AegisMotionModule.isShakeDetectionActive();
        }
        return _isMonitoring;
    },
    /**
     * Testing simulator for shake trigger.
     */
    _simulateShake(accelerationG = 2.8) {
        if (AegisMotionModule?._simulateShake) {
            return AegisMotionModule._simulateShake(accelerationG);
        }
        return false;
    },
};
