"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.VoiceService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const safetyStore_1 = require("../store/safetyStore");
const SosService_1 = require("./SosService");
const { AegisSpeechModule } = nativeBridge_1.NativeModules;
const EMERGENCY_KEYWORDS = [
    'help',
    'emergency',
    'save me',
    'aiza help',
    'aiza emergency',
];
let _isListening = false;
let _subscription = null;
exports.VoiceService = {
    /**
     * Requests speech recognition authorization from system.
     */
    async requestSpeechAuthorization() {
        if (AegisSpeechModule?.requestSpeechAuthorization) {
            return await AegisSpeechModule.requestSpeechAuthorization();
        }
        return { granted: true, status: 'AUTHORIZED' };
    },
    /**
     * Starts hands-free voice SOS recognition session.
     * Strictly on-device keyword recognition; no distress audio is recorded or uploaded.
     */
    async startVoiceRecognition() {
        if (_isListening) {
            return { status: 'ALREADY_LISTENING', active: true };
        }
        if (AegisSpeechModule?.startVoiceRecognition) {
            await AegisSpeechModule.startVoiceRecognition();
        }
        if (nativeBridge_1.DeviceEventEmitter) {
            _subscription = nativeBridge_1.DeviceEventEmitter.addListener('onAegisVoiceSosTrigger', async (event) => {
                const store = safetyStore_1.useSafetyStore.getState();
                // Verify feature is enabled and keyword is authorized
                const phrase = (event.matchedPhrase || '').toLowerCase().trim();
                const isAuthorizedKeyword = EMERGENCY_KEYWORDS.some((kw) => phrase.includes(kw));
                if (isAuthorizedKeyword && store.voiceSosEnabled && !store.sosActive) {
                    console.log(`[VoiceService] Valid distress keyword recognized ("${phrase}"). Triggering unified SOS dispatch.`);
                    // Dispatches unified SOS with 'VOICE' source without uploading audio
                    await SosService_1.SosService.executeSosDispatch('VOICE');
                }
            });
        }
        _isListening = true;
        return { status: 'LISTENING_STARTED', active: true };
    },
    /**
     * Stops hands-free voice SOS recognition session.
     */
    async stopVoiceRecognition() {
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
    async isVoiceRecognitionActive() {
        if (AegisSpeechModule?.isVoiceRecognitionActive) {
            return await AegisSpeechModule.isVoiceRecognitionActive();
        }
        return _isListening;
    },
    /**
     * Testing simulator for voice phrase detection.
     */
    _simulateVoicePhrase(phrase) {
        if (AegisSpeechModule?._simulateVoicePhrase) {
            return AegisSpeechModule._simulateVoicePhrase(phrase);
        }
        return false;
    },
};
