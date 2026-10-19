package com.example

import android.content.Context
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EmergencyIncidentPhase1Test {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var incidentDao: EmergencyIncidentDao
    private lateinit var prefs: PreferencesManager

    private val authenticatedUserUuid = "550e8400-e29b-41d4-a716-446655440000"
    private val otherUserUuid = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    private class FakeIncidentRemoteDataSource(
        var shouldFailUpsert: Boolean = false
    ) : EmergencyIncidentRemoteDataSource {
        val uploadedIncidents = mutableListOf<EmergencyIncidentDto>()

        override suspend fun upsertEmergencyIncident(dto: EmergencyIncidentDto) {
            if (shouldFailUpsert) {
                throw IllegalStateException("Simulated Supabase network failure")
            }
            uploadedIncidents.removeAll { it.id == dto.id }
            uploadedIncidents.add(dto)
        }

        override suspend fun fetchEmergencyIncidentsForUser(userId: String): List<EmergencyIncidentDto> {
            return uploadedIncidents.filter { it.userId == userId }
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        incidentDao = db.emergencyIncidentDao()
        prefs = PreferencesManager(context)
        prefs.supabaseUserId = authenticatedUserUuid
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testEntityDtoRoundTripAndColumnMapping() {
        val now = 1712000000000L
        val entity = EmergencyIncidentEntity(
            id = "123e4567-e89b-12d3-a456-426614174000",
            userId = authenticatedUserUuid,
            triggerSource = EmergencyIncidentEntity.TRIGGER_VOICE,
            status = EmergencyIncidentEntity.STATUS_ACTIVE,
            startedAt = now,
            endedAt = null,
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = now - 1000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 82,
            networkStatus = "ONLINE",
            medicalSharingEnabled = true,
            medicalSummary = "Blood: O+, Allergies: Penicillin",
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_RECORDING,
            audioDurationMs = 5000L,
            audioStoragePath = "/data/user/0/com.example/files/emergency_voice_clip.mp4",
            deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_PENDING,
            lastDeliveryError = null,
            createdAt = now,
            updatedAt = now
        )

        val dto = entity.toDto()
        val jsonStr = Json.encodeToString(dto)
        val jsonObj = Json.parseToJsonElement(jsonStr).jsonObject

        assertEquals(entity.id, jsonObj["id"]?.jsonPrimitive?.content)
        assertEquals(authenticatedUserUuid, jsonObj["user_id"]?.jsonPrimitive?.content)
        assertEquals("VOICE", jsonObj["trigger_source"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", jsonObj["status"]?.jsonPrimitive?.content)
        assertEquals(now.toString(), jsonObj["started_at"]?.jsonPrimitive?.content)
        assertEquals("LIVE", jsonObj["location_status"]?.jsonPrimitive?.content)
        assertEquals("82", jsonObj["battery_percent"]?.jsonPrimitive?.content)
        assertEquals("true", jsonObj["medical_sharing_enabled"]?.jsonPrimitive?.content)
        assertEquals("Blood: O+, Allergies: Penicillin", jsonObj["medical_summary"]?.jsonPrimitive?.content)
        assertEquals("RECORDING", jsonObj["audio_recording_status"]?.jsonPrimitive?.content)
        assertEquals("5000", jsonObj["audio_duration_ms"]?.jsonPrimitive?.content)
        assertEquals("PENDING", jsonObj["delivery_status"]?.jsonPrimitive?.content)

        val roundTripEntity = dto.toEntity()
        assertEquals(entity, roundTripEntity)
    }

    @Test
    fun testDaoOperationsScopedByUserId() = runBlocking {
        val incidentUser1 = EmergencyIncidentEntity(
            id = "a1111111-e89b-12d3-a456-426614174000",
            userId = authenticatedUserUuid,
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            status = EmergencyIncidentEntity.STATUS_ACTIVE,
            startedAt = 1000L
        )
        val incidentUser2 = EmergencyIncidentEntity(
            id = "b2222222-e89b-12d3-a456-426614174000",
            userId = otherUserUuid,
            triggerSource = EmergencyIncidentEntity.TRIGGER_SHAKE,
            status = EmergencyIncidentEntity.STATUS_ACTIVE,
            startedAt = 2000L
        )

        incidentDao.insertIncident(incidentUser1)
        incidentDao.insertIncident(incidentUser2)

        val user1List = incidentDao.observeIncidents(authenticatedUserUuid).first()
        assertEquals(1, user1List.size)
        assertEquals(incidentUser1.id, user1List.first().id)

        val activeUser1 = incidentDao.observeActiveIncident(authenticatedUserUuid).first()
        assertNotNull(activeUser1)
        assertEquals(incidentUser1.id, activeUser1!!.id)

        // Update location
        incidentDao.updateLocation(
            id = incidentUser1.id,
            userId = authenticatedUserUuid,
            latitude = 28.6139,
            longitude = 77.2090,
            locationTimestamp = 3000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            updatedAt = 3000L
        )
        val afterLocation = incidentDao.getIncidentById(incidentUser1.id)!!
        assertEquals(28.6139, afterLocation.latitude!!, 0.0001)
        assertEquals(77.2090, afterLocation.longitude!!, 0.0001)
        assertEquals(EmergencyIncidentEntity.LOCATION_STATUS_LIVE, afterLocation.locationStatus)

        // Update audio status
        incidentDao.updateAudioStatus(
            id = incidentUser1.id,
            userId = authenticatedUserUuid,
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED,
            audioDurationMs = 12000L,
            audioStoragePath = "/local/audio.mp4",
            updatedAt = 4000L
        )
        val afterAudio = incidentDao.getIncidentById(incidentUser1.id)!!
        assertEquals(EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED, afterAudio.audioRecordingStatus)
        assertEquals(12000L, afterAudio.audioDurationMs)
        assertEquals("/local/audio.mp4", afterAudio.audioStoragePath)

        // Mark resolved
        incidentDao.markResolved(
            id = incidentUser1.id,
            userId = authenticatedUserUuid,
            status = EmergencyIncidentEntity.STATUS_RESOLVED,
            endedAt = 5000L,
            updatedAt = 5000L
        )
        val afterResolved = incidentDao.getIncidentById(incidentUser1.id)!!
        assertEquals(EmergencyIncidentEntity.STATUS_RESOLVED, afterResolved.status)
        assertEquals(5000L, afterResolved.endedAt)
        assertNull(incidentDao.observeActiveIncident(authenticatedUserUuid).first())

        // Delete incident
        incidentDao.deleteIncident(incidentUser1.id, authenticatedUserUuid)
        assertNull(incidentDao.getIncidentById(incidentUser1.id))
        assertNotNull(incidentDao.getIncidentById(incidentUser2.id))
    }

    @Test
    fun testRepositoryRejectsInvalidUserIdsAndSyncsValidIncidents() = runBlocking {
        val fakeRemote = FakeIncidentRemoteDataSource()
        val repository = SafetyRepository(
            contactDao = db.contactDao(),
            sosLogDao = db.sosLogDao(),
            preferencesManager = prefs,
            emergencyIncidentDao = incidentDao,
            incidentRemoteDataSource = fakeRemote
        )

        // Rejects email, phone number, default_user, placeholder UUID
        val invalidIdentities = listOf(
            "user@gmail.com",
            "+919876543210",
            "default_user",
            "00000000-0000-0000-0000-000000000000",
            ""
        )
        for (invalidId in invalidIdentities) {
            val res = repository.createEmergencyIncident(
                triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
                explicitUserId = invalidId
            )
            assertTrue("Expected failure for userId='$invalidId'", res.isFailure)
        }

        // Valid creation with authenticated Supabase user UUID
        val createResult = repository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_BLUETOOTH,
            latitude = 13.0827,
            longitude = 80.2707,
            locationTimestamp = 10000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 91,
            networkStatus = "ONLINE",
            medicalSharingEnabled = false,
            medicalSummary = "Should be omitted when medicalSharingEnabled=false",
            explicitUserId = authenticatedUserUuid
        )
        assertTrue(createResult.isSuccess)
        val created = createResult.getOrNull()!!
        assertEquals(authenticatedUserUuid, created.userId)
        assertNull(created.medicalSummary) // Omitted when medicalSharingEnabled is false
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_PENDING, created.deliveryStatus)

        // Failed cloud sync records FAILED status and error
        fakeRemote.shouldFailUpsert = true
        val failedSync = repository.syncEmergencyIncidentToSupabase(created, authenticatedUserUuid)
        assertTrue(failedSync.isFailure)
        val afterFail = repository.getEmergencyIncident(created.id, authenticatedUserUuid)!!
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_FAILED, afterFail.deliveryStatus)
        assertNotNull(afterFail.lastDeliveryError)

        // Pending sync retries and succeeds when online
        fakeRemote.shouldFailUpsert = false
        val syncedCount = repository.syncPendingEmergencyIncidents(authenticatedUserUuid)
        assertEquals(1, syncedCount)
        assertEquals(1, fakeRemote.uploadedIncidents.size)

        val afterSuccess = repository.getEmergencyIncident(created.id, authenticatedUserUuid)!!
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_SENT, afterSuccess.deliveryStatus)
        assertNull(afterSuccess.lastDeliveryError)
    }
}
