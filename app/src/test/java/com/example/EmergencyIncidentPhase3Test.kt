package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.ContactDao
import com.example.data.ContactEntity
import com.example.data.EmergencyIncidentDao
import com.example.data.EmergencyIncidentEntity
import com.example.data.EmergencyIncidentRecipientEntity
import com.example.data.GuardianChatDao
import com.example.data.GuardianPairingRequestDao
import com.example.data.PreferencesManager
import com.example.data.SosLogDao
import com.example.data.TrackedLocationDao
import com.example.data.supabase.EmergencyIncidentDto
import com.example.data.supabase.EmergencyIncidentRecipientDto
import com.example.domain.EmergencyIncidentRemoteDataSource
import com.example.domain.SafetyRepository
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmergencyIncidentPhase3Test {

    private lateinit var application: Application
    private lateinit var senderDb: AppDatabase
    private lateinit var guardianDb: AppDatabase
    private lateinit var guardianTwoDb: AppDatabase
    private lateinit var preferencesManager: PreferencesManager

    private lateinit var senderContactDao: ContactDao
    private lateinit var senderSosLogDao: SosLogDao
    private lateinit var senderTrackedLocationDao: TrackedLocationDao
    private lateinit var senderChatDao: GuardianChatDao
    private lateinit var senderPairingDao: GuardianPairingRequestDao
    private lateinit var senderIncidentDao: EmergencyIncidentDao

    private lateinit var guardianContactDao: ContactDao
    private lateinit var guardianSosLogDao: SosLogDao
    private lateinit var guardianTrackedLocationDao: TrackedLocationDao
    private lateinit var guardianChatDao: GuardianChatDao
    private lateinit var guardianPairingDao: GuardianPairingRequestDao
    private lateinit var guardianIncidentDao: EmergencyIncidentDao

    private lateinit var guardianTwoContactDao: ContactDao
    private lateinit var guardianTwoSosLogDao: SosLogDao
    private lateinit var guardianTwoIncidentDao: EmergencyIncidentDao

    private lateinit var sharedRemote: FakePhase3IncidentRemoteDataSource
    private lateinit var senderRepository: SafetyRepository
    private lateinit var guardianRepository: SafetyRepository
    private lateinit var guardianTwoRepository: SafetyRepository

    private val validSenderUid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val validGuardianOneUid = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
    private val validGuardianTwoUid = "c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f"
    private val unauthorizedOutsiderUid = "d4e5f6a7-b8c9-4d0e-9f2a-3b4c5d6e7f80"

    private class FakePhase3IncidentRemoteDataSource : EmergencyIncidentRemoteDataSource {
        val remoteIncidents = linkedMapOf<String, EmergencyIncidentDto>()
        val remoteRecipients = linkedMapOf<String, EmergencyIncidentRecipientDto>() // key: "$incidentId|$guardianUserId"
        val pairedRelationships = linkedSetOf<Pair<String, String>>() // Pair(senderUidLower, guardianUidLower)

        var shouldFailIncidentUpsert: Boolean = false
        var shouldFailRecipientUpsert: Boolean = false
        var shouldFailGuardianIncidentFetch: Boolean = false

        private val realtimeListeners = linkedMapOf<String, suspend (EmergencyIncidentRecipientDto) -> Unit>()

        fun registerPairing(senderUid: String, guardianUid: String) {
            pairedRelationships.add(
                senderUid.trim().lowercase() to guardianUid.trim().lowercase()
            )
        }

        override suspend fun upsertEmergencyIncident(dto: EmergencyIncidentDto) {
            if (shouldFailIncidentUpsert) {
                throw IllegalStateException("Simulated Supabase incident upsert failure")
            }
            remoteIncidents[dto.id] = dto
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
            if (shouldFailGuardianIncidentFetch) {
                throw IllegalStateException("Simulated guardian incident fetch failure")
            }
            val incident = remoteIncidents[incidentId.trim()] ?: return null
            // Simulate Supabase RLS policy: guardian can only SELECT if paired to incident.userId
            val isOwner = incident.userId.equals(guardianUserId.trim(), ignoreCase = true)
            val isPaired = isGuardianPairedWithSender(incident.userId, guardianUserId)
            return if (isOwner || isPaired) incident else null
        }

        override suspend fun upsertIncidentRecipient(dto: EmergencyIncidentRecipientDto) {
            if (shouldFailRecipientUpsert) {
                throw IllegalStateException("Simulated recipient upsert failure")
            }
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
            if (existing != null && existing.id.equals(recipientId.trim(), ignoreCase = true)) {
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
            return {
                realtimeListeners.remove(key)
            }
        }
    }

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("aegis_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        preferencesManager = PreferencesManager(application)
        preferencesManager.isLoggedIn = true
        preferencesManager.supabaseUserId = validSenderUid
        preferencesManager.userEmail = "sender.aiza@gmail.com"

        senderDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        guardianDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        guardianTwoDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        senderContactDao = senderDb.contactDao()
        senderSosLogDao = senderDb.sosLogDao()
        senderTrackedLocationDao = senderDb.trackedLocationDao()
        senderChatDao = senderDb.guardianChatDao()
        senderPairingDao = senderDb.guardianPairingRequestDao()
        senderIncidentDao = senderDb.emergencyIncidentDao()

        guardianContactDao = guardianDb.contactDao()
        guardianSosLogDao = guardianDb.sosLogDao()
        guardianTrackedLocationDao = guardianDb.trackedLocationDao()
        guardianChatDao = guardianDb.guardianChatDao()
        guardianPairingDao = guardianDb.guardianPairingRequestDao()
        guardianIncidentDao = guardianDb.emergencyIncidentDao()

        guardianTwoContactDao = guardianTwoDb.contactDao()
        guardianTwoSosLogDao = guardianTwoDb.sosLogDao()
        guardianTwoIncidentDao = guardianTwoDb.emergencyIncidentDao()

        sharedRemote = FakePhase3IncidentRemoteDataSource()

        senderRepository = SafetyRepository(
            contactDao = senderContactDao,
            sosLogDao = senderSosLogDao,
            preferencesManager = preferencesManager,
            trackedLocationDao = senderTrackedLocationDao,
            guardianChatDao = senderChatDao,
            guardianPairingRequestDao = senderPairingDao,
            emergencyIncidentDao = senderIncidentDao,
            incidentRemoteDataSource = sharedRemote
        )

        guardianRepository = SafetyRepository(
            contactDao = guardianContactDao,
            sosLogDao = guardianSosLogDao,
            preferencesManager = preferencesManager,
            trackedLocationDao = guardianTrackedLocationDao,
            guardianChatDao = guardianChatDao,
            guardianPairingRequestDao = guardianPairingDao,
            emergencyIncidentDao = guardianIncidentDao,
            incidentRemoteDataSource = sharedRemote
        )

        guardianTwoRepository = SafetyRepository(
            contactDao = guardianTwoContactDao,
            sosLogDao = guardianTwoSosLogDao,
            preferencesManager = preferencesManager,
            emergencyIncidentDao = guardianTwoIncidentDao,
            incidentRemoteDataSource = sharedRemote
        )
    }

    @After
    fun tearDown() {
        senderDb.close()
        guardianDb.close()
        guardianTwoDb.close()
        application.getSharedPreferences("aegis_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private suspend fun seedPairedGuardianContactOnSender(
        guardianUid: String,
        name: String = "Paired Guardian",
        phone: String = "+919876543210"
    ) {
        senderRepository.insertContact(
            ContactEntity(
                userId = validSenderUid,
                guardianUserId = guardianUid,
                name = name,
                phone = phone,
                relation = "Sister",
                priority = "1",
                isVerified = true
            )
        )
        sharedRemote.registerPairing(validSenderUid, guardianUid)
    }

    @Test
    fun pairedGuardianReceivesActiveEmergencyIncidentAndMarksDelivered() = runBlocking {
        seedPairedGuardianContactOnSender(validGuardianOneUid)

        // 1. Sender triggers an active emergency incident
        val createResult = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            latitude = 28.6139,
            longitude = 77.2090,
            locationTimestamp = 1712000100000L,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 82,
            networkStatus = "ONLINE",
            medicalSharingEnabled = true,
            medicalSummary = "Blood: O+, Allergies: Penicillin",
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_RECORDING,
            explicitUserId = validSenderUid
        )
        assertTrue(createResult.isSuccess)
        val createdIncident = createResult.getOrThrow()
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_PENDING, createdIncident.deliveryStatus)

        // Local recipient row prepared on sender with status = PENDING
        val senderLocalRecipientsBeforeSync = senderRepository.getIncidentRecipients(createdIncident.id)
        assertEquals(1, senderLocalRecipientsBeforeSync.size)
        assertEquals(validGuardianOneUid, senderLocalRecipientsBeforeSync.first().guardianUserId)
        assertEquals(validSenderUid, senderLocalRecipientsBeforeSync.first().senderUserId)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_PENDING, senderLocalRecipientsBeforeSync.first().deliveryStatus)

        // 2. Sender syncs incident to Supabase -> transitions to SENT (NOT DELIVERED yet)
        val syncResult = senderRepository.syncEmergencyIncidentToSupabase(
            incident = createdIncident,
            explicitUserId = validSenderUid
        )
        assertTrue(syncResult.isSuccess)
        val syncedIncident = syncResult.getOrThrow()
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_SENT, syncedIncident.deliveryStatus)

        val senderLocalRecipientsAfterSync = senderRepository.getIncidentRecipients(createdIncident.id)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_SENT, senderLocalRecipientsAfterSync.first().deliveryStatus)

        // 3. Paired guardian syncs received emergency incidents
        val guardianSyncResult = guardianRepository.syncReceivedEmergencyIncidents(validGuardianOneUid)
        assertTrue(guardianSyncResult.isSuccess)
        val receivedList = guardianSyncResult.getOrThrow()
        assertEquals(1, receivedList.size)

        val received = receivedList.first()
        assertEquals(createdIncident.id, received.id)
        // Sender's original userId MUST NOT be overwritten with guardian's userId
        assertEquals(validSenderUid, received.userId)
        assertEquals(validSenderUid, received.senderUserId)
        assertEquals(validGuardianOneUid, received.recipientGuardianUserId)
        assertEquals(validGuardianOneUid, received.guardianUserId)
        assertTrue(received.isReceivedByGuardian(validGuardianOneUid))
        assertFalse(received.isOwnedBy(validGuardianOneUid))

        // Verify all incident fields are preserved
        assertEquals(EmergencyIncidentEntity.TRIGGER_MANUAL, received.triggerSource)
        assertEquals(EmergencyIncidentEntity.STATUS_ACTIVE, received.status)
        assertEquals(28.6139, received.latitude ?: 0.0, 0.0001)
        assertEquals(77.2090, received.longitude ?: 0.0, 0.0001)
        assertEquals(EmergencyIncidentEntity.LOCATION_STATUS_LIVE, received.locationStatus)
        assertEquals(82, received.batteryPercent)
        assertEquals("ONLINE", received.networkStatus)
        assertTrue(received.medicalSharingEnabled)
        assertEquals("Blood: O+, Allergies: Penicillin", received.medicalSummary)
        assertEquals(EmergencyIncidentEntity.AUDIO_STATUS_RECORDING, received.audioRecordingStatus)

        // Delivery status is now DELIVERED after guardian persisted locally in Room
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, received.deliveryStatus)

        // Verify Flow observation on guardian repository
        val observedActiveReceived = guardianRepository
            .observeActiveReceivedEmergencyIncidents(validGuardianOneUid)
            .first()
        assertEquals(1, observedActiveReceived.size)
        assertEquals(createdIncident.id, observedActiveReceived.first().id)

        // Verify remote recipient acknowledgement was also updated to DELIVERED with deliveredAt
        val remoteRecipientsForGuardian = sharedRemote.fetchRecipientRowsForGuardian(validGuardianOneUid)
        assertEquals(1, remoteRecipientsForGuardian.size)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, remoteRecipientsForGuardian.first().deliveryStatus)
        assertNotNull(remoteRecipientsForGuardian.first().deliveredAt)
    }

    @Test
    fun unpairedContactDoesNotReceiveEmergencyIncident() = runBlocking {
        // Seed an emergency contact WITHOUT guardian_user_id (phone/SMS only contact)
        senderRepository.insertContact(
            ContactEntity(
                userId = validSenderUid,
                guardianUserId = null,
                name = "Unpaired SMS Contact",
                phone = "+919811112222",
                relation = "Brother",
                priority = "1",
                isVerified = true
            )
        )

        val pairedIds = senderRepository.resolvePairedGuardianUserIds(validSenderUid)
        assertTrue(pairedIds.isEmpty())

        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_SHAKE,
            explicitUserId = validSenderUid
        ).getOrThrow()

        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        // No recipient rows created locally or remotely for unpaired contact
        assertTrue(senderRepository.getIncidentRecipients(incident.id).isEmpty())
        assertTrue(sharedRemote.remoteRecipients.isEmpty())

        // Attempting to explicitly create a recipient for an unpaired UUID fails with SecurityException
        val explicitRecipientAttempt = senderRepository.createIncidentRecipientForGuardian(
            incidentId = incident.id,
            guardianUserId = unauthorizedOutsiderUid,
            explicitSenderUserId = validSenderUid
        )
        assertTrue(explicitRecipientAttempt.isFailure)
        assertTrue(explicitRecipientAttempt.exceptionOrNull() is SecurityException)
    }

    @Test
    fun nonPairedAccountCannotAccessSendersIncident() = runBlocking {
        seedPairedGuardianContactOnSender(validGuardianOneUid)

        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_VOICE,
            explicitUserId = validSenderUid
        ).getOrThrow()
        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        // Even if a rogue recipient row addressed to unauthorizedOutsiderUid were present in remote
        sharedRemote.remoteRecipients["${incident.id.lowercase()}|${unauthorizedOutsiderUid.lowercase()}"] =
            EmergencyIncidentRecipientDto(
                id = "e5f6a7b8-c9d0-4e1f-8a2b-3c4d5e6f7a81",
                incidentId = incident.id,
                senderUserId = validSenderUid,
                guardianUserId = unauthorizedOutsiderUid,
                deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_SENT,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

        // Outsider tries to sync received incidents
        val outsiderSync = guardianTwoRepository.syncReceivedEmergencyIncidents(unauthorizedOutsiderUid)
        assertTrue(outsiderSync.isSuccess)
        assertTrue(outsiderSync.getOrThrow().isEmpty())

        val outsiderObserved = guardianTwoRepository
            .observeReceivedEmergencyIncidents(unauthorizedOutsiderUid)
            .first()
        assertTrue(outsiderObserved.isEmpty())
    }

    @Test
    fun guardianCannotModifyOrResolveSendersIncident() = runBlocking {
        seedPairedGuardianContactOnSender(validGuardianOneUid)

        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_BLUETOOTH,
            latitude = 19.0760,
            longitude = 72.8777,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            explicitUserId = validSenderUid
        ).getOrThrow()
        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        // Guardian receives and persists the incident locally
        val received = guardianRepository.syncReceivedEmergencyIncidents(validGuardianOneUid)
            .getOrThrow()
            .first()

        // Guardian attempts to update the incident -> rejected with SecurityException
        val updateAttempt = guardianRepository.updateEmergencyIncident(
            incident = received.copy(status = EmergencyIncidentEntity.STATUS_CANCELLED),
            explicitUserId = validGuardianOneUid
        )
        assertTrue(updateAttempt.isFailure)
        assertTrue(updateAttempt.exceptionOrNull() is SecurityException)

        // Guardian attempts to update location -> rejected with SecurityException
        val locAttempt = guardianRepository.updateIncidentLocation(
            id = received.id,
            latitude = 0.0,
            longitude = 0.0,
            explicitUserId = validGuardianOneUid
        )
        assertTrue(locAttempt.isFailure)
        assertTrue(locAttempt.exceptionOrNull() is SecurityException)

        // Guardian attempts to update audio status -> rejected with SecurityException
        val audioAttempt = guardianRepository.updateIncidentAudioStatus(
            id = received.id,
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED,
            explicitUserId = validGuardianOneUid
        )
        assertTrue(audioAttempt.isFailure)
        assertTrue(audioAttempt.exceptionOrNull() is SecurityException)

        // Guardian attempts to resolve sender's incident -> rejected with SecurityException
        val resolveAttempt = guardianRepository.resolveEmergencyIncident(
            id = received.id,
            explicitUserId = validGuardianOneUid
        )
        assertTrue(resolveAttempt.isFailure)
        assertTrue(resolveAttempt.exceptionOrNull() is SecurityException)

        // Guardian attempts to upsert sender's incident to Supabase -> rejected with SecurityException
        val syncAttempt = guardianRepository.syncEmergencyIncidentToSupabase(
            incident = received,
            explicitUserId = validGuardianOneUid
        )
        assertTrue(syncAttempt.isFailure)
        assertTrue(syncAttempt.exceptionOrNull() is SecurityException)

        // Verify local received incident in guardian DB remains untouched and ACTIVE
        val stillActive = guardianIncidentDao.getReceivedIncidentByIdForGuardian(received.id, validGuardianOneUid)
        assertNotNull(stillActive)
        assertEquals(EmergencyIncidentEntity.STATUS_ACTIVE, stillActive?.status)
        assertEquals(validSenderUid, stillActive?.userId)
    }

    @Test
    fun duplicateRecipientRowsArePreventedForSameIncidentAndGuardian() = runBlocking {
        // Even if two contacts on sender's phone point to the same paired guardian UUID
        seedPairedGuardianContactOnSender(validGuardianOneUid, name = "Mom Primary", phone = "+919876543210")
        seedPairedGuardianContactOnSender(validGuardianOneUid, name = "Mom Secondary", phone = "+919876543211")

        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            explicitUserId = validSenderUid
        ).getOrThrow()

        // Calling prepareIncidentRecipientsForSender and createIncidentRecipientForGuardian repeatedly
        senderRepository.prepareIncidentRecipientsForSender(incident.id, validSenderUid)
        val firstCreate = senderRepository.createIncidentRecipientForGuardian(
            incidentId = incident.id,
            guardianUserId = validGuardianOneUid,
            explicitSenderUserId = validSenderUid
        ).getOrThrow()
        val secondCreate = senderRepository.createIncidentRecipientForGuardian(
            incidentId = incident.id,
            guardianUserId = validGuardianOneUid,
            explicitSenderUserId = validSenderUid
        ).getOrThrow()

        assertEquals(firstCreate.id, secondCreate.id)

        val recipients = senderRepository.getIncidentRecipients(incident.id)
        assertEquals(1, recipients.size)
    }

    @Test
    fun multiplePairedGuardiansReceiveIndependentRecipientRecordsAndStatuses() = runBlocking {
        seedPairedGuardianContactOnSender(validGuardianOneUid, name = "Guardian One", phone = "+919800000001")
        seedPairedGuardianContactOnSender(validGuardianTwoUid, name = "Guardian Two", phone = "+919800000002")

        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            latitude = 12.9716,
            longitude = 77.5946,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            explicitUserId = validSenderUid
        ).getOrThrow()

        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        // Two distinct recipient records exist in local Room and Supabase with status = SENT
        val senderRecipients = senderRepository.getIncidentRecipients(incident.id)
        assertEquals(2, senderRecipients.size)
        assertTrue(senderRecipients.all { it.deliveryStatus == EmergencyIncidentEntity.DELIVERY_STATUS_SENT })

        // Guardian 1 is online and syncs; Guardian 2 is still offline
        val g1Received = guardianRepository.syncReceivedEmergencyIncidents(validGuardianOneUid).getOrThrow()
        assertEquals(1, g1Received.size)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, g1Received.first().deliveryStatus)

        // Remote recipient for Guardian 1 is DELIVERED, while Guardian 2 is still SENT
        val remoteG1 = sharedRemote.fetchRecipientRowsForGuardian(validGuardianOneUid).first()
        val remoteG2 = sharedRemote.fetchRecipientRowsForGuardian(validGuardianTwoUid).first()
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, remoteG1.deliveryStatus)
        assertNotNull(remoteG1.deliveredAt)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_SENT, remoteG2.deliveryStatus)
        assertNull(remoteG2.deliveredAt)

        // Now Guardian 2 comes online and syncs
        val g2Received = guardianTwoRepository.syncReceivedEmergencyIncidents(validGuardianTwoUid).getOrThrow()
        assertEquals(1, g2Received.size)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, g2Received.first().deliveryStatus)
        assertEquals(validSenderUid, g2Received.first().userId)
        assertEquals(validGuardianTwoUid, g2Received.first().recipientGuardianUserId)

        val remoteG2After = sharedRemote.fetchRecipientRowsForGuardian(validGuardianTwoUid).first()
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, remoteG2After.deliveryStatus)
        assertNotNull(remoteG2After.deliveredAt)
    }

    @Test
    fun offlineSenderAndOfflineGuardianRecoverAndDeliverOnReconnect() = runBlocking {
        seedPairedGuardianContactOnSender(validGuardianOneUid)

        // 1. Sender triggers SOS while offline (incident upsert fails)
        sharedRemote.shouldFailIncidentUpsert = true
        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_SHAKE,
            latitude = 22.5726,
            longitude = 88.3639,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_RECENT,
            explicitUserId = validSenderUid
        ).getOrThrow()

        val failedSync = senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)
        assertTrue(failedSync.isFailure)

        val senderAfterFail = senderRepository.getEmergencyIncident(incident.id, validSenderUid)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_FAILED, senderAfterFail?.deliveryStatus)
        assertTrue(sharedRemote.remoteIncidents.isEmpty())

        // 2. Sender reconnects and runs syncPendingEmergencyIncidents()
        sharedRemote.shouldFailIncidentUpsert = false
        val syncedCount = senderRepository.syncPendingEmergencyIncidents(validSenderUid)
        assertEquals(1, syncedCount)

        val senderAfterReconnect = senderRepository.getEmergencyIncident(incident.id, validSenderUid)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_SENT, senderAfterReconnect?.deliveryStatus)
        assertEquals(1, sharedRemote.remoteRecipients.size)
        assertEquals(
            EmergencyIncidentEntity.DELIVERY_STATUS_SENT,
            sharedRemote.remoteRecipients.values.first().deliveryStatus
        )

        // 3. Guardian is initially unable to fetch incident (simulated network failure on guardian)
        sharedRemote.shouldFailGuardianIncidentFetch = true
        val guardianFailedSync = guardianRepository.syncReceivedEmergencyIncidents(validGuardianOneUid)
        assertTrue(guardianFailedSync.isFailure)
        val localRecipientAfterFail = guardianIncidentDao.getRecipientForIncidentAndGuardian(
            incident.id,
            validGuardianOneUid
        )
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_FAILED, localRecipientAfterFail?.deliveryStatus)
        assertNotNull(localRecipientAfterFail?.lastError)

        // 4. Guardian reconnects and syncs -> retrieves incident, stores locally, marks DELIVERED
        sharedRemote.shouldFailGuardianIncidentFetch = false
        val guardianRecoveredSync = guardianRepository.syncReceivedEmergencyIncidents(validGuardianOneUid)
        assertTrue(guardianRecoveredSync.isSuccess)
        val recoveredList = guardianRecoveredSync.getOrThrow()
        assertEquals(1, recoveredList.size)
        assertEquals(incident.id, recoveredList.first().id)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, recoveredList.first().deliveryStatus)
    }

    @Test
    fun realtimeGuardianSubscriptionImmediatelySyncsAndDeliversIncomingIncident() = runBlocking {
        seedPairedGuardianContactOnSender(validGuardianOneUid)

        val syncedRealtimeLists = mutableListOf<List<EmergencyIncidentEntity>>()
        val unsubscribe = guardianRepository.subscribeToGuardianIncidentsRealtime(
            explicitGuardianUserId = validGuardianOneUid
        ) { syncedList ->
            syncedRealtimeLists.add(syncedList)
        }
        assertNotNull(unsubscribe)

        // Sender creates and syncs an active emergency incident
        val incident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_VOICE,
            latitude = 13.0827,
            longitude = 80.2707,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            explicitUserId = validSenderUid
        ).getOrThrow()
        senderRepository.syncEmergencyIncidentToSupabase(incident, validSenderUid)

        // Realtime listener on guardian automatically fetched, verified pairing, persisted, and marked DELIVERED
        assertFalse(syncedRealtimeLists.isEmpty())
        val guardianLocalList = guardianIncidentDao.getReceivedIncidentsForGuardian(validGuardianOneUid)
        assertEquals(1, guardianLocalList.size)
        assertEquals(incident.id, guardianLocalList.first().id)
        assertEquals(validSenderUid, guardianLocalList.first().userId)
        assertEquals(validGuardianOneUid, guardianLocalList.first().recipientGuardianUserId)
        assertEquals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, guardianLocalList.first().deliveryStatus)

        unsubscribe?.invoke()
        assertTrue(true)
    }

    @Test
    fun invalidIdentityValuesAreRejectedForBothSenderAndGuardian() = runBlocking {
        val invalidIdentities = listOf(
            "",
            "   ",
            "default_user",
            "anonymous_aegis_user",
            "guardian@gmail.com",
            "+919876543210",
            "9876543210",
            "00000000-0000-0000-0000-000000000000",
            "11111111-1111-1111-1111-111111111111",
            "ffffffff-ffff-ffff-ffff-ffffffffffff"
        )

        val validIncident = senderRepository.createEmergencyIncident(
            triggerSource = EmergencyIncidentEntity.TRIGGER_MANUAL,
            explicitUserId = validSenderUid
        ).getOrThrow()

        for (invalidId in invalidIdentities) {
            // Invalid guardian identity rejected in createIncidentRecipientForGuardian
            val createRecResult = senderRepository.createIncidentRecipientForGuardian(
                incidentId = validIncident.id,
                guardianUserId = invalidId,
                explicitSenderUserId = validSenderUid
            )
            assertTrue("Expected failure for invalid guardianId '$invalidId'", createRecResult.isFailure)

            // Invalid guardian identity rejected in syncReceivedEmergencyIncidents
            preferencesManager.supabaseUserId = ""
            val syncRecResult = guardianRepository.syncReceivedEmergencyIncidents(invalidId)
            assertTrue("Expected failure for invalid guardian sync '$invalidId'", syncRecResult.isFailure)

            // Invalid pairing check returns false
            assertFalse(
                senderRepository.isGuardianAuthorizedForSender(
                    senderUserId = validSenderUid,
                    guardianUserId = invalidId
                )
            )
            assertFalse(
                senderRepository.isGuardianAuthorizedForSender(
                    senderUserId = invalidId,
                    guardianUserId = validGuardianOneUid
                )
            )
        }
    }
}
