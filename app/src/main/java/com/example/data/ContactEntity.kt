package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "emergency_contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val contactId: String = UUID.randomUUID().toString(),
    val userId: String = "",
    val guardianUserId: String? = null,
    val name: String = "",
    val phone: String = "",
    val relation: String = "",
    val priority: String = "1", // "1", "2", "3"...
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val email: String = "",
    val isVerified: Boolean = false
) {
    val phoneNumber: String get() = phone

    /**
     * Returns the guardian's paired Supabase Auth user UUID (`auth.users.id`) if and only if
     * [guardianUserId] is a valid UUID that is neither the guardian's phone number nor the
     * emergency-contact UUID ([contactId]). Never generates or returns a fake UUID.
     */
    val pairedGuardianUserId: String?
        get() {
            val candidate = guardianUserId?.trim()
            if (candidate.isNullOrEmpty()) return null
            if (!UUID_REGEX.matches(candidate)) return null
            if (candidate.equals(contactId.trim(), ignoreCase = true)) return null
            if (candidate == phone.trim() || candidate.all { it.isDigit() || it == '+' }) return null
            return candidate
        }

    val isGuardianAccountPaired: Boolean
        get() = pairedGuardianUserId != null

    companion object {
        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
    }
}


