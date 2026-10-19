package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.EmergencyIncidentDao
import com.example.data.EmergencyIncidentEntity
import com.example.data.PreferencesManager
import com.example.data.supabase.EmergencyIncidentDto
import com.example.domain.EmergencyIncidentRemoteDataSource
import com.example.domain.SafetyRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 verification tests for AIZA Emergency App-to-App Incident Sharing:
 * - Trigger source normalization & creation for MANUAL, SHAKE, VOICE, BLUETOOTH
 * - Non-creation on non-SOS actions
 * - Single ACTIVE incident deduplication per authenticated user
 * - Location status classification (LIVE, RECENT, STALE, UNKNOWN) and live mirroring
 * - Device status capture (batteryPercent, networkStatus)
 * - Selective medical sharing defaults and privacy
 * - Audio status transitions without uploading audio Storage paths
 * - Incident resolution on SOS deactivation
 * - Supabase sync delivery status transitions (PENDING -> SENT / FAILED)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyIncidentPhase2Test {

    private lateinit var database: AppDatabase
    private lateinit var incidentDao: EmergencyIncidentDao
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fakeRemote: FakePhase2IncidentRemoteDataSource
    private lateinit var repository: SafetyRepository

    private val validUserUuid = "11111111-2222-3333-4444-555555555555"

    private class FakePhase2IncidentRemoteDataSource : EmergencyIncidentRemoteDataSource {
        val upsertedDtos = mutableListOf<EmergencyIncidentDto>()
        val remoteStore = linkedMapOf<String, EmergencyIncidentDto>()
        var shouldFailUpsert: Boolean = false
        var failureMessage: String = "Simulated network outage"

        override suspend fun upsertEmergencyIncident(dto: EmergencyIncidentDto) {
            if (shouldFailUpsert) {
                throw IllegalStateException(failureMessage)
            }
            upsertedDtos.add(dto)
            remoteStore[dto.id] = dto
        }

        override suspend fun fetchEmergencyIncidentsForUser(userId: String): List<EmergencyIncidentDto> {
            return remoteStore.values
                .filter { it.userId.equals(userId, ignoreCase = true) }
                .sortedByDescending { it.startedAt }
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("AegisPrefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        preferencesManager = PreferencesManager(context).apply {
            isLoggedIn = true
            supabaseUserId = validUserUuid
            userEmail = "tam@example.com"
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        incidentDao = database.emergencyIncidentDao()
        fakeRemote = FakePhase2IncidentRemoteDataSource()
        repository = SafetyRepository(
            contactDao = database.contactDao(),
            sosLogDao = database.sosLogDao(),
            preferencesManager = preferencesManager,
            trackedLocationDao = database.trackedLocationDao(),
            cachedPlaceDao = database.cachedPlaceDao(),
            guardianChatDao = database.guardianChatDao(),
            guardianPairingRequestDao = database.guardianPairingRequestDao(),
            emergencyIncidentDao = incidentDao,
            incidentRemoteDataSource = fakeRemote
        )
    }

    @After
    fun tearDown() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("AegisPrefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun triggerSourceNormalization_mapsAllSupportedTriggersAndRejectsNonSosActions() {
        assertEquals(
            EmergencyIncidentEntity.TRIGGER_MANUAL,
            SafetyRepository.normalizeIncidentTriggerSource("Manual")
        )
        assertEquals(
            EmergencyIncidentEntity.TRIGGER_MANUAL,
            SafetyRepository.normalizeIncidentTriggerSource("MANUAL")
        )
        assertEquals(
            EmergencyIncidentEntity.TRIGGER_SHAKE,
            SafetyRepository.normalizeIncidentTriggerSource("SHAKE")
        )
        assertEquals(
            EmergencyIncidentEntity.TRIGGER_VOICE,
            SafetyRepository.normalizeIncidentTriggerSource("VOICE")
        )
        assertEquals(
            EmergencyIncidentEntity.TRIGGER_BLUETOOTH,
            SafetyRepository.normalizeIncidentTriggerSource("BLUETOOTH")
        )
        assertEquals(
            EmergencyIncidentEntity.TRIGGER_BLUETOOTH,
            SafetyRepository.normalizeIncidentTriggerSource("BLE")
        )

        // Non-SOS actions must never normalize to a valid SOS incident trigger
        assertNull(SafetyRepository.normalizeIncidentTriggerSource("CALL_GUARDIAN"))
        assertNull(SafetyRepository.normalizeIncidentTriggerSource("SEND_ALERT_SMS"))
        assertNull(SafetyRepository.normalizeIncidentTriggerSource("SIREN"))
        assertNull(SafetyRepository.normalizeIncidentTriggerSource("RADAR"))
        assertNull(SafetyRepository.normalizeIncidentTriggerSource("HOTLINES"))
        assertNull(SafetyRepository.normalizeIncidentTriggerSource(""))
    }

    @Test
    fun createIncident_supportsManualShakeVoiceAndBluetoothTriggersWhenResolvedBetweenEvents() = runBlocking {
        val triggers = listOf(
            "Manual" to EmergencyIncidentEntity.TRIGGER_MANUAL,
            "SHAKE" to EmergencyIncidentEntity.TRIGGER_SHAKE,
            "VOICE" to EmergencyIncidentEntity.TRIGGER_VOICE,
            "BLE" to EmergencyIncidentEntity.TRIGGER_BLUETOOTH
        )

        for ((rawSource, expectedNormalized) in triggers) {
            val createRes = repository.createEmergencyIncident(
                triggerSource = rawSource,
                latitude = 12.9716,
                longitude = 77.5946,
                locationTimestamp = 1_700_000_000_000L,
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                batteryPercent = 84,
                networkStatus = "ONLINE",
                medicalSharingEnabled = false,
                medicalSummary = null
            )
            assertTrue("Expected creation success for $rawSource", createRes.isSuccess)
            val active = createRes.getOrThrow()
            assertEquals(expectedNormalized, active.triggerSource)
            assertEquals(EmergencyIncidentEntity.STATUS_ACTIVE, active.status)
            assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_PENDING, active.deliveryStatus)
            assertEquals(EmergencyIncidentEntity.AUDIO_STATUS_NOT_STARTED, active.audioRecordingStatus)
            assertNull(active.audioStoragePath)
            assertFalse(active.medicalSharingEnabled)
            assertNull(active.medicalSummary)

            // Resolve so next trigger creates a fresh incident
            val resolveRes = repository.resolveActiveEmergencyIncident()
            assertTrue(resolveRes.isSuccess)
            assertEquals(EmergencyIncidentEntity.STATUS_RESOLVED, resolveRes.getOrThrow().status)
            assertNotNull(resolveRes.getOrThrow().endedAt)
        }

        val history = repository.observeEmergencyIncidents().first()
        assertEquals(4, history.size)
        assertTrue(history.all { it.status == EmergencyIncidentEntity.STATUS_RESOLVED })
    }

    @Test
    fun duplicateActiveIncidentPrevention_reusesExistingActiveIncidentForSameUser() = runBlocking {
        val firstRes = repository.createEmergencyIncident(
            triggerSource = "Manual",
            latitude = 12.9000,
            longitude = 77.5000,
            locationTimestamp = 1_700_000_001_000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_RECENT,
            batteryPercent = 90,
            networkStatus = "ONLINE"
        )
        assertTrue(firstRes.isSuccess)
        val firstIncident = firstRes.getOrThrow()

        // Trigger another SOS while the first incident is still ACTIVE
        val secondRes = repository.createEmergencyIncident(
            triggerSource = "SHAKE",
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = 1_700_000_005_000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 88,
            networkStatus = "ONLINE"
        )
        assertTrue(secondRes.isSuccess)
        val reusedIncident = secondRes.getOrThrow()

        // Must reuse the exact same incident ID and startedAt
        assertEquals(firstIncident.id, reusedIncident.id)
        assertEquals(firstIncident.startedAt, reusedIncident.startedAt)
        assertEquals(EmergencyIncidentEntity.STATUS_ACTIVE, reusedIncident.status)
        assertEquals(12.9716, reusedIncident.latitude ?: 0.0, 0.0001)
        assertEquals(77.5946, reusedIncident.longitude ?: 0.0, 0.0001)
        assertEquals(EmergencyIncidentEntity.LOCATION_STATUS_LIVE, reusedIncident.locationStatus)
        assertEquals(88, reusedIncident.batteryPercent)

        // Confirm only ONE incident exists in Room for the authenticated user
        val allIncidents = repository.observeEmergencyIncidents().first()
        assertEquals(1, allIncidents.size)
    }

    @Test
    fun locationClassificationAndMirroring_updatesActiveIncidentFromUnknownToLive() = runBlocking {
        val nowMs = 1_700_000_500_000L

        assertEquals(
            EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            SafetyRepository.classifyLocationStatus(
                hasLiveFix = true,
                hasCachedFix = true,
                locationTimestamp = nowMs,
                nowMs = nowMs
            )
        )
        assertEquals(
            EmergencyIncidentEntity.LOCATION_STATUS_RECENT,
            SafetyRepository.classifyLocationStatus(
                hasLiveFix = false,
                hasCachedFix = true,
                locationTimestamp = nowMs - 60_000L,
                nowMs = nowMs
            )
        )
        assertEquals(
            EmergencyIncidentEntity.LOCATION_STATUS_STALE,
            SafetyRepository.classifyLocationStatus(
                hasLiveFix = false,
                hasCachedFix = true,
                locationTimestamp = nowMs - (10 * 60_000L),
                nowMs = nowMs
            )
        )
        assertEquals(
            EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN,
            SafetyRepository.classifyLocationStatus(
                hasLiveFix = false,
                hasCachedFix = false,
                locationTimestamp = null,
                nowMs = nowMs
            )
        )

        // Start incident with UNKNOWN location
        val initial = repository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            latitude = null,
            longitude = null,
            locationTimestamp = null,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN
        ).getOrThrow()
        assertNull(initial.latitude)
        assertNull(initial.longitude)
        assertEquals(EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN, initial.locationStatus)

        // Mirror live SOS GPS fix into the active incident
        val liveUpdate = repository.updateActiveIncidentLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            locationTimestamp = nowMs,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE
        )
        assertTrue(liveUpdate.isSuccess)
        val updated = liveUpdate.getOrThrow()
        assertEquals(initial.id, updated.id)
        assertEquals(28.6139, updated.latitude ?: 0.0, 0.0001)
        assertEquals(77.2090, updated.longitude ?: 0.0, 0.0001)
        assertEquals(nowMs, updated.locationTimestamp)
        assertEquals(EmergencyIncidentEntity.LOCATION_STATUS_LIVE, updated.locationStatus)
    }

    @Test
    fun medicalSharingDefault_isFalseUntilExplicitlyConfiguredInPreferences() {
        // Fresh preferences without explicit configuration must report false for incident sharing
        assertFalse(preferencesManager.isMedicalSharingExplicitlyConfigured)

        // Once explicitly set to true, it becomes explicitly configured and enabled
        preferencesManager.shareMedicalInfo = true
        assertTrue(preferencesManager.isMedicalSharingExplicitlyConfigured)
        assertTrue(preferencesManager.shareMedicalInfo)

        // Once explicitly set to false, it remains explicitly configured and disabled
        preferencesManager.shareMedicalInfo = false
        assertTrue(preferencesManager.isMedicalSharingExplicitlyConfigured)
        assertFalse(preferencesManager.shareMedicalInfo)
    }

    @Test
    fun audioStatusLifecycle_updatesActiveIncidentWithoutStoringAudioUploadPath() = runBlocking {
        val incident = repository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_VOICE
        ).getOrThrow()
        assertEquals(EmergencyIncidentEntity.AUDIO_STATUS_NOT_STARTED, incident.audioRecordingStatus)
        assertNull(incident.audioDurationMs)
        assertNull(incident.audioStoragePath)

        // Transition to RECORDING
        val recording = repository.updateActiveIncidentAudioStatus(
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_RECORDING,
            audioDurationMs = null
        ).getOrThrow()
        assertEquals(EmergencyIncidentEntity.AUDIO_STATUS_RECORDING, recording.audioRecordingStatus)
        assertNull(recording.audioDurationMs)
        assertNull(recording.audioStoragePath)

        // Transition to COMPLETED with duration
        val completed = repository.updateActiveIncidentAudioStatus(
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED,
            audioDurationMs = 2500L
        ).getOrThrow()
        assertEquals(EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED, completed.audioRecordingStatus)
        assertEquals(2500L, completed.audioDurationMs)
        assertNull(completed.audioStoragePath)
    }

    @Test
    fun resolutionAndSupabaseSync_tracksSentAndFailedDeliveryStatesAndAllowsSubsequentIncident() = runBlocking {
        val created = repository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_SHAKE,
            latitude = 19.0760,
            longitude = 72.8777,
            locationTimestamp = 1_700_000_100_000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 72,
            networkStatus = "ONLINE"
        ).getOrThrow()

        // Simulate failed Supabase sync
        fakeRemote.shouldFailUpsert = true
        val failedSync = repository.syncEmergencyIncidentToSupabase(created)
        assertTrue(failedSync.isFailure)

        val afterFail = repository.getEmergencyIncident(created.id)
        assertNotNull(afterFail)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_FAILED, afterFail?.deliveryStatus)
        assertTrue(afterFail?.lastDeliveryError?.contains("Simulated network outage") == true)

        // Recover network and resolve active incident
        fakeRemote.shouldFailUpsert = false
        val resolved = repository.resolveActiveEmergencyIncident(
            status = EmergencyIncidentEntity.STATUS_RESOLVED,
            endedAt = 1_700_000_200_000L
        ).getOrThrow()
        assertEquals(EmergencyIncidentEntity.STATUS_RESOLVED, resolved.status)
        assertEquals(1_700_000_200_000L, resolved.endedAt)
        assertNull(repository.getActiveEmergencyIncident())

        val syncResolved = repository.syncEmergencyIncidentToSupabase(resolved)
        assertTrue(syncResolved.isSuccess)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_SENT, syncResolved.getOrThrow().deliveryStatus)
        assertNull(syncResolved.getOrThrow().lastDeliveryError)
        assertEquals(EmergencyIncidentEntity.STATUS_RESOLVED, fakeRemote.remoteStore[created.id]?.status)

        // A new SOS after resolution must create a distinct new ACTIVE incident
        val nextIncident = repository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL
        ).getOrThrow()
        assertNotEquals(created.id, nextIncident.id)
        assertEquals(EmergencyIncidentEntity.STATUS_ACTIVE, nextIncident.status)
    }
}
