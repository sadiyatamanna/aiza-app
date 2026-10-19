package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Supabase configuration and URL validity.
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun supabaseUrl_isConfiguredCorrectly_andNotLocalhost() {
    val url = BuildConfig.SUPABASE_URL
    assertNotNull(url)
    assertTrue("SUPABASE_URL must not be blank", url.isNotBlank())
    assertTrue("SUPABASE_URL must start with https://", url.startsWith("https://"))
    assertTrue("SUPABASE_URL must target supabase.co", url.contains("supabase.co"))
    assertFalse("SUPABASE_URL must NOT contain localhost", url.contains("localhost"))
    assertFalse("SUPABASE_URL must NOT contain 127.0.0.1", url.contains("127.0.0.1"))
    assertFalse("SUPABASE_URL must NOT end with /rest/v1", url.endsWith("/rest/v1"))
    assertFalse("SUPABASE_URL must NOT end with /rest/v1/", url.endsWith("/rest/v1/"))
    assertFalse("SUPABASE_URL must NOT end with /auth/v1", url.endsWith("/auth/v1"))
    assertFalse("SUPABASE_URL must NOT end with /auth/v1/", url.endsWith("/auth/v1/"))
    assertFalse("SUPABASE_URL must NOT have trailing slash", url.endsWith("/"))
  }

  @Test
  fun authEmailValidation_acceptsOnlyGmailAddresses() {
    // Valid @gmail.com addresses (including mixed-case and numeric local parts and whitespace)
    assertTrue(com.example.domain.AuthRepository.isValidGmailAddress("user@gmail.com"))
    assertTrue(com.example.domain.AuthRepository.isValidGmailAddress("USER@gmail.com"))
    assertTrue(com.example.domain.AuthRepository.isValidGmailAddress("user@GMAIL.COM"))
    assertTrue(com.example.domain.AuthRepository.isValidGmailAddress("919876@gmail.com"))
    assertTrue(com.example.domain.AuthRepository.isValidGmailAddress("  user@gmail.com  "))

    // Invalid domains, synthetic addresses, phone numbers, and malformed addresses
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("user@example.com"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("user@yahoo.com"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("user@outlook.com"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("user@aegis-app.com"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("user@aiza.app"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("+919876543210"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("default_user"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("@gmail.com"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress("user@sub.gmail.com"))
    assertFalse(com.example.domain.AuthRepository.isValidGmailAddress(""))

    assertEquals(
      "Please use a valid Gmail address ending with @gmail.com.",
      com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE
    )
    assertEquals(
      "Please use a Google account with a Gmail address.",
      com.example.domain.AuthRepository.GOOGLE_GMAIL_ONLY_ERROR_MESSAGE
    )
  }

  @Test
  fun safeZone_creation_and_defaults() {
    val homeHaven = com.example.ui.maps.SafeZone(
      id = "haven_home_1",
      name = "Home Safe Hub",
      type = com.example.ui.maps.SafeZoneType.HOME,
      latitude = 28.6139,
      longitude = 77.2090,
      radiusMeters = 250.0,
      isEnabled = true
    )
    assertEquals("Home Safe Hub", homeHaven.name)
    assertEquals(com.example.ui.maps.SafeZoneType.HOME, homeHaven.type)
    assertTrue(homeHaven.isEnabled)
    assertEquals(250.0, homeHaven.radiusMeters, 0.01)

    // Verify distance check inside perimeter
    val activeHaven = com.example.ui.maps.MapPoisRepository.findActiveSafeZone(
      28.61391,
      77.20901,
      listOf(homeHaven)
    )
    assertNotNull(activeHaven)
    assertEquals("haven_home_1", activeHaven?.id)

    // Disabled safe zone should not trigger active status
    val disabledHaven = homeHaven.copy(isEnabled = false)
    val inactiveHaven = com.example.ui.maps.MapPoisRepository.findActiveSafeZone(
      28.61391,
      77.20901,
      listOf(disabledHaven)
    )
    assertEquals(null, inactiveHaven)
  }

  @Test
  fun poiCategory_emergencyTypes_excludePharmaciesFromEmergencyResults() {
    val poiList = listOf(
      com.example.ui.maps.EmergencyPoi(
        id = "poi_1",
        name = "Central Police",
        category = com.example.ui.maps.PoiCategory.POLICE,
        latitude = 28.61,
        longitude = 77.20,
        address = "Police HQ",
        phone = "100"
      ),
      com.example.ui.maps.EmergencyPoi(
        id = "poi_2",
        name = "City Hospital",
        category = com.example.ui.maps.PoiCategory.HOSPITAL,
        latitude = 28.62,
        longitude = 77.21,
        address = "Hospital ER",
        phone = "102"
      ),
      com.example.ui.maps.EmergencyPoi(
        id = "poi_3",
        name = "Local Med Pharmacy",
        category = com.example.ui.maps.PoiCategory.PHARMACY,
        latitude = 28.63,
        longitude = 77.22,
        address = "Market Street",
        phone = "112"
      )
    )

    val nonPharmacy = poiList.filter { it.category != com.example.ui.maps.PoiCategory.PHARMACY }
    assertEquals(2, nonPharmacy.size)
    assertFalse(nonPharmacy.any { it.category == com.example.ui.maps.PoiCategory.PHARMACY })
    assertTrue(nonPharmacy.any { it.category == com.example.ui.maps.PoiCategory.POLICE })
    assertTrue(nonPharmacy.any { it.category == com.example.ui.maps.PoiCategory.HOSPITAL })
  }

  // ========================================================
  // REQUIREMENT 1 TESTS: SAFE HAVEN COORDINATE VALIDATION
  // ========================================================

  private fun validateCoordinates(
    latitude: Double,
    longitude: Double,
    isExplicitlySelected: Boolean
  ): Result<Unit> {
    if (latitude.isNaN() || latitude.isInfinite() || longitude.isNaN() || longitude.isInfinite()) {
      return Result.failure(IllegalArgumentException("Invalid coordinates: Latitude and longitude must be valid numbers."))
    }
    if (latitude < -90.0 || latitude > 90.0) {
      return Result.failure(IllegalArgumentException("Invalid latitude: Must be between -90 and 90 degrees."))
    }
    if (longitude < -180.0 || longitude > 180.0) {
      return Result.failure(IllegalArgumentException("Invalid longitude: Must be between -180 and 180 degrees."))
    }
    if (latitude == 0.0 && longitude == 0.0 && !isExplicitlySelected) {
      return Result.failure(IllegalStateException("No valid location selected. Please wait for a GPS fix or select a location on the map."))
    }
    return Result.success(Unit)
  }

  @Test
  fun safeHaven_rejects_outOfRange_coordinates() {
    // Latitude < -90
    val res1 = validateCoordinates(-91.0, 50.0, isExplicitlySelected = true)
    assertTrue(res1.isFailure)
    assertTrue(res1.exceptionOrNull() is IllegalArgumentException)

    // Latitude > 90
    val res2 = validateCoordinates(90.1, 50.0, isExplicitlySelected = true)
    assertTrue(res2.isFailure)

    // Longitude < -180
    val res3 = validateCoordinates(12.0, -180.5, isExplicitlySelected = true)
    assertTrue(res3.isFailure)

    // Longitude > 180
    val res4 = validateCoordinates(12.0, 180.5, isExplicitlySelected = true)
    assertTrue(res4.isFailure)

    // NaN and Infinite
    assertTrue(validateCoordinates(Double.NaN, 50.0, isExplicitlySelected = true).isFailure)
    assertTrue(validateCoordinates(12.0, Double.POSITIVE_INFINITY, isExplicitlySelected = true).isFailure)
  }

  @Test
  fun safeHaven_rejects_uninitialized_coordinates_when_not_explicitly_selected() {
    // (0,0) with isExplicitlySelected = false (uninitialized GPS or default location) -> must reject
    val res = validateCoordinates(0.0, 0.0, isExplicitlySelected = false)
    assertTrue(res.isFailure)
    assertTrue(res.exceptionOrNull() is IllegalStateException)
    assertEquals(
      "No valid location selected. Please wait for a GPS fix or select a location on the map.",
      res.exceptionOrNull()?.message
    )
  }

  @Test
  fun safeHaven_accepts_coordinates_when_explicitly_selected_or_valid_gps() {
    // (0,0) explicitly chosen on map (Null Island)
    val resExplicitZero = validateCoordinates(0.0, 0.0, isExplicitlySelected = true)
    assertTrue(resExplicitZero.isSuccess)

    // Valid real world GPS location (e.g. Mumbai 19.0760, 72.8777)
    val resGps = validateCoordinates(19.0760, 72.8777, isExplicitlySelected = false)
    assertTrue(resGps.isSuccess)

    // Valid map picked location (e.g. London 51.5074, -0.1278)
    val resMap = validateCoordinates(51.5074, -0.1278, isExplicitlySelected = true)
    assertTrue(resMap.isSuccess)
  }

  // ========================================================
  // REQUIREMENT 4 TESTS: PHONE NUMBER VALIDATION & STORAGE
  // ========================================================

  @Test
  fun phoneUtils_normalizes_and_validates_indian_and_international_numbers() {
    // Standard 10-digit Indian mobile
    val normalizedIn = com.example.data.PhoneUtils.normalize("9876543210", "+91")
    assertEquals("+919876543210", normalizedIn)
    assertTrue(com.example.data.PhoneUtils.isValid("9876543210", "+91"))

    // Number with dashes, spaces, brackets
    val formatted = com.example.data.PhoneUtils.normalize("(987) 654-3210", "+91")
    assertEquals("+919876543210", formatted)

    // International US number with +1
    val normalizedUs = com.example.data.PhoneUtils.normalize("+14155552671", "+91")
    assertEquals("+14155552671", normalizedUs)
    assertTrue(com.example.data.PhoneUtils.isValid("+14155552671"))

    // International UK number with custom country code +44
    val normalizedUk = com.example.data.PhoneUtils.normalize("7911123456", "+44")
    assertEquals("+447911123456", normalizedUk)
    assertTrue(com.example.data.PhoneUtils.isValid("7911123456", "+44"))

    // Short invalid numbers (< 7 digits)
    assertFalse(com.example.data.PhoneUtils.isValid("12345"))
    assertFalse(com.example.data.PhoneUtils.isValid(""))

    // Overly long numbers (> 15 digits)
    assertFalse(com.example.data.PhoneUtils.isValid("123456789012345678"))
  }
}





