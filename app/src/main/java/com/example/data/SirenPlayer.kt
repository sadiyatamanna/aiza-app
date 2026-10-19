package com.example.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.example.R

/**
 * High-decibel Emergency Siren player for Aegis.
 * Plays the emergency acoustic alarm asset from res/raw/emergency_siren.wav at maximum alarm volume,
 * handling audio focus and looping playback.
 */
object SirenPlayer {

    private const val TAG = "SirenPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var isPlayingState = false

    fun isPlaying(): Boolean = isPlayingState

    @Synchronized
    fun start(context: Context) {
        Log.d(TAG, "SIREN button pressed")
        Log.d(TAG, "audio resource=R.raw.emergency_siren")

        if (isPlayingState) {
            Log.d(TAG, "Siren already playing. Skipping duplicate start.")
            return
        }

        try {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager = am

            // Elevate alarm and music volume to maximum for emergency audibility
            am?.let {
                try {
                    val maxAlarmVol = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    it.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set STREAM_ALARM max volume: ${e.message}")
                }
                try {
                    val maxMusicVol = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    it.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set STREAM_MUSIC max volume: ${e.message}")
                }
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Request Audio Focus to supersede non-critical background audio
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(audioAttributes)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener { /* maintain siren priority */ }
                        .build()
                    focusRequest = req
                    am.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(
                        null,
                        AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                }
            }

            val player = MediaPlayer.create(context.applicationContext, R.raw.emergency_siren)
            val initialized = (player != null)
            Log.d(TAG, "player initialized=$initialized")

            if (player != null) {
                player.setAudioAttributes(audioAttributes)
                player.isLooping = true
                player.setVolume(1.0f, 1.0f)
                player.start()
                mediaPlayer = player
                isPlayingState = true
                Log.d(TAG, "playback started=true")
            } else {
                Log.e(TAG, "playback error=MediaPlayer.create returned null for R.raw.emergency_siren")
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback error=${e.message}", e)
            isPlayingState = false
        }
    }

    @Synchronized
    fun stop() {
        Log.d(TAG, "STOP SIREN requested")
        isPlayingState = false
        try {
            mediaPlayer?.apply {
                try {
                    if (isPlaying) {
                        stop()
                    }
                } catch (_: Exception) {}
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer: ${e.message}")
        }
        mediaPlayer = null

        // Abandon audio focus
        try {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest?.let { am.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
        focusRequest = null
        audioManager = null
        Log.d(TAG, "Siren playback stopped and audio resources released.")
    }
}
