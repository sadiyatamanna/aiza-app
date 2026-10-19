package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.days

/**
 * Manages audio capture of emergency distress clips, local persistence,
 * secure authorized upload to Supabase Storage, and URL generation.
 */
object DistressClipManager {

    private const val TAG = "DistressClipManager"
    const val STORAGE_BUCKET = "distress_clips"

    private var activeRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null

    /**
     * Captures a voice distress audio clip.
     * If an existing pre-recorded clip is available and valid, it can be returned,
     * or a fresh recording is captured from the microphone.
     */
    fun startDistressRecording(context: Context): File? {
        Log.d(TAG, "VOICE SOS detected")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission not granted for distress clip capture.")
            return null
        }

        try {
            stopDistressRecording()

            val timestamp = System.currentTimeMillis()
            val outputFile = File(context.filesDir, "distress_clip_$timestamp.mp4")
            currentRecordingFile = outputFile

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
            Log.d(TAG, "recording started")
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start distress recording: ${e.message}", e)
            activeRecorder = null
            return null
        }
    }

    /**
     * Stops active distress recording and returns the final saved File.
     */
    fun stopDistressRecording(): File? {
        val file = currentRecordingFile
        try {
            activeRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while stopping MediaRecorder: ${e.message}")
        }
        activeRecorder = null

        if (file != null && file.exists()) {
            Log.d(TAG, "recording stopped")
            Log.d(TAG, "file path=${file.absolutePath}")
            Log.d(TAG, "file size=${file.length()}")
        }
        return file
    }

    /**
     * Uploads the distress audio clip to Supabase Storage securely,
     * and obtains an authorized, time-limited signed URL.
     */
    suspend fun uploadDistressClip(file: File, userId: String): Result<String> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Distress file is missing or empty"))
        }

        val resolvedUserId = userId.ifBlank { "anonymous_user" }
        val storagePath = "user_${resolvedUserId}/clip_${file.name}"

        try {
            Log.d(TAG, "upload started")
            val bytes = file.readBytes()

            val bucket = SupabaseClientProvider.client.storage.from(STORAGE_BUCKET)
            bucket.upload(path = storagePath, data = bytes) {
                upsert = true
            }
            Log.d(TAG, "upload completed")
            Log.d(TAG, "storage path=$storagePath")

            val signedUrl = bucket.createSignedUrl(path = storagePath, expiresIn = 7.days)
            Log.d(TAG, "URL generated: $signedUrl")
            Result.success(signedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload distress clip or generate signed URL: ${e.message}", e)
            Result.failure(e)
        }
    }
}
