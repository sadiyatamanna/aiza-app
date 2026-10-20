package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PhoneUtils
import com.example.data.PreferencesManager
import com.example.data.supabase.ProfileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserPhoneRegistrationTest {

    private lateinit var context: Context
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        context = app
        preferencesManager = PreferencesManager(app)
    }

    @Test
    fun testIndianPhoneNumberValidationAndNormalization() {
        // Standard 10-digit mobile number
        val input = "9876543210"
        assertTrue("Standard 10-digit number must be valid", PhoneUtils.isValid(input, "+91"))
        val normalized = PhoneUtils.normalize(input, "+91")
        assertEquals("+919876543210", normalized)

        // Pre-formatted with country code and spaces
        val formattedInput = "+91 98765-43210"
        assertTrue(PhoneUtils.isValid(formattedInput, "+91"))
        assertEquals("+919876543210", PhoneUtils.normalize(formattedInput, "+91"))
    }

    @Test
    fun testInternationalPhoneNumberValidationAndNormalization() {
        // US / Canada number (+1 415 555 2671)
        val usNumber = "+1 415 555 2671"
        assertTrue("Valid US number must be accepted", PhoneUtils.isValid(usNumber, "+1"))
        assertEquals("+14155552671", PhoneUtils.normalize(usNumber, "+1"))

        // UK number (+44 7911 123456)
        val ukNumber = "+447911123456"
        assertTrue("Valid UK number must be accepted", PhoneUtils.isValid(ukNumber, "+44"))
        assertEquals("+447911123456", PhoneUtils.normalize(ukNumber, "+44"))

        // Custom country code provided as field
        val localAustralian = "412345678" // 9 digits in Australia
        val normalizedAu = PhoneUtils.normalize(localAustralian, "+61")
        assertEquals("+61412345678", normalizedAu)
        assertTrue(PhoneUtils.isValid(localAustralian, "+61"))
    }

    @Test
    fun testInvalidPhoneNumbersRejected() {
        // Empty or blank
        assertFalse(PhoneUtils.isValid(""))
        assertFalse(PhoneUtils.isValid("   "))

        // Less than 7 digits
        assertFalse(PhoneUtils.isValid("12345"))

        // Over 15 digits
        assertFalse(PhoneUtils.isValid("12345678901234567"))
    }

    @Test
    fun testUserProfilePhoneStoredDistinctFromEmergencyContacts() {
        // Store user's own phone in preferences
        val userPhone = "+919876543210"
        preferencesManager.userPhone = userPhone
        assertEquals(userPhone, preferencesManager.userPhone)

        // Verify profile DTO correctly holds user's phone
        val profile = ProfileDto(
            id = "user_abc_123",
            name = "Test User",
            email = "test@gmail.com",
            phone = userPhone
        )
        assertEquals(userPhone, profile.phone)
        assertEquals("test@gmail.com", profile.email)

        // Existing user with no phone number: default empty string without crash
        val legacyProfile = ProfileDto(
            id = "legacy_user_456",
            name = "Legacy User",
            email = "legacy@gmail.com"
        )
        assertEquals("", legacyProfile.phone)
        assertNull(legacyProfile.createdAt)
    }

    @Test
    fun testUserPhonePreservedAcrossSessions() {
        val testPhone = "+919812345678"
        preferencesManager.userPhone = testPhone
        preferencesManager.countryCode = "+91"

        // Simulate session logout (isLoggedIn becomes false, but cached userPhone is retained)
        preferencesManager.isLoggedIn = false
        assertEquals(testPhone, preferencesManager.userPhone)

        // Re-login
        preferencesManager.isLoggedIn = true
        assertEquals(testPhone, preferencesManager.userPhone)
    }
}
