package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.supabase.GuardianPairingRequestDto
import java.util.UUID

/**
 * Local Room entity representing a Guardian Account Pairing / Invitation request.
 *
 * Security Invariants:
 * - Creating a request with a guardian's email sets [status] = [STATUS_PENDING] and [guardianUserId] = null.
 * - Entering an email NEVER assigns [guardianUserId] automatically.
 * - [guardianUserId] is populated ONLY after explicit acceptance by the authenticated guardian account
 *   with their real Supabase Auth user UUID (`auth.users.id`).
 * - Never stores passwords or authentication tokens.
 */
@Entity(
    tableName = "guardian_pairing_requests",
    indices = [
        Index(value = ["requesterUserId", "emergencyContactId"]),
        Index(value = ["guardianEmail", "status"])
    ]
)
data class GuardianPairingRequestEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val requesterUserId: String,
    val guardianEmail: String,
    val emergencyContactId: String,
    val guardianUserId: String? = null,
    val status: String = STATUS_PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val requesterName: String = "",
    val contactName: String = "",
    val relationship: String = "",
    val guardianPhone: String = ""
) {
    fun toDto(): GuardianPairingRequestDto {
        return GuardianPairingRequestDto(
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

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_REJECTED = "REJECTED"
    }
}
