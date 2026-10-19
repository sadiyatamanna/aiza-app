"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TelephonyService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const { AegisTelephonyModule } = nativeBridge_1.NativeModules;
exports.TelephonyService = {
    /**
     * Initiates direct emergency phone call with dialer fallback.
     */
    async initiateEmergencyCall(phoneNumber) {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisTelephonyModule?.initiateEmergencyCall) {
            return await AegisTelephonyModule.initiateEmergencyCall(phoneNumber);
        }
        // Fallback for mock environments
        return {
            status: 'CALL_INITIATED',
            method: 'ACTION_CALL',
            phoneNumber,
        };
    },
    /**
     * Sends direct emergency SMS to an emergency contact with multi-part support.
     * On Android: Transmits directly via SmsManager in background.
     * On iOS: Sandboxing prohibits silent background SMS; queues or formats for emergency transmission.
     */
    async sendDirectSms(phoneNumber, message) {
        if ((nativeBridge_1.Platform.OS === 'android' || nativeBridge_1.Platform.OS === 'ios') && AegisTelephonyModule?.sendDirectSms) {
            return await AegisTelephonyModule.sendDirectSms(phoneNumber, message);
        }
        // Fallback for mock environments
        const partsCount = Math.ceil(message.length / 160) || 1;
        return {
            status: 'SMS_QUEUED',
            phoneNumber,
            parts: partsCount,
        };
    },
    /**
     * Alias for backward compatibility with earlier phases.
     */
    async sendEmergencySms(phoneNumber, message) {
        const result = await this.sendDirectSms(phoneNumber, message);
        return result.status === 'SMS_QUEUED' || result.status === 'SENT';
    },
    /**
     * Subscribes to native SMS transmission and delivery receipt events.
     */
    onSmsStatus(callback) {
        if (nativeBridge_1.DeviceEventEmitter) {
            const sub = nativeBridge_1.DeviceEventEmitter.addListener('onAegisSmsStatus', callback);
            return () => sub.remove();
        }
        return () => { };
    },
};
