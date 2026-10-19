package com.example.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

object VoiceSosConfig {
    const val EMERGENCY_PHRASE = "help me"
    val RECOGNIZED_PHRASES = listOf("help me", "help", "emergency", "aiza help", "aiza emergency", "aegis help", "save me", "please help me")
    const val COOLDOWN_MS = 5000L
}

enum class VoiceDetectionState {
    DISABLED,
    REQUESTING_PERMISSION,
    READY,
    LISTENING,
    PROCESSING,
    ERROR
}

class VoiceSosManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceSosManager"
        const val EMERGENCY_PHRASE = VoiceSosConfig.EMERGENCY_PHRASE
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentState = VoiceDetectionState.DISABLED
    private var onVoiceTriggerCallback: ((String) -> Unit)? = null
    private var lastTriggerTime = 0L

    private val handler = Handler(Looper.getMainLooper())

    fun getState(): VoiceDetectionState = currentState

    fun normalizeText(input: String): String {
        return input.lowercase(Locale.getDefault())
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .trim()
    }

    fun isEmergencyPhrase(recognizedText: String): Boolean {
        val normalized = normalizeText(recognizedText)
        if (normalized.isEmpty()) return false

        val matches = com.example.domain.VoiceTriggerValidator.validateAndConsume(recognizedText) ||
                com.example.domain.VoiceTriggerValidator.validateAndConsume(normalized)

        Log.d(TAG, "Checking voice input: '$recognizedText' -> normalized: '$normalized', matches emergency phrase: $matches")

        if (matches) {
            Log.d(TAG, "EMERGENCY PHRASE DETECTED ('$recognizedText')!")
            return true
        }
        return false
    }

    fun startListening(onTrigger: (String) -> Unit) {
        this.onVoiceTriggerCallback = onTrigger

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition is not available on this device.")
            currentState = VoiceDetectionState.ERROR
            return
        }

        handler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    speechRecognizer?.setRecognitionListener(createListener())
                }

                isListening = true
                currentState = VoiceDetectionState.LISTENING

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Voice SOS Detection active. Listening for emergency phrase '$EMERGENCY_PHRASE'...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognizer", e)
                currentState = VoiceDetectionState.ERROR
                isListening = false
            }
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            currentState = VoiceDetectionState.LISTENING
            Log.d(TAG, "SpeechRecognizer ready for speech.")
        }

        override fun onBeginningOfSpeech() {
            currentState = VoiceDetectionState.PROCESSING
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech detected.")
        }

        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer error code: $error")
            currentState = VoiceDetectionState.LISTENING
            
            // Restart listening automatically after error (e.g. timeout / no match) if still active
            if (isListening) {
                handler.postDelayed({
                    if (isListening) {
                        restartListening()
                    }
                }, 1000)
            }
        }

        override fun onResults(results: Bundle?) {
            processMatches(results)
            if (isListening) {
                handler.postDelayed({
                    if (isListening) {
                        restartListening()
                    }
                }, 500)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            processMatches(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processMatches(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (match in matches) {
            if (isEmergencyPhrase(match)) {
                onVoiceTriggerCallback?.invoke(match)
                break
            }
        }
    }

    private fun restartListening() {
        if (!isListening) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting SpeechRecognizer", e)
        }
    }

    fun stopListening() {
        isListening = false
        currentState = VoiceDetectionState.DISABLED
        handler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying SpeechRecognizer", e)
            }
            speechRecognizer = null
            Log.d(TAG, "VoiceSosManager stopped listening and released microphone resources.")
        }
    }
}
