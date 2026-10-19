import { NativeModules, DeviceEventEmitter, Platform } from './nativeBridge';
import { useSafetyStore } from '../store/safetyStore';
import { SosService } from './SosService';

const { AegisSpeechModule } = NativeModules;

export interface VoiceTriggerEvent {
  source: 'VOICE';
  triggerType: 'SPEECH_RECOGNITION';
  matchedPhrase: string;
  transcript: string;
  timestamp: number;
  platform: string;
}

const EMERGENCY_KEYWORDS = [
  'help',
  'emergency',
  'save me',
  'aiza help',
  'aiza emergency',
];

let _isListening = false;
let _subscription: any = null;

export const VoiceService = {
  /**
   * Requests speech recognition authorization from system.
   */
  async requestSpeechAuthorization(): Promise<{ granted: boolean; status: string }> {
    if (AegisSpeechModule?.requestSpeechAuthorization) {
      return await AegisSpeechModule.requestSpeechAuthorization();
    }
    return { granted: true, status: 'AUTHORIZED' };
  },

  /**
   * Starts hands-free voice SOS recognition session.
   * Strictly on-device keyword recognition; no distress audio is recorded or uploaded.
   */
  async startVoiceRecognition(): Promise<{ status: string; active: boolean }> {
    if (_isListening) {
      return { status: 'ALREADY_LISTENING', active: true };
    }

    if (AegisSpeechModule?.startVoiceRecognition) {
      await AegisSpeechModule.startVoiceRecognition();
    }

    if (DeviceEventEmitter) {
      _subscription = DeviceEventEmitter.addListener(
        'onAegisVoiceSosTrigger',
        async (event: VoiceTriggerEvent) => {
          const store = useSafetyStore.getState();
          // Verify feature is enabled and keyword is authorized
          const phrase = (event.matchedPhrase || '').toLowerCase().trim();
          const isAuthorizedKeyword = EMERGENCY_KEYWORDS.some((kw) => phrase.includes(kw));

          if (isAuthorizedKeyword && store.voiceSosEnabled && !store.sosActive) {
            console.log(`[VoiceService] Valid distress keyword recognized ("${phrase}"). Triggering unified SOS dispatch.`);
            // Dispatches unified SOS with 'VOICE' source without uploading audio
            await SosService.executeSosDispatch('VOICE');
          }
        }
      );
    }

    _isListening = true;
    return { status: 'LISTENING_STARTED', active: true };
  },

  /**
   * Stops hands-free voice SOS recognition session.
   */
  async stopVoiceRecognition(): Promise<{ status: string; active: boolean }> {
    if (_subscription) {
      _subscription.remove();
      _subscription = null;
    }

    if (AegisSpeechModule?.stopVoiceRecognition) {
      await AegisSpeechModule.stopVoiceRecognition();
    }

    _isListening = false;
    return { status: 'LISTENING_STOPPED', active: false };
  },

  /**
   * Checks if voice recognition is actively listening.
   */
  async isVoiceRecognitionActive(): Promise<boolean> {
    if (AegisSpeechModule?.isVoiceRecognitionActive) {
      return await AegisSpeechModule.isVoiceRecognitionActive();
    }
    return _isListening;
  },

  /**
   * Testing simulator for voice phrase detection.
   */
  _simulateVoicePhrase(phrase: string): boolean {
    if (AegisSpeechModule?._simulateVoicePhrase) {
      return AegisSpeechModule._simulateVoicePhrase(phrase);
    }
    return false;
  },
};
