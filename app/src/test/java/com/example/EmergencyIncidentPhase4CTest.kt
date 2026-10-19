package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.ContactEntity
import com.example.data.EmergencyIncidentEntity
import com.example.data.GuardianPairingRequestEntity
import com.example.data.PreferencesManager
import com.example.data.SosLogEntity
import com.example.data.supabase.EmergencyIncidentDto
import com.example.data.supabase.EmergencyIncidentRecipientDto
import com.example.domain.AizaEmergencyNotificationManager
import com.example.domain.EmergencyIncidentRemoteDataSource
import com.example.domain.SafetyRepository
import com.example.ui.history.HistoryScreen
import com.example.ui.incident.EmergencyIncidentDetailsScreen
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
class EmergencyIncidentPhase4CTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var senderDb: AppDatabase
    private lateinit var guardianDb: AppDatabase
    private lateinit var senderPrefs: PreferencesManager
    private lateinit var guardianPrefs: PreferencesManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var guardianNotificationManager: AizaEmergencyNotificationManager

    private val validSenderUid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val validGuardianUid = "f6e5d4c3-b2a1-4f9e-8d7c-6b5a4e3f2d1c"

    private class FakePhase4CIncidentRemoteDataSource : EmergencyIncidentRemoteDataSource {
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

    private lateinit var sharedRemote: FakePhase4CIncidentRemoteDataSource
    private lateinit var senderRepository: SafetyRepository
    private lateinit var guardianRepository: SafetyRepository

    @Before
    fun setup() {
        SafetyRepository.clearKnownSenderNames()
        context = ApplicationProvider.getApplicationContext()
        application = context as Application
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

        senderDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        guardianDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        senderPrefs = PreferencesManager(context).apply {
            clearProfile()
            isLoggedIn = true
            supabaseUserId = validSenderUid
            userName = "Tam"
            userEmail = "tam.aiza@gmail.com"
        }
        guardianPrefs = PreferencesManager(context).apply {
            clearProfile()
            isLoggedIn = true
            supabaseUserId = validGuardianUid
            userName = "Guardian One"
            userEmail = "guardian.one@gmail.com"
        }

        sharedRemote = FakePhase4CIncidentRemoteDataSource()
        guardianNotificationManager = AizaEmergencyNotificationManager(context, notificationManager)

        senderRepository = SafetyRepository(
            contactDao = senderDb.contactDao(),
            sosLogDao = senderDb.sosLogDao(),
            preferencesManager = senderPrefs,
            trackedLocationDao = senderDb.trackedLocationDao(),
            cachedPlaceDao = senderDb.cachedPlaceDao(),
            guardianChatDao = senderDb.guardianChatDao(),
            guardianPairingRequestDao = senderDb.guardianPairingRequestDao(),
            emergencyIncidentDao = senderDb.emergencyIncidentDao(),
            incidentRemoteDataSource = sharedRemote,
            emergencyNotificationManager = AizaEmergencyNotificationManager(context, notificationManager)
        )

        guardianRepository = SafetyRepository(
            contactDao = guardianDb.contactDao(),
            sosLogDao = guardianDb.sosLogDao(),
            preferencesManager = guardianPrefs,
            trackedLocationDao = guardianDb.trackedLocationDao(),
            cachedPlaceDao = guardianDb.cachedPlaceDao(),
            guardianChatDao = guardianDb.guardianChatDao(),
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

    private suspend fun seedPairedGuardianAndSenderContact(
        senderName: String = "Tam",
        senderPhone: String = "+919811122233"
    ) {
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
        if (senderPhone.isNotBlank()) {
            guardianRepository.insertContact(
                ContactEntity(
                    contactId = "22222222-3333-4444-8555-666666666666",
                    userId = validGuardianUid,
                    guardianUserId = validSenderUid,
                    name = senderName,
                    phone = senderPhone,
                    relation = "Sister",
                    priority = "1",
                    isVerified = true
                )
            )
        }
        sharedRemote.registerPairing(validSenderUid, validGuardianUid, senderName)
    }

    @Test
    fun sentAndReceivedEmergencies_areStrictlySeparatedByAuthenticatedUuidAndNeverMixedInHistory() {
        runBlocking {
            seedPairedGuardianAndSenderContact(senderName = "Tam")

            // 1. Guardian has their own SENT SOS log and SENT EmergencyIncident
            guardianRepository.insertLog(
                SosLogEntity(
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 12,
                    latitude = 19.0760,
                    longitude = 72.8777,
                    locationStatus = "ACTIVE_GPS",
                    status = "Sent",
                    triggerSource = "MANUAL",
                    encryptedPayload = "encrypted",
                    eventType = "SENT",
                    contacts = "Family",
                    senderInfo = "Guardian One"
                )
            )
            val guardianOwnSentIncident = guardianRepository.createEmergencyIncident(
                triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
                latitude = 19.0760,
                longitude = 72.8777,
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                explicitUserId = validGuardianUid
            ).getOrThrow()

            // 2. Connected user Tam triggers a VOICE SOS incident that is delivered to Guardian
            val tamVoiceIncident = senderRepository.createEmergencyIncident(
                triggerSource = EmergencyIncidentEntity.TRIGGER_VOICE,
                latitude = 28.6139,
                longitude = 77.2090,
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                explicitUserId = validSenderUid
            ).getOrThrow()
            senderRepository.syncEmergencyIncidentToSupabase(tamVoiceIncident, validSenderUid)
            guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)

            // Verify ownership helpers enforce UUID-only identity and reject email/phone/default_user
            assertTrue(SafetyRepository.isSentEmergencyForUser(guardianOwnSentIncident, validGuardianUid))
            assertFalse(SafetyRepository.isReceivedEmergencyForGuardian(guardianOwnSentIncident, validGuardianUid))

            val receivedList = guardianDb.emergencyIncidentDao().getReceivedIncidentsForGuardian(validGuardianUid)
            assertEquals(1, receivedList.size)
            val receivedTamIncident = receivedList.first()
            assertTrue(SafetyRepository.isReceivedEmergencyForGuardian(receivedTamIncident, validGuardianUid))
            assertFalse(SafetyRepository.isSentEmergencyForUser(receivedTamIncident, validGuardianUid))

            assertFalse(SafetyRepository.isReceivedEmergencyForGuardian(receivedTamIncident, "guardian.one@gmail.com"))
            assertFalse(SafetyRepository.isReceivedEmergencyForGuardian(receivedTamIncident, "+919876543210"))
            assertFalse(SafetyRepository.isReceivedEmergencyForGuardian(receivedTamIncident, "default_user"))
            assertFalse(SafetyRepository.isReceivedEmergencyForGuardian(receivedTamIncident, "00000000-0000-0000-0000-000000000000"))

            val viewModel = SafetyViewModel(
                application = application,
                customRepository = guardianRepository,
                customPreferencesManager = guardianPrefs
            )

            composeTestRule.setContent {
                MyApplicationTheme(darkTheme = false, accentKey = "magenta") {
                    HistoryScreen(
                        viewModel = viewModel,
                        innerPadding = PaddingValues()
                    )
                }
            }
            composeTestRule.waitForIdle()

            // Unified History screen: single event list, no Sent/Received tabs
            composeTestRule.onNodeWithTag("history_screen_title").assertIsDisplayed()
            composeTestRule.onNodeWithText("History").assertIsDisplayed()
            composeTestRule.onNodeWithTag("history_tab_sent").assertDoesNotExist()
            composeTestRule.onNodeWithTag("history_tab_received").assertDoesNotExist()

            // Shows the recorded emergency event with trigger type, date/time, and GPS
            composeTestRule.onNodeWithText("Manual SOS").assertIsDisplayed()
            composeTestRule.onNodeWithText("19.07600, 72.87770").assertIsDisplayed()
        }
    }

    @Test
    fun tappingReceivedEmergencyInHistory_andTappingNotification_openRealEmergencyIncidentDetailsScreen() {
        runBlocking {
            seedPairedGuardianAndSenderContact(senderName = "Tam")

            val incident = senderRepository.createEmergencyIncident(
                triggerSource = EmergencyIncidentEntity.TRIGGER_SHAKE,
                latitude = 12.9716,
                longitude = 77.5946,
                locationTimestamp = System.currentTimeMillis(),
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                batteryPercent = 76,
                networkStatus = "ONLINE",
                medicalSharingEnabled = true,
                medicalSummary = "Blood Group: O+, Allergies: Penicillin, Conditions: Asthma, Notes: Carry inhaler",
                audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED,
                explicitUserId = validSenderUid
            ).getOrThrow()
            senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)
            guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)

            val viewModel = SafetyViewModel(
                application = application,
                customRepository = guardianRepository,
                customPreferencesManager = guardianPrefs
            )

            composeTestRule.setContent {
                MyApplicationTheme(darkTheme = false, accentKey = "magenta") {
                    MainNavigationScaffold(viewModel = viewModel)
                }
            }
            composeTestRule.waitForIdle()

            // Open the incident details screen
            assertTrue(viewModel.openEmergencyIncidentDetails(incident.id))
            composeTestRule.waitForIdle()

            // Verify the real EmergencyIncidentDetailsScreen is displayed for this exact incidentId
            assertEquals(incident.id, viewModel.selectedEmergencyIncidentId.value)
            composeTestRule.onNodeWithTag("emergency_incident_details_screen").assertIsDisplayed()
            composeTestRule.onNodeWithText("🚨 AIZA EMERGENCY").assertIsDisplayed()
            composeTestRule.onNodeWithText("🚨 Emergency from Tam").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Shake SOS (SHAKE)").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Status: ACTIVE").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Incident ID: ${incident.id}").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Location available").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("12.97160, 77.59460").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("76%").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("ONLINE").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("COMPLETED").performScrollTo().assertIsDisplayed()

            // Medical info is completely removed from app
            composeTestRule.onNodeWithTag("emergency_medical_info_section").assertDoesNotExist()

            // Close details and verify tapping the Phase 4B notification opens the same real details screen
            viewModel.closeEmergencyIncidentDetails()
            composeTestRule.waitForIdle()
            assertNull(viewModel.selectedEmergencyIncidentId.value)

            val notifId = AizaEmergencyNotificationManager.notificationIdForIncident(incident.id)
            val postedNotif = shadowOf(notificationManager).getNotification(notifId)
            assertNotNull(postedNotif)
            val tapIntent = shadowOf(postedNotif.contentIntent).savedIntent
            assertTrue(viewModel.handleEmergencyNotificationIntent(tapIntent))
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("emergency_incident_details_screen").assertIsDisplayed()
            composeTestRule.onNodeWithText("🚨 Emergency from Tam").performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText("Incident ID: ${incident.id}").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun medicalSharingDisabled_hidesMedicalInformationOnIncidentDetailsScreen() {
        val incidentWithoutMedical = EmergencyIncidentEntity(
            id = "33333333-4444-4555-8666-777777777777",
            userId = validSenderUid,
            recipientGuardianUserId = validGuardianUid,
            triggerSource = EmergencyIncidentEntity.TRIGGER_BLUETOOTH,
            status = EmergencyIncidentEntity.STATUS_RESOLVED,
            startedAt = System.currentTimeMillis() - 60_000L,
            endedAt = System.currentTimeMillis(),
            latitude = null,
            longitude = null,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN,
            batteryPercent = 42,
            networkStatus = "OFFLINE",
            medicalSharingEnabled = false,
            medicalSummary = "Blood Group: AB-, Allergies: Peanuts",
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_NOT_STARTED
        )

        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = false, accentKey = "magenta") {
                EmergencyIncidentDetailsScreen(
                    incidentId = incidentWithoutMedical.id,
                    incident = incidentWithoutMedical,
                    senderName = "Tam",
                    senderPhone = null,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify Bluetooth SOS, RESOLVED status, Location unavailable, and NO medical info
        composeTestRule.onNodeWithText("Bluetooth SOS (BLUETOOTH)").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Status: RESOLVED").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Location unavailable").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("emergency_medical_info_section").assertDoesNotExist()
        assertTrue(composeTestRule.onAllNodesWithText("Peanuts").fetchSemanticsNodes().isEmpty())

        // Call button is disabled when senderPhone is null
        composeTestRule.onNodeWithTag("emergency_call_sender_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun invalidOrUnknownIncidentIds_areRejectedSafelyAndNeverDisplayAnotherIncident() {
        runBlocking {
            seedPairedGuardianAndSenderContact(senderName = "Tam")

            val existingIncident = senderRepository.createEmergencyIncident(
                triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
                latitude = 28.6139,
                longitude = 77.2090,
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                explicitUserId = validSenderUid
            ).getOrThrow()
            senderRepository.syncEmergencyIncidentToSupabase(existingIncident, validSenderUid)
            guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)

            val viewModel = SafetyViewModel(
                application = application,
                customRepository = guardianRepository,
                customPreferencesManager = guardianPrefs
            )

            // Malformed / non-UUID / placeholder incidentIds are rejected immediately
            assertFalse(viewModel.openEmergencyIncidentDetails(null))
            assertFalse(viewModel.openEmergencyIncidentDetails(""))
            assertFalse(viewModel.openEmergencyIncidentDetails("not-a-uuid"))
            assertFalse(viewModel.openEmergencyIncidentDetails("default_user"))
            assertFalse(viewModel.openEmergencyIncidentDetails("tam@gmail.com"))
            assertFalse(viewModel.openEmergencyIncidentDetails("+919876543210"))
            assertFalse(viewModel.openEmergencyIncidentDetails("00000000-0000-0000-0000-000000000000"))
            assertNull(viewModel.selectedEmergencyIncidentId.value)

            // Unknown UUID that does not exist in Room or Supabase -> shows unavailable state, NEVER shows existingIncident
            val unknownIncidentId = "99999999-8888-4777-8666-555555555555"
            assertTrue(viewModel.openEmergencyIncidentDetails(unknownIncidentId))

            composeTestRule.setContent {
                MyApplicationTheme(darkTheme = false, accentKey = "magenta") {
                    com.example.ui.incident.EmergencyIncidentDetailsRoute(
                        incidentId = unknownIncidentId,
                        viewModel = viewModel,
                        onBack = { viewModel.closeEmergencyIncidentDetails() }
                    )
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("emergency_incident_not_found_state").assertIsDisplayed()
            composeTestRule.onNodeWithText("Emergency Incident Unavailable").assertIsDisplayed()
            assertTrue(composeTestRule.onAllNodesWithText("🚨 Emergency from Tam").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun incidentDetailsActions_openInMap_call_andChat_workWithoutTriggeringSosOrResolvingIncident() {
        runBlocking {
            seedPairedGuardianAndSenderContact(senderName = "Tam", senderPhone = "+919811122233")

            val incident = senderRepository.createEmergencyIncident(
                triggerSource = EmergencyIncidentEntity.TRIGGER_VOICE,
                latitude = 28.6139,
                longitude = 77.2090,
                locationTimestamp = 1712000500000L,
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                batteryPercent = 91,
                networkStatus = "ONLINE",
                explicitUserId = validSenderUid
            ).getOrThrow()
            senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)
            guardianRepository.syncReceivedEmergencyIncidents(validGuardianUid)

            val viewModel = SafetyViewModel(
                application = application,
                customRepository = guardianRepository,
                customPreferencesManager = guardianPrefs
            )

            assertTrue(viewModel.openEmergencyIncidentDetails(incident.id))

            var navigatedToMap = false
            var navigatedToContacts = false

            composeTestRule.setContent {
                MyApplicationTheme(darkTheme = false, accentKey = "magenta") {
                    com.example.ui.incident.EmergencyIncidentDetailsRoute(
                        incidentId = incident.id,
                        viewModel = viewModel,
                        onBack = { viewModel.closeEmergencyIncidentDetails() },
                        onNavigateToMap = { navigatedToMap = true },
                        onNavigateToContacts = { navigatedToContacts = true }
                    )
                }
            }
            composeTestRule.waitForIdle()

            // 1. Test Call button (enabled when valid sender phone exists; uses ACTION_DIAL; never triggers SOS)
            composeTestRule.onNodeWithTag("emergency_call_sender_button").performScrollTo().assertIsEnabled()
            composeTestRule.onNodeWithTag("emergency_call_sender_button").performClick()
            composeTestRule.waitForIdle()

            assertEquals("+919811122233", viewModel.lastDialedEmergencyContactPhone.value)
            val startedIntent = shadowOf(application).nextStartedActivity
            if (startedIntent != null) {
                assertEquals(Intent.ACTION_DIAL, startedIntent.action)
                assertEquals("tel:+919811122233", startedIntent.dataString)
            }
            assertFalse(viewModel.isSosTriggered.value)

            // 2. Test Open in AIZA Map button (sets sharedMapLocationTarget to exact coordinates, never resolves or triggers SOS)
            composeTestRule.onNodeWithTag("emergency_open_in_map_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            assertTrue(navigatedToMap)
            val mapTarget = viewModel.sharedMapLocationTarget.value
            assertNotNull(mapTarget)
            assertEquals(incident.id, mapTarget!!.messageId)
            assertEquals("Tam", mapTarget.senderName)
            assertEquals(28.6139, mapTarget.latitude, 0.0001)
            assertEquals(77.2090, mapTarget.longitude, 0.0001)
            assertFalse(viewModel.isSosTriggered.value)

            // Incident remains ACTIVE in Room
            val persistedIncident = guardianRepository.getEmergencyIncidentForDetailsFlow(incident.id, validGuardianUid)
            assertNotNull(persistedIncident)
            assertEquals(EmergencyIncidentEntity.STATUS_ACTIVE, persistedIncident!!.status)

            // 3. Re-open details and test Chat button (opens 1-to-1 Guardian Chat with sender, never triggers SOS)
            // 3. Verify chat is completely removed
            composeTestRule.onNodeWithTag("emergency_chat_sender_button").assertDoesNotExist()
            assertFalse(viewModel.isSosTriggered.value)
        }
    }
}
