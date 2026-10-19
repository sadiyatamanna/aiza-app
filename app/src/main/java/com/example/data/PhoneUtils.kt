package com.example.data

object PhoneUtils {
    /**
     * Normalizes a phone number to standard international E.164 or dialable format.
     * Preserves existing country code if already present with leading '+'.
     * If 10 digits without country code, applies the configured defaultCountryCode.
     */
    fun normalize(phone: String, defaultCountryCode: String = "+91"): String {
        val trimmed = phone.trim()
        if (trimmed.isBlank()) return ""
        val cleaned = trimmed.replace("[\\s\\-\\(\\)]".toRegex(), "")
        val cc = if (defaultCountryCode.startsWith("+")) defaultCountryCode else "+$defaultCountryCode"
        val ccDigits = cc.removePrefix("+")

        return when {
            // Already starts with '+' e.g. +14155552671, +919876543210
            cleaned.startsWith("+") -> {
                val digits = cleaned.filter { it.isDigit() }
                "+$digits"
            }
            // Starts with the digits of the country code (e.g. 919876543210)
            cleaned.startsWith(ccDigits) && cleaned.length > ccDigits.length + 6 -> {
                "+$cleaned"
            }
            // Standard 10-digit mobile number -> prepend default country code
            cleaned.length == 10 && cleaned.all { it.isDigit() } -> {
                "$cc$cleaned"
            }
            // Other numbers: if digits only, preserve with default country code or as-is
            else -> {
                val digits = cleaned.filter { it.isDigit() }
                if (digits.isEmpty()) {
                    ""
                } else if (digits.length >= 10 && !digits.startsWith(ccDigits)) {
                    "$cc$digits"
                } else {
                    "+$digits"
                }
            }
        }
    }

    /**
     * Validates if a phone number contains a valid dialable phone sequence.
     */
    fun isValid(phone: String, defaultCountryCode: String = "+91"): Boolean {
        val trimmed = phone.trim()
        if (trimmed.isBlank()) return false
        val normalized = normalize(trimmed, defaultCountryCode)
        val digits = normalized.filter { it.isDigit() }
        return digits.length in 7..15
    }
}
