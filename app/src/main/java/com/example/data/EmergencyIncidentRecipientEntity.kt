package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.supabase.EmergencyIncidentRecipientDto
import java.util.UUID

/**
 * Persistent Room entity representing per-guardian delivery state for an [EmergencyIncidentEntity].
 *
 * Identity & Uniqueness Rules:
 * - [incidentId] references [EmergencyIncidentEntity.id].
 * - [senderUserId] MUST be the incident owner's authenticated Supabase user UUID (`auth.users.id`).
 * - [guardianUserId] MUST be the paired guardian's authenticated Supabase user UUID (`auth.users.id`),
 *   verified through `emergency_contacts.guardian_user_id`.
 * - Composite unique index on `(incidentId, guardianUserId)` prevents duplicate recipient records
 *   for the same incident and guardian while supporting multiple paired guardians per incident.
 *
 * Delivery Status Semantics:
 * - [DELIVERY_STATUS_PENDING]: Waiting for cloud synchronization / delivery.
 * - [DELIVERY_STATUS_SENT]: Sender successfully synchronized the incident & recipient record to Supabase.
 * - [DELIVERY_STATUS_DELIVERED]: Guardian authenticated account successfully retrieved and persisted
 *   the incident in local Room storage.
 * - [DELIVERY_STATUS_FAILED]: Delivery or retrieval attempt failed.
 */
@Entity(
    tableName = "emergency_incident_recipients",
    indices = [
        Index(value = ["incidentId", "guardianUserId"], unique = true),
        Index(value = ["incidentId"]),
        Index(value = ["guardianUserId"]),
        Index(value = ["deliveryStatus"])
    ]
)
data class EmergencyIncidentRecipientEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val incidentId: String,
    val senderUserId: String,
    val guardianUserId: String,
    val deliveryStatus: String = DELIVERY_STATUS_PENDING,
    val deliveredAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDto(): EmergencyIncidentRecipientDto {
        return EmergencyIncidentRecipientDto(
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
        const val DELIVERY_STATUS_PENDING = EmergencyIncidentEntity.DELIVERY_STATUS_PENDING
        const val DELIVERY_STATUS_SENT = EmergencyIncidentEntity.DELIVERY_STATUS_SENT
        const val DELIVERY_STATUS_DELIVERED = EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED
        const val DELIVERY_STATUS_FAILED = EmergencyIncidentEntity.DELIVERY_STATUS_FAILED
    }
}
