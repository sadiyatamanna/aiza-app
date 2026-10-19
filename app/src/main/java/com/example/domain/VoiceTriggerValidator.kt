package com.example.domain

import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Validates voice phrases and enforces rate limiting/cooldown for Voice SOS triggers.
 */
object VoiceTriggerValidator {

    private const val TAG = "VoiceTriggerValidator"
    private const val TRIGGER_COOLDOWN_MS = 5000L
    private val lastTriggerTimestamp = AtomicLong(0L)

    val EMERGENCY_KEYPHRASES = listOf(
        "help me",
        "help",
        "emergency",
        "save me",
        "aiza help",
        "aiza emergency",
        "please help me",
        "call for help",
        "danger",
        "police",
        "bachao",
        "need help"
    )

    /**
     * Checks whether a recognized voice transcript matches any emergency keyphrase.
     */
    fun isEmergencyPhrase(transcript: String?): Boolean {
        if (transcript.isNullOrBlank()) return false
        val clean = transcript.lowercase(Locale.ROOT).trim()
        return EMERGENCY_KEYPHRASES.any { phrase ->
            clean.contains(phrase)
        }
    }

    fun matchesEmergencyPhrase(transcript: String?): Boolean = isEmergencyPhrase(transcript)

    /**
     * Checks whether a voice trigger can fire right now (enforcing 5s cooldown).
     */
    fun canTriggerNow(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastTriggerTimestamp.get()
        return (now - last) >= TRIGGER_COOLDOWN_MS
    }

    /**
     * Marks a voice trigger as executed at the current timestamp.
     */
    fun markTriggerExecuted() {
        lastTriggerTimestamp.set(System.currentTimeMillis())
        try {
            Log.d(TAG, "Voice trigger marked executed at ${System.currentTimeMillis()}")
        } catch (_: Throwable) {}
    }

    /**
     * Validates both phrase match and cooldown window.
     */
    fun validateAndConsume(transcript: String?): Boolean {
        if (!isEmergencyPhrase(transcript)) {
            return false
        }
        if (!canTriggerNow()) {
            try {
                Log.w(TAG, "Voice SOS trigger suppressed: Cooldown window active.")
            } catch (_: Throwable) {}
            return false
        }
        markTriggerExecuted()
        return true
    }

    /**
     * Resets cooldown (used primarily for unit testing).
     */
    fun resetCooldown() {
        lastTriggerTimestamp.set(0L)
    }

    fun resetCooldownForTesting() = resetCooldown()
}
