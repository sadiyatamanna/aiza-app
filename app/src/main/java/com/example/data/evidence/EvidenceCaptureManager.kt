package com.example.data.evidence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages photo, audio, and video evidence capture.
 * Provides clear recording indicators, stop/cancel controls, and strict permission handling.
 */
object EvidenceCaptureManager {

    private const val TAG = "EvidenceCaptureManager"
    const val MIN_FREE_STORAGE_BYTES = 10L * 1024L * 1024L // 10 MB

    private var activeRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private val _currentRecordingType = MutableStateFlow<String?>(null)
    val currentRecordingType: StateFlow<String?> = _currentRecordingType.asStateFlow()

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun checkStorageAvailable(context: Context): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val available = stat.availableBytes
            available > MIN_FREE_STORAGE_BYTES
        } catch (e: Exception) {
            true
        }
    }

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Creates a temporary file in the app's cache/files directory for photo, audio, or video capture.
     */
    fun createTempCaptureFile(context: Context, type: String): File {
        val dir = File(context.filesDir, "raw_evidence").apply { mkdirs() }
        val ext = when (type) {
            EvidenceType.PHOTO -> "jpg"
            EvidenceType.AUDIO -> "mp4"
            EvidenceType.VIDEO -> "mp4"
            else -> "dat"
        }
        return File(dir, "evidence_${System.currentTimeMillis()}.$ext")
    }

    /**
     * Starts audio evidence recording with visible indicator and timer.
     */
    fun startAudioRecording(context: Context): Result<File> {
        if (!hasAudioPermission(context)) {
            return Result.failure(SecurityException("Microphone permission (RECORD_AUDIO) denied"))
        }

        if (!checkStorageAvailable(context)) {
            return Result.failure(IllegalStateException("Insufficient device storage (less than 10MB available)"))
        }

        try {
            stopRecording()

            val outputFile = createTempCaptureFile(context, EvidenceType.AUDIO)
            currentOutputFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            activeRecorder = recorder
            _isRecording.value = true
            _currentRecordingType.value = EvidenceType.AUDIO
            _recordingDurationSeconds.value = 0

            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive && _isRecording.value) {
                    delay(1000)
                    _recordingDurationSeconds.value += 1
                }
            }

            Log.d(TAG, "Audio evidence recording started: ${outputFile.name}")
            return Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio evidence recording: ${e.message}", e)
            cleanUpRecorder()
            return Result.failure(e)
        }
    }

    /**
     * Stops active recording and returns the final media file.
     */
    fun stopRecording(): File? {
        timerJob?.cancel()
        val file = currentOutputFile
        try {
            activeRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping MediaRecorder: ${e.message}")
        }
        cleanUpRecorder()
        return file
    }

    /**
     * Cancels active recording and discards the temporary media file.
     */
    fun cancelRecording() {
        timerJob?.cancel()
        val file = currentOutputFile
        try {
            activeRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception cancelling MediaRecorder: ${e.message}")
        }
        cleanUpRecorder()
        try {
            if (file != null && file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun cleanUpRecorder() {
        activeRecorder = null
        currentOutputFile = null
        _isRecording.value = false
        _currentRecordingType.value = null
        _recordingDurationSeconds.value = 0
    }
}
