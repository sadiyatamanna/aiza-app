package com.example.bridge

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * Android Native Telephony Module for Aegis Safety.
 * Handles background direct emergency calls with graceful dialer fallback,
 * and background direct emergency SMS dispatch with delivery receipts.
 */
class AegisTelephonyModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "AegisTelephonyModule"
        const val EVENT_SMS_STATUS = "onAegisSmsStatus"
        private const val ACTION_SMS_SENT = "com.example.aegis.SMS_SENT"
        private const val ACTION_SMS_DELIVERED = "com.example.aegis.SMS_DELIVERED"
    }

    private var sentReceiverRegistered = false
    private var deliveredReceiverRegistered = false

    init {
        registerSmsReceivers()
    }

    override fun getName(): String = MODULE_NAME

    private fun sendEvent(eventName: String, params: WritableMap?) {
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN built-in EventEmitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN built-in EventEmitter
    }

    /**
     * Initiates emergency call to the given phone number.
     * Uses Intent.ACTION_CALL if CALL_PHONE permission is granted,
     * otherwise falls back to Intent.ACTION_DIAL to avoid blocking emergency operations.
     */
    @ReactMethod
    fun initiateEmergencyCall(phoneNumber: String, promise: Promise) {
        try {
            val cleanPhone = phoneNumber.trim()
            if (cleanPhone.isBlank()) {
                promise.reject("INVALID_PHONE_NUMBER", "Phone number cannot be empty")
                return
            }

            val hasCallPermission = ContextCompat.checkSelfPermission(
                reactContext,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intentAction = if (hasCallPermission) {
                Intent.ACTION_CALL
            } else {
                Intent.ACTION_DIAL
            }

            val callIntent = Intent(intentAction).apply {
                data = Uri.parse("tel:${Uri.encode(cleanPhone)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            reactContext.startActivity(callIntent)

            val result = Arguments.createMap().apply {
                putString("status", if (hasCallPermission) "CALL_INITIATED" else "DIAL_FALLBACK_INITIATED")
                putString("method", if (hasCallPermission) "ACTION_CALL" else "ACTION_DIAL")
                putString("phoneNumber", cleanPhone)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("CALL_FAILED", "Failed to initiate call: ${e.message}", e)
        }
    }

    /**
     * Sends a direct SMS with delivery tracking.
     * Supports single-part and multipart SMS messages (>160 characters).
     */
    @ReactMethod
    fun sendDirectSms(phoneNumber: String, message: String, promise: Promise) {
        try {
            val cleanPhone = phoneNumber.trim()
            if (cleanPhone.isBlank()) {
                promise.reject("INVALID_PHONE_NUMBER", "Phone number cannot be empty")
                return
            }

            if (message.isBlank()) {
                promise.reject("INVALID_MESSAGE", "SMS message cannot be empty")
                return
            }

            val hasSmsPermission = ContextCompat.checkSelfPermission(
                reactContext,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasSmsPermission) {
                promise.reject("PERMISSION_DENIED", "SEND_SMS permission not granted")
                return
            }

            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                reactContext.getSystemService(SmsManager::class.java)
                    ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val sentIntent = Intent(ACTION_SMS_SENT).apply {
                putExtra("phoneNumber", cleanPhone)
            }
            val sentPI = PendingIntent.getBroadcast(
                reactContext,
                System.currentTimeMillis().toInt(),
                sentIntent,
                pendingFlags
            )

            val deliveredIntent = Intent(ACTION_SMS_DELIVERED).apply {
                putExtra("phoneNumber", cleanPhone)
            }
            val deliveredPI = PendingIntent.getBroadcast(
                reactContext,
                (System.currentTimeMillis() + 1).toInt(),
                deliveredIntent,
                pendingFlags
            )

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>()
                val deliveredIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    sentIntents.add(sentPI)
                    deliveredIntents.add(deliveredPI)
                }
                smsManager.sendMultipartTextMessage(
                    cleanPhone,
                    null,
                    parts,
                    sentIntents,
                    deliveredIntents
                )
            } else {
                smsManager.sendTextMessage(
                    cleanPhone,
                    null,
                    message,
                    sentPI,
                    deliveredPI
                )
            }

            val result = Arguments.createMap().apply {
                putString("status", "SMS_QUEUED")
                putString("phoneNumber", cleanPhone)
                putInt("parts", parts.size)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("SMS_FAILED", "Failed to send SMS: ${e.message}", e)
        }
    }

    private fun registerSmsReceivers() {
        val sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val phoneNumber = intent?.getStringExtra("phoneNumber") ?: ""
                val statusStr = when (resultCode) {
                    Activity.RESULT_OK -> "SENT"
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "ERROR_GENERIC_FAILURE"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "ERROR_NO_SERVICE"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "ERROR_NULL_PDU"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "ERROR_RADIO_OFF"
                    else -> "ERROR_UNKNOWN"
                }

                val event = Arguments.createMap().apply {
                    putString("type", "SENT_STATUS")
                    putString("status", statusStr)
                    putString("phoneNumber", phoneNumber)
                    putInt("resultCode", resultCode)
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                }
                sendEvent(EVENT_SMS_STATUS, event)
            }
        }

        val deliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val phoneNumber = intent?.getStringExtra("phoneNumber") ?: ""
                val statusStr = when (resultCode) {
                    Activity.RESULT_OK -> "DELIVERED"
                    Activity.RESULT_CANCELED -> "DELIVERY_CANCELED"
                    else -> "DELIVERY_FAILED"
                }

                val event = Arguments.createMap().apply {
                    putString("type", "DELIVERY_STATUS")
                    putString("status", statusStr)
                    putString("phoneNumber", phoneNumber)
                    putInt("resultCode", resultCode)
                    putDouble("timestamp", System.currentTimeMillis().toDouble())
                }
                sendEvent(EVENT_SMS_STATUS, event)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reactContext.registerReceiver(
                    sentReceiver,
                    IntentFilter(ACTION_SMS_SENT),
                    Context.RECEIVER_NOT_EXPORTED
                )
                reactContext.registerReceiver(
                    deliveredReceiver,
                    IntentFilter(ACTION_SMS_DELIVERED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                reactContext.registerReceiver(
                    sentReceiver,
                    IntentFilter(ACTION_SMS_SENT)
                )
                reactContext.registerReceiver(
                    deliveredReceiver,
                    IntentFilter(ACTION_SMS_DELIVERED)
                )
            }
            sentReceiverRegistered = true
            deliveredReceiverRegistered = true
        } catch (_: Exception) {
            // Ignored if receiver cannot be registered in test/mock environment
        }
    }
}
