package com.example.domain

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
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.ContactEntity
import com.example.data.PhoneUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified, deduplicated SOS Communication Pipeline for AIZA.
 *
 * Rules:
 * - Rank 1 emergency contact receives the emergency phone CALL.
 * - Rank 2, Rank 3, Rank 4... receive emergency SMS.
 * - If exactly one emergency contact exists: receives CALL + SMS.
 *
 * SMS Content Format:
 *   AIZA SOS ALERT
 *
 *   User:
 *   [Name]
 *
 *   Trigger:
 *   [MANUAL / SHAKE / VOICE / BLE]
 *
 *   Location:
 *   [lat, lng] (or last known location)
 *
 *   Google Maps:
 *   https://maps.google.com/?q=LAT,LONG
 *
 *   Time:
 *   [timestamp]
 *
 *   Distress Clip:
 *   [URL] (Voice SOS only)
 */
object SosCommunicationDispatcher {

    private const val TAG = "SosCommDispatcher"
    private const val DEDUPLICATION_WINDOW_MS = 5000L
    private val lastDispatchTimestamp = AtomicLong(0L)

    data class DispatchResult(
        val callContact: ContactEntity?,
        val smsContacts: List<ContactEntity>,
        val messageText: String,
        val dispatched: Boolean,
        val reason: String = ""
    )

    /**
     * Determines Rank 1 contact (receives Call) and remaining contacts (receive SMS).
     * Contacts are sorted ascending by numeric priority rank (1, 2, 3...).
     */
    fun partitionContactsByRank(contacts: List<ContactEntity>): Pair<ContactEntity?, List<ContactEntity>> {
        if (contacts.isEmpty()) return Pair(null, emptyList())

        val sorted = contacts.sortedWith(
            compareBy<ContactEntity> {
                it.priority.toIntOrNull() ?: when (it.priority.lowercase(Locale.getDefault())) {
                    "high" -> 1
                    "medium" -> 2
                    "low" -> 3
                    else -> Int.MAX_VALUE
                }
            }.thenBy { it.id }
        )

        val rank1 = sorted.first()
        // If multiple contacts exist, remaining (Rank > 1) receive SMS.
        // If exactly 1 contact exists, they receive CALL and SMS so the map link is delivered.
        val remaining = if (sorted.size > 1) sorted.drop(1) else sorted
        return Pair(rank1, remaining)
    }

    /**
     * Constructs the standardized AIZA emergency alert message.
     */
    fun buildEmergencySmsText(
        userName: String,
        triggerSource: String,
        latitude: Double,
        longitude: Double,
        isLastKnownLocation: Boolean = false,
        timestamp: Long = System.currentTimeMillis(),
        voiceClipUrl: String? = null
    ): String {
        val resolvedName = userName.ifBlank { "AIZA User" }
        val timeString = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val sb = StringBuilder()
        sb.append("AIZA SOS ALERT\n\n")
        sb.append("User: ").append(resolvedName).append("\n")
        sb.append("Trigger: ").append(triggerSource.uppercase(Locale.US)).append("\n")

        if (latitude != 0.0 || longitude != 0.0) {
            val latStr = String.format(Locale.US, "%.5f", latitude)
            val lngStr = String.format(Locale.US, "%.5f", longitude)
            val locLabel = if (isLastKnownLocation) "$latStr, $lngStr (last known location)" else "$latStr, $lngStr"
            sb.append("Location: ").append(locLabel).append("\n")
            sb.append("Google Maps: https://maps.google.com/?q=").append(latStr).append(",").append(lngStr).append("\n")
        } else {
            sb.append("Location: Unavailable (Searching for satellite lock)\n")
        }

        sb.append("Time: ").append(timeString)

        if (!voiceClipUrl.isNullOrBlank()) {
            sb.append("\nDistress Clip: ").append(voiceClipUrl)
        }

        return sb.toString()
    }

    /**
     * Dispatches emergency phone call to Rank 1 contact and emergency SMS to all remaining contacts.
     */
    fun dispatchEmergencyCommunication(
        context: Context,
        userName: String,
        triggerSource: String,
        latitude: Double,
        longitude: Double,
        contacts: List<ContactEntity>,
        countryCode: String = "+91",
        isLastKnownLocation: Boolean = false,
        voiceClipUrl: String? = null,
        onLog: ((String) -> Unit)? = null
    ): DispatchResult {
        val now = System.currentTimeMillis()
        val lastTime = lastDispatchTimestamp.get()

        if (now - lastTime < DEDUPLICATION_WINDOW_MS) {
            val msg = "Duplicate SOS communication dispatch suppressed within ${DEDUPLICATION_WINDOW_MS}ms window."
            Log.w(TAG, msg)
            onLog?.invoke(msg)
            return DispatchResult(null, emptyList(), "", false, "Deduplication window active")
        }
        lastDispatchTimestamp.set(now)

        val (rank1Contact, smsContacts) = partitionContactsByRank(contacts)

        if (rank1Contact == null) {
            val msg = "No emergency contacts configured for SOS dispatch."
            Log.e(TAG, msg)
            onLog?.invoke(msg)
            return DispatchResult(null, emptyList(), "", false, msg)
        }

        val smsMessage = buildEmergencySmsText(
            userName = userName,
            triggerSource = triggerSource,
            latitude = latitude,
            longitude = longitude,
            isLastKnownLocation = isLastKnownLocation,
            timestamp = now,
            voiceClipUrl = voiceClipUrl
        )

        // 1. INITIATE EMERGENCY CALL TO RANK 1 CONTACT
        val callPhone = PhoneUtils.normalize(rank1Contact.phone, countryCode)
        val callPermGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        try {
            val callIntent = if (callPermGranted) {
                Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$callPhone")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$callPhone")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(callIntent)
            val callLog = "CALL INITIATED -> Rank 1: ${rank1Contact.name} ($callPhone)"
            Log.i(TAG, callLog)
            onLog?.invoke(callLog)
        } catch (e: Exception) {
            val err = "Failed to launch call intent to ${rank1Contact.name}: ${e.message}"
            Log.e(TAG, err, e)
            onLog?.invoke(err)
        }

        // 2. DISPATCH EMERGENCY SMS TO ALL REMAINING CONTACTS (RANK > 1 OR RANK 1 IF ONLY CONTACT)
        Log.d(TAG, "SMS dispatch started")
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "permission=$hasSmsPermission")

        if (!voiceClipUrl.isNullOrBlank()) {
            Log.d(TAG, "SMS includes voice URL")
        }

        if (!hasSmsPermission) {
            Log.e(TAG, "exception=SecurityException: SEND_SMS permission is not granted")
            onLog?.invoke("SEND_SMS permission not granted. Cannot deliver SMS to guardians.")
            return DispatchResult(rank1Contact, smsContacts, smsMessage, false, "Permission SEND_SMS missing")
        }

        val smsManager = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: @Suppress("DEPRECATION") SmsManager.getDefault()

        smsContacts.forEach { contact ->
            val normalizedPhone = PhoneUtils.normalize(contact.phone, countryCode)
            Log.d(TAG, "recipient=$normalizedPhone")

            try {
                val sentAction = "com.example.aegis.SMS_SENT_${contact.id}_$now"
                val deliveryAction = "com.example.aegis.SMS_DELIVERED_${contact.id}_$now"

                val sentReceiver = object : BroadcastReceiver() {
                    override fun onReceive(recvContext: Context?, intent: Intent?) {
                        try {
                            context.applicationContext.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        when (resultCode) {
                            Activity.RESULT_OK -> {
                                Log.i(TAG, "sent result=SUCCESS")
                                onLog?.invoke("SMS sent successfully to ${contact.name} ($normalizedPhone)")
                            }
                            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                                Log.e(TAG, "sent result=ERROR_GENERIC_FAILURE")
                            }
                            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                                Log.e(TAG, "sent result=ERROR_NO_SERVICE")
                            }
                            SmsManager.RESULT_ERROR_NULL_PDU -> {
                                Log.e(TAG, "sent result=ERROR_NULL_PDU")
                            }
                            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                                Log.e(TAG, "sent result=ERROR_RADIO_OFF")
                            }
                            else -> {
                                Log.w(TAG, "sent result=CODE_$resultCode")
                            }
                        }
                    }
                }

                val deliveryReceiver = object : BroadcastReceiver() {
                    override fun onReceive(recvContext: Context?, intent: Intent?) {
                        try {
                            context.applicationContext.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        when (resultCode) {
                            Activity.RESULT_OK -> {
                                Log.i(TAG, "delivery result=SUCCESS")
                                onLog?.invoke("SMS DELIVERED -> ${contact.name} at $normalizedPhone")
                            }
                            else -> {
                                Log.w(TAG, "delivery result=FAILED (code=$resultCode)")
                            }
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.applicationContext.registerReceiver(
                        sentReceiver,
                        IntentFilter(sentAction),
                        Context.RECEIVER_EXPORTED
                    )
                    context.applicationContext.registerReceiver(
                        deliveryReceiver,
                        IntentFilter(deliveryAction),
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    context.applicationContext.registerReceiver(sentReceiver, IntentFilter(sentAction))
                    context.applicationContext.registerReceiver(deliveryReceiver, IntentFilter(deliveryAction))
                }

                val sentFlags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                val sentIntent = PendingIntent.getBroadcast(
                    context.applicationContext,
                    (contact.id * 1000 + 1),
                    Intent(sentAction).setPackage(context.packageName),
                    sentFlags
                )
                val deliveryIntent = PendingIntent.getBroadcast(
                    context.applicationContext,
                    (contact.id * 1000 + 2),
                    Intent(deliveryAction).setPackage(context.packageName),
                    sentFlags
                )

                val parts = smsManager.divideMessage(smsMessage)
                Log.d(TAG, "SmsManager invoked=true")

                if (parts.size > 1) {
                    val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                        repeat(parts.size) { add(sentIntent) }
                    }
                    val deliveryIntents = ArrayList<PendingIntent>(parts.size).apply {
                        repeat(parts.size) { add(deliveryIntent) }
                    }
                    smsManager.sendMultipartTextMessage(normalizedPhone, null, parts, sentIntents, deliveryIntents)
                } else {
                    smsManager.sendTextMessage(normalizedPhone, null, smsMessage, sentIntent, deliveryIntent)
                }

                val log = "SMS DISPATCHED -> ${contact.name} (Rank ${contact.priority}) at $normalizedPhone"
                Log.i(TAG, log)
                onLog?.invoke(log)
            } catch (e: SecurityException) {
                Log.e(TAG, "exception=SecurityException: ${e.message}", e)
                onLog?.invoke("Security error dispatching SMS: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "exception=${e.javaClass.simpleName}: ${e.message}", e)
                onLog?.invoke("Failed to dispatch SMS to ${contact.name}: ${e.message}")
            }
        }

        return DispatchResult(
            callContact = rank1Contact,
            smsContacts = smsContacts,
            messageText = smsMessage,
            dispatched = true
        )
    }
}
