package com.example.bridge

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * Android Native Hardware Alert Module for Aegis Safety.
 * Controls the emergency loud siren (MediaPlayer) and device torch/strobe (Camera2 API).
 */
class AegisHardwareModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "AegisHardwareModule"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isTorchOn = false

    override fun getName(): String = MODULE_NAME

    /**
     * Plays the emergency siren in a loop.
     * Uses USAGE_ALARM to ensure high audibility.
     * Gracefully handles missing emergency_siren audio resource without crashing.
     */
    @ReactMethod
    fun playEmergencySiren(promise: Promise) {
        try {
            stopCurrentMediaPlayer()

            val resId = reactContext.resources.getIdentifier(
                "emergency_siren",
                "raw",
                reactContext.packageName
            )

            if (resId == 0) {
                val result = Arguments.createMap().apply {
                    putBoolean("success", false)
                    putString("status", "SIREN_RESOURCE_UNAVAILABLE")
                    putString("message", "emergency_siren.wav resource is not bundled in res/raw")
                }
                promise.resolve(result)
                return
            }

            requestAlarmAudioFocus()

            val player = MediaPlayer.create(reactContext, resId)
            if (player == null) {
                val result = Arguments.createMap().apply {
                    putBoolean("success", false)
                    putString("status", "SIREN_INITIALIZATION_FAILED")
                }
                promise.resolve(result)
                return
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            player.setAudioAttributes(audioAttributes)
            player.isLooping = true
            player.start()
            mediaPlayer = player

            val result = Arguments.createMap().apply {
                putBoolean("success", true)
                putString("status", "PLAYING")
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("SIREN_ERROR", "Failed to play emergency siren: ${e.message}", e)
        }
    }

    /**
     * Stops and releases the emergency siren player and abandons audio focus.
     */
    @ReactMethod
    fun stopEmergencySiren(promise: Promise) {
        try {
            stopCurrentMediaPlayer()
            abandonAlarmAudioFocus()
            val result = Arguments.createMap().apply {
                putString("status", "STOPPED")
                putBoolean("playing", false)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("SIREN_STOP_ERROR", "Failed to stop siren: ${e.message}", e)
        }
    }

    @ReactMethod
    fun isSirenPlaying(promise: Promise) {
        val playing = mediaPlayer?.isPlaying == true
        promise.resolve(playing)
    }

    /**
     * Checks if the device has a camera flash unit available.
     */
    @ReactMethod
    fun isTorchAvailable(promise: Promise) {
        try {
            val cameraManager = reactContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                promise.resolve(false)
                return
            }

            var hasFlash = false
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (flashAvailable) {
                    hasFlash = true
                    break
                }
            }
            promise.resolve(hasFlash)
        } catch (_: Exception) {
            promise.resolve(false)
        }
    }

    /**
     * Toggles device torch mode on/off using Camera2 API.
     */
    @ReactMethod
    fun toggleTorch(enabled: Boolean, promise: Promise) {
        try {
            val cameraManager = reactContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                promise.reject("CAMERA_UNAVAILABLE", "Camera service not available")
                return
            }

            var flashCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (flashAvailable && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    flashCameraId = id
                    break
                }
            }

            if (flashCameraId == null) {
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    if (flashAvailable) {
                        flashCameraId = id
                        break
                    }
                }
            }

            if (flashCameraId == null) {
                promise.reject("TORCH_UNAVAILABLE", "No flash-capable camera found on device")
                return
            }

            cameraManager.setTorchMode(flashCameraId, enabled)
            isTorchOn = enabled

            val result = Arguments.createMap().apply {
                putString("status", if (enabled) "TORCH_ON" else "TORCH_OFF")
                putBoolean("enabled", enabled)
            }
            promise.resolve(result)
        } catch (e: CameraAccessException) {
            promise.reject("CAMERA_ACCESS_ERROR", "Failed to access camera torch: ${e.message}", e)
        } catch (e: Exception) {
            promise.reject("TORCH_ERROR", "Failed to toggle torch: ${e.message}", e)
        }
    }

    @ReactMethod
    fun isTorchOn(promise: Promise) {
        promise.resolve(isTorchOn)
    }

    private fun requestAlarmAudioFocus() {
        val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .build()

            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAlarmAudioFocus() {
        val audioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun stopCurrentMediaPlayer() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        stopCurrentMediaPlayer()
        abandonAlarmAudioFocus()
    }
}
