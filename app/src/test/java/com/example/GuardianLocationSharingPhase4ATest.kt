package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.ContactEntity
import com.example.data.GuardianChatMessageEntity
import com.example.data.PreferencesManager
import com.example.data.supabase.GuardianChatMessageDto
import com.example.domain.GuardianChatRemoteDataSource
import com.example.domain.SafetyRepository
import com.example.viewmodel.SafetyViewModel
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardianLocationSharingPhase4ATest {

    private lateinit var context: Application
    private lateinit var database: AppDatabase
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var fakeRemote: FakeLocationChatRemoteDataSource
    private lateinit var repository: SafetyRepository

    private val testUserId = "11111111-2222-3333-4444-555555555555"
    private val testGuardianContactId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val testPairedGuardianUserId = "99999999-8888-7777-6666-555555555555"

    private val pairedGuardianContact = ContactEntity(
        id = 1,
        contactId = testGuardianContactId,
        userId = testUserId,
        guardianUserId = testPairedGuardianUserId,
        name = "Priya Guardian",
        phone = "+919876543210",
        relation = "Sister",
        priority = "1",
        email = "priya.guardian@gmail.com"
    )

    class FakeLocationChatRemoteDataSource : GuardianChatRemoteDataSource {
        val upsertedMessages = CopyOnWriteArrayList<GuardianChatMessageDto>()
        val remoteMessages = CopyOnWriteArrayList<GuardianChatMessageDto>()

        override suspend fun upsertChatMessage(dto: GuardianChatMessageDto) {
            upsertedMessages.add(dto)
            remoteMessages.removeAll { it.id == dto.id }
            remoteMessages.add(dto)
        }

        override suspend fun fetchChatMessages(
            userId: String,
            guardianId: String
        ): List<GuardianChatMessageDto> {
            return remoteMessages.filter { it.userId == userId && it.guardianId == guardianId }
        }

        override suspend fun subscribeToGuardianChat(
            channelName: String,
            onInsert: suspend (GuardianChatMessageDto) -> Unit
        ): suspend () -> Unit {
            return {}
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesManager = PreferencesManager(context).apply {
            clearProfile()
            userName = "Aarav Sharma"
            supabaseUserId = testUserId
            isLoggedIn = true
        }
        fakeRemote = FakeLocationChatRemoteDataSource()
        repository = SafetyRepository(
            contactDao = database.contactDao(),
            sosLogDao = database.sosLogDao(),
            preferencesManager = preferencesManager,
            guardianChatDao = database.guardianChatDao(),
            emergencyIncidentDao = database.emergencyIncidentDao(),
            chatRemoteDataSource = fakeRemote
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun textMessages_remainDefaultTextType_withNullLocationFields() = runBlocking {
        val sendResult = repository.sendGuardianChatMessage(
            contact = pairedGuardianContact,
            content = "Reached office safely",
            isOnline = true,
            explicitUserId = testUserId
        )
        assertTrue(sendResult.isSuccess)
        val saved = sendResult.getOrThrow()
        assertEquals(GuardianChatMessageEntity.TYPE_TEXT, saved.messageType)
        assertFalse(saved.isLocationMessage)
        assertNull(saved.latitude)
        assertNull(saved.longitude)
        assertNull(saved.locationTimestamp)
        assertNull(saved.locationStatus)

        val remoteDto = fakeRemote.upsertedMessages.single()
        assertEquals(GuardianChatMessageEntity.TYPE_TEXT, remoteDto.messageType)
        assertNull(remoteDto.latitude)
        assertNull(remoteDto.longitude)
    }

    @Test
    fun sendGuardianLocationChatMessage_persistsInRoomAndSyncsToSupabase() = runBlocking {
        val nowMs = 1_728_000_000_000L
        val sendResult = repository.sendGuardianLocationChatMessage(
            contact = pairedGuardianContact,
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = nowMs,
            locationStatus = GuardianChatMessageEntity.LOCATION_STATUS_LIVE,
            isOnline = true,
            explicitUserId = testUserId
        )

        assertTrue(sendResult.isSuccess)
        val saved = sendResult.getOrThrow()
        assertEquals(GuardianChatMessageEntity.TYPE_LOCATION, saved.messageType)
        assertTrue(saved.isLocationMessage)
        assertEquals(12.9716, saved.latitude ?: 0.0, 0.00001)
        assertEquals(77.5946, saved.longitude ?: 0.0, 0.00001)
        assertEquals(nowMs, saved.locationTimestamp)
        assertEquals(GuardianChatMessageEntity.LOCATION_STATUS_LIVE, saved.locationStatus)
        assertEquals(testUserId, saved.userId)
        assertEquals(testGuardianContactId, saved.guardianId)
        assertEquals(testPairedGuardianUserId, saved.recipientId)
        assertEquals(GuardianChatMessageEntity.STATUS_SENT, saved.status)
        assertTrue(saved.isSynced)

        val remoteDto = fakeRemote.upsertedMessages.single()
        assertEquals("LOCATION", remoteDto.messageType)
        assertEquals(12.9716, remoteDto.latitude ?: 0.0, 0.00001)
        assertEquals(77.5946, remoteDto.longitude ?: 0.0, 0.00001)
        assertEquals(nowMs, remoteDto.locationTimestamp)
        assertEquals("LIVE", remoteDto.locationStatus)
    }

    @Test
    fun sendGuardianLocationChatMessage_rejectsInvalidCoordinates_withoutSavingOrSending() = runBlocking {
        val zeroResult = repository.sendGuardianLocationChatMessage(
            contact = pairedGuardianContact,
            latitude = 0.0,
            longitude = 0.0,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = "LIVE",
            isOnline = true,
            explicitUserId = testUserId
        )
        assertTrue(zeroResult.isFailure)

        val nullResult = repository.sendGuardianLocationChatMessage(
            contact = pairedGuardianContact,
            latitude = null,
            longitude = 77.5946,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = "LIVE",
            isOnline = true,
            explicitUserId = testUserId
        )
        assertTrue(nullResult.isFailure)

        val nanResult = repository.sendGuardianLocationChatMessage(
            contact = pairedGuardianContact,
            latitude = Double.NaN,
            longitude = 77.5946,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = "LIVE",
            isOnline = true,
            explicitUserId = testUserId
        )
        assertTrue(nanResult.isFailure)

        val roomMessages = database.guardianChatDao()
            .getMessagesForGuardianOnce(testUserId, testGuardianContactId)
        assertTrue(roomMessages.isEmpty())
        assertTrue(fakeRemote.upsertedMessages.isEmpty())
    }


    @Test
    fun viewModel_shareLocationAndOpenInMap_worksAndDoesNotTriggerSos() = runBlocking {
        preferencesManager.hasAcquiredRealGpsFix = false
        preferencesManager.lastLatitude = 0f
        preferencesManager.lastLongitude = 0f
        preferencesManager.lastLocationTimestamp = 0L

        val viewModel = SafetyViewModel(context)
        viewModel.openGuardianChat(pairedGuardianContact)

        // 1. When no valid GPS fix is available (0.0, 0.0), sharing fails with clear error and no SOS
        var invalidResult: Result<GuardianChatMessageEntity>? = null
        viewModel.shareCurrentLocationInGuardianChat { invalidResult = it }
        assertNotNull(invalidResult)
        assertTrue(invalidResult!!.isFailure)
        assertNotNull(viewModel.guardianLocationShareStatus.value)
        assertTrue(viewModel.guardianLocationShareStatus.value!!.contains("Unable to share location"))
        assertFalse(viewModel.isSosTriggered.value)
        assertNull(viewModel.activeEmergencyIncident.value)

        // 2. When valid GPS fix is acquired via existing location subsystem, sharing succeeds
        viewModel.updateLocation(19.0760, 72.8777)
        val locationMsg = GuardianChatMessageEntity(
            id = "loc_msg_vm",
            conversationId = GuardianChatMessageEntity.buildConversationId(testUserId, testGuardianContactId),
            userId = testUserId,
            guardianId = testGuardianContactId,
            senderId = testUserId,
            senderName = "Aarav Sharma",
            recipientId = testPairedGuardianUserId,
            content = "Shared current location (19.07600, 72.87770)",
            status = GuardianChatMessageEntity.STATUS_SENT,
            createdAt = System.currentTimeMillis(),
            isSynced = true,
            messageType = GuardianChatMessageEntity.TYPE_LOCATION,
            latitude = 19.0760,
            longitude = 72.8777,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = GuardianChatMessageEntity.LOCATION_STATUS_LIVE
        )

        val opened = viewModel.openSharedLocationInMap(locationMsg)
        assertTrue(opened)
        val mapTarget = viewModel.sharedMapLocationTarget.value
        assertNotNull(mapTarget)
        assertEquals(19.0760, mapTarget!!.latitude, 0.00001)
        assertEquals(72.8777, mapTarget.longitude, 0.00001)
        assertEquals("LIVE", mapTarget.locationStatus)
        assertFalse(viewModel.isSosTriggered.value)
    }
}
