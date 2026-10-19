"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BackgroundService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const { AegisBackgroundServiceModule } = nativeBridge_1.NativeModules;
let _mockServiceRunning = false;
exports.BackgroundService = {
    /**
     * Starts the Android foreground service with persistent notification.
     */
    async startService() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBackgroundServiceModule?.startService) {
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
    async stopService() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBackgroundServiceModule?.stopService) {
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
    async isServiceRunning() {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisBackgroundServiceModule?.isServiceRunning) {
            return await AegisBackgroundServiceModule.isServiceRunning();
        }
        return _mockServiceRunning;
    },
};
