package com.example.domain

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.PreferencesManager
import com.example.data.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AuthRepository(
    private val context: Context,
    private val prefs: PreferencesManager
) {
    companion object {
        const val GMAIL_ONLY_ERROR_MESSAGE =
            "Please use a valid Gmail address ending with @gmail.com."
        const val GOOGLE_GMAIL_ONLY_ERROR_MESSAGE =
            "Please use a Google account with a Gmail address."

        private val GMAIL_LOCAL_PART_REGEX = Regex("^[A-Za-z0-9._%+\\-]+$")

        /**
         * Validates that [email] (after trimming leading/trailing whitespace) is a valid email
         * address whose domain is strictly `@gmail.com` (case-insensitive).
         *
         * Never converts phone numbers to emails and never accepts synthetic domains such as
         * `@example.com` or `@aegis-app.com`.
         */
        fun isValidGmailAddress(email: String?): Boolean {
            val trimmed = email?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
            if (trimmed.equals("default_user", ignoreCase = true)) return false

            val parts = trimmed.split("@")
            if (parts.size != 2) return false

            val localPart = parts[0]
            val domainPart = parts[1]

            if (localPart.isEmpty()) return false
            if (localPart.startsWith(".") || localPart.endsWith(".") || localPart.contains("..")) {
                return false
            }
            if (!GMAIL_LOCAL_PART_REGEX.matches(localPart)) return false

            return domainPart.equals("gmail.com", ignoreCase = true)
        }
    }

    private val supabaseClient get() = SupabaseClientProvider.client

    /**
     * Initiates real Supabase Google OAuth flow and verifies that any authenticated Google account
     * uses a `@gmail.com` email address. If not, signs the user out immediately.
     */
    suspend fun signInWithGoogle(): Result<String> {
        Log.d("AuthRepository", "Supabase Google OAuth signIn ATTEMPTED")
        return try {
            supabaseClient.auth.signInWith(Google)
            val currentUser = supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
            if (currentUser != null) {
                val googleEmail = currentUser.email?.trim().orEmpty()
                if (!isValidGmailAddress(googleEmail)) {
                    Log.w("AuthRepository", "Google OAuth rejected non-Gmail address: $googleEmail")
                    signOut()
                    return Result.failure(IllegalArgumentException(GOOGLE_GMAIL_ONLY_ERROR_MESSAGE))
                }
                val userId = currentUser.id.trim()
                if (userId.isNotBlank()) {
                    prefs.supabaseUserId = userId
                    prefs.userEmail = googleEmail
                    val rawName = currentUser.userMetadata?.get("full_name")?.toString()
                        ?: currentUser.userMetadata?.get("name")?.toString()
                    val cleanName = rawName?.replace("\"", "")?.trim()
                    if (!cleanName.isNullOrBlank()) {
                        prefs.userName = cleanName
                    }
                    prefs.isLoggedIn = true
                    Log.d("AuthRepository", "Supabase Google OAuth successful, userId: $userId")
                }
                Result.success(userId)
            } else {
                // OAuth redirect launched in browser; callback will be verified in verifyAndSyncOAuthSession()
                Result.success("")
            }
        } catch (e: Exception) {
            val cleanMsg = sanitizeAuthErrorMessage(e)
            Log.w("AuthRepository", "Supabase Google OAuth note: $cleanMsg")
            Result.failure(Exception(cleanMsg, e))
        }
    }

    /**
     * Verifies and synchronizes the session state from Supabase client after Google OAuth redirect.
     * If the authenticated Google account does not end with `@gmail.com`, signs the user out
     * immediately and returns a failure with [GOOGLE_GMAIL_ONLY_ERROR_MESSAGE].
     */
    suspend fun verifyAndSyncOAuthSession(): Result<String> {
        return try {
            val user = supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
            if (user != null) {
                val googleEmail = user.email?.trim().orEmpty()
                if (!isValidGmailAddress(googleEmail)) {
                    Log.w("AuthRepository", "OAuth session rejected non-Gmail address: $googleEmail")
                    signOut()
                    return Result.failure(IllegalArgumentException(GOOGLE_GMAIL_ONLY_ERROR_MESSAGE))
                }
                val userId = user.id.trim()
                prefs.supabaseUserId = userId
                prefs.userEmail = googleEmail
                val rawName = user.userMetadata?.get("full_name")?.toString()
                    ?: user.userMetadata?.get("name")?.toString()
                val cleanName = rawName?.replace("\"", "")?.trim()
                if (!cleanName.isNullOrBlank()) {
                    prefs.userName = cleanName
                }
                prefs.isLoggedIn = true
                Log.d("AuthRepository", "OAuth session synchronized for Gmail user: $userId")
                Result.success(userId)
            } else {
                Result.failure(IllegalStateException("Could not synchronize OAuth session"))
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "OAuth session sync error: ${sanitizeAuthErrorMessage(e)}")
            Result.failure(e)
        }
    }

    /**
     * Synchronizes session state from Supabase client after OAuth redirect.
     */
    fun syncOAuthSession(): Boolean {
        return try {
            val user = supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
            if (user != null) {
                val googleEmail = user.email?.trim().orEmpty()
                if (!isValidGmailAddress(googleEmail)) {
                    Log.w("AuthRepository", "OAuth session rejected non-Gmail address: $googleEmail")
                    logout()
                    return false
                }
                prefs.supabaseUserId = user.id
                prefs.userEmail = googleEmail
                val rawName = user.userMetadata?.get("full_name")?.toString()
                    ?: user.userMetadata?.get("name")?.toString()
                val cleanName = rawName?.replace("\"", "")?.trim()
                if (!cleanName.isNullOrBlank()) {
                    prefs.userName = cleanName
                }
                prefs.isLoggedIn = true
                Log.d("AuthRepository", "OAuth session synchronized for user: ${user.id}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "OAuth session sync error: ${sanitizeAuthErrorMessage(e)}")
            false
        }
    }

    /**
     * Trims leading/trailing whitespace from an email identifier for Supabase Auth.
     * Never converts phone numbers to synthetic emails.
     */
    fun resolveEmail(email: String): String {
        return email.trim()
    }

    /**
     * Validates that the email is a valid `@gmail.com` address.
     */
    fun isValidEmail(email: String): Boolean {
        return isValidGmailAddress(email)
    }

    private fun sanitizeAuthErrorMessage(e: Throwable, emailHint: String? = null): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("user_already_exists", ignoreCase = true) ||
                raw.contains("User already registered", ignoreCase = true) -> {
                val target = emailHint?.takeIf { it.isNotBlank() } ?: "this Gmail address"
                "An account with $target already exists. Please sign in or tap 'Forgot Password?'."
            }
            raw.contains("invalid_credentials", ignoreCase = true) ||
                raw.contains("Invalid login credentials", ignoreCase = true) -> {
                "Invalid email or password. Please check your credentials or tap 'Forgot Password?'."
            }
            raw.contains("email_not_confirmed", ignoreCase = true) ||
                raw.contains("Email not confirmed", ignoreCase = true) -> {
                "Email not confirmed. Please check your inbox for confirmation link or enter the OTP verification code."
            }
            raw.contains("over_email_send_rate_limit", ignoreCase = true) ||
                raw.contains("over_request_rate_limit", ignoreCase = true) -> {
                "Too many requests. Please wait a moment and try again."
            }
            else -> {
                raw.lineSequence()
                    .firstOrNull { line ->
                        line.isNotBlank() &&
                            !line.startsWith("URL:", ignoreCase = true) &&
                            !line.startsWith("Headers:", ignoreCase = true) &&
                            !line.startsWith("Http Method:", ignoreCase = true)
                    }
                    ?.trim()
                    ?: "Authentication request could not be completed."
            }
        }
    }

    /**
     * Signs up a new user with email and password via Supabase Auth.
     * Validates Gmail-only rule before making any Supabase Auth request.
     * If the email is already registered in Supabase (`user_already_exists`), automatically attempts
     * `signIn` with the provided credentials so existing users are signed in seamlessly.
     */
    suspend fun signUp(email: String, pass: String): Result<String> {
        val targetEmail = resolveEmail(email)
        if (!isValidGmailAddress(targetEmail)) {
            return Result.failure(IllegalArgumentException(GMAIL_ONLY_ERROR_MESSAGE))
        }
        Log.d("AuthRepository", "Supabase signUp ATTEMPTED for email: $targetEmail")
        return try {
            supabaseClient.auth.signUpWith(Email) {
                this.email = targetEmail
                this.password = pass
            }
            val currentUser = supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
            val isUserNull = (currentUser == null)
            Log.d(
                "AuthRepository",
                "Supabase signUp response received. currentUserOrNull is null? $isUserNull (ID: ${currentUser?.id})"
            )

            val userId = currentUser?.id?.trim()?.takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: prefs.supabaseUserId.trim().takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: ""
            val authEmail = currentUser?.email?.trim()?.takeIf { isValidGmailAddress(it) } ?: targetEmail
            prefs.supabaseUserId = userId
            prefs.userEmail = authEmail
            prefs.userPassword = pass
            prefs.isLoggedIn = true

            Log.d("AuthRepository", "Supabase signUp successful for $authEmail, userId: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            val rawMsg = e.message.orEmpty()
            if (rawMsg.contains("user_already_exists", ignoreCase = true) ||
                rawMsg.contains("User already registered", ignoreCase = true)
            ) {
                Log.i("AuthRepository", "Account already exists for $targetEmail during signUp; attempting automatic signIn.")
                val signInFallback = signIn(targetEmail, pass)
                if (signInFallback.isSuccess) {
                    return signInFallback
                }
            }
            val cleanMsg = sanitizeAuthErrorMessage(e, targetEmail)
            Log.w("AuthRepository", "Supabase signUp note for $targetEmail: $cleanMsg")
            Result.failure(Exception(cleanMsg))
        }
    }

    /**
     * Signs in an existing user with email and password via Supabase Auth.
     * Validates Gmail-only rule before making any Supabase Auth request.
     */
    suspend fun signIn(email: String, pass: String): Result<String> {
        val targetEmail = resolveEmail(email)
        if (!isValidGmailAddress(targetEmail)) {
            return Result.failure(IllegalArgumentException(GMAIL_ONLY_ERROR_MESSAGE))
        }
        Log.d("AuthRepository", "Supabase signIn ATTEMPTED for email: $targetEmail")
        return try {
            supabaseClient.auth.signInWith(Email) {
                this.email = targetEmail
                this.password = pass
            }
            val currentUser = supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
            val isUserNull = (currentUser == null)
            Log.d(
                "AuthRepository",
                "Supabase signIn response received. currentUserOrNull is null? $isUserNull (ID: ${currentUser?.id})"
            )

            val userId = currentUser?.id?.trim()?.takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: prefs.supabaseUserId.trim().takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: ""
            val authEmail = currentUser?.email?.trim()?.takeIf { isValidGmailAddress(it) } ?: targetEmail
            prefs.supabaseUserId = userId
            prefs.userEmail = authEmail
            prefs.userPassword = pass
            prefs.isLoggedIn = true

            Log.d("AuthRepository", "Supabase signIn successful for $authEmail, userId: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            val cleanMsg = sanitizeAuthErrorMessage(e, targetEmail)
            Log.w("AuthRepository", "Supabase signIn note for $targetEmail: $cleanMsg")
            Result.failure(Exception(cleanMsg))
        }
    }

    /**
     * Sends a password reset email via Supabase Auth password-reset flow.
     * Uses the app deep link scheme aegis://auth-callback rather than localhost.
     * Validates Gmail-only rule before making any Supabase Auth request.
     */
    suspend fun sendPasswordResetEmail(email: String, redirectUrl: String = "aiza://reset-password"): Result<Unit> {
        val targetEmail = resolveEmail(email)
        if (!isValidGmailAddress(targetEmail)) {
            return Result.failure(IllegalArgumentException(GMAIL_ONLY_ERROR_MESSAGE))
        }
        Log.d("AuthRepository", "Sending Supabase password reset email to: $targetEmail with redirectUrl: $redirectUrl")
        return try {
            supabaseClient.auth.resetPasswordForEmail(email = targetEmail, redirectUrl = redirectUrl)
            Log.d("AuthRepository", "Password reset email sent successfully to $targetEmail")
            Result.success(Unit)
        } catch (e: Exception) {
            val cleanMsg = sanitizeAuthErrorMessage(e, targetEmail)
            Log.w("AuthRepository", "Password reset email note: $cleanMsg")
            Result.failure(Exception(cleanMsg))
        }
    }

    /**
     * Updates the authenticated user's password in Supabase Auth (e.g. after deep-link recovery).
     */
    suspend fun updateUserPassword(newPassword: String): Result<Unit> {
    if (newPassword.length < 6) {
        return Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
    }
    return try {
        supabaseClient.auth.updateUser {
            this.password = newPassword
        }
        Log.d("AuthRepository", "User password updated successfully via Supabase Auth.")
        Result.success(Unit)
    } catch (e: Exception) {
        val cleanMsg = sanitizeAuthErrorMessage(e)
        Log.w("AuthRepository", "Password update error: $cleanMsg")
        Result.failure(Exception(cleanMsg, e))
    }
}

    /**
     * Real Supabase phone OTP sign-in / OTP request.
     * Never generates synthetic emails (@example.com, @aegis-app.com, default_user).
     */
    suspend fun sendPhoneOtp(phoneNumber: String, countryCode: String = "+91"): Result<Unit> {
        val normalized = com.example.data.PhoneUtils.normalize(phoneNumber, countryCode)
        if (!com.example.data.PhoneUtils.isValid(normalized)) {
            return Result.failure(IllegalArgumentException("Please enter a valid phone number with country code."))
        }
        Log.d("AuthRepository", "Requesting Supabase Phone OTP for: $normalized")
        return try {
            supabaseClient.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.OTP) {
                this.phone = normalized
            }
            Log.d("AuthRepository", "Phone OTP request accepted for $normalized")
            Result.success(Unit)
        } catch (e: Exception) {
            val cleanMsg = sanitizePhoneAuthErrorMessage(e, normalized)
            Log.w("AuthRepository", "Phone OTP request error: $cleanMsg")
            Result.failure(Exception(cleanMsg, e))
        }
    }

    /**
     * Resends phone OTP via Supabase Auth.
     */
    suspend fun resendPhoneOtp(phoneNumber: String, countryCode: String = "+91"): Result<Unit> {
        val normalized = com.example.data.PhoneUtils.normalize(phoneNumber, countryCode)
        if (!com.example.data.PhoneUtils.isValid(normalized)) {
            return Result.failure(IllegalArgumentException("Please enter a valid phone number with country code."))
        }
        Log.d("AuthRepository", "Resending Supabase Phone OTP for: $normalized")
        return try {
            try {
                supabaseClient.auth.resendPhone(
                    type = io.github.jan.supabase.auth.OtpType.Phone.SMS,
                    phone = normalized
                )
            } catch (_: Exception) {
                // Fallback to re-requesting OTP via signInWith(OTP)
                supabaseClient.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.OTP) {
                    this.phone = normalized
                }
            }
            Log.d("AuthRepository", "Phone OTP resent successfully to $normalized")
            Result.success(Unit)
        } catch (e: Exception) {
            val cleanMsg = sanitizePhoneAuthErrorMessage(e, normalized)
            Log.w("AuthRepository", "Phone OTP resend error: $cleanMsg")
            Result.failure(Exception(cleanMsg, e))
        }
    }

    /**
     * Verifies phone OTP token via Supabase Auth and authenticates user by UUID.
     * Never generates synthetic emails.
     */
    suspend fun verifyPhoneOtp(phoneNumber: String, token: String, countryCode: String = "+91"): Result<String> {
        val normalized = com.example.data.PhoneUtils.normalize(phoneNumber, countryCode)
        val cleanToken = token.trim()
        if (cleanToken.length < 4) {
            return Result.failure(IllegalArgumentException("Please enter a valid OTP code."))
        }
        Log.d("AuthRepository", "Verifying Phone OTP for: $normalized")
        return try {
            supabaseClient.auth.verifyPhoneOtp(
                type = io.github.jan.supabase.auth.OtpType.Phone.SMS,
                phone = normalized,
                token = cleanToken
            )
            val currentUser = supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
            val userId = currentUser?.id?.trim()?.takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: prefs.supabaseUserId.trim().takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: ""

            if (!SafetyRepository.isRealAuthenticatedUserUuid(userId)) {
                return Result.failure(IllegalStateException("Supabase authenticated user UUID not found."))
            }

            prefs.supabaseUserId = userId
            prefs.userPhone = normalized
            prefs.isLoggedIn = true

            Log.d("AuthRepository", "Phone OTP verified successfully! userId: $userId")
            Result.success(userId)
        } catch (e: Exception) {
            val cleanMsg = sanitizePhoneAuthErrorMessage(e, normalized)
            Log.w("AuthRepository", "Phone OTP verification error: $cleanMsg")
            Result.failure(Exception(cleanMsg, e))
        }
    }

    private fun sanitizePhoneAuthErrorMessage(e: Throwable, phoneHint: String? = null): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("invalid", ignoreCase = true) && raw.contains("otp", ignoreCase = true) ||
                raw.contains("Token has expired", ignoreCase = true) ||
                raw.contains("expired", ignoreCase = true) -> {
                "Invalid or expired OTP code. Please enter the correct code or tap Resend."
            }
            raw.contains("rate_limit", ignoreCase = true) ||
                raw.contains("over_request_rate_limit", ignoreCase = true) ||
                raw.contains("too many", ignoreCase = true) -> {
                "Too many OTP requests. Please wait a few moments before trying again."
            }
            raw.contains("not found", ignoreCase = true) -> {
                "Account not found for this phone number. Please check the number."
            }
            raw.contains("network", ignoreCase = true) ||
                raw.contains("connect", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) -> {
                "Network connection error. Please check your internet connection."
            }
            raw.contains("disabled", ignoreCase = true) ||
                raw.contains("phone_provider_disabled", ignoreCase = true) ||
                raw.contains("unsupported phone provider", ignoreCase = true) ||
                raw.contains("provider is not enabled", ignoreCase = true) -> {
                "Phone SMS provider is not enabled in your Supabase project. In the Supabase Dashboard, navigate to Authentication -> Providers -> Phone and enable Twilio or MessageBird."
            }
            else -> {
                raw.lineSequence()
                    .firstOrNull { line ->
                        line.isNotBlank() &&
                            !line.startsWith("URL:", ignoreCase = true) &&
                            !line.startsWith("Headers:", ignoreCase = true) &&
                            !line.startsWith("Http Method:", ignoreCase = true)
                    }
                    ?.trim()
                    ?: "Phone authentication could not be completed."
            }
        }
    }

    /**
     * Resends email confirmation OTP or link via Supabase Auth.
     * Validates Gmail-only rule before making any Supabase Auth request.
     */
    suspend fun resendConfirmationEmail(email: String): Result<Unit> {
        val targetEmail = resolveEmail(email)
        if (!isValidGmailAddress(targetEmail)) {
            return Result.failure(IllegalArgumentException(GMAIL_ONLY_ERROR_MESSAGE))
        }
        Log.d("AuthRepository", "Resending confirmation email to: $targetEmail")
        return try {
            supabaseClient.auth.resendEmail(OtpType.Email.SIGNUP, targetEmail)
            Log.d("AuthRepository", "Confirmation email resent successfully to $targetEmail")
            Result.success(Unit)
        } catch (e: Exception) {
            val cleanMsg = sanitizeAuthErrorMessage(e, targetEmail)
            Log.w("AuthRepository", "Resend confirmation email note: $cleanMsg")
            Result.failure(Exception(cleanMsg))
        }
    }

    /**
     * Verifies the email confirmation OTP token entered by the user.
     * Validates Gmail-only rule before making any Supabase Auth request.
     */
    suspend fun verifyEmailOtp(email: String, token: String): Result<String> {
        val targetEmail = resolveEmail(email)
        if (!isValidGmailAddress(targetEmail)) {
            return Result.failure(IllegalArgumentException(GMAIL_ONLY_ERROR_MESSAGE))
        }
        Log.d("AuthRepository", "Verifying email OTP for $targetEmail")
        return try {
            supabaseClient.auth.verifyEmailOtp(
                type = OtpType.Email.SIGNUP,
                email = targetEmail,
                token = token.trim()
            )
            val currentUser = supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
            val userId = currentUser?.id?.trim()?.takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: prefs.supabaseUserId.trim().takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                ?: ""
            val authEmail = currentUser?.email?.trim()?.takeIf { isValidGmailAddress(it) } ?: targetEmail
            prefs.supabaseUserId = userId
            prefs.userEmail = authEmail
            prefs.isLoggedIn = true
            Log.d("AuthRepository", "Email verified and logged in successfully for $authEmail")
            Result.success(userId)
        } catch (e: Exception) {
            val cleanMsg = sanitizeAuthErrorMessage(e, targetEmail)
            Log.w("AuthRepository", "Email OTP verification note: $cleanMsg")
            Result.failure(Exception(cleanMsg))
        }
    }

    /**
     * Signs out the current user from Supabase and clears local login state.
     */
    suspend fun signOut() {
        try {
            supabaseClient.auth.signOut()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Supabase signOut error: ${e.message}")
        } finally {
            prefs.isLoggedIn = false
            prefs.supabaseUserId = ""
            prefs.userEmail = ""
            prefs.userPassword = ""
        }
    }

    /**
     * Retrieves the current Supabase UserInfo if authenticated.
     */
    fun getCurrentUser(): UserInfo? {
        return try {
            supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Retrieves the current Supabase UserSession if available.
     */
    fun getCurrentSession(): UserSession? {
        return try {
            supabaseClient.auth.currentSessionOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the active Supabase user ID or cached real UUID (never generates a fake UUID).
     */
    fun getCurrentUserId(): String? {
        val remoteId = try {
            (supabaseClient.auth.currentUserOrNull() ?: supabaseClient.auth.currentSessionOrNull()?.user)?.id?.trim()
        } catch (e: Exception) {
            null
        }
        if (SafetyRepository.isRealAuthenticatedUserUuid(remoteId)) {
            return remoteId
        }
        return prefs.supabaseUserId.trim().takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
    }

    /**
     * Returns the active authenticated Supabase Gmail address or cached Gmail address.
     */
    fun getCurrentUserEmail(): String? {
        val remoteEmail = try {
            (supabaseClient.auth.currentUserOrNull() ?: supabaseClient.auth.currentSessionOrNull()?.user)?.email?.trim()
        } catch (e: Exception) {
            null
        }
        if (isValidGmailAddress(remoteEmail)) {
            return remoteEmail
        }
        return prefs.userEmail.trim().takeIf { isValidGmailAddress(it) }
    }

    fun logout() {
        try {
            prefs.isLoggedIn = false
            prefs.supabaseUserId = ""
            prefs.userEmail = ""
            prefs.userPassword = ""
        } catch (e: Exception) {
            prefs.isLoggedIn = false
        }
    }

    /**
     * Permanently deletes the authenticated user's account via the secure backend
     * delete-account Edge Function.
     *
     * Never exposes the service-role key to the client.
     * Deletes user cloud evidence and metadata, then purges local tables on success.
     */
    suspend fun deleteAccount(purgeLocalOfflineEvidence: Boolean = false): Result<Unit> {
        val userId = getCurrentUserId()
        if (userId.isNullOrBlank() || !SafetyRepository.isRealAuthenticatedUserUuid(userId)) {
            return Result.failure(IllegalStateException("No authenticated user session found to delete."))
        }

        val session = getCurrentSession()
        val token = session?.accessToken?.trim().orEmpty()
        if (token.isBlank()) {
            return Result.failure(IllegalStateException("Active session token required for permanent account deletion."))
        }

        val endpointUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/delete-account"
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $token")
            .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            .header("Content-Type", "application/json")
            .post("{}".toRequestBody(jsonMedia))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                // Clear local data upon confirmed backend deletion
                try {
                    val db = com.example.data.AppDatabase.getDatabase(context)
                    if (purgeLocalOfflineEvidence) {
                        db.clearAllTables()
                    } else {
                        // Preserve offline evidence in evidenceDao; purge session and sync tables
                        db.contactDao().deleteAllContacts()
                        db.emergencyIncidentDao().deleteAllIncidents()
                        db.sosLogDao().clearAllLogs()
                    }
                } catch (e: Exception) {
                    Log.w("AuthRepository", "Error clearing local database on account deletion: ${e.message}")
                }
                logout()
                prefs.clearProfile()
                try {
                    supabaseClient.auth.signOut()
                } catch (_: Exception) {}
                Result.success(Unit)
            } else {
                val errorMsg = try {
                    JSONObject(responseBody).optString("error", "HTTP ${response.code}: ${response.message}")
                } catch (_: Exception) {
                    "HTTP ${response.code}: ${response.message}"
                }
                Result.failure(Exception("Account deletion rejected by server: $errorMsg"))
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Account deletion network error: ${e.message}", e)
            Result.failure(Exception("Network error contacting deletion endpoint: ${e.message ?: "Failed to connect to server"}"))
        }
    }
}
