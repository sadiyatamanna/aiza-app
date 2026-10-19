package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.supabase.EmergencyIncidentDto
import java.util.UUID

/**
 * Persistent Room entity representing one complete emergency incident event in AIZA.
 *
 * Identity Rule:
 * - [userId] MUST be the authenticated Supabase user UUID (`auth.users.id`).
 * - NEVER use an email address, phone number, `"default_user"`, anonymous placeholder UUID,
 *   or generated fallback identity.
 */
@Entity(
    tableName = "emergency_incidents",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["startedAt"]),
        Index(value = ["deliveryStatus"]),
        Index(value = ["recipientGuardianUserId"])
    ]
)
data class EmergencyIncidentEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val triggerSource: String = TRIGGER_MANUAL,
    val status: String = STATUS_ACTIVE,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationTimestamp: Long? = null,
    val locationStatus: String? = LOCATION_STATUS_UNKNOWN,
    val batteryPercent: Int? = null,
    val networkStatus: String? = null,
    val medicalSharingEnabled: Boolean = false,
    val medicalSummary: String? = null,
    val audioRecordingStatus: String = AUDIO_STATUS_NOT_STARTED,
    val audioDurationMs: Long? = null,
    val audioStoragePath: String? = null,
    val deliveryStatus: String = DELIVERY_STATUS_PENDING,
    val lastDeliveryError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val recipientGuardianUserId: String? = null
) {
    /**
     * Original incident owner's authenticated Supabase user UUID (`auth.users.id`).
     * Never overwritten with a guardian's user ID.
     */
    val senderUserId: String
        get() = userId

    /**
     * Paired guardian account's authenticated Supabase user UUID when this incident was received
     * for a guardian account, or null for sender-owned incidents.
     */
    val guardianUserId: String?
        get() = recipientGuardianUserId

    /**
     * True when this local incident record represents an incident received by a paired guardian account.
     */
    val isReceivedForGuardian: Boolean
        get() = !recipientGuardianUserId.isNullOrBlank() &&
            !recipientGuardianUserId.trim().equals(userId.trim(), ignoreCase = true)

    fun isReceivedByGuardian(guardianUid: String?): Boolean {
        if (guardianUid.isNullOrBlank()) return false
        return isReceivedForGuardian &&
            recipientGuardianUserId?.trim()?.equals(guardianUid.trim(), ignoreCase = true) == true
    }

    fun isOwnedBy(ownerUid: String?): Boolean {
        if (ownerUid.isNullOrBlank()) return false
        return userId.trim().equals(ownerUid.trim(), ignoreCase = true)
    }

    fun toDto(): EmergencyIncidentDto {
        return EmergencyIncidentDto(
            id = id,
            userId = userId,
            triggerSource = triggerSource,
            status = status,
            startedAt = startedAt,
            endedAt = endedAt,
            latitude = latitude,
            longitude = longitude,
            locationTimestamp = locationTimestamp,
            locationStatus = locationStatus,
            batteryPercent = batteryPercent,
            networkStatus = networkStatus,
            medicalSharingEnabled = medicalSharingEnabled,
            medicalSummary = medicalSummary,
            audioRecordingStatus = audioRecordingStatus,
            audioDurationMs = audioDurationMs,
            audioStoragePath = audioStoragePath,
            deliveryStatus = deliveryStatus,
            lastDeliveryError = lastDeliveryError,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    companion object {
        // Trigger sources
        const val TRIGGER_MANUAL = "MANUAL"
        const val TRIGGER_SHAKE = "SHAKE"
        const val TRIGGER_VOICE = "VOICE"
        const val TRIGGER_BLUETOOTH = "BLUETOOTH"

        // Incident statuses
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_RESOLVED = "RESOLVED"
        const val STATUS_CANCELLED = "CANCELLED"

        // Location statuses
        const val LOCATION_STATUS_LIVE = "LIVE"
        const val LOCATION_STATUS_RECENT = "RECENT"
        const val LOCATION_STATUS_STALE = "STALE"
        const val LOCATION_STATUS_UNKNOWN = "UNKNOWN"

        // Audio recording statuses
        const val AUDIO_STATUS_NOT_STARTED = "NOT_STARTED"
        const val AUDIO_STATUS_RECORDING = "RECORDING"
        const val AUDIO_STATUS_COMPLETED = "COMPLETED"
        const val AUDIO_STATUS_FAILED = "FAILED"
        const val AUDIO_STATUS_NOT_AVAILABLE = "NOT_AVAILABLE"

        // Delivery statuses
        const val DELIVERY_STATUS_PENDING = "PENDING"
        const val DELIVERY_STATUS_SENT = "SENT"
        const val DELIVERY_STATUS_DELIVERED = "DELIVERED"
        const val DELIVERY_STATUS_FAILED = "FAILED"
    }
}
