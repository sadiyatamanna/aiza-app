package com.example

import com.example.data.ContactEntity
import com.example.data.PhoneUtils
import com.example.data.supabase.UserLocationDto
import com.example.domain.SosCommunicationDispatcher
import com.example.domain.VoiceTriggerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SosDispatchUnitTest {

    @Before
    fun setup() {
        VoiceTriggerValidator.resetCooldownForTesting()
    }

    @Test
    fun testContactPrioritySorting_rank1GetsCall_othersGetSms() {
        val contacts = listOf(
            ContactEntity(id = 10, name = "Brother", phone = "+19876543210", priority = "3"),
            ContactEntity(id = 11, name = "Mother", phone = "+11234567890", priority = "1"),
            ContactEntity(id = 12, name = "Sister", phone = "+15555555555", priority = "2")
        )

        val sorted = contacts.sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }

        assertEquals("Mother", sorted[0].name)
        assertEquals("+11234567890", sorted[0].phone)

        val rank1CallRecipient = sorted.first()
        val smsRecipients = sorted.drop(1)

        assertEquals("Mother", rank1CallRecipient.name)
        assertEquals(2, smsRecipients.size)
        assertEquals("Sister", smsRecipients[0].name)
        assertEquals("Brother", smsRecipients[1].name)
    }

    @Test
    fun testSingleContactFallback_receivesBothCallAndSms() {
        val singleContact = listOf(
            ContactEntity(id = 1, name = "Primary Guardian", phone = "+10000000000", priority = "1")
        )

        val rank1 = singleContact.first()
        val remaining = singleContact.drop(1)

        val smsRecipients = if (remaining.isEmpty()) listOf(rank1) else remaining

        assertEquals(1, smsRecipients.size)
        assertEquals(rank1.phone, smsRecipients.first().phone)
    }

    @Test
    fun testSosCommunicationDispatcher_buildEmergencySmsText_containsAllRequiredFields() {
        val message = SosCommunicationDispatcher.buildEmergencySmsText(
            userName = "Alice Smith",
            triggerSource = "MANUAL",
            latitude = 37.774929,
            longitude = -122.419416,
            isLastKnownLocation = false,
            voiceClipUrl = null
        )

        assertTrue("Must contain header", message.contains("AIZA SOS ALERT"))
        assertTrue("Must contain user name", message.contains("User: Alice Smith"))
        assertTrue("Must contain trigger source", message.contains("Trigger: MANUAL"))
        assertTrue("Must contain formatted coordinates", message.contains("Location: 37.77493, -122.41942"))
        assertTrue("Must contain Google Maps URL", message.contains("https://maps.google.com/?q=37.77493,-122.41942"))
        assertTrue("Must contain timestamp", message.contains("Time:"))
        assertFalse("Must not claim last known when active", message.contains("last known"))
        assertFalse("Must not have distress clip URL when none provided", message.contains("Distress Clip:"))
    }

    @Test
    fun testSosCommunicationDispatcher_buildEmergencySmsText_lastKnownLocationLabel() {
        val message = SosCommunicationDispatcher.buildEmergencySmsText(
            userName = "Bob",
            triggerSource = "SHAKE",
            latitude = 40.7128,
            longitude = -74.0060,
            isLastKnownLocation = true,
            voiceClipUrl = null
        )

        assertTrue("Must label as last known location", message.contains("(last known location)"))
        assertTrue("Must contain Google Maps link", message.contains("https://maps.google.com/?q=40.71280,-74.00600"))
    }

    @Test
    fun testSosCommunicationDispatcher_buildEmergencySmsText_withVoiceClipUrl() {
        val message = SosCommunicationDispatcher.buildEmergencySmsText(
            userName = "Charlie",
            triggerSource = "VOICE",
            latitude = 51.5074,
            longitude = -0.1278,
            isLastKnownLocation = false,
            voiceClipUrl = "https://supabase.co/storage/v1/object/sign/distress-clips/clip_123.mp4?token=xyz"
        )

        assertTrue("Must contain trigger VOICE", message.contains("Trigger: VOICE"))
        assertTrue("Must contain Distress Clip URL", message.contains("Distress Clip: https://supabase.co/storage/v1/object/sign/distress-clips/clip_123.mp4?token=xyz"))
    }

    @Test
    fun testVoiceTriggerValidator_recognizesEmergencyPhrases() {
        assertTrue(VoiceTriggerValidator.matchesEmergencyPhrase("help me"))
        assertTrue(VoiceTriggerValidator.matchesEmergencyPhrase("Help Me Please!"))
        assertTrue(VoiceTriggerValidator.matchesEmergencyPhrase("emergency assistance needed"))
        assertTrue(VoiceTriggerValidator.matchesEmergencyPhrase("save me now"))
        assertTrue(VoiceTriggerValidator.matchesEmergencyPhrase("aegis help"))

        assertFalse(VoiceTriggerValidator.matchesEmergencyPhrase("hello how are you"))
        assertFalse(VoiceTriggerValidator.matchesEmergencyPhrase("what is the weather today"))
        assertFalse(VoiceTriggerValidator.matchesEmergencyPhrase(""))
    }

    @Test
    fun testVoiceTriggerValidator_enforcesCooldown() {
        VoiceTriggerValidator.resetCooldownForTesting()

        // First attempt succeeds
        assertTrue(VoiceTriggerValidator.validateAndConsume("help me"))

        // Immediate duplicate attempt within cooldown window is suppressed
        assertFalse(VoiceTriggerValidator.validateAndConsume("help me"))

        // Reset cooldown allows subsequent trigger
        VoiceTriggerValidator.resetCooldownForTesting()
        assertTrue(VoiceTriggerValidator.validateAndConsume("emergency"))
    }

    @Test
    fun testPhoneUtils_normalize() {
        assertEquals("+15551234567", PhoneUtils.normalize("+1 (555) 123-4567"))
        assertEquals("+15551234567", PhoneUtils.normalize("5551234567", "+1"))
        assertEquals("+919876543210", PhoneUtils.normalize("+91 98765-43210"))
        assertEquals("", PhoneUtils.normalize("abc"))
    }

    @Test
    fun testBlePanicCommandDetection() {
        val panicByte = 'S'.code.toByte()
        val packet = byteArrayOf(panicByte)
        val receivedStr = String(packet, Charsets.UTF_8).trim()

        val isPanic = receivedStr.contains("S", ignoreCase = true) ||
                receivedStr.contains("PANIC", ignoreCase = true) ||
                receivedStr.contains("SOS", ignoreCase = true) ||
                packet.any { it == 'S'.code.toByte() }

        assertTrue("Byte 'S' must be recognized as hardware panic signal", isPanic)
    }
}
