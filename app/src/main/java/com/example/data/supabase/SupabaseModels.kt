package com.example.data.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Profile DTO corresponding to the Supabase `profiles` table.
 * Primary key `id` corresponds directly to `auth.users.id`.
 */
@Serializable
data class ProfileDto(
    val id: String,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Emergency contact DTO corresponding to the Supabase `emergency_contacts` table.
 * Bound to the authenticated user's `user_id` and optionally paired with the guardian's
 * Supabase Auth user UUID (`guardian_user_id`).
 */
@Serializable
data class EmergencyContactDto(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("guardian_user_id") val guardianUserId: String? = null,
    val name: String,
    val phone: String,
    val relationship: String,
    val priority: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * SOS alert DTO corresponding to the Supabase `sos_alerts` table.
 * Contains PQC encrypted payload, Dilithium lattice signature, and real GPS coordinates.
 */
@Serializable
data class SosAlertDto(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_available") val locationAvailable: Boolean = true,
    @SerialName("triggered_at") val triggeredAt: Long = System.currentTimeMillis(),
    val status: String = "ACTIVE",
    @SerialName("trigger_type") val triggerType: String = "MANUAL",
    @SerialName("encrypted_payload") val encryptedPayload: String = "",
    val signature: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("voice_clip_url") val voiceClipUrl: String? = null
)

/**
 * Continuous user location sharing DTO corresponding to Supabase `user_locations` table.
 * Supports continuous live location broadcasting to authorized emergency contacts.
 */
@Serializable
data class UserLocationDto(
    @SerialName("user_id") val userId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    @SerialName("is_sharing_enabled") val isSharingEnabled: Boolean = true,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Guardian chat message DTO corresponding to the existing Supabase `public.guardian_chat_messages` table.
 * Matches exact PostgreSQL column names and types:
 * - id: TEXT PRIMARY KEY
 * - conversation_id: TEXT NOT NULL
 * - user_id: UUID NOT NULL
 * - guardian_id: TEXT NOT NULL
 * - sender_id: TEXT NOT NULL
 * - sender_name: TEXT NOT NULL
 * - recipient_id: TEXT NOT NULL
 * - content: TEXT NOT NULL
 * - status: TEXT NOT NULL
 * - created_at: BIGINT NOT NULL
 */
@Serializable
data class GuardianChatMessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("guardian_id") val guardianId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_name") val senderName: String,
    @SerialName("recipient_id") val recipientId: String,
    val content: String,
    val status: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("message_type") val messageType: String = com.example.data.GuardianChatMessageEntity.TYPE_TEXT,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_timestamp") val locationTimestamp: Long? = null,
    @SerialName("location_status") val locationStatus: String? = null
) {
    fun toEntity(isSynced: Boolean = true): com.example.data.GuardianChatMessageEntity {
        val normalizedType = when {
            messageType.trim().equals(com.example.data.GuardianChatMessageEntity.TYPE_LOCATION, ignoreCase = true) ->
                com.example.data.GuardianChatMessageEntity.TYPE_LOCATION
            latitude != null && longitude != null && com.example.data.GuardianChatMessageEntity.hasValidLocationCoordinates(latitude, longitude) ->
                com.example.data.GuardianChatMessageEntity.TYPE_LOCATION
            else ->
                messageType.trim().uppercase(java.util.Locale.US).ifBlank { com.example.data.GuardianChatMessageEntity.TYPE_TEXT }
        }
        return com.example.data.GuardianChatMessageEntity(
            id = id,
            conversationId = conversationId,
            userId = userId,
            guardianId = guardianId,
            senderId = senderId,
            senderName = senderName,
            recipientId = recipientId,
            content = content,
            status = status,
            createdAt = createdAt,
            isSynced = isSynced,
            messageType = normalizedType,
            latitude = latitude,
            longitude = longitude,
            locationTimestamp = locationTimestamp,
            locationStatus = locationStatus
        )
    }
}

/**
 * Fallback DTO used if remote Supabase schema cache has not yet reloaded the `message_type` column.
 * Preserves all real GPS coordinates (latitude, longitude, location_timestamp, location_status).
 */
@Serializable
data class GuardianChatMessageLegacyDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("guardian_id") val guardianId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_name") val senderName: String,
    @SerialName("recipient_id") val recipientId: String,
    val content: String,
    val status: String,
    @SerialName("created_at") val createdAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_timestamp") val locationTimestamp: Long? = null,
    @SerialName("location_status") val locationStatus: String? = null
)

/**
 * Guardian Account Pairing / Invitation request DTO corresponding to `public.guardian_pairing_requests`.
 * Never stores passwords or authentication tokens.
 */
@Serializable
data class GuardianPairingRequestDto(
    val id: String,
    @SerialName("requester_user_id") val requesterUserId: String,
    @SerialName("guardian_email") val guardianEmail: String,
    @SerialName("emergency_contact_id") val emergencyContactId: String,
    @SerialName("guardian_user_id") val guardianUserId: String? = null,
    val status: String = com.example.data.GuardianPairingRequestEntity.STATUS_PENDING,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
    @SerialName("requester_name") val requesterName: String = "",
    @SerialName("contact_name") val contactName: String = "",
    val relationship: String = "",
    @SerialName("guardian_phone") val guardianPhone: String = ""
) {
    fun toEntity(): com.example.data.GuardianPairingRequestEntity {
        return com.example.data.GuardianPairingRequestEntity(
            id = id,
            requesterUserId = requesterUserId,
            guardianEmail = guardianEmail,
            emergencyContactId = emergencyContactId,
            guardianUserId = guardianUserId,
            status = status,
            createdAt = createdAt,
            requesterName = requesterName,
            contactName = contactName,
            relationship = relationship,
            guardianPhone = guardianPhone
        )
    }
}

/**
 * Emergency Incident DTO corresponding to `public.emergency_incidents` in Supabase.
 * Maps 1-to-1 with PostgreSQL columns and converts cleanly to/from [com.example.data.EmergencyIncidentEntity].
 */
@Serializable
data class EmergencyIncidentDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("trigger_source") val triggerSource: String,
    val status: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("ended_at") val endedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_timestamp") val locationTimestamp: Long? = null,
    @SerialName("location_status") val locationStatus: String? = null,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
    @SerialName("network_status") val networkStatus: String? = null,
    @SerialName("medical_sharing_enabled") val medicalSharingEnabled: Boolean,
    @SerialName("medical_summary") val medicalSummary: String? = null,
    @SerialName("audio_recording_status") val audioRecordingStatus: String,
    @SerialName("audio_duration_ms") val audioDurationMs: Long? = null,
    @SerialName("audio_storage_path") val audioStoragePath: String? = null,
    @SerialName("delivery_status") val deliveryStatus: String,
    @SerialName("last_delivery_error") val lastDeliveryError: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
) {
    fun toEntity(): com.example.data.EmergencyIncidentEntity {
        return com.example.data.EmergencyIncidentEntity(
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
            updatedAt = updatedAt,
            recipientGuardianUserId = null
        )
    }

    fun toReceivedEntityForGuardian(
        guardianUserId: String,
        guardianDeliveryStatus: String = com.example.data.EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
        guardianDeliveryError: String? = null
    ): com.example.data.EmergencyIncidentEntity {
        return com.example.data.EmergencyIncidentEntity(
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
            deliveryStatus = guardianDeliveryStatus,
            lastDeliveryError = guardianDeliveryError,
            createdAt = createdAt,
            updatedAt = updatedAt,
            recipientGuardianUserId = guardianUserId
        )
    }

    companion object {
        fun fromEntity(entity: com.example.data.EmergencyIncidentEntity): EmergencyIncidentDto {
            return entity.toDto()
        }
    }
}

/**
 * Emergency Incident Recipient DTO corresponding to `public.emergency_incident_recipients` in Supabase.
 * Tracks per-guardian delivery and acknowledgement status (`PENDING`, `SENT`, `DELIVERED`, `FAILED`).
 */
@Serializable
data class EmergencyIncidentRecipientDto(
    val id: String,
    @SerialName("incident_id") val incidentId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("guardian_user_id") val guardianUserId: String,
    @SerialName("delivery_status") val deliveryStatus: String = com.example.data.EmergencyIncidentEntity.DELIVERY_STATUS_PENDING,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
) {
    fun toEntity(): com.example.data.EmergencyIncidentRecipientEntity {
        return com.example.data.EmergencyIncidentRecipientEntity(
            id = id,
            incidentId = incidentId,
            senderUserId = senderUserId,
            guardianUserId = guardianUserId,
            deliveryStatus = deliveryStatus,
            deliveredAt = deliveredAt,
            lastError = lastError,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    companion object {
        fun fromEntity(entity: com.example.data.EmergencyIncidentRecipientEntity): EmergencyIncidentRecipientDto {
            return entity.toDto()
        }
    }
}

/**
 * Narrow acknowledgement payload used by a paired guardian to mark their own recipient row as DELIVERED.
 */
@Serializable
data class GuardianRecipientDeliveryAckDto(
    @SerialName("delivery_status") val deliveryStatus: String = com.example.data.EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
    @SerialName("delivered_at") val deliveredAt: Long,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("updated_at") val updatedAt: Long
)




