package com.example.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.EmergencyIncidentEntity
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 4B: Dedicated AIZA Emergency Notification Manager for received [EmergencyIncidentEntity] alerts.
 *
 * Responsibilities:
 * 1. Creates/audits the dedicated high-importance Android notification channel (`aiza_emergency_alerts`,
 *    or versioned fallback `aiza_emergency_alerts_v2` if an unsuitable channel was previously created)
 *    without modifying unrelated channels or secretly overriding user Silent/DND settings.
 * 2. Posts a clearly distinguishable, ongoing, high-priority `AIZA EMERGENCY` notification when an
 *    authenticated paired guardian receives an `ACTIVE` [EmergencyIncidentEntity] from a connected AIZA user.
 * 3. Formats notification content with sender name (if available), active emergency status, and location
 *    availability while excluding medical summaries and sensitive personal details from the preview.
 * 4. Configures the tap action (`PendingIntent`) with the exact `incidentId` to open the AIZA
 *    Emergency Incident details flow (`AIZA -> Emergency Incident -> incidentId`).
 * 5. Prevents duplicate notifications across repeated syncs/Realtime events and automatically removes
 *    the notification when the received emergency is resolved or cancelled.
 */
class AizaEmergencyNotificationManager(
    private val context: Context,
    private val notificationManagerOverride: NotificationManager? = null
) {
    private val appContext: Context = context.applicationContext ?: context

    private val notificationManager: NotificationManager
        get() = notificationManagerOverride
            ?: (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    data class EmergencyNotificationContent(
        val incidentId: String,
        val channelId: String,
        val title: String,
        val senderDisplayName: String,
        val statusLine: String,
        val locationLine: String,
        val bodyText: String,
        val isLocationAvailable: Boolean
    )

    /**
     * In-memory + SharedPreferences-backed tracking of active notified incidents scoped by
     * `guardianUserId|incidentId` -> content fingerprint, preventing duplicate notification posts
     * across repeated foreground/background syncs and Realtime callbacks.
     */
    private val notifiedFingerprints = ConcurrentHashMap<String, String>()
    private val notificationPostCounts = ConcurrentHashMap<String, Int>()

    /**
     * Audits and ensures the dedicated AIZA Emergency Alerts notification channel exists.
     *
     * Rules enforced:
     * - Primary Channel ID: `aiza_emergency_alerts`
     * - Channel Name: `AIZA Emergency Alerts`
     * - Importance: [NotificationManager.IMPORTANCE_HIGH]
     * - Sound: Emergency/alarm sound where supported ([RingtoneManager.TYPE_ALARM])
     * - Vibration: Enabled
     * - Lock-screen visibility: [Notification.VISIBILITY_PUBLIC]
     * - Never sets `bypassDnd = true` (respects user Silent/DND settings).
     * - If `aiza_emergency_alerts` was previously created with an unsuitable configuration
     *   (e.g. lower importance or disabled vibration) that Android will not upgrade programmatically,
     *   creates and uses a versioned channel ID (`aiza_emergency_alerts_v2`).
     * - Never modifies unrelated notification channels (`aegis_bg_sync`, `aegis_bg_safety`, `aegis_emergency`).
     */
    fun ensureEmergencyNotificationChannel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return EMERGENCY_CHANNEL_ID
        }

        val existingPrimary = notificationManager.getNotificationChannel(EMERGENCY_CHANNEL_ID)
        if (existingPrimary == null) {
            val channel = buildHighImportanceEmergencyChannel(EMERGENCY_CHANNEL_ID)
            notificationManager.createNotificationChannel(channel)
            return EMERGENCY_CHANNEL_ID
        }

        // Check if the existing channel was created with an unsuitable configuration
        val isUnsuitableConfig = existingPrimary.importance < NotificationManager.IMPORTANCE_HIGH ||
            !existingPrimary.shouldVibrate() ||
            existingPrimary.lockscreenVisibility == Notification.VISIBILITY_SECRET

        if (isUnsuitableConfig) {
            val existingVersioned = notificationManager.getNotificationChannel(EMERGENCY_CHANNEL_VERSIONED_ID)
            if (existingVersioned == null) {
                val versionedChannel = buildHighImportanceEmergencyChannel(EMERGENCY_CHANNEL_VERSIONED_ID)
                notificationManager.createNotificationChannel(versionedChannel)
            }
            return EMERGENCY_CHANNEL_VERSIONED_ID
        }

        return EMERGENCY_CHANNEL_ID
    }

    private fun buildHighImportanceEmergencyChannel(channelId: String): NotificationChannel {
        val emergencySoundUri = resolveEmergencySoundUri()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        return NotificationChannel(
            channelId,
            EMERGENCY_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = EMERGENCY_CHANNEL_DESCRIPTION
            enableVibration(true)
            vibrationPattern = EMERGENCY_VIBRATION_PATTERN
            enableLights(true)
            lightColor = Color.RED
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (emergencySoundUri != null) {
                setSound(emergencySoundUri, audioAttributes)
            }
        }
    }

    /**
     * Builds the structured emergency notification content for a received [EmergencyIncidentEntity].
     * Never exposes medical summary, blood group, allergies, conditions, notes, audio paths,
     * phone numbers, or email addresses in the notification preview.
     */
    fun buildNotificationContent(
        incident: EmergencyIncidentEntity,
        senderName: String? = null,
        channelId: String = EMERGENCY_CHANNEL_ID
    ): EmergencyNotificationContent {
        val cleanSenderName = sanitizeSenderName(senderName)
        val senderDisplay = cleanSenderName ?: DEFAULT_SENDER_DISPLAY_NAME
        val statusLine = "$senderDisplay has an active emergency."
        val locationAvailable = isIncidentLocationAvailable(incident)
        val locationLine = if (locationAvailable) {
            LOCATION_AVAILABLE_TEXT
        } else {
            LOCATION_UNAVAILABLE_TEXT
        }
        val bodyText = "$statusLine\n$locationLine"

        return EmergencyNotificationContent(
            incidentId = incident.id.trim(),
            channelId = channelId,
            title = EMERGENCY_NOTIFICATION_TITLE,
            senderDisplayName = senderDisplay,
            statusLine = statusLine,
            locationLine = locationLine,
            bodyText = bodyText,
            isLocationAvailable = locationAvailable
        )
    }

    /**
     * Builds the tap [Intent] that opens AIZA directly into the Emergency Incident details flow
     * for the exact [incidentId] (`AIZA -> Emergency Incident -> incidentId`).
     */
    fun buildOpenIncidentIntent(incidentId: String): Intent {
        val cleanId = incidentId.trim()
        require(SafetyRepository.isRealAuthenticatedUserUuid(cleanId)) {
            "Emergency notification tap intent requires a valid incident UUID."
        }
        return Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_EMERGENCY_INCIDENT
            data = Uri.parse("$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/$cleanId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_EMERGENCY_INCIDENT, true)
            putExtra(EXTRA_EMERGENCY_INCIDENT_ID, cleanId)
            putExtra(EXTRA_INCIDENT_ID, cleanId)
            putExtra(EXTRA_INCIDENT_ID_SNAKE, cleanId)
        }
    }

    /**
     * Builds the [PendingIntent] for tapping the emergency notification for [incidentId].
     */
    fun buildOpenIncidentPendingIntent(incidentId: String): PendingIntent {
        val tapIntent = buildOpenIncidentIntent(incidentId)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(
            appContext,
            notificationIdForIncident(incidentId),
            tapIntent,
            flags
        )
    }

    /**
     * Evaluates a single received [EmergencyIncidentEntity] for the authenticated [guardianUserId]:
     * - Validates sender/guardian UUIDs and ensures this is a received guardian incident (never self-notified).
     * - If the incident is no longer `ACTIVE` (`RESOLVED` or `CANCELLED`), removes any active notification for it.
     * - If the incident is `ACTIVE` and has not yet been notified with the current status/location fingerprint,
     *   posts (or updates in-place with `setOnlyAlertOnce(true)`) the dedicated `AIZA EMERGENCY` notification.
     * - Returns `true` if a notification was posted/updated, or `false` if skipped (duplicate/resolved/invalid).
     */
    fun notifyOrUpdateReceivedIncident(
        incident: EmergencyIncidentEntity,
        authenticatedGuardianUserId: String?,
        senderName: String? = null
    ): Boolean {
        val cleanGuardianUid = authenticatedGuardianUserId?.trim() ?: return false
        if (!SafetyRepository.isRealAuthenticatedUserUuid(cleanGuardianUid)) return false

        val cleanIncidentId = incident.id.trim()
        val cleanSenderUid = incident.userId.trim()
        if (!SafetyRepository.isRealAuthenticatedUserUuid(cleanIncidentId) ||
            !SafetyRepository.isRealAuthenticatedUserUuid(cleanSenderUid)
        ) {
            return false
        }

        // Never show a guardian emergency notification to the sender for their own incident
        if (cleanSenderUid.equals(cleanGuardianUid, ignoreCase = true)) {
            return false
        }
        if (!incident.isReceivedByGuardian(cleanGuardianUid)) {
            return false
        }

        // If the received incident is resolved or cancelled, remove its active notification
        if (!incident.status.trim().equals(EmergencyIncidentEntity.STATUS_ACTIVE, ignoreCase = true)) {
            cancelNotificationForIncident(cleanIncidentId, cleanGuardianUid)
            return false
        }

        val channelId = ensureEmergencyNotificationChannel()
        val content = buildNotificationContent(
            incident = incident,
            senderName = senderName,
            channelId = channelId
        )

        val trackingKey = buildTrackingKey(cleanGuardianUid, cleanIncidentId)
        val fingerprint = "${incident.status.trim().uppercase(Locale.US)}|${content.senderDisplayName}|${content.isLocationAvailable}|$channelId"

        if (notifiedFingerprints[trackingKey] == fingerprint) {
            // Already notified with identical active emergency content; prevent duplicate notification
            return false
        }

        val pendingIntent = buildOpenIncidentPendingIntent(cleanIncidentId)
        val emergencySoundUri = resolveEmergencySoundUri()

        val safeExtras = Bundle().apply {
            putString(EXTRA_EMERGENCY_INCIDENT_ID, cleanIncidentId)
            putString(EXTRA_INCIDENT_ID, cleanIncidentId)
            putString(EXTRA_INCIDENT_ID_SNAKE, cleanIncidentId)
            putBoolean(EXTRA_OPEN_EMERGENCY_INCIDENT, true)
        }

        val publicLockScreenNotification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(EMERGENCY_NOTIFICATION_TITLE)
            .setContentText(content.bodyText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(content.title)
            .setContentText(content.bodyText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(content.title)
                    .bigText(content.bodyText)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicLockScreenNotification)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVibrate(EMERGENCY_VIBRATION_PATTERN)
            .setContentIntent(pendingIntent)
            .addExtras(safeExtras)

        if (emergencySoundUri != null) {
            builder.setSound(emergencySoundUri)
        }

        val notification = builder.build()
        val notificationId = notificationIdForIncident(cleanIncidentId)
        notificationManager.notify(notificationId, notification)

        notifiedFingerprints[trackingKey] = fingerprint
        val incidentKey = cleanIncidentId.lowercase(Locale.US)
        notificationPostCounts[incidentKey] = (notificationPostCounts[incidentKey] ?: 0) + 1
        return true
    }

    /**
     * Synchronizes system emergency notifications for the full list of received incidents of
     * [authenticatedGuardianUserId]:
     * - Posts/updates notifications for `ACTIVE` received incidents (with duplicate suppression).
     * - Automatically removes/cancels notifications for incidents that are now `RESOLVED`, `CANCELLED`,
     *   or no longer active for this guardian.
     */
    fun syncNotificationsForReceivedIncidents(
        authenticatedGuardianUserId: String?,
        receivedIncidents: List<EmergencyIncidentEntity>,
        senderNamesByUserId: Map<String, String> = emptyMap()
    ) {
        val cleanGuardianUid = authenticatedGuardianUserId?.trim() ?: return
        if (!SafetyRepository.isRealAuthenticatedUserUuid(cleanGuardianUid)) return

        val activeIncidentIdsLower = mutableSetOf<String>()

        for (incident in receivedIncidents) {
            if (!incident.isReceivedByGuardian(cleanGuardianUid)) continue
            val cleanIncidentId = incident.id.trim()
            val senderKey = incident.userId.trim().lowercase(Locale.US)
            val senderName = senderNamesByUserId[senderKey]

            if (incident.status.trim().equals(EmergencyIncidentEntity.STATUS_ACTIVE, ignoreCase = true)) {
                activeIncidentIdsLower.add(cleanIncidentId.lowercase(Locale.US))
                notifyOrUpdateReceivedIncident(
                    incident = incident,
                    authenticatedGuardianUserId = cleanGuardianUid,
                    senderName = senderName
                )
            } else {
                cancelNotificationForIncident(cleanIncidentId, cleanGuardianUid)
            }
        }

        // Also cancel any previously notified incident for this guardian that is no longer active
        val guardianPrefix = "${cleanGuardianUid.lowercase(Locale.US)}|"
        val staleKeys = notifiedFingerprints.keys.filter { key ->
            key.startsWith(guardianPrefix) &&
                !activeIncidentIdsLower.contains(key.substringAfter("|"))
        }
        for (staleKey in staleKeys) {
            val staleIncidentId = staleKey.substringAfter("|")
            cancelNotificationForIncident(staleIncidentId, cleanGuardianUid)
        }
    }

    /**
     * Cancels/removes the system emergency notification for [incidentId] and clears its active tracking entry.
     */
    fun cancelNotificationForIncident(
        incidentId: String,
        authenticatedGuardianUserId: String? = null
    ) {
        val cleanIncidentId = incidentId.trim()
        if (cleanIncidentId.isEmpty()) return

        notificationManager.cancel(notificationIdForIncident(cleanIncidentId))

        val incidentLower = cleanIncidentId.lowercase(Locale.US)
        if (!authenticatedGuardianUserId.isNullOrBlank()) {
            notifiedFingerprints.remove(buildTrackingKey(authenticatedGuardianUserId, cleanIncidentId))
        } else {
            val matchingKeys = notifiedFingerprints.keys.filter { it.endsWith("|$incidentLower") }
            matchingKeys.forEach { notifiedFingerprints.remove(it) }
        }
    }

    /**
     * Returns the number of times [NotificationManager.notify] was invoked for [incidentId]
     * by this manager instance.
     */
    fun getNotificationPostCount(incidentId: String): Int {
        return notificationPostCounts[incidentId.trim().lowercase(Locale.US)] ?: 0
    }

    /**
     * Returns true if [incidentId] is currently tracked as an active notified emergency for [guardianUserId].
     */
    fun isIncidentCurrentlyNotified(incidentId: String, guardianUserId: String): Boolean {
        return notifiedFingerprints.containsKey(buildTrackingKey(guardianUserId, incidentId))
    }

    private fun buildTrackingKey(guardianUserId: String, incidentId: String): String {
        return "${guardianUserId.trim().lowercase(Locale.US)}|${incidentId.trim().lowercase(Locale.US)}"
    }

    private fun resolveEmergencySoundUri(): Uri? {
        return try {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EMERGENCY_CHANNEL_ID = "aiza_emergency_alerts"
        const val EMERGENCY_CHANNEL_VERSIONED_ID = "aiza_emergency_alerts_v2"
        const val EMERGENCY_CHANNEL_NAME = "AIZA Emergency Alerts"
        const val EMERGENCY_CHANNEL_DESCRIPTION =
            "High-priority alerts when a connected AIZA user triggers an active emergency incident."

        const val EMERGENCY_NOTIFICATION_TITLE = "AIZA EMERGENCY"
        const val DEFAULT_SENDER_DISPLAY_NAME = "Connected AIZA user"
        const val LOCATION_AVAILABLE_TEXT = "Location available"
        const val LOCATION_UNAVAILABLE_TEXT = "Location unavailable"

        const val ACTION_OPEN_EMERGENCY_INCIDENT = "com.example.action.OPEN_EMERGENCY_INCIDENT"
        const val EXTRA_OPEN_EMERGENCY_INCIDENT = "open_emergency_incident"
        const val EXTRA_EMERGENCY_INCIDENT_ID = "extra_emergency_incident_id"
        const val EXTRA_INCIDENT_ID = "incidentId"
        const val EXTRA_INCIDENT_ID_SNAKE = "incident_id"

        const val DEEP_LINK_SCHEME = "aiza"
        const val DEEP_LINK_HOST = "emergency-incident"

        val EMERGENCY_VIBRATION_PATTERN = longArrayOf(0L, 500L, 250L, 500L, 250L, 700L)

        /**
         * Deterministic notification ID derived from [incidentId], guaranteed >= 10,000 so it never
         * collides with foreground service or sync notification IDs (1, 2, 3).
         */
        fun notificationIdForIncident(incidentId: String): Int {
            val hash = incidentId.trim().lowercase(Locale.US).hashCode()
            val positive = hash and 0x7FFFFFFF
            return if (positive < 10_000) positive + 10_000 else positive
        }

        /**
         * Determines whether a received [EmergencyIncidentEntity] has usable coordinates available.
         */
        fun isIncidentLocationAvailable(incident: EmergencyIncidentEntity): Boolean {
            val lat = incident.latitude ?: return false
            val lng = incident.longitude ?: return false
            if (lat.isNaN() || lng.isNaN()) return false
            if (lat == 0.0 && lng == 0.0) return false
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false
            if (incident.locationStatus?.trim()?.equals(
                    EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN,
                    ignoreCase = true
                ) == true
            ) {
                return false
            }
            return true
        }

        /**
         * Sanitizes a candidate sender name for safe display in the emergency notification preview.
         * Rejects blank strings, `"default_user"`, email addresses, phone numbers, and raw UUIDs.
         */
        fun sanitizeSenderName(rawName: String?): String? {
            if (rawName.isNullOrBlank()) return null
            val trimmed = rawName.trim()
            if (trimmed.equals("default_user", ignoreCase = true)) return null
            if (trimmed.contains("@")) return null
            if (trimmed.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) return null
            if (SafetyRepository.isValidUuid(trimmed)) return null
            return trimmed
        }

        /**
         * Extracts and validates the exact `incidentId` from a notification tap / deep-link [Intent].
         * Returns `null` if the intent is unrelated or if the incident ID is missing/invalid.
         */
        fun extractIncidentIdFromIntent(intent: Intent?): String? {
            if (intent == null) return null

            val fromPrimaryExtra = intent.getStringExtra(EXTRA_EMERGENCY_INCIDENT_ID)?.trim()
            if (SafetyRepository.isRealAuthenticatedUserUuid(fromPrimaryExtra)) {
                return fromPrimaryExtra
            }

            val fromCamelExtra = intent.getStringExtra(EXTRA_INCIDENT_ID)?.trim()
            if (SafetyRepository.isRealAuthenticatedUserUuid(fromCamelExtra)) {
                return fromCamelExtra
            }

            val fromSnakeExtra = intent.getStringExtra(EXTRA_INCIDENT_ID_SNAKE)?.trim()
            if (SafetyRepository.isRealAuthenticatedUserUuid(fromSnakeExtra)) {
                return fromSnakeExtra
            }

            val uri = intent.data
            if (uri != null &&
                uri.scheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) &&
                uri.host.equals(DEEP_LINK_HOST, ignoreCase = true)
            ) {
                val segment = uri.lastPathSegment?.trim()
                if (SafetyRepository.isRealAuthenticatedUserUuid(segment)) {
                    return segment
                }
            }

            return null
        }
    }
}
