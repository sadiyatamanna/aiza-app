package com.example.bridge

import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.service.AegisBackgroundService
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * Android Native Bridge to manage the Aegis Background Foreground Service.
 */
class AegisBackgroundServiceModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "AegisBackgroundServiceModule"
    }

    override fun getName(): String = MODULE_NAME

    @ReactMethod
    fun startService(promise: Promise) {
        try {
            val intent = Intent(reactContext, AegisBackgroundService::class.java)
            ContextCompat.startForegroundService(reactContext, intent)
            val result = Arguments.createMap().apply {
                putString("status", "STARTED")
                putBoolean("running", true)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("START_SERVICE_FAILED", "Failed to start background service: ${e.message}", e)
        }
    }

    @ReactMethod
    fun stopService(promise: Promise) {
        try {
            val intent = Intent(reactContext, AegisBackgroundService::class.java)
            reactContext.stopService(intent)
            val result = Arguments.createMap().apply {
                putString("status", "STOPPED")
                putBoolean("running", false)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("STOP_SERVICE_FAILED", "Failed to stop background service: ${e.message}", e)
        }
    }

    @ReactMethod
    fun isServiceRunning(promise: Promise) {
        promise.resolve(AegisBackgroundService.isRunning)
    }
}
