import { NativeModules, DeviceEventEmitter, Platform } from './nativeBridge';

const { AegisTelephonyModule } = NativeModules;

export interface TelephonyCallResult {
  status: 'CALL_INITIATED' | 'DIAL_FALLBACK_INITIATED';
  method: 'ACTION_CALL' | 'ACTION_DIAL';
  phoneNumber: string;
}

export interface TelephonySmsResult {
  status: 'SMS_QUEUED' | 'SENT' | 'FAILED';
  phoneNumber: string;
  parts: number;
}

export interface SmsStatusEvent {
  type: 'SENT_STATUS' | 'DELIVERY_STATUS';
  status:
    | 'SENT'
    | 'DELIVERED'
    | 'ERROR_GENERIC_FAILURE'
    | 'ERROR_NO_SERVICE'
    | 'ERROR_NULL_PDU'
    | 'ERROR_RADIO_OFF'
    | 'ERROR_UNKNOWN'
    | 'DELIVERY_FAILED'
    | 'DELIVERY_CANCELED';
  phoneNumber: string;
  resultCode?: number;
  timestamp: number;
}

export const TelephonyService = {
  /**
   * Initiates direct emergency phone call with dialer fallback.
   */
  async initiateEmergencyCall(phoneNumber: string): Promise<TelephonyCallResult> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisTelephonyModule?.initiateEmergencyCall) {
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
  async sendDirectSms(phoneNumber: string, message: string): Promise<TelephonySmsResult> {
    if ((Platform.OS === 'android' || Platform.OS === 'ios') && AegisTelephonyModule?.sendDirectSms) {
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
  async sendEmergencySms(phoneNumber: string, message: string): Promise<boolean> {
    const result = await this.sendDirectSms(phoneNumber, message);
    return result.status === 'SMS_QUEUED' || result.status === 'SENT';
  },

  /**
   * Subscribes to native SMS transmission and delivery receipt events.
   */
  onSmsStatus(callback: (event: SmsStatusEvent) => void): () => void {
    if (DeviceEventEmitter) {
      const sub = DeviceEventEmitter.addListener('onAegisSmsStatus', callback);
      return () => sub.remove();
    }
    return () => {};
  },
};
