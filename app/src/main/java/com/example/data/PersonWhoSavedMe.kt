package com.example.data

import kotlinx.serialization.Serializable

/**
 * Represents an authenticated AIZA user who has saved/added the current user
 * as an emergency contact or guardian.
 *
 * Privacy & Security Rules:
 * - Identified strictly by authenticated Supabase UUID (`userId`).
 * - Never constructed from unauthenticated placeholders or local phone-only contacts.
 * - Does not expose private profile fields, location, medical notes, or unrelated contacts.
 */
@Serializable
data class PersonWhoSavedMe(
    val userId: String,
    val name: String,
    val relationship: String = "Emergency Contact",
    val isConnectedUser: Boolean = true,
    val contactRowId: String? = null
)
