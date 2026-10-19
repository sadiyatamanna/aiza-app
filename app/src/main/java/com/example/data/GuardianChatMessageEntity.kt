package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.supabase.GuardianChatMessageDto
import java.util.UUID

/**
 * Local Room entity for offline-first 1-to-1 Guardian Chat messages.
 *
 * Architectural Note on Guardian Identity:
 * - [guardianId] is always the canonical UUID string from [ContactEntity.contactId]
 *   (which matches `public.emergency_contacts.id` in Supabase), never the local Room integer [ContactEntity.id].
 * - [recipientId] for outgoing user-to-guardian messages is the guardian's paired Supabase Auth
 *   `auth.users` UUID ([ContactEntity.pairedGuardianUserId]) when a guardian account is connected,
 *   or `""` when unpaired. It never uses the guardian's phone number and never uses [ContactEntity.contactId]
 *   when [ContactEntity.guardianUserId] exists.
 */
@Entity(
    tableName = "guardian_chat_messages",
    indices = [
        Index(value = ["userId", "guardianId", "createdAt"]),
        Index(value = ["conversationId", "createdAt"]),
        Index(value = ["isSynced"])
    ]
)
data class GuardianChatMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val userId: String,
    val guardianId: String,
    val senderId: String,
    val senderName: String,
    val recipientId: String,
    val content: String,
    val status: String = STATUS_PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val messageType: String = TYPE_TEXT,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationTimestamp: Long? = null,
    val locationStatus: String? = null
) {
    /**
     * True when this chat message is a Phase 4A LOCATION message with valid coordinates.
     */
    val isLocationMessage: Boolean
        get() = messageType.equals(TYPE_LOCATION, ignoreCase = true) &&
            hasValidLocationCoordinates(latitude, longitude)

    fun formatCoordinateSummary(): String {
        val lat = latitude ?: return ""
        val lng = longitude ?: return ""
        return String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng)
    }

    fun formatLocationFreshnessLabel(): String {
        return when (locationStatus?.trim()?.uppercase(java.util.Locale.US)) {
            LOCATION_STATUS_LIVE -> "Live GPS"
            LOCATION_STATUS_RECENT -> "Recent GPS"
            LOCATION_STATUS_STALE -> "Stale GPS"
            LOCATION_STATUS_LAST_KNOWN -> "Last Known Location"
            "UNKNOWN" -> "Location"
            else -> "Location"
        }
    }

    fun toDto(statusOverride: String = status): GuardianChatMessageDto {
        val normalizedType = messageType.trim().uppercase(java.util.Locale.US).ifBlank { TYPE_TEXT }
        return GuardianChatMessageDto(
            id = id,
            conversationId = conversationId,
            userId = userId,
            guardianId = guardianId,
            senderId = senderId,
            senderName = senderName,
            recipientId = recipientId,
            content = content,
            status = statusOverride,
            createdAt = createdAt,
            messageType = normalizedType,
            latitude = latitude,
            longitude = longitude,
            locationTimestamp = locationTimestamp,
            locationStatus = locationStatus
        )
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"

        const val TYPE_TEXT = "TEXT"
        const val TYPE_LOCATION = "LOCATION"

        const val LOCATION_STATUS_LIVE = "LIVE"
        const val LOCATION_STATUS_RECENT = "RECENT"
        const val LOCATION_STATUS_STALE = "STALE"
        const val LOCATION_STATUS_LAST_KNOWN = "LAST_KNOWN"

        /**
         * Validates that [latitude] and [longitude] are real, finite, in-bounds GPS coordinates
         * and not `(0.0, 0.0)`.
         */
        fun hasValidLocationCoordinates(latitude: Double?, longitude: Double?): Boolean {
            if (latitude == null || longitude == null) return false
            if (latitude.isNaN() || longitude.isNaN() || latitude.isInfinite() || longitude.isInfinite()) {
                return false
            }
            if (latitude == 0.0 && longitude == 0.0) return false
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
            return true
        }

        /**
         * Builds a deterministic conversation ID from the authenticated user's UUID
         * and the guardian's canonical UUID ([ContactEntity.contactId]).
         */
        fun buildConversationId(userId: String, guardianId: String): String {
            return "conv_${userId.trim()}_${guardianId.trim()}"
        }
    }
}

