package com.example

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.ContactEntity
import com.example.data.EmergencyIncidentEntity
import com.example.data.GuardianPairingRequestEntity
import com.example.data.PreferencesManager
import com.example.data.supabase.EmergencyIncidentDto
import com.example.data.supabase.EmergencyIncidentRecipientDto
import com.example.data.supabase.GuardianChatMessageDto
import com.example.domain.AizaEmergencyNotificationManager
import com.example.domain.EmergencyIncidentRemoteDataSource
import com.example.domain.GuardianChatRemoteDataSource
import com.example.domain.SafetyRepository
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SafetyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyNotificationPhase4BTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var notificationManager: NotificationManager
    private lateinit var senderDb: AppDatabase
    private lateinit var guardianDb: AppDatabase
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var sharedRemote: FakePhase4BIncidentRemoteDataSource
    private lateinit var fakeChatRemote: FakePhase4BChatRemoteDataSource
    private lateinit var guardianNotificationManager: AizaEmergencyNotificationManager
    private lateinit var senderRepository: SafetyRepository
    private lateinit var guardianRepository: SafetyRepository

    private val validSenderUid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val validGuardianUid = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
    private val unauthorizedUid = "d4e5f6a7-b8c9-4d0e-9f2a-3b4c5d6e7f80"

    private class FakePhase4BIncidentRemoteDataSource : EmergencyIncidentRemoteDataSource {
        val remoteIncidents = linkedMapOf<String, EmergencyIncidentDto>()
        val remoteRecipients = linkedMapOf<String, EmergencyIncidentRecipientDto>()
        val pairedRelationships = linkedSetOf<Pair<String, String>>()
        val senderNames = linkedMapOf<String, String>()

        private val realtimeListeners = linkedMapOf<String, suspend (EmergencyIncidentRecipientDto) -> Unit>()

        fun registerPairing(senderUid: String, guardianUid: String, senderName: String? = null) {
            pairedRelationships.add(senderUid.trim().lowercase() to guardianUid.trim().lowercase())
            if (!senderName.isNullOrBlank()) {
                senderNames[senderUid.trim().lowercase()] = senderName.trim()
            }
        }

        override suspend fun upsertEmergencyIncident(dto: EmergencyIncidentDto) {
            remoteIncidents[dto.id.trim()] = dto
        }

        override suspend fun fetchEmergencyIncidentsForUser(userId: String): List<EmergencyIncidentDto> {
            return remoteIncidents.values.filter {
                it.userId.equals(userId.trim(), ignoreCase = true)
            }
        }

        override suspend fun fetchEmergencyIncidentByIdForGuardian(
            incidentId: String,
            guardianUserId: String
        ): EmergencyIncidentDto? {
            val incident = remoteIncidents[incidentId.trim()] ?: return null
            val isPaired = isGuardianPairedWithSender(incident.userId, guardianUserId)
            return if (isPaired) incident else null
        }

        override suspend fun upsertIncidentRecipient(dto: EmergencyIncidentRecipientDto) {
            val key = "${dto.incidentId.trim().lowercase()}|${dto.guardianUserId.trim().lowercase()}"
            remoteRecipients[key] = dto
            realtimeListeners[dto.guardianUserId.trim().lowercase()]?.invoke(dto)
        }

        override suspend fun fetchRecipientRowsForIncident(
            incidentId: String,
            senderUserId: String
        ): List<EmergencyIncidentRecipientDto> {
            return remoteRecipients.values.filter {
                it.incidentId.equals(incidentId.trim(), ignoreCase = true) &&
                    it.senderUserId.equals(senderUserId.trim(), ignoreCase = true)
            }
        }

        override suspend fun fetchRecipientRowsForGuardian(
            guardianUserId: String
        ): List<EmergencyIncidentRecipientDto> {
            return remoteRecipients.values.filter {
                it.guardianUserId.equals(guardianUserId.trim(), ignoreCase = true)
            }
        }

        override suspend fun acknowledgeRecipientDelivery(
            recipientId: String,
            incidentId: String,
            guardianUserId: String,
            deliveredAt: Long,
            updatedAt: Long
        ) {
            val key = "${incidentId.trim().lowercase()}|${guardianUserId.trim().lowercase()}"
            val existing = remoteRecipients[key]
            if (existing != null) {
                remoteRecipients[key] = existing.copy(
                    deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
                    deliveredAt = deliveredAt,
                    lastError = null,
                    updatedAt = updatedAt
                )
            }
        }

        override suspend fun isGuardianPairedWithSender(
            senderUserId: String,
            guardianUserId: String
        ): Boolean {
            return pairedRelationships.contains(
                senderUserId.trim().lowercase() to guardianUserId.trim().lowercase()
            )
        }

        override suspend fun fetchPairedGuardianUserIdsForSender(
            senderUserId: String
        ): List<String> {
            val cleanSender = senderUserId.trim().lowercase()
            return pairedRelationships
                .filter { it.first == cleanSender }
                .map { it.second }
        }

        override suspend fun subscribeToGuardianIncidentRecipients(
            channelName: String,
            guardianUserId: String,
            onRecipientEvent: suspend (EmergencyIncidentRecipientDto) -> Unit
        ): suspend () -> Unit {
            val key = guardianUserId.trim().lowercase()
            realtimeListeners[key] = onRecipientEvent
            return { realtimeListeners.remove(key) }
        }

        override suspend fun fetchSenderDisplayName(senderUserId: String): String? {
            return senderNames[senderUserId.trim().lowercase()]
        }
    }

    private class FakePhase4BChatRemoteDataSource : GuardianChatRemoteDataSource {
        val messages = mutableListOf<GuardianChatMessageDto>()
        override suspend fun upsertChatMessage(dto: GuardianChatMessageDto) {
            messages.removeAll { it.id == dto.id }
            messages.add(dto)
        }

        override suspend fun fetchChatMessages(userId: String, guardianId: String): List<GuardianChatMessageDto> {
            return messages.filter { it.userId == userId && it.guardianId == guardianId }
        }

        override suspend fun subscribeToGuardianChat(
            channelName: String,
            onInsert: suspend (GuardianChatMessageDto) -> Unit
        ): suspend () -> Unit = {}
    }

    @Before
    fun setUp() {
        SafetyRepository.clearKnownSenderNames()
        application = ApplicationProvider.getApplicationContext()
        notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        notificationManager.notificationChannels.toList().forEach { ch ->
            notificationManager.deleteNotificationChannel(ch.id)
        }

        preferencesManager = PreferencesManager(application).apply {
            clearProfile()
            isLoggedIn = true
            supabaseUserId = validSenderUid
            userName = "Tam"
            userEmail = "tam.aiza@gmail.com"
        }

        senderDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        guardianDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        sharedRemote = FakePhase4BIncidentRemoteDataSource()
        fakeChatRemote = FakePhase4BChatRemoteDataSource()
        guardianNotificationManager = AizaEmergencyNotificationManager(application, notificationManager)

        senderRepository = SafetyRepository(
            contactDao = senderDb.contactDao(),
            sosLogDao = senderDb.sosLogDao(),
            preferencesManager = preferencesManager,
            guardianChatDao = senderDb.guardianChatDao(),
            chatRemoteDataSource = fakeChatRemote,
            guardianPairingRequestDao = senderDb.guardianPairingRequestDao(),
            emergencyIncidentDao = senderDb.emergencyIncidentDao(),
            incidentRemoteDataSource = sharedRemote,
            emergencyNotificationManager = AizaEmergencyNotificationManager(application, notificationManager)
        )

        guardianRepository = SafetyRepository(
            contactDao = guardianDb.contactDao(),
            sosLogDao = guardianDb.sosLogDao(),
            preferencesManager = preferencesManager,
            guardianChatDao = guardianDb.guardianChatDao(),
            chatRemoteDataSource = fakeChatRemote,
            guardianPairingRequestDao = guardianDb.guardianPairingRequestDao(),
            emergencyIncidentDao = guardianDb.emergencyIncidentDao(),
            incidentRemoteDataSource = sharedRemote,
            emergencyNotificationManager = guardianNotificationManager
        )
    }

    @After
    fun tearDown() {
        SafetyRepository.clearKnownSenderNames()
        notificationManager.cancelAll()
        senderDb.close()
        guardianDb.close()
    }

    private suspend fun seedPairedGuardian(senderName: String = "Tam") {
        senderRepository.insertContact(
            ContactEntity(
                userId = validSenderUid,
                guardianUserId = validGuardianUid,
                name = "Guardian One",
                phone = "+919876543210",
                relation = "Sister",
                priority = "1",
                isVerified = true
            )
        )
        guardianDb.guardianPairingRequestDao().insertRequest(
            GuardianPairingRequestEntity(
                requesterUserId = validSenderUid,
                guardianEmail = "guardian.one@gmail.com",
                emergencyContactId = "11111111-2222-4333-8444-555555555555",
                guardianUserId = validGuardianUid,
                status = GuardianPairingRequestEntity.STATUS_ACCEPTED,
                requesterName = senderName,
                contactName = "Guardian One",
                relationship = "Sister"
            )
        )
        sharedRemote.registerPairing(validSenderUid, validGuardianUid, senderName)
    }

    @Test
    fun dedicatedEmergencyNotificationChannel_isCreatedWithHighImportanceAndVersionedFallbackWhenUnsuitable() {
        // Pre-create an unrelated notification channel to verify it is NOT modified
        val unrelatedChannel = NotificationChannel(
            "aegis_bg_sync",
            "AIZA Cloud Auto-Sync",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(unrelatedChannel)

        val channelId = guardianNotificationManager.ensureEmergencyNotificationChannel()
        assertEquals(AizaEmergencyNotificationManager.EMERGENCY_CHANNEL_ID, channelId)

        val createdChannel = notificationManager.getNotificationChannel(AizaEmergencyNotificationManager.EMERGENCY_CHANNEL_ID)
        assertNotNull(createdChannel)
        assertEquals("AIZA Emergency Alerts", createdChannel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, createdChannel.importance)
        assertTrue(createdChannel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, createdChannel.lockscreenVisibility)
        assertFalse(createdChannel.canBypassDnd())

        // Unrelated channel remains intact
        val preservedUnrelated = notificationManager.getNotificationChannel("aegis_bg_sync")
        assertNotNull(preservedUnrelated)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, preservedUnrelated.importance)

        // Simulate a previously created unsuitable channel with IMPORTANCE_LOW
        notificationManager.deleteNotificationChannel(AizaEmergencyNotificationManager.EMERGENCY_CHANNEL_ID)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                AizaEmergencyNotificationManager.EMERGENCY_CHANNEL_ID,
                "Old Unsuitable Channel",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val resolvedVersionedId = guardianNotificationManager.ensureEmergencyNotificationChannel()
        assertEquals(AizaEmergencyNotificationManager.EMERGENCY_CHANNEL_VERSIONED_ID, resolvedVersionedId)
        val versionedChannel = notificationManager.getNotificationChannel(resolvedVersionedId)
        assertNotNull(versionedChannel)
        assertEquals("AIZA Emergency Alerts", versionedChannel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, versionedChannel.importance)
        assertTrue(versionedChannel.shouldVibrate())
    }

    @Test
    fun guardianReceivingActiveEmergencyIncident_showsHighPriorityEmergencyNotificationWithoutMedicalLeak() = runBlocking {
        seedPairedGuardian(senderName = "Tam")

        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            latitude = 28.6139,
            longitude = 77.2090,
            locationTimestamp = 1712000100000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 84,
            networkStatus = "ONLINE",
            medicalSharingEnabled = true,
            medicalSummary = "Blood: O+, Allergies: Penicillin, Conditions: Asthma",
            explicitUserId = validSenderUid
        ).getOrThrow()

        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        // Guardian syncs received emergency incidents
        val syncRes = guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)
        assertTrue(syncRes.isSuccess)

        val expectedNotifId = AizaEmergencyNotificationManager.notificationIdForIncident(incident.id)
        val postedNotification = shadowOf(notificationManager).getNotification(expectedNotifId)
        assertNotNull("Expected active emergency notification to be posted", postedNotification)

        assertEquals(AizaEmergencyNotificationManager.EMERGENCY_CHANNEL_ID, postedNotification.channelId)
        assertEquals(
            "AIZA EMERGENCY",
            postedNotification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )

        val text = postedNotification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(text.contains("Tam has an active emergency."))
        assertTrue(text.contains("Location available"))

        // Verify sensitive medical details are NOT exposed in the notification preview
        assertFalse(text.contains("Penicillin", ignoreCase = true))
        assertFalse(text.contains("Asthma", ignoreCase = true))
        assertFalse(text.contains("Blood", ignoreCase = true))

        // Verify high-priority, ongoing, public lockscreen visibility, and alarm category
        assertEquals(Notification.CATEGORY_ALARM, postedNotification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, postedNotification.visibility)
        assertTrue((postedNotification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test
    fun duplicateNotificationsArePreventedAcrossRepeatedSyncs_andNotificationRemovedOnResolution() = runBlocking {
        seedPairedGuardian(senderName = "Tam")

        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_SHAKE,
            latitude = 19.0760,
            longitude = 72.8777,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            explicitUserId = validSenderUid
        ).getOrThrow()
        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        // First guardian sync posts the notification once
        guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)
        assertEquals(1, guardianNotificationManager.getNotificationPostCount(incident.id))
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)

        // Second & third guardian syncs for the same unchanged active incident do NOT post duplicates
        guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)
        guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)
        assertEquals(1, guardianNotificationManager.getNotificationPostCount(incident.id))
        assertEquals(1, shadowOf(notificationManager).allNotifications.size)

        // Sender resolves the active emergency incident and syncs to Supabase
        val resolved = senderRepository.resolveEmergencyIncident(
            id = incident.id,
            status = EmergencyIncidentEntity.STATUS_RESOLVED,
            explicitUserId = validSenderUid
        ).getOrThrow()
        senderRepository.syncEmergencyIncidentToSupabase(resolved, validSenderUid)

        // Guardian syncs the resolved incident -> active emergency notification is automatically removed
        guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)
        val expectedNotifId = AizaEmergencyNotificationManager.notificationIdForIncident(incident.id)
        assertNull(shadowOf(notificationManager).getNotification(expectedNotifId))
        assertFalse(guardianNotificationManager.isIncidentCurrentlyNotified(incident.id, validGuardianUid))
    }

    @Test
    fun tappingEmergencyNotification_carriesExactIncidentIdAndOpensEmergencyIncidentDetailsPlaceholder() {
        runBlocking {
            seedPairedGuardian(senderName = "Tam")

            val incident = senderRepository.createEmergencyIncident(
                triggerSource = EmergencyIncidentEntity.TRIGGER_VOICE,
                latitude = 12.9716,
                longitude = 77.5946,
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                explicitUserId = validSenderUid
            ).getOrThrow()
            senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)
            guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)

            val expectedNotifId = AizaEmergencyNotificationManager.notificationIdForIncident(incident.id)
            val postedNotification = shadowOf(notificationManager).getNotification(expectedNotifId)
            assertNotNull(postedNotification)
            assertNotNull(postedNotification.contentIntent)

            val tapIntent = shadowOf(postedNotification.contentIntent).savedIntent
            val extractedId = AizaEmergencyNotificationManager.extractIncidentIdFromIntent(tapIntent)
            assertEquals(incident.id, extractedId)

            // Verify generic/random/non-UUID intent values are rejected
            val invalidIntent = Intent().apply {
                putExtra(AizaEmergencyNotificationManager.EXTRA_EMERGENCY_INCIDENT_ID, "generic_incident")
            }
            assertNull(AizaEmergencyNotificationManager.extractIncidentIdFromIntent(invalidIntent))

            // Verify ViewModel + MainNavigationScaffold route directly to AIZA -> Emergency Incident -> incidentId
            preferencesManager.supabaseUserId = validGuardianUid
            val viewModel = SafetyViewModel(application)
            assertTrue(viewModel.handleEmergencyNotificationIntent(tapIntent))
            assertEquals(incident.id, viewModel.selectedEmergencyIncidentId.value)

            composeTestRule.setContent {
                MyApplicationTheme(darkTheme = false, accentKey = "magenta") {
                    MainNavigationScaffold(viewModel = viewModel)
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("emergency_incident_details_placeholder").assertIsDisplayed()
            composeTestRule.onNodeWithText("Incident ID: ${incident.id}").assertIsDisplayed()
        }
    }

    @Test
    fun normalChatAndLocationMessages_andUnauthorizedUsers_neverTriggerEmergencyNotifications() = runBlocking {
        seedPairedGuardian(senderName = "Tam")
        notificationManager.cancelAll()

        // 1. Normal text chat message -> no emergency notification
        val pairedContact = ContactEntity(
            contactId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            userId = validSenderUid,
            guardianUserId = validGuardianUid,
            name = "Guardian One",
            phone = "+919876543210",
            relation = "Sister"
        )
        senderRepository.sendGuardianChatMessage(
            contact = pairedContact,
            content = "Checking in normally",
            isOnline = true,
            explicitUserId = validSenderUid
        )
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())

        // 2. Normal location sharing message -> no emergency notification
        senderRepository.sendGuardianLocationChatMessage(
            contact = pairedContact,
            latitude = 28.6139,
            longitude = 77.2090,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            isOnline = true,
            explicitUserId = validSenderUid
        )
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())

        // 3. Unauthorized user syncing -> no emergency notification
        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            explicitUserId = validSenderUid
        ).getOrThrow()
        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        guardianRepository.syncReceivedEmergencyIncidents(unauthorizedUid)
        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }
}
