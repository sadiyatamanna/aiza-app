package com.example.domain

import android.util.Log
import com.example.data.CachedPlaceDao
import com.example.data.ContactDao
import com.example.data.ContactEntity
import com.example.data.EmergencyIncidentDao
import com.example.data.EmergencyIncidentEntity
import com.example.data.EmergencyIncidentRecipientEntity
import com.example.data.GuardianChatDao
import com.example.data.GuardianChatMessageEntity
import com.example.data.GuardianPairingRequestDao
import com.example.data.GuardianPairingRequestEntity
import com.example.data.PersonWhoSavedMe
import com.example.data.PreferencesManager
import com.example.data.SecurityUtils
import com.example.data.SosLogDao
import com.example.data.SosLogEntity
import com.example.data.SupabaseClientProvider
import com.example.data.TrackedLocationDao
import com.example.data.TrackedLocationEntity
import com.example.data.supabase.EmergencyContactDto
import com.example.data.supabase.EmergencyIncidentDto
import com.example.data.supabase.EmergencyIncidentRecipientDto
import com.example.data.supabase.GuardianChatMessageDto
import com.example.data.supabase.GuardianPairingRequestDto
import com.example.data.supabase.GuardianRecipientDeliveryAckDto
import com.example.data.supabase.ProfileDto
import com.example.data.supabase.SosAlertDto
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.KeyPair
import java.util.UUID

interface GuardianChatRemoteDataSource {
    suspend fun upsertChatMessage(dto: GuardianChatMessageDto)
    suspend fun fetchChatMessages(userId: String, guardianId: String): List<GuardianChatMessageDto>
    suspend fun fetchChatMessages(
        userId: String,
        guardianId: String,
        pairedGuardianUserId: String?
    ): List<GuardianChatMessageDto> = fetchChatMessages(userId, guardianId)
    suspend fun subscribeToGuardianChat(
        channelName: String,
        onInsert: suspend (GuardianChatMessageDto) -> Unit
    ): suspend () -> Unit
}

interface GuardianPairingRemoteDataSource {
    suspend fun upsertPairingRequest(dto: GuardianPairingRequestDto)
    suspend fun fetchPairingRequestsForRequester(requesterUserId: String): List<GuardianPairingRequestDto>
    suspend fun fetchPairingRequestsForGuardianEmail(guardianEmail: String): List<GuardianPairingRequestDto>
    suspend fun updatePairingRequestDecision(requestId: String, status: String, guardianUserId: String?)
}

interface EmergencyIncidentRemoteDataSource {
    suspend fun upsertEmergencyIncident(dto: EmergencyIncidentDto)
    suspend fun fetchEmergencyIncidentsForUser(userId: String): List<EmergencyIncidentDto>

    suspend fun fetchEmergencyIncidentByIdForGuardian(
        incidentId: String,
        guardianUserId: String
    ): EmergencyIncidentDto? = null

    suspend fun upsertIncidentRecipient(dto: EmergencyIncidentRecipientDto) {}

    suspend fun fetchRecipientRowsForIncident(
        incidentId: String,
        senderUserId: String
    ): List<EmergencyIncidentRecipientDto> = emptyList()

    suspend fun fetchRecipientRowsForGuardian(
        guardianUserId: String
    ): List<EmergencyIncidentRecipientDto> = emptyList()

    suspend fun acknowledgeRecipientDelivery(
        recipientId: String,
        incidentId: String,
        guardianUserId: String,
        deliveredAt: Long,
        updatedAt: Long
    ) {}

    suspend fun isGuardianPairedWithSender(
        senderUserId: String,
        guardianUserId: String
    ): Boolean = false

    suspend fun fetchPairedGuardianUserIdsForSender(
        senderUserId: String
    ): List<String> = emptyList()

    suspend fun subscribeToGuardianIncidentRecipients(
        channelName: String,
        guardianUserId: String,
        onRecipientEvent: suspend (EmergencyIncidentRecipientDto) -> Unit
    ): suspend () -> Unit = {}

    suspend fun fetchSenderDisplayName(
        senderUserId: String
    ): String? = null
}

interface PeopleWhoSavedMeRemoteDataSource {
    suspend fun fetchContactsWhoSavedUser(guardianUserId: String): List<EmergencyContactDto> = emptyList()
    suspend fun fetchSenderDisplayName(senderUserId: String): String? = null
    suspend fun fetchAcceptedPairingRequestsForGuardian(guardianUserId: String): List<GuardianPairingRequestDto> = emptyList()
}

class SafetyRepository(
    private val contactDao: ContactDao,
    private val sosLogDao: SosLogDao,
    private val preferencesManager: PreferencesManager,
    private val trackedLocationDao: TrackedLocationDao? = null,
    private val cachedPlaceDao: CachedPlaceDao? = null,
    private val guardianChatDao: GuardianChatDao? = null,
    private val chatRemoteDataSource: GuardianChatRemoteDataSource? = null,
    private val guardianPairingRequestDao: GuardianPairingRequestDao? = null,
    private val pairingRemoteDataSource: GuardianPairingRemoteDataSource? = null,
    private val emergencyIncidentDao: EmergencyIncidentDao? = null,
    private val incidentRemoteDataSource: EmergencyIncidentRemoteDataSource? = null,
    private val emergencyNotificationManager: AizaEmergencyNotificationManager =
        AizaEmergencyNotificationManager(preferencesManager.appContext),
    private val peopleWhoSavedMeRemoteDataSource: PeopleWhoSavedMeRemoteDataSource? = null
) {
    private val supabaseClient get() = SupabaseClientProvider.client
    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val chatSyncMutex = Mutex()
    private val incidentSyncMutex = Mutex()
    private val incidentMutationMutex = Mutex()

    fun getEmergencyNotificationManager(): AizaEmergencyNotificationManager = emergencyNotificationManager

    @Volatile
    var lastChatSyncError: String? = null
        private set

    private val defaultChatRemoteDataSource = object : GuardianChatRemoteDataSource {
        override suspend fun upsertChatMessage(dto: GuardianChatMessageDto) {
            Log.d(
                TAG_CHAT_RUNTIME,
                "Supabase INSERT started: messageId=${dto.id}, userId=${dto.userId}, guardianId=${dto.guardianId}"
            )
            try {
                supabaseClient.from("guardian_chat_messages").insert(dto)
                Log.d(
                    TAG_CHAT_RUNTIME,
                    "Supabase INSERT succeeded: messageId=${dto.id}, userId=${dto.userId}, guardianId=${dto.guardianId}"
                )
            } catch (insertError: Exception) {
                Log.e(
                    TAG_CHAT_RUNTIME,
                    "Supabase INSERT failed: messageId=${dto.id}, userId=${dto.userId}, guardianId=${dto.guardianId}, exceptionClass=${insertError.javaClass.name}, message=${insertError.message}",
                    insertError
                )
                throw insertError
            }
        }

        override suspend fun fetchChatMessages(
            userId: String,
            guardianId: String,
            pairedGuardianUserId: String?
        ): List<GuardianChatMessageDto> {
            val cleanUserId = userId.trim()
            val cleanGuardianId = guardianId.trim()
            val cleanPairedUid = pairedGuardianUserId?.trim()?.takeIf { isValidUuid(it) }

            val results = mutableListOf<GuardianChatMessageDto>()

            // 1. Direct messages by user_id and guardian_id
            try {
                val direct = supabaseClient.from("guardian_chat_messages")
                    .select {
                        filter {
                            eq("user_id", cleanUserId)
                            eq("guardian_id", cleanGuardianId)
                        }
                    }
                    .decodeList<GuardianChatMessageDto>()
                results.addAll(direct)
            } catch (e: Exception) {
                Log.w(TAG_CHAT_SYNC, "Error fetching direct chat messages: ${e.message}")
            }

            // 2. Paired bidirectional conversation messages
            if (cleanPairedUid != null) {
                // Incoming messages sent by paired guardian to current user
                try {
                    val incoming = supabaseClient.from("guardian_chat_messages")
                        .select {
                            filter {
                                eq("recipient_id", cleanUserId)
                                eq("sender_id", cleanPairedUid)
                            }
                        }
                        .decodeList<GuardianChatMessageDto>()
                    results.addAll(incoming)
                } catch (e: Exception) {
                    Log.w(TAG_CHAT_SYNC, "Error fetching incoming paired messages: ${e.message}")
                }

                // Outgoing messages sent by current user to paired guardian
                try {
                    val sentToPaired = supabaseClient.from("guardian_chat_messages")
                        .select {
                            filter {
                                eq("sender_id", cleanUserId)
                                eq("recipient_id", cleanPairedUid)
                            }
                        }
                        .decodeList<GuardianChatMessageDto>()
                    results.addAll(sentToPaired)
                } catch (e: Exception) {
                    Log.w(TAG_CHAT_SYNC, "Error fetching sent paired messages: ${e.message}")
                }
            }

            return results.distinctBy { it.id }.sortedBy { it.createdAt }
        }

        override suspend fun fetchChatMessages(
            userId: String,
            guardianId: String
        ): List<GuardianChatMessageDto> {
            return fetchChatMessages(userId, guardianId, null)
        }

        override suspend fun subscribeToGuardianChat(
            channelName: String,
            onInsert: suspend (GuardianChatMessageDto) -> Unit
        ): suspend () -> Unit {
            val channel = supabaseClient.realtime.channel(channelName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val changeFlow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "guardian_chat_messages"
            }
            changeFlow.onEach { action ->
                try {
                    val dto = jsonParser.decodeFromJsonElement(
                        GuardianChatMessageDto.serializer(),
                        action.record
                    )
                    onInsert(dto)
                } catch (e: Exception) {
                    Log.w("SafetyRepository", "Failed to decode Realtime chat message: ${e.message}")
                }
            }.launchIn(scope)

            try {
                channel.subscribe()
            } catch (e: Exception) {
                Log.w("SafetyRepository", "Realtime chat channel subscribe warning: ${e.message}")
            }

            return {
                try {
                    scope.cancel()
                } catch (_: Exception) {}
                try {
                    channel.unsubscribe()
                } catch (_: Exception) {}
                try {
                    supabaseClient.realtime.removeChannel(channel)
                } catch (_: Exception) {}
            }
        }
    }

    private val activeChatRemote: GuardianChatRemoteDataSource
        get() = chatRemoteDataSource ?: defaultChatRemoteDataSource

    suspend fun getPrimaryEmergencyContact(): ContactEntity? {
        val list = allContacts.first().sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }
        return list.firstOrNull()
    }

    private fun encryptContact(contact: ContactEntity): ContactEntity {
        val validGuardianUid = resolvePairedGuardianUserId(contact)
        return contact.copy(
            guardianUserId = validGuardianUid,
            name = com.example.data.SecureStorageHelper.encrypt(contact.name),
            phone = com.example.data.SecureStorageHelper.encrypt(contact.phone),
            relation = com.example.data.SecureStorageHelper.encrypt(contact.relation)
        )
    }

    private fun decryptContact(contact: ContactEntity): ContactEntity {
        val decrypted = contact.copy(
            name = com.example.data.SecureStorageHelper.decrypt(contact.name),
            phone = com.example.data.SecureStorageHelper.decrypt(contact.phone),
            relation = com.example.data.SecureStorageHelper.decrypt(contact.relation)
        )
        return decrypted.copy(
            guardianUserId = resolvePairedGuardianUserId(decrypted)
        )
    }

    // --- Contacts Local Queries (Room) ---
    val allContacts: Flow<List<ContactEntity>> = contactDao.getAllContacts().map { list ->
        list.map { decryptContact(it) }
    }
    val contactCount: Flow<Int> = contactDao.getContactCountFlow()

    suspend fun insertContact(contact: ContactEntity): Long {
        val ownerUid = if (isRealAuthenticatedUserUuid(contact.userId)) {
            contact.userId.trim()
        } else {
            resolveAuthenticatedIncidentUserId() ?: contact.userId
        }
        val sanitized = contact.copy(
            userId = ownerUid,
            guardianUserId = resolvePairedGuardianUserId(contact)
        )
        val result = contactDao.insertContact(encryptContact(sanitized))
        syncSingleContactToSupabase(sanitized)
        return result
    }

    suspend fun updateContact(contact: ContactEntity) {
        val ownerUid = if (isRealAuthenticatedUserUuid(contact.userId)) {
            contact.userId.trim()
        } else {
            resolveAuthenticatedIncidentUserId() ?: contact.userId
        }
        val sanitized = contact.copy(
            userId = ownerUid,
            guardianUserId = resolvePairedGuardianUserId(contact)
        )
        contactDao.updateContact(encryptContact(sanitized))
        syncSingleContactToSupabase(sanitized)
    }

    suspend fun deleteContact(contact: ContactEntity) {
        contactDao.deleteContact(contact)
        deleteContactFromSupabase(contact.contactId)
    }

    // --- SOS History Local Queries (Room) ---
    val allLogs: Flow<List<SosLogEntity>> = sosLogDao.getAllLogs()

    suspend fun insertLog(log: SosLogEntity): Long {
        return sosLogDao.insertLog(log)
    }

    suspend fun clearLogs() {
        sosLogDao.clearAllLogs()
    }

    // --- Supabase User Profile Synchronization ---

    suspend fun syncProfileToSupabase(profile: ProfileDto): Result<Unit> {
        return try {
            supabaseClient.from("profiles").upsert(profile)
            Log.d("SafetyRepository", "Profile synced successfully to Supabase for userId: ${profile.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Profile sync to Supabase failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun fetchProfileFromSupabase(userId: String): Result<ProfileDto?> {
        return try {
            val profile = supabaseClient.from("profiles")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingleOrNull<ProfileDto>()
            Result.success(profile)
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Fetch profile from Supabase failed: ${e.message}")
            Result.failure(e)
        }
    }

    // --- Supabase Emergency Contacts Synchronization ---

    suspend fun syncSingleContactToSupabase(contact: ContactEntity, explicitUserId: String? = null) {
        val userId = ensureAuthenticatedChatSession(explicitUserId)
        if (!isValidUuid(userId)) {
            Log.w(TAG_CHAT_SYNC, "Skipping contact sync: resolved userId is not a valid UUID.")
            return
        }
        val guardianId = contact.contactId.trim()
        if (!isValidUuid(guardianId)) {
            Log.w(TAG_CHAT_SYNC, "Skipping contact sync: contactId '$guardianId' is not a valid UUID.")
            return
        }

        try {
            // Ensure profile row exists first in case emergency_contacts.user_id references public.profiles(id)
            try {
                val profileDto = ProfileDto(
                    id = userId,
                    name = preferencesManager.userName.ifBlank { "AIZA User" },
                    email = preferencesManager.userEmail,
                    phone = preferencesManager.userPhone
                )
                supabaseClient.from("profiles").upsert(profileDto)
            } catch (profileErr: kotlinx.coroutines.CancellationException) {
                throw profileErr
            } catch (profileErr: Exception) {
                Log.d(TAG_CHAT_SYNC, "Profile pre-sync note for userId=$userId: ${profileErr.message}")
            }

            val pairedGuardianUid = resolvePairedGuardianUserId(contact)
            val contactDto = EmergencyContactDto(
                id = guardianId,
                userId = userId,
                guardianUserId = pairedGuardianUid,
                name = contact.name.ifBlank { "Guardian" },
                phone = contact.phone.ifBlank { "+910000000000" },
                relationship = contact.relation.ifBlank { "Emergency Contact" },
                priority = contact.priority.ifBlank { "1" }
            )
            try {
                supabaseClient.from("emergency_contacts").insert(contactDto)
                Log.d(TAG_CHAT_RUNTIME, "Guardian contact INSERT success in public.emergency_contacts: guardianId=$guardianId, userId=$userId, guardianUserId=${pairedGuardianUid.orEmpty()}")
            } catch (insertErr: kotlinx.coroutines.CancellationException) {
                throw insertErr
            } catch (insertErr: Exception) {
                val errMsg = insertErr.message.orEmpty()
                if (errMsg.contains("23505") ||
                    errMsg.contains("duplicate key", ignoreCase = true) ||
                    errMsg.contains("already exists", ignoreCase = true)
                ) {
                    Log.d(TAG_CHAT_RUNTIME, "Guardian contact already exists in public.emergency_contacts (23505): guardianId=$guardianId")
                    try {
                        supabaseClient.from("emergency_contacts").upsert(contactDto)
                        Log.d(TAG_CHAT_RUNTIME, "Guardian contact UPSERT success in public.emergency_contacts: guardianId=$guardianId, userId=$userId")
                    } catch (upsertErr: kotlinx.coroutines.CancellationException) {
                        throw upsertErr
                    } catch (upsertErr: Exception) {
                        Log.d(TAG_CHAT_RUNTIME, "Guardian contact already present (23505); upsert note: ${upsertErr.message}")
                    }
                } else if (errMsg.contains("guardian_user_id", ignoreCase = true) || errMsg.contains("PGRST204")) {
                    val fallbackDto = contactDto.copy(guardianUserId = null)
                    supabaseClient.from("emergency_contacts").upsert(fallbackDto)
                } else {
                    throw insertErr
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                TAG_CHAT_RUNTIME,
                "Supabase emergency_contacts pre-sync failed: userId=$userId, guardianId=$guardianId, exceptionClass=${e.javaClass.name}, message=${e.message}"
            )
        }
    }

    suspend fun deleteContactFromSupabase(contactId: String) {
        val userId = preferencesManager.supabaseUserId.ifBlank { return }
        try {
            supabaseClient.from("emergency_contacts").delete {
                filter {
                    eq("id", contactId)
                    eq("user_id", userId)
                }
            }
            Log.d("SafetyRepository", "Emergency contact $contactId deleted from Supabase.")
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Supabase delete contact failed: ${e.message}")
        }
    }

    // --- Supabase SOS Alerts Dispatch & Realtime ---

    suspend fun recordSosAlertToSupabase(sosAlert: SosAlertDto): Result<Unit> {
        return try {
            supabaseClient.from("sos_alerts").insert(sosAlert)
            Log.d("SafetyRepository", "SOS alert recorded to Supabase with status ${sosAlert.status}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Supabase SOS alert record failed (offline fallback active): ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun fetchSosAlertsForAuthorizedUser(userId: String): Result<List<SosAlertDto>> {
        return try {
            val alerts = supabaseClient.from("sos_alerts")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<SosAlertDto>()
            Result.success(alerts)
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Fetch SOS alerts failed: ${e.message}")
            Result.failure(e)
        }
    }

    // --- Continuous Location Sharing (Independent of SOS) ---

    suspend fun pushUserLocationToSupabase(
        lat: Double,
        lng: Double,
        accuracy: Float = 0f,
        isSharing: Boolean = true,
        batteryLevel: Int? = null
    ): Result<Boolean> {
        val uid = preferencesManager.supabaseUserId.ifBlank { "anonymous_user" }
        val locationDto = com.example.data.supabase.UserLocationDto(
            userId = uid,
            latitude = lat,
            longitude = lng,
            accuracy = accuracy,
            isSharingEnabled = isSharing,
            batteryLevel = batteryLevel,
            timestamp = System.currentTimeMillis()
        )
        return try {
            supabaseClient.from("user_locations").upsert(locationDto)
            preferencesManager.lastLocationSyncTimestamp = System.currentTimeMillis()
            Log.d("SafetyRepository", "Continuous location pushed to Supabase (sharing=$isSharing, lat=$lat, lng=$lng)")
            Result.success(true)
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Failed to push continuous location to Supabase: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun fetchSharedLocationForUser(targetUserId: String): Result<com.example.data.supabase.UserLocationDto?> {
        return try {
            val list = supabaseClient.from("user_locations")
                .select {
                    filter {
                        eq("user_id", targetUserId)
                    }
                }
                .decodeList<com.example.data.supabase.UserLocationDto>()
            val loc = list.firstOrNull()
            Result.success(loc)
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Failed to fetch shared location for user $targetUserId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Prepares Realtime Channel observation for incoming or updating SOS alerts.
     */
    fun getSosAlertsRealtimeFlow(): Flow<PostgresAction>? {
        return try {
            val channel = supabaseClient.realtime.channel("sos_alerts_channel")
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "sos_alerts"
            }
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Realtime SOS channel preparation: ${e.message}")
            null
        }
    }

    // --- Preferences Delegate API ---
    var isOnboardingComplete: Boolean
        get() = preferencesManager.isOnboardingComplete
        set(value) { preferencesManager.isOnboardingComplete = value }

    var userName: String
        get() = preferencesManager.userName
        set(value) { preferencesManager.userName = value }

    var userPhone: String
        get() = preferencesManager.userPhone
        set(value) { preferencesManager.userPhone = value }

    var userEmail: String
        get() = preferencesManager.userEmail
        set(value) { preferencesManager.userEmail = value }

    var supabaseUserId: String
        get() = preferencesManager.supabaseUserId
        set(value) { preferencesManager.supabaseUserId = value }

    var isShakeEnabled: Boolean
        get() = preferencesManager.isShakeEnabled
        set(value) { preferencesManager.isShakeEnabled = value }

    var isLocationEnabled: Boolean
        get() = preferencesManager.isLocationEnabled
        set(value) { preferencesManager.isLocationEnabled = value }

    var isBluetoothEnabled: Boolean
        get() = preferencesManager.isBluetoothEnabled
        set(value) { preferencesManager.isBluetoothEnabled = value }

    var isVoiceSosEnabled: Boolean
        get() = preferencesManager.isVoiceSosEnabled
        set(value) { preferencesManager.isVoiceSosEnabled = value }

    var isWifiEnabled: Boolean
        get() = preferencesManager.isWifiEnabled
        set(value) { preferencesManager.isWifiEnabled = value }

    val shakeSensitivity: Float
        get() = preferencesManager.shakeSensitivity

    fun clearProfile() {
        preferencesManager.clearProfile()
    }

    // Cryptographic Key Initialization Helper
    fun checkAndGenerateKeyPair(): KeyPair? {
        if (preferencesManager.devicePublicKey.isEmpty() || preferencesManager.devicePrivateKey.isEmpty()) {
            try {
                val keyPair = SecurityUtils.generateKeyPair()
                preferencesManager.devicePublicKey = SecurityUtils.keyToString(keyPair.public)
                preferencesManager.devicePrivateKey = SecurityUtils.keyToString(keyPair.private)
                return keyPair
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    // --- Offline Location & Pending SOS Sync ---

    suspend fun saveTrackedLocation(
        lat: Double,
        lng: Double,
        accuracy: Float = 0f,
        isSos: Boolean = false
    ): Long {
        if (trackedLocationDao == null) return -1L
        return try {
            trackedLocationDao.insertLocation(
                TrackedLocationEntity(
                    latitude = lat,
                    longitude = lng,
                    accuracy = accuracy,
                    isSosAssociated = isSos,
                    isSynced = false
                )
            )
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Failed to persist tracked location: ${e.message}")
            -1L
        }
    }

    suspend fun syncPendingDataToSupabase(): Int {
        var syncedCount = 0
        // 1. Sync pending SOS logs
        try {
            val pendingLogs = sosLogDao.getUnsyncedSosLogs()
            for (log in pendingLogs) {
                var currentVoiceUrl = log.voiceClipUrl
                // If local voice clip exists and hasn't been uploaded yet, upload now without duplicate uploads
                if (log.voiceClipPath.isNotBlank() && currentVoiceUrl.isBlank()) {
                    val localFile = java.io.File(log.voiceClipPath)
                    if (localFile.exists() && localFile.length() > 0) {
                        val userId = preferencesManager.supabaseUserId.ifBlank { "anonymous_user" }
                        val uploadResult = com.example.data.DistressClipManager.uploadDistressClip(localFile, userId)
                        if (uploadResult.isSuccess) {
                            currentVoiceUrl = uploadResult.getOrNull() ?: ""
                            sosLogDao.updateVoiceClipUrl(log.id, currentVoiceUrl, "Pending_Sync")
                            Log.d("SafetyRepository", "Synchronized offline voice clip to Supabase Storage: $currentVoiceUrl")
                        }
                    }
                }

                val alertDto = SosAlertDto(
                    id = "sos_${log.id}_${log.timestamp}",
                    userId = preferencesManager.supabaseUserId.ifBlank { "anonymous_user" },
                    latitude = log.latitude,
                    longitude = log.longitude,
                    locationAvailable = log.latitude != 0.0 || log.longitude != 0.0,
                    triggeredAt = log.timestamp,
                    status = "RESOLVED",
                    triggerType = log.triggerSource,
                    createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date(log.timestamp)),
                    voiceClipUrl = currentVoiceUrl.ifBlank { null }
                )
                val result = recordSosAlertToSupabase(alertDto)
                if (result.isSuccess) {
                    sosLogDao.updateLogStatus(log.id, "Sent")
                    syncedCount++
                }
            }
        } catch (e: Exception) {
            Log.w("SafetyRepository", "Error syncing pending SOS logs: ${e.message}")
        }

        // 2. Sync pending continuous locations & mark unsynced locations as synced
        if (trackedLocationDao != null) {
            try {
                val unsyncedLocs = trackedLocationDao.getUnsyncedLocations()
                if (unsyncedLocs.isNotEmpty()) {
                    if (preferencesManager.isContinuousLocationSharingEnabled) {
                        val latest = unsyncedLocs.maxByOrNull { it.timestamp }
                        if (latest != null && (latest.latitude != 0.0 || latest.longitude != 0.0)) {
                            pushUserLocationToSupabase(
                                lat = latest.latitude,
                                lng = latest.longitude,
                                accuracy = latest.accuracy,
                                isSharing = true
                            )
                        }
                    }
                    trackedLocationDao.markAsSynced(unsyncedLocs.map { it.id })
                    Log.d("SafetyRepository", "Synced ${unsyncedLocs.size} pending location breadcrumbs.")
                }
            } catch (e: Exception) {
                Log.w("SafetyRepository", "Error syncing tracked locations: ${e.message}")
            }
        }

        // 3. Sync pending offline Guardian Chat messages, Guardian Account Pairing requests, & Emergency Incidents
        syncedCount += syncPendingChatMessagesToSupabase()
        syncedCount += syncPairingRequestsWithSupabase()
        syncedCount += syncPendingEmergencyIncidents()
        try {
            val receivedResult = syncReceivedEmergencyIncidents()
            syncedCount += receivedResult.getOrNull()?.size ?: 0
        } catch (_: Exception) {}

        return syncedCount
    }

    // --- Guardian Chat (1-to-1 Offline-First + Supabase PostgREST & Realtime) ---

    /**
     * Resolves the current authenticated user's canonical Supabase UUID.
     * Always prioritizes the active Supabase Auth session (`auth.uid()`) so RLS checks
     * (`auth.uid() = user_id`) succeed, and NEVER returns email, phone number, or "default_user".
     */
    fun resolveAuthenticatedChatUserId(explicitUserId: String? = null): String {
        val authUserId = try {
            supabaseClient.auth.currentUserOrNull()?.id
        } catch (_: Exception) {
            null
        }
        if (isValidUuid(authUserId)) {
            val clean = authUserId!!.trim()
            if (preferencesManager.supabaseUserId != clean) {
                preferencesManager.supabaseUserId = clean
            }
            return clean
        }
        if (isValidUuid(explicitUserId)) {
            return explicitUserId!!.trim()
        }
        val prefUserId = preferencesManager.supabaseUserId.trim()
        if (isValidUuid(prefUserId)) {
            return prefUserId
        }
        val generatedUuid = UUID.randomUUID().toString()
        preferencesManager.supabaseUserId = generatedUuid
        return generatedUuid
    }

    /**
     * Awaits Supabase Auth initialization and, if no active JWT session is present in memory
     * while cached user credentials exist, restores the session so `auth.uid()` is non-null
     * for PostgreSQL Row-Level Security (RLS).
     */
    suspend fun ensureAuthenticatedChatSession(explicitUserId: String? = null): String {
        if (chatRemoteDataSource != null) {
            return resolveAuthenticatedChatUserId(explicitUserId)
        }
        try {
            val initialized = kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                supabaseClient.auth.awaitInitialization()
                true
            }
            if (initialized == null) {
                Log.w(TAG_CHAT_RUNTIME, "Supabase auth.awaitInitialization timed out after 1500ms; proceeding with current auth state")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG_CHAT_RUNTIME,
                "Supabase auth.awaitInitialization note: exceptionClass=${e.javaClass.name}, message=${e.message}"
            )
        }

        var currentUser = try {
            supabaseClient.auth.currentUserOrNull()
        } catch (_: Exception) {
            null
        }
        val currentSession = try {
            supabaseClient.auth.currentSessionOrNull()
        } catch (_: Exception) {
            null
        }

        if (currentUser == null || currentSession == null) {
            val targetEmail = preferencesManager.userEmail.trim()
            val password = preferencesManager.userPassword
            if (targetEmail.isNotBlank() && targetEmail.contains("@") && password.isNotBlank()) {
                try {
                    Log.d(TAG_CHAT_RUNTIME, "Auth session restore started (no active session in memory)")
                    supabaseClient.auth.signInWith(Email) {
                        this.email = targetEmail
                        this.password = password
                    }
                    currentUser = supabaseClient.auth.currentUserOrNull()
                    Log.d(TAG_CHAT_RUNTIME, "Auth session restore succeeded: userId=${currentUser?.id.orEmpty()}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(
                        TAG_CHAT_RUNTIME,
                        "Auth session restore failed: exceptionClass=${e.javaClass.name}, message=${e.message}"
                    )
                }
            } else {
                Log.w(TAG_CHAT_RUNTIME, "No active Supabase Auth session and no cached credentials available.")
            }
        }

        val liveAuthUid = currentUser?.id?.trim()
        if (isValidUuid(liveAuthUid)) {
            if (preferencesManager.supabaseUserId != liveAuthUid) {
                preferencesManager.supabaseUserId = liveAuthUid!!
            }
            return liveAuthUid!!
        }

        val fallbackUid = resolveAuthenticatedChatUserId(explicitUserId)
        Log.w(
            TAG_CHAT_RUNTIME,
            "Using non-session fallback userId=$fallbackUid (liveAuthUid=${liveAuthUid.orEmpty()})"
        )
        return fallbackUid
    }

    /**
     * Observes local Room messages for the given canonical guardian UUID (`contact.contactId`),
     * ordered oldest to newest.
     */
    fun observeGuardianChatMessages(
        guardianId: String,
        explicitUserId: String? = null
    ): Flow<List<GuardianChatMessageEntity>> {
        val dao = guardianChatDao ?: return flowOf(emptyList())
        val userId = if (explicitUserId != null) {
            if (explicitUserId.isBlank()) "" else resolveAuthenticatedChatUserId(explicitUserId)
        } else {
            resolveAuthenticatedChatUserId()
        }
        return dao.observeMessagesForGuardian(userId = userId, guardianId = guardianId.trim())
    }

    /**
     * Offline-first send for a 1-to-1 Guardian Chat message.
     *
     * 1. Saves message immediately to local Room with status = "pending", isSynced = false.
     * 2. If offline, keeps status = "pending", isSynced = false.
     * 3. If online, invokes [syncPendingChatMessagesToSupabase] to upload the message to
     *    `public.guardian_chat_messages` and immediately update Room to `status = "sent", isSynced = true`.
     *    If the upload fails, keeps local message with `status = "pending", isSynced = false`.
     *
     * CRITICAL SAFETY ISOLATION: Never invokes SOS, calls, SMS, siren, flashlight, or hardware triggers.
     */
    suspend fun sendGuardianChatMessage(
        contact: ContactEntity,
        content: String,
        isOnline: Boolean,
        explicitUserId: String? = null
    ): Result<GuardianChatMessageEntity> {
        val trimmedContent = content.trim()
        if (trimmedContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message content cannot be empty."))
        }
        val guardianUuid = contact.contactId.trim()
        if (guardianUuid.isEmpty()) {
            return Result.failure(IllegalArgumentException("Guardian canonical contactId is required."))
        }

        val userId = ensureAuthenticatedChatSession(explicitUserId)
        val senderName = preferencesManager.userName.trim().ifBlank { "AIZA User" }
        val conversationId = GuardianChatMessageEntity.buildConversationId(userId, guardianUuid)
        val messageId = UUID.randomUUID().toString()
        val recipientUserId = resolveGuardianChatRecipientId(contact)

        // guardian_id always represents the emergency-contact UUID (contact.contactId).
        // recipient_id represents the actual guardian Supabase Auth user UUID when paired,
        // or "" when guardianUserId is null/unpaired (never uses phone number or contactId).
        val localMessage = GuardianChatMessageEntity(
            id = messageId,
            conversationId = conversationId,
            userId = userId,
            guardianId = guardianUuid,
            senderId = userId,
            senderName = senderName,
            recipientId = recipientUserId,
            content = trimmedContent,
            status = GuardianChatMessageEntity.STATUS_PENDING,
            createdAt = System.currentTimeMillis(),
            isSynced = false
        )

        Log.d(
            TAG_CHAT_RUNTIME,
            "SafetyRepository.sendGuardianChatMessage entered: messageId=$messageId, userId=$userId, guardianId=$guardianUuid, recipientId=$recipientUserId, isOnline=$isOnline"
        )
        guardianChatDao?.insertMessage(localMessage)
        Log.d(
            TAG_CHAT_RUNTIME,
            "Local Room insert (pending) completed: messageId=$messageId, userId=$userId, guardianId=$guardianUuid, isOnline=$isOnline"
        )

        if (!isOnline) {
            Log.d(
                TAG_CHAT_RUNTIME,
                "Device offline; skipping Supabase sync: messageId=$messageId, userId=$userId, guardianId=$guardianUuid, isOnline=$isOnline"
            )
            return Result.success(localMessage)
        }

        Log.d(
            TAG_CHAT_RUNTIME,
            "Calling syncPendingChatMessagesToSupabase: messageId=$messageId, userId=$userId, guardianId=$guardianUuid, isOnline=$isOnline"
        )
        syncPendingChatMessagesToSupabase(
            targetContact = contact,
            explicitUserId = userId
        )

        val updatedInRoom = guardianChatDao?.getMessageById(messageId) ?: localMessage
        Log.d(
            TAG_CHAT_RUNTIME,
            "SafetyRepository.sendGuardianChatMessage finished: messageId=$messageId, userId=${updatedInRoom.userId}, guardianId=${updatedInRoom.guardianId}, recipientId=${updatedInRoom.recipientId}, isOnline=$isOnline, status=${updatedInRoom.status}, isSynced=${updatedInRoom.isSynced}"
        )
        return Result.success(updatedInRoom)
    }

    /**
     * Phase 4A: Offline-first send for a normal 1-to-1 Guardian Chat LOCATION message.
     *
     * Validates that [latitude] and [longitude] are valid GPS coordinates (non-null, finite,
     * in-bounds, and not 0.0, 0.0). If invalid, returns [Result.failure] without inserting
     * or syncing any message.
     *
     * CRITICAL SAFETY ISOLATION: Never invokes SOS, calls, SMS, siren, flashlight, or emergency incidents.
     */
    suspend fun sendGuardianLocationChatMessage(
        contact: ContactEntity,
        latitude: Double?,
        longitude: Double?,
        locationTimestamp: Long?,
        locationStatus: String?,
        isOnline: Boolean,
        explicitUserId: String? = null
    ): Result<GuardianChatMessageEntity> {
        if (!GuardianChatMessageEntity.hasValidLocationCoordinates(latitude, longitude)) {
            return Result.failure(
                IllegalArgumentException("Current location is unavailable. Please enable GPS and try again.")
            )
        }
        val validLat = latitude!!
        val validLng = longitude!!
        val guardianUuid = contact.contactId.trim()
        if (guardianUuid.isEmpty()) {
            return Result.failure(IllegalArgumentException("Guardian canonical contactId is required."))
        }

        val userId = ensureAuthenticatedChatSession(explicitUserId)
        val senderName = preferencesManager.userName.trim().ifBlank { "AIZA User" }
        val conversationId = GuardianChatMessageEntity.buildConversationId(userId, guardianUuid)
        val messageId = UUID.randomUUID().toString()
        val recipientUserId = resolveGuardianChatRecipientId(contact)
        val now = System.currentTimeMillis()
        val resolvedTimestamp = locationTimestamp?.takeIf { it > 0L } ?: now
        val resolvedStatus = locationStatus?.trim()?.uppercase(java.util.Locale.US)
            ?.takeIf { it.isNotBlank() && it != EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN }
            ?: classifyLocationStatus(
                hasLiveFix = locationTimestamp == null || (now - resolvedTimestamp) <= 60_000L,
                hasCachedFix = true,
                locationTimestamp = resolvedTimestamp,
                nowMs = now
            )
        val summaryContent = String.format(
            java.util.Locale.US,
            "Shared current location (%.5f, %.5f)",
            validLat,
            validLng
        )

        val localMessage = GuardianChatMessageEntity(
            id = messageId,
            conversationId = conversationId,
            userId = userId,
            guardianId = guardianUuid,
            senderId = userId,
            senderName = senderName,
            recipientId = recipientUserId,
            content = summaryContent,
            status = GuardianChatMessageEntity.STATUS_PENDING,
            createdAt = now,
            isSynced = false,
            messageType = GuardianChatMessageEntity.TYPE_LOCATION,
            latitude = validLat,
            longitude = validLng,
            locationTimestamp = resolvedTimestamp,
            locationStatus = resolvedStatus
        )

        Log.d(
            TAG_CHAT_RUNTIME,
            "SafetyRepository.sendGuardianLocationChatMessage entered: messageId=$messageId, userId=$userId, guardianId=$guardianUuid, recipientId=$recipientUserId, lat=$validLat, lng=$validLng, status=$resolvedStatus, isOnline=$isOnline"
        )
        guardianChatDao?.insertMessage(localMessage)

        if (!isOnline) {
            return Result.success(localMessage)
        }

        syncPendingChatMessagesToSupabase(
            targetContact = contact,
            explicitUserId = userId
        )

        val updatedInRoom = guardianChatDao?.getMessageById(messageId) ?: localMessage
        return Result.success(updatedInRoom)
    }


    /**
     * Fetches existing messages for a guardian from Supabase and merges them into Room
     * without creating duplicates. Supports bidirectional conversations for paired guardians.
     */
    suspend fun fetchAndMergeGuardianChatMessages(
        guardianId: String,
        explicitUserId: String? = null,
        pairedGuardianUserId: String? = null
    ): Result<List<GuardianChatMessageEntity>> {
        val cleanGuardianId = guardianId.trim()
        val userId = ensureAuthenticatedChatSession(explicitUserId)
        val cleanPairedGuardianId = pairedGuardianUserId?.trim()?.takeIf { isValidUuid(it) }
        return try {
            val remoteMessages = activeChatRemote.fetchChatMessages(
                userId = userId,
                guardianId = cleanGuardianId,
                pairedGuardianUserId = cleanPairedGuardianId
            )
            val dao = guardianChatDao
            if (dao != null) {
                dao.reconcileGuardianMessagesUserId(
                    guardianId = cleanGuardianId,
                    userId = userId
                )
                for (dto in remoteMessages) {
                    val isDirectMatch = (dto.userId == userId && dto.guardianId == cleanGuardianId)
                    val isPairedMatch = cleanPairedGuardianId != null && (
                        (dto.senderId == userId && dto.recipientId == cleanPairedGuardianId) ||
                        (dto.senderId == cleanPairedGuardianId && dto.recipientId == userId)
                    )
                    if (!isDirectMatch && !isPairedMatch) continue

                    val rawStatus = dto.status.lowercase(java.util.Locale.US)
                    val isOutgoingUnpaired = dto.senderId == userId &&
                        (!isValidUuid(dto.recipientId) || dto.recipientId.equals(cleanGuardianId, ignoreCase = true))
                    val normalizedStatus = if (isOutgoingUnpaired &&
                        (rawStatus == GuardianChatMessageEntity.STATUS_DELIVERED ||
                            rawStatus == GuardianChatMessageEntity.STATUS_READ)
                    ) {
                        GuardianChatMessageEntity.STATUS_SENT
                    } else {
                        rawStatus
                    }

                    // For paired messages from the other user, associate with local cleanGuardianId
                    // and local authenticated userId so Room Flow queries for (userId, cleanGuardianId) find them
                    val normalizedDto = if (isPairedMatch) {
                        dto.copy(
                            userId = userId,
                            guardianId = cleanGuardianId,
                            conversationId = GuardianChatMessageEntity.buildConversationId(userId, cleanGuardianId),
                            status = normalizedStatus
                        )
                    } else {
                        dto.copy(status = normalizedStatus)
                    }

                    val existing = dao.getMessageById(dto.id)
                    if (existing == null) {
                        dao.insertMessage(normalizedDto.toEntity(isSynced = true))
                    } else if (!existing.isSynced || existing.status != normalizedStatus) {
                        dao.updateMessageSyncStatus(
                            id = dto.id,
                            status = normalizedStatus,
                            isSynced = true
                        )
                    }
                }
            }
            val merged = dao?.getMessagesForGuardianOnce(userId, cleanGuardianId) ?: emptyList()
            Result.success(merged)
        } catch (e: Exception) {
            Log.w(TAG_CHAT_SYNC, "Failed to fetch remote guardian chat messages: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Validates and inserts an incoming Realtime chat message into Room if not already present.
     * Returns true if a new message was inserted, or false if ignored/deduplicated.
     */
    suspend fun handleIncomingRealtimeChatMessage(
        dto: GuardianChatMessageDto,
        expectedUserId: String,
        expectedGuardianId: String,
        expectedPairedGuardianUserId: String? = null
    ): Boolean {
        val cleanUserId = expectedUserId.trim()
        val cleanGuardianId = expectedGuardianId.trim()
        val cleanPairedGuardianId = expectedPairedGuardianUserId?.trim()?.takeIf { isValidUuid(it) }

        val isDirectMatch = (dto.userId == cleanUserId && dto.guardianId == cleanGuardianId)
        val isPairedMatch = cleanPairedGuardianId != null && (
            (dto.senderId == cleanUserId && dto.recipientId == cleanPairedGuardianId) ||
            (dto.senderId == cleanPairedGuardianId && dto.recipientId == cleanUserId)
        )

        if (!isDirectMatch && !isPairedMatch) {
            return false
        }

        val rawStatus = dto.status.lowercase(java.util.Locale.US)
        val isOutgoingUnpaired = dto.senderId == cleanUserId &&
            (!isValidUuid(dto.recipientId) || dto.recipientId.equals(cleanGuardianId, ignoreCase = true))
        val safeStatus = if (isOutgoingUnpaired &&
            (rawStatus == GuardianChatMessageEntity.STATUS_DELIVERED ||
                rawStatus == GuardianChatMessageEntity.STATUS_READ)
        ) {
            GuardianChatMessageEntity.STATUS_SENT
        } else {
            rawStatus
        }
        val safeDto = dto.copy(status = safeStatus)
        val dao = guardianChatDao ?: return false

        val normalizedDto = if (isPairedMatch) {
            safeDto.copy(
                userId = cleanUserId,
                guardianId = cleanGuardianId,
                conversationId = GuardianChatMessageEntity.buildConversationId(cleanUserId, cleanGuardianId)
            )
        } else {
            safeDto
        }

        val existing = dao.getMessageById(normalizedDto.id)
        return if (existing == null) {
            dao.insertMessage(normalizedDto.toEntity(isSynced = true))
            true
        } else {
            if (!existing.isSynced || existing.status != safeDto.status) {
                dao.updateMessageSyncStatus(
                    id = safeDto.id,
                    status = safeDto.status,
                    isSynced = true
                )
            }
            false
        }
    }

    /**
     * Subscribes to Supabase Realtime `postgres_changes` INSERT events on `public.guardian_chat_messages`
     * for the active guardian conversation. Returns an unsubscribe suspend function that must be called
     * when leaving the chat screen.
     */
    suspend fun subscribeToGuardianChatRealtime(
        guardianId: String,
        explicitUserId: String? = null,
        pairedGuardianUserId: String? = null
    ): suspend () -> Unit {
        val cleanGuardianId = guardianId.trim()
        val userId = ensureAuthenticatedChatSession(explicitUserId)
        val cleanPairedGuardianId = pairedGuardianUserId?.trim()?.takeIf { isValidUuid(it) }
        val channelName = "guardian_chat_${userId}_${cleanGuardianId}"
        return try {
            activeChatRemote.subscribeToGuardianChat(channelName) { dto ->
                handleIncomingRealtimeChatMessage(
                    dto = dto,
                    expectedUserId = userId,
                    expectedGuardianId = cleanGuardianId,
                    expectedPairedGuardianUserId = cleanPairedGuardianId
                )
            }
        } catch (e: Exception) {
            Log.w(TAG_CHAT_SYNC, "Realtime subscription error: ${e.message}")
            return {}
        }
    }

    /**
     * Uploads any locally pending (`isSynced == false`) Guardian Chat messages to Supabase.
     * Successfully synced messages are immediately marked `status = "sent", isSynced = true` in Room;
     * failed messages remain `"pending"` in Room and the failure reason is logged and stored in [lastChatSyncError].
     */
    suspend fun syncPendingChatMessagesToSupabase(
        targetContact: ContactEntity? = null,
        explicitUserId: String? = null
    ): Int = chatSyncMutex.withLock {
        Log.d(
            TAG_CHAT_RUNTIME,
            "syncPendingChatMessagesToSupabase entered: explicitUserId=${explicitUserId.orEmpty()}, guardianId=${targetContact?.contactId.orEmpty()}"
        )
        val dao = guardianChatDao ?: return@withLock 0
        var syncedCount = 0
        try {
            val pendingMessages = dao.getPendingMessages()
            Log.d(
                TAG_CHAT_RUNTIME,
                "syncPendingChatMessagesToSupabase WHERE isSynced = 0 query: count=${pendingMessages.size}, messageIds=${pendingMessages.map { it.id }}"
            )
            if (pendingMessages.isEmpty()) {
                return@withLock 0
            }

            val authenticatedUserId = ensureAuthenticatedChatSession(explicitUserId)
            Log.d(
                TAG_CHAT_RUNTIME,
                "syncPendingChatMessagesToSupabase resolved userId=$authenticatedUserId, pendingCount=${pendingMessages.size}"
            )

            val localContactsByGuardianId = try {
                allContacts.first().associateBy { it.contactId.trim() }
            } catch (_: Exception) {
                emptyMap()
            }
            val syncedGuardianIds = mutableSetOf<String>()

            for (msg in pendingMessages) {
                val effectiveUserId = if (chatRemoteDataSource == null) {
                    authenticatedUserId
                } else if (isValidUuid(msg.userId)) {
                    msg.userId.trim()
                } else {
                    authenticatedUserId
                }

                try {
                    val guardianContact = targetContact?.takeIf {
                        it.contactId.trim() == msg.guardianId
                    } ?: localContactsByGuardianId[msg.guardianId]

                    val effectiveRecipientId = when {
                        guardianContact != null -> resolveGuardianChatRecipientId(guardianContact)
                        isValidUuid(msg.recipientId) && !msg.recipientId.equals(msg.guardianId, ignoreCase = true) -> msg.recipientId.trim()
                        else -> ""
                    }

                    // Reconcile local Room message if its userId or recipientId differed
                    val reconciledMsg = if (msg.userId != effectiveUserId || msg.recipientId != effectiveRecipientId) {
                        val updated = msg.copy(
                            userId = effectiveUserId,
                            senderId = if (msg.senderId == msg.userId) effectiveUserId else msg.senderId,
                            recipientId = effectiveRecipientId,
                            conversationId = GuardianChatMessageEntity.buildConversationId(
                                effectiveUserId,
                                msg.guardianId
                            )
                        )
                        dao.insertMessage(updated)
                        updated
                    } else {
                        msg
                    }

                    // Ensure the guardian contact exists in public.emergency_contacts so the
                    // guardian_chat_messages RLS INSERT policy EXISTS(...) check passes.
                    if (chatRemoteDataSource == null && syncedGuardianIds.add(reconciledMsg.guardianId)) {
                        if (guardianContact != null) {
                            syncSingleContactToSupabase(
                                contact = guardianContact,
                                explicitUserId = effectiveUserId
                            )
                        } else {
                            syncSingleContactToSupabase(
                                contact = ContactEntity(
                                    contactId = reconciledMsg.guardianId,
                                    userId = effectiveUserId,
                                    name = "Guardian",
                                    phone = "+910000000000",
                                    relation = "Emergency Contact",
                                    priority = "1"
                                ),
                                explicitUserId = effectiveUserId
                            )
                        }
                    }

                    val dto = reconciledMsg.toDto(
                        statusOverride = GuardianChatMessageEntity.STATUS_SENT
                    )
                    Log.d(
                        TAG_CHAT_RUNTIME,
                        "syncPendingChatMessagesToSupabase calling upsertChatMessage: messageId=${dto.id}, userId=${dto.userId}, guardianId=${dto.guardianId}, recipientId=${dto.recipientId}"
                    )
                    activeChatRemote.upsertChatMessage(dto)

                    Log.d(
                        TAG_CHAT_RUNTIME,
                        "Room status update started: messageId=${reconciledMsg.id}, userId=$effectiveUserId, guardianId=${reconciledMsg.guardianId}"
                    )
                    dao.updateMessageSyncStatus(
                        id = reconciledMsg.id,
                        status = GuardianChatMessageEntity.STATUS_SENT,
                        isSynced = true
                    )
                    val updatedRow = dao.getMessageById(reconciledMsg.id)
                    Log.d(
                        TAG_CHAT_RUNTIME,
                        "Room status update succeeded: messageId=${reconciledMsg.id}, userId=$effectiveUserId, guardianId=${reconciledMsg.guardianId}, status=${updatedRow?.status}, isSynced=${updatedRow?.isSynced}"
                    )
                    lastChatSyncError = null
                    syncedCount++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastChatSyncError = "${e.javaClass.name}: ${e.message}"
                    Log.e(
                        TAG_CHAT_RUNTIME,
                        "Supabase INSERT failed in syncPendingChatMessagesToSupabase: messageId=${msg.id}, userId=$effectiveUserId, guardianId=${msg.guardianId}, exceptionClass=${e.javaClass.name}, message=${e.message}",
                        e
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastChatSyncError = "${e.javaClass.name}: ${e.message}"
            Log.e(
                TAG_CHAT_RUNTIME,
                "syncPendingChatMessagesToSupabase top-level failure: exceptionClass=${e.javaClass.name}, message=${e.message}",
                e
            )
        }
        return@withLock syncedCount
    }

    var isContinuousLocationSharingEnabled: Boolean
        get() = preferencesManager.isContinuousLocationSharingEnabled
        set(value) { preferencesManager.isContinuousLocationSharingEnabled = value }

    fun getPublicKey(): String {
        return preferencesManager.devicePublicKey
    }

    // --- Guardian Account Pairing / Invitation (Explicit Guardian Approval Flow) ---

    private fun parsePairingRequestJson(obj: JsonObject): GuardianPairingRequestDto? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val requesterUserId = obj["requester_user_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val guardianEmail = obj["guardian_email"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val emergencyContactId = (
            obj["emergency_contact_id"]?.jsonPrimitive?.contentOrNull
                ?: obj["contact_id"]?.jsonPrimitive?.contentOrNull
            )?.trim().orEmpty()
        if (id.isEmpty() || requesterUserId.isEmpty() || guardianEmail.isEmpty() || emergencyContactId.isEmpty()) {
            return null
        }
        val guardianUserId = obj["guardian_user_id"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        val status = obj["status"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: GuardianPairingRequestEntity.STATUS_PENDING

        val rawCreatedAt = obj["created_at"]?.jsonPrimitive
        val createdAtMillis = rawCreatedAt?.longOrNull ?: run {
            val str = rawCreatedAt?.contentOrNull?.trim().orEmpty()
            parseIsoTimestampOrDefault(str)
        }

        val requesterName = obj["requester_name"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { !it.equals("null", ignoreCase = true) }
            .orEmpty()
        val contactName = (
            obj["contact_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
            )?.trim()
            ?.takeIf { !it.equals("null", ignoreCase = true) }
            .orEmpty()
        val relationship = (
            obj["relationship"]?.jsonPrimitive?.contentOrNull
                ?: obj["relation"]?.jsonPrimitive?.contentOrNull
            )?.trim()
            ?.takeIf { !it.equals("null", ignoreCase = true) }
            .orEmpty()
        val guardianPhone = (
            obj["guardian_phone"]?.jsonPrimitive?.contentOrNull
                ?: obj["phone"]?.jsonPrimitive?.contentOrNull
            )?.trim()
            ?.takeIf { !it.equals("null", ignoreCase = true) }
            .orEmpty()

        return GuardianPairingRequestDto(
            id = id,
            requesterUserId = requesterUserId,
            guardianEmail = guardianEmail,
            emergencyContactId = emergencyContactId,
            guardianUserId = guardianUserId,
            status = status,
            createdAt = createdAtMillis,
            requesterName = requesterName,
            contactName = contactName,
            relationship = relationship,
            guardianPhone = guardianPhone
        )
    }

    private suspend fun enrichIncomingPairingRequestDto(dto: GuardianPairingRequestDto): GuardianPairingRequestDto {
        var enriched = dto
        if (enriched.contactName.isBlank() || enriched.relationship.isBlank() || enriched.guardianPhone.isBlank()) {
            try {
                val contactObj = supabaseClient.from("emergency_contacts")
                    .select {
                        filter {
                            eq("id", enriched.emergencyContactId)
                        }
                    }
                    .decodeList<JsonObject>()
                    .firstOrNull()
                if (contactObj != null) {
                    val cName = contactObj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val cRel = contactObj["relationship"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val cPhone = contactObj["phone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    enriched = enriched.copy(
                        contactName = enriched.contactName.ifBlank { cName },
                        relationship = enriched.relationship.ifBlank { cRel },
                        guardianPhone = enriched.guardianPhone.ifBlank { cPhone }
                    )
                }
            } catch (_: Exception) {}
        }
        if (enriched.requesterName.isBlank()) {
            try {
                val profileObj = supabaseClient.from("profiles")
                    .select {
                        filter {
                            eq("id", enriched.requesterUserId)
                        }
                    }
                    .decodeList<JsonObject>()
                    .firstOrNull()
                if (profileObj != null) {
                    val pName = profileObj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (pName.isNotBlank()) {
                        enriched = enriched.copy(requesterName = pName)
                    }
                }
            } catch (_: Exception) {}
        }
        return enriched
    }

    private val defaultPairingRemoteDataSource = object : GuardianPairingRemoteDataSource {
        override suspend fun upsertPairingRequest(dto: GuardianPairingRequestDto) {
            val normalizedEmail = dto.guardianEmail.trim().lowercase(java.util.Locale.US)
            val fullPayload = buildJsonObject {
                put("id", dto.id)
                put("requester_user_id", dto.requesterUserId)
                put("guardian_email", normalizedEmail)
                put("emergency_contact_id", dto.emergencyContactId)
                put("guardian_user_id", dto.guardianUserId)
                put("status", dto.status)
                put("created_at", dto.createdAt)
                if (dto.requesterName.isNotBlank()) put("requester_name", dto.requesterName)
                if (dto.contactName.isNotBlank()) put("contact_name", dto.contactName)
                if (dto.relationship.isNotBlank()) put("relationship", dto.relationship)
                if (dto.guardianPhone.isNotBlank()) put("guardian_phone", dto.guardianPhone)
            }
            try {
                supabaseClient.from("guardian_pairing_requests").insert(fullPayload)
            } catch (firstErr: kotlinx.coroutines.CancellationException) {
                throw firstErr
            } catch (firstErr: Exception) {
                val corePayloadWithCreatedAt = buildJsonObject {
                    put("id", dto.id)
                    put("requester_user_id", dto.requesterUserId)
                    put("guardian_email", normalizedEmail)
                    put("emergency_contact_id", dto.emergencyContactId)
                    put("guardian_user_id", dto.guardianUserId)
                    put("status", dto.status)
                    put("created_at", dto.createdAt)
                }
                try {
                    supabaseClient.from("guardian_pairing_requests").insert(corePayloadWithCreatedAt)
                } catch (secondErr: kotlinx.coroutines.CancellationException) {
                    throw secondErr
                } catch (secondErr: Exception) {
                    val corePayloadMinimal = buildJsonObject {
                        put("id", dto.id)
                        put("requester_user_id", dto.requesterUserId)
                        put("guardian_email", normalizedEmail)
                        put("emergency_contact_id", dto.emergencyContactId)
                        put("guardian_user_id", dto.guardianUserId)
                        put("status", dto.status)
                    }
                    supabaseClient.from("guardian_pairing_requests").insert(corePayloadMinimal)
                }
            }
        }

        override suspend fun fetchPairingRequestsForRequester(
            requesterUserId: String
        ): List<GuardianPairingRequestDto> {
            val rawList = supabaseClient.from("guardian_pairing_requests")
                .select {
                    filter {
                        eq("requester_user_id", requesterUserId.trim())
                    }
                }
                .decodeList<JsonObject>()
            return rawList.mapNotNull { parsePairingRequestJson(it) }
        }

        override suspend fun fetchPairingRequestsForGuardianEmail(
            guardianEmail: String
        ): List<GuardianPairingRequestDto> {
            val normalizedEmail = guardianEmail.trim().lowercase(java.util.Locale.US)
            if (!isValidPairingEmail(normalizedEmail)) {
                return emptyList()
            }
            val rawList = try {
                supabaseClient.from("guardian_pairing_requests")
                    .select {
                        filter {
                            ilike("guardian_email", normalizedEmail)
                            eq("status", GuardianPairingRequestEntity.STATUS_PENDING)
                        }
                    }
                    .decodeList<JsonObject>()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                supabaseClient.from("guardian_pairing_requests")
                    .select()
                    .decodeList<JsonObject>()
            }

            return rawList
                .mapNotNull { parsePairingRequestJson(it) }
                .filter { dto ->
                    dto.guardianEmail.trim().lowercase(java.util.Locale.US) == normalizedEmail &&
                        dto.status.trim().equals(GuardianPairingRequestEntity.STATUS_PENDING, ignoreCase = true)
                }
                .map { dto -> enrichIncomingPairingRequestDto(dto) }
        }

        override suspend fun updatePairingRequestDecision(
            requestId: String,
            status: String,
            guardianUserId: String?
        ) {
            val payload = buildJsonObject {
                put("status", status)
                put("guardian_user_id", guardianUserId)
            }
            supabaseClient.from("guardian_pairing_requests").update(payload) {
                filter {
                    eq("id", requestId.trim())
                }
            }
        }
    }

    private val activePairingRemote: GuardianPairingRemoteDataSource
        get() = pairingRemoteDataSource ?: defaultPairingRemoteDataSource

    fun observePairingRequests(): Flow<List<GuardianPairingRequestEntity>> {
        return guardianPairingRequestDao?.observeAllRequests() ?: flowOf(emptyList())
    }

    /**
     * Resolves the current authenticated Supabase user's real UUID and real email for Guardian Pairing.
     *
     * Rules enforced:
     * - NEVER uses placeholder UUIDs, "default_user", or randomly generated UUIDs.
     * - NEVER uses phone-derived fake emails or @example.com emails.
     * - Restores the active Supabase Auth session if needed when running against live Supabase.
     */
    suspend fun resolveAuthenticatedPairingIdentity(
        explicitUserId: String? = null,
        explicitEmail: String? = null
    ): Pair<String?, String?> {
        if (pairingRemoteDataSource == null) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                    supabaseClient.auth.awaitInitialization()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}

            var liveUser = try {
                supabaseClient.auth.currentUserOrNull()
                    ?: supabaseClient.auth.currentSessionOrNull()?.user
            } catch (_: Exception) {
                null
            }

            if (liveUser == null) {
                val cachedEmail = preferencesManager.userEmail.trim()
                val cachedPassword = preferencesManager.userPassword
                if (isValidPairingEmail(cachedEmail) && cachedPassword.isNotBlank()) {
                    try {
                        supabaseClient.auth.signInWith(Email) {
                            this.email = cachedEmail
                            this.password = cachedPassword
                        }
                        liveUser = supabaseClient.auth.currentUserOrNull()
                            ?: supabaseClient.auth.currentSessionOrNull()?.user
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                }
            }

            val liveUid = liveUser?.id?.trim()?.takeIf { isRealAuthenticatedUserUuid(it) }
            val liveEmail = liveUser?.email?.trim()?.lowercase(java.util.Locale.US)
                ?.takeIf { isValidPairingEmail(it) }

            if (liveUid != null && preferencesManager.supabaseUserId != liveUid) {
                preferencesManager.supabaseUserId = liveUid
            }
            if (liveEmail != null && !preferencesManager.userEmail.equals(liveEmail, ignoreCase = true)) {
                preferencesManager.userEmail = liveEmail
            }

            val resolvedUid = liveUid
                ?: explicitUserId?.trim()?.takeIf { isRealAuthenticatedUserUuid(it) }
                ?: preferencesManager.supabaseUserId.trim().takeIf { isRealAuthenticatedUserUuid(it) }
            val resolvedEmail = liveEmail
                ?: explicitEmail?.trim()?.lowercase(java.util.Locale.US)?.takeIf { isValidPairingEmail(it) }
                ?: preferencesManager.userEmail.trim().lowercase(java.util.Locale.US).takeIf { isValidPairingEmail(it) }

            return Pair(resolvedUid, resolvedEmail)
        } else {
            val liveUid = try {
                (supabaseClient.auth.currentUserOrNull() ?: supabaseClient.auth.currentSessionOrNull()?.user)?.id?.trim()
            } catch (_: Exception) {
                null
            }?.takeIf { isRealAuthenticatedUserUuid(it) }
            val liveEmail = try {
                (supabaseClient.auth.currentUserOrNull() ?: supabaseClient.auth.currentSessionOrNull()?.user)?.email?.trim()
            } catch (_: Exception) {
                null
            }?.lowercase(java.util.Locale.US)?.takeIf { isValidPairingEmail(it) }

            val resolvedUid = explicitUserId?.trim()?.takeIf { isRealAuthenticatedUserUuid(it) }
                ?: liveUid
                ?: preferencesManager.supabaseUserId.trim().takeIf { isRealAuthenticatedUserUuid(it) }
            val resolvedEmail = explicitEmail?.trim()?.lowercase(java.util.Locale.US)?.takeIf { isValidPairingEmail(it) }
                ?: liveEmail
                ?: preferencesManager.userEmail.trim().lowercase(java.util.Locale.US).takeIf { isValidPairingEmail(it) }

            return Pair(resolvedUid, resolvedEmail)
        }
    }

    /**
     * Creates a `PENDING` Guardian Account Pairing request for [contact] using [guardianEmail].
     *
     * CRITICAL SECURITY RULES:
     * - NEVER queries `auth.users` from the client.
     * - NEVER assigns `guardianUserId` merely because an email was entered.
     * - NEVER uses a phone number or `contactId` as `guardianUserId`.
     * - Never asks for or stores a password.
     * - Leaves `contact.guardianUserId` untouched (`null` if unpaired) until the guardian
     *   explicitly accepts the request while authenticated in their own AIZA account.
     */
    suspend fun createGuardianPairingRequest(
        contact: ContactEntity,
        guardianEmail: String,
        explicitRequesterUserId: String? = null
    ): Result<GuardianPairingRequestEntity> {
        val normalizedEmail = guardianEmail.trim().lowercase(java.util.Locale.US)
        if (!isValidPairingEmail(normalizedEmail)) {
            return Result.failure(
                IllegalArgumentException("Please enter a valid guardian AIZA account email address.")
            )
        }
        val canonicalContactId = contact.contactId.trim()
        if (!isRealAuthenticatedUserUuid(canonicalContactId)) {
            return Result.failure(
                IllegalArgumentException("Emergency contact must have a valid canonical contactId UUID.")
            )
        }

        if (explicitRequesterUserId != null && !isRealAuthenticatedUserUuid(explicitRequesterUserId)) {
            return Result.failure(
                IllegalArgumentException("Authenticated requester user UUID is required to send a pairing request.")
            )
        }

        val (requesterUserId, _) = resolveAuthenticatedPairingIdentity(
            explicitUserId = explicitRequesterUserId
        )
        if (!isRealAuthenticatedUserUuid(requesterUserId)) {
            return Result.failure(
                IllegalStateException("Authenticated requester user UUID is required to send a pairing request.")
            )
        }

        val requestEntity = GuardianPairingRequestEntity(
            id = UUID.randomUUID().toString(),
            requesterUserId = requesterUserId!!,
            guardianEmail = normalizedEmail,
            emergencyContactId = canonicalContactId,
            guardianUserId = null, // Strictly null until explicit guardian acceptance
            status = GuardianPairingRequestEntity.STATUS_PENDING,
            createdAt = System.currentTimeMillis(),
            requesterName = preferencesManager.userName.trim(),
            contactName = contact.name.trim(),
            relationship = contact.relation.trim().ifBlank { "Emergency Contact" },
            guardianPhone = contact.phone.trim()
        )

        guardianPairingRequestDao?.insertRequest(requestEntity)

        try {
            // Ensure emergency contact row exists in public.emergency_contacts before inserting pairing request
            if (pairingRemoteDataSource == null) {
                syncSingleContactToSupabase(contact = contact, explicitUserId = requesterUserId)
            }
            activePairingRemote.upsertPairingRequest(requestEntity.toDto())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_CHAT_SYNC, "Pairing request stored locally; cloud sync note: ${e.message}")
        }

        return Result.success(requestEntity)
    }

    /**
     * Explicitly accepts a `PENDING` pairing request while the guardian is authenticated
     * in their own AIZA account.
     *
     * Rules enforced:
     * - [authenticatedGuardianUserId] (or live `auth.uid()`) MUST be a valid real Supabase Auth UUID
     *   (never generated, never placeholder, never "default_user").
     * - MUST NOT be a phone number.
     * - MUST NOT equal the emergency contact's `contactId`.
     * - Updates ONLY the selected pairing request (`status = 'ACCEPTED'`, `guardian_user_id = realAuthUuid`).
     */
    suspend fun acceptGuardianPairingRequest(
        requestId: String,
        authenticatedGuardianUserId: String? = null,
        authenticatedGuardianEmail: String? = null
    ): Result<ContactEntity?> {
        val cleanRequestId = requestId.trim()
        if (cleanRequestId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pairing request ID is required."))
        }

        val existingRequest = guardianPairingRequestDao?.getRequestById(cleanRequestId)
            ?: return Result.failure(IllegalStateException("Pairing request not found."))

        // Reject phone numbers or emergency contact UUIDs before resolving identity
        if (!authenticatedGuardianUserId.isNullOrBlank()) {
            val trimmedInputUid = authenticatedGuardianUserId.trim()
            if (trimmedInputUid.all { it.isDigit() || it == '+' }) {
                return Result.failure(
                    IllegalArgumentException("Phone number must never be used as guardianUserId.")
                )
            }
            if (trimmedInputUid.equals(existingRequest.emergencyContactId.trim(), ignoreCase = true)) {
                return Result.failure(
                    IllegalArgumentException("Emergency contact UUID must never be used as guardianUserId.")
                )
            }
            if (!isRealAuthenticatedUserUuid(trimmedInputUid)) {
                return Result.failure(
                    IllegalArgumentException("A valid authenticated guardian Supabase user UUID is required to accept.")
                )
            }
        }

        if (!authenticatedGuardianEmail.isNullOrBlank() && !isValidPairingEmail(authenticatedGuardianEmail)) {
            return Result.failure(
                IllegalArgumentException("A valid authenticated guardian email is required to accept.")
            )
        }

        val (rawGuardianUid, liveGuardianEmail) = resolveAuthenticatedPairingIdentity(
            explicitUserId = authenticatedGuardianUserId,
            explicitEmail = authenticatedGuardianEmail
        )

        if (rawGuardianUid.isNullOrEmpty() || !isRealAuthenticatedUserUuid(rawGuardianUid)) {
            return Result.failure(
                IllegalArgumentException("A valid authenticated guardian Supabase user UUID is required to accept.")
            )
        }
        if (rawGuardianUid.all { it.isDigit() || it == '+' }) {
            return Result.failure(
                IllegalArgumentException("Phone number must never be used as guardianUserId.")
            )
        }
        if (rawGuardianUid.equals(existingRequest.emergencyContactId.trim(), ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException("Emergency contact UUID must never be used as guardianUserId.")
            )
        }

        if (liveGuardianEmail.isNullOrEmpty() || !isValidPairingEmail(liveGuardianEmail)) {
            return Result.failure(
                IllegalArgumentException("Authenticated guardian email is required to accept a pairing request.")
            )
        }
        if (!liveGuardianEmail.equals(existingRequest.guardianEmail.trim(), ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException("Authenticated guardian email does not match the pairing request email.")
            )
        }

        // Update ONLY the selected pairing request in local Room
        guardianPairingRequestDao?.updateRequestStatus(
            id = cleanRequestId,
            status = GuardianPairingRequestEntity.STATUS_ACCEPTED,
            guardianUserId = rawGuardianUid
        )

        try {
            activePairingRemote.updatePairingRequestDecision(
                requestId = cleanRequestId,
                status = GuardianPairingRequestEntity.STATUS_ACCEPTED,
                guardianUserId = rawGuardianUid
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_CHAT_SYNC, "Pairing acceptance cloud update note: ${e.message}")
        }

        val currentContacts = try {
            allContacts.first()
        } catch (_: Exception) {
            emptyList()
        }
        val targetContact = currentContacts.firstOrNull {
            it.contactId.trim().equals(existingRequest.emergencyContactId.trim(), ignoreCase = true)
        }
        val updatedContact = if (targetContact != null) {
            if (rawGuardianUid == targetContact.phone.trim()) {
                return Result.failure(
                    IllegalArgumentException("Guardian phone number must never be used as guardianUserId.")
                )
            }
            val paired = targetContact.copy(
                guardianUserId = rawGuardianUid,
                updatedAt = System.currentTimeMillis()
            )
            updateContact(paired)
            paired
        } else {
            null
        }

        // Refresh the guardian request list after acceptance
        syncPairingRequestsWithSupabase(
            explicitRequesterUserId = rawGuardianUid,
            explicitGuardianEmail = liveGuardianEmail
        )

        return Result.success(updatedContact)
    }

    /**
     * Explicitly rejects a `PENDING` pairing request.
     * Updates ONLY the selected pairing request with `status = 'REJECTED'` and keeps `guardianUserId = null`.
     */
    suspend fun rejectGuardianPairingRequest(
        requestId: String,
        authenticatedGuardianUserId: String? = null,
        authenticatedGuardianEmail: String? = null
    ): Result<GuardianPairingRequestEntity> {
        val cleanRequestId = requestId.trim()
        if (cleanRequestId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pairing request ID is required."))
        }
        val existingRequest = guardianPairingRequestDao?.getRequestById(cleanRequestId)
            ?: return Result.failure(IllegalStateException("Pairing request not found."))

        if (!authenticatedGuardianEmail.isNullOrBlank()) {
            if (!isValidPairingEmail(authenticatedGuardianEmail) ||
                !authenticatedGuardianEmail.trim().equals(existingRequest.guardianEmail.trim(), ignoreCase = true)
            ) {
                return Result.failure(
                    IllegalArgumentException("Authenticated guardian email does not match the pairing request email.")
                )
            }
        }

        val rejectedRequest = existingRequest.copy(
            status = GuardianPairingRequestEntity.STATUS_REJECTED,
            guardianUserId = null
        )
        guardianPairingRequestDao?.updateRequestStatus(
            id = cleanRequestId,
            status = GuardianPairingRequestEntity.STATUS_REJECTED,
            guardianUserId = null
        )

        try {
            activePairingRemote.updatePairingRequestDecision(
                requestId = cleanRequestId,
                status = GuardianPairingRequestEntity.STATUS_REJECTED,
                guardianUserId = null
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_CHAT_SYNC, "Pairing rejection cloud update note: ${e.message}")
        }

        // Refresh the guardian request list after rejection
        val (resolvedUid, resolvedEmail) = resolveAuthenticatedPairingIdentity(
            explicitUserId = authenticatedGuardianUserId,
            explicitEmail = authenticatedGuardianEmail
        )
        syncPairingRequestsWithSupabase(
            explicitRequesterUserId = resolvedUid,
            explicitGuardianEmail = resolvedEmail
        )

        return Result.success(rejectedRequest)
    }

    /**
     * Synchronizes remote pairing requests for the current requester AND incoming pending requests
     * addressed to the current authenticated user's email (`LOWER(guardian_email) == LOWER(authEmail)`
     * and `status == 'PENDING'`). Also applies any explicitly `ACCEPTED` guardian user UUIDs to
     * Account A's local emergency contacts.
     */
    suspend fun syncPairingRequestsWithSupabase(
        explicitRequesterUserId: String? = null,
        explicitGuardianEmail: String? = null
    ): Int {
        val (userId, currentEmail) = resolveAuthenticatedPairingIdentity(
            explicitUserId = explicitRequesterUserId,
            explicitEmail = explicitGuardianEmail
        )
        var appliedCount = 0
        try {
            val currentContacts = try {
                allContacts.first().associateBy { it.contactId.trim() }
            } catch (_: Exception) {
                emptyMap()
            }

            if (isRealAuthenticatedUserUuid(userId)) {
                val remoteRequests = activePairingRemote.fetchPairingRequestsForRequester(userId!!)
                for (dto in remoteRequests) {
                    val localMatch = currentContacts[dto.emergencyContactId.trim()]
                    val existingEntity = guardianPairingRequestDao?.getRequestById(dto.id)
                    val mergedEntity = dto.toEntity().let { entity ->
                        entity.copy(
                            requesterName = entity.requesterName.ifBlank { existingEntity?.requesterName.orEmpty() },
                            contactName = entity.contactName.ifBlank { localMatch?.name ?: existingEntity?.contactName.orEmpty() },
                            relationship = entity.relationship.ifBlank { localMatch?.relation ?: existingEntity?.relationship.orEmpty() },
                            guardianPhone = entity.guardianPhone.ifBlank { localMatch?.phone ?: existingEntity?.guardianPhone.orEmpty() }
                        )
                    }
                    guardianPairingRequestDao?.insertRequest(mergedEntity)

                    val approvedUid = dto.guardianUserId?.trim()
                    if (dto.status.equals(GuardianPairingRequestEntity.STATUS_ACCEPTED, ignoreCase = true) &&
                        isRealAuthenticatedUserUuid(approvedUid) &&
                        !approvedUid.equals(dto.emergencyContactId.trim(), ignoreCase = true)
                    ) {
                        val contact = currentContacts[dto.emergencyContactId.trim()]
                        if (contact != null && contact.pairedGuardianUserId != approvedUid) {
                            updateContact(
                                contact.copy(
                                    guardianUserId = approvedUid,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            appliedCount++
                        }
                    }
                }
            }

            if (isValidPairingEmail(currentEmail)) {
                val normalizedGuardianEmail = currentEmail!!.trim().lowercase(java.util.Locale.US)
                val incomingRequests = activePairingRemote.fetchPairingRequestsForGuardianEmail(normalizedGuardianEmail)
                for (dto in incomingRequests) {
                    if (!dto.guardianEmail.trim().equals(normalizedGuardianEmail, ignoreCase = true)) {
                        continue
                    }
                    val localMatch = currentContacts[dto.emergencyContactId.trim()]
                    val existingEntity = guardianPairingRequestDao?.getRequestById(dto.id)
                    val mergedEntity = dto.toEntity().let { entity ->
                        entity.copy(
                            requesterName = entity.requesterName.ifBlank { existingEntity?.requesterName.orEmpty() },
                            contactName = entity.contactName.ifBlank { localMatch?.name ?: existingEntity?.contactName.orEmpty() },
                            relationship = entity.relationship.ifBlank { localMatch?.relation ?: existingEntity?.relationship.orEmpty() },
                            guardianPhone = entity.guardianPhone.ifBlank { localMatch?.phone ?: existingEntity?.guardianPhone.orEmpty() }
                        )
                    }
                    guardianPairingRequestDao?.insertRequest(mergedEntity)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG_CHAT_SYNC, "syncPairingRequestsWithSupabase note: ${e.message}")
        }
        return appliedCount
    }

    // --- Emergency Incidents (Phase 1-3: Local Persistence, Sender Sync, & Paired Guardian Delivery) ---

    private val defaultIncidentRemoteDataSource = object : EmergencyIncidentRemoteDataSource {
        override suspend fun upsertEmergencyIncident(dto: EmergencyIncidentDto) {
            supabaseClient.from("emergency_incidents").upsert(dto)
        }

        override suspend fun fetchEmergencyIncidentsForUser(userId: String): List<EmergencyIncidentDto> {
            return supabaseClient.from("emergency_incidents")
                .select {
                    filter {
                        eq("user_id", userId.trim())
                    }
                }
                .decodeList<EmergencyIncidentDto>()
        }

        override suspend fun fetchEmergencyIncidentByIdForGuardian(
            incidentId: String,
            guardianUserId: String
        ): EmergencyIncidentDto? {
            return supabaseClient.from("emergency_incidents")
                .select {
                    filter {
                        eq("id", incidentId.trim())
                    }
                }
                .decodeSingleOrNull<EmergencyIncidentDto>()
        }

        override suspend fun upsertIncidentRecipient(dto: EmergencyIncidentRecipientDto) {
            supabaseClient.from("emergency_incident_recipients").upsert(dto)
        }

        override suspend fun fetchRecipientRowsForIncident(
            incidentId: String,
            senderUserId: String
        ): List<EmergencyIncidentRecipientDto> {
            return try {
                supabaseClient.from("emergency_incident_recipients")
                    .select {
                        filter {
                            eq("incident_id", incidentId.trim())
                            eq("sender_user_id", senderUserId.trim())
                        }
                    }
                    .decodeList<EmergencyIncidentRecipientDto>()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("SafetyRepository", "fetchRecipientRowsForIncident note: ${e.message}")
                emptyList()
            }
        }

        override suspend fun fetchRecipientRowsForGuardian(
            guardianUserId: String
        ): List<EmergencyIncidentRecipientDto> {
            return try {
                supabaseClient.from("emergency_incident_recipients")
                    .select {
                        filter {
                            eq("guardian_user_id", guardianUserId.trim())
                        }
                    }
                    .decodeList<EmergencyIncidentRecipientDto>()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("SafetyRepository", "fetchRecipientRowsForGuardian note: ${e.message}")
                emptyList()
            }
        }

        override suspend fun acknowledgeRecipientDelivery(
            recipientId: String,
            incidentId: String,
            guardianUserId: String,
            deliveredAt: Long,
            updatedAt: Long
        ) {
            val ackPayload = GuardianRecipientDeliveryAckDto(
                deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
                deliveredAt = deliveredAt,
                lastError = null,
                updatedAt = updatedAt
            )
            supabaseClient.from("emergency_incident_recipients").update(ackPayload) {
                filter {
                    eq("id", recipientId.trim())
                    eq("incident_id", incidentId.trim())
                    eq("guardian_user_id", guardianUserId.trim())
                }
            }
        }

        override suspend fun isGuardianPairedWithSender(
            senderUserId: String,
            guardianUserId: String
        ): Boolean {
            val cleanSender = senderUserId.trim()
            val cleanGuardian = guardianUserId.trim()
            if (!isRealAuthenticatedUserUuid(cleanSender) ||
                !isRealAuthenticatedUserUuid(cleanGuardian) ||
                cleanSender.equals(cleanGuardian, ignoreCase = true)
            ) {
                return false
            }
            return try {
                val contacts = supabaseClient.from("emergency_contacts")
                    .select {
                        filter {
                            eq("user_id", cleanSender)
                            eq("guardian_user_id", cleanGuardian)
                        }
                    }
                    .decodeList<EmergencyContactDto>()
                contacts.any { dto ->
                    dto.userId.trim().equals(cleanSender, ignoreCase = true) &&
                        dto.guardianUserId?.trim()?.equals(cleanGuardian, ignoreCase = true) == true
                }
            } catch (e: Exception) {
                Log.w("SafetyRepository", "Remote guardian pairing lookup warning: ${e.message}")
                false
            }
        }

        override suspend fun fetchPairedGuardianUserIdsForSender(
            senderUserId: String
        ): List<String> {
            val cleanSender = senderUserId.trim()
            if (!isRealAuthenticatedUserUuid(cleanSender)) return emptyList()
            return try {
                val contacts = supabaseClient.from("emergency_contacts")
                    .select {
                        filter {
                            eq("user_id", cleanSender)
                        }
                    }
                    .decodeList<EmergencyContactDto>()
                contacts.mapNotNull { dto ->
                    val guid = dto.guardianUserId?.trim()
                    if (isRealAuthenticatedUserUuid(guid) &&
                        !guid.equals(cleanSender, ignoreCase = true) &&
                        !guid.equals(dto.id?.trim(), ignoreCase = true)
                    ) {
                        guid
                    } else {
                        null
                    }
                }.distinctBy { it.lowercase(java.util.Locale.US) }
            } catch (e: Exception) {
                Log.w("SafetyRepository", "fetchPairedGuardianUserIdsForSender warning: ${e.message}")
                emptyList()
            }
        }

        override suspend fun subscribeToGuardianIncidentRecipients(
            channelName: String,
            guardianUserId: String,
            onRecipientEvent: suspend (EmergencyIncidentRecipientDto) -> Unit
        ): suspend () -> Unit {
            val cleanGuardianId = guardianUserId.trim()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val seenUpdatedAtById = linkedMapOf<String, Long>()
            kotlinx.coroutines.flow.flow {
                while (true) {
                    kotlinx.coroutines.delay(15_000L)
                    emit(Unit)
                }
            }.onEach {
                try {
                    val rows = fetchRecipientRowsForGuardian(cleanGuardianId)
                    for (dto in rows) {
                        if (!dto.guardianUserId.trim().equals(cleanGuardianId, ignoreCase = true)) {
                            continue
                        }
                        val prevUpdated = seenUpdatedAtById[dto.id]
                        val isPendingOrSent = !dto.deliveryStatus.equals(
                            EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
                            ignoreCase = true
                        )
                        if (prevUpdated == null || dto.updatedAt > prevUpdated || isPendingOrSent) {
                            seenUpdatedAtById[dto.id] = dto.updatedAt
                            onRecipientEvent(dto)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d("SafetyRepository", "Guardian incident foreground poll note: ${e.message}")
                }
            }.launchIn(scope)

            return {
                try {
                    scope.cancel()
                } catch (_: Exception) {}
            }
        }

        override suspend fun fetchSenderDisplayName(senderUserId: String): String? {
            val cleanSender = senderUserId.trim()
            if (!isRealAuthenticatedUserUuid(cleanSender)) return null
            return try {
                val profile = supabaseClient.from("profiles")
                    .select {
                        filter {
                            eq("id", cleanSender)
                        }
                    }
                    .decodeSingleOrNull<ProfileDto>()
                AizaEmergencyNotificationManager.sanitizeSenderName(profile?.name)
            } catch (_: Exception) {
                null
            }
        }
    }

    private val activeIncidentRemote: EmergencyIncidentRemoteDataSource
        get() = incidentRemoteDataSource ?: defaultIncidentRemoteDataSource

    private val defaultPeopleWhoSavedMeRemoteDataSource = object : PeopleWhoSavedMeRemoteDataSource {
        override suspend fun fetchContactsWhoSavedUser(guardianUserId: String): List<EmergencyContactDto> {
            val cleanGuardianId = guardianUserId.trim()
            if (!isRealAuthenticatedUserUuid(cleanGuardianId)) return emptyList()
            return try {
                val contacts = supabaseClient.from("emergency_contacts")
                    .select {
                        filter {
                            eq("guardian_user_id", cleanGuardianId)
                        }
                    }
                    .decodeList<EmergencyContactDto>()
                contacts.filter {
                    isRealAuthenticatedUserUuid(it.userId) &&
                        it.guardianUserId?.trim()?.equals(cleanGuardianId, ignoreCase = true) == true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SafetyRepository", "fetchContactsWhoSavedUser warning: ${e.message}")
                emptyList()
            }
        }

        override suspend fun fetchSenderDisplayName(senderUserId: String): String? {
            return activeIncidentRemote.fetchSenderDisplayName(senderUserId)
        }

        override suspend fun fetchAcceptedPairingRequestsForGuardian(guardianUserId: String): List<GuardianPairingRequestDto> {
            val cleanGuardianId = guardianUserId.trim()
            if (!isRealAuthenticatedUserUuid(cleanGuardianId)) return emptyList()
            return try {
                val rawList = supabaseClient.from("guardian_pairing_requests")
                    .select {
                        filter {
                            eq("guardian_user_id", cleanGuardianId)
                            eq("status", GuardianPairingRequestEntity.STATUS_ACCEPTED)
                        }
                    }
                    .decodeList<JsonObject>()
                rawList.mapNotNull { parsePairingRequestJson(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private val activePeopleWhoSavedMeRemote: PeopleWhoSavedMeRemoteDataSource
        get() = peopleWhoSavedMeRemoteDataSource ?: defaultPeopleWhoSavedMeRemoteDataSource

    /**
     * Resolves the current authenticated Supabase user's UUID strictly.
     *
     * Rules enforced:
     * - NEVER uses email, phone number, "default_user", anonymous placeholder UUID,
     *   or randomly generated fallback identity.
     * - Returns null if no real authenticated Supabase user UUID is available.
     */
    fun resolveAuthenticatedIncidentUserId(explicitUserId: String? = null): String? {
        if (explicitUserId != null) {
            val trimmed = explicitUserId.trim()
            return if (isRealAuthenticatedUserUuid(trimmed)) trimmed else null
        }
        val liveUid = try {
            (supabaseClient.auth.currentUserOrNull()
                ?: supabaseClient.auth.currentSessionOrNull()?.user)?.id?.trim()
        } catch (_: Exception) {
            null
        }?.takeIf { isRealAuthenticatedUserUuid(it) }

        if (liveUid != null) {
            if (preferencesManager.supabaseUserId != liveUid) {
                preferencesManager.supabaseUserId = liveUid
            }
            return liveUid
        }
        return preferencesManager.supabaseUserId.trim().takeIf { isRealAuthenticatedUserUuid(it) }
    }

    /**
     * Observes the currently active emergency incident for the authenticated user, or null if none.
     */
    fun observeActiveEmergencyIncident(
        explicitUserId: String? = null
    ): Flow<EmergencyIncidentEntity?> {
        val dao = emergencyIncidentDao ?: return flowOf(null)
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId) ?: return flowOf(null)
        return dao.observeActiveIncident(userId)
    }

    /**
     * Observes all sent emergency incidents owned by the authenticated user, ordered newest first.
     * Strictly excludes received incidents or invalid identities.
     */
    fun observeEmergencyIncidents(
        explicitUserId: String? = null
    ): Flow<List<EmergencyIncidentEntity>> {
        val dao = emergencyIncidentDao ?: return flowOf(emptyList())
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId) ?: return flowOf(emptyList())
        return dao.observeIncidents(userId).map { list ->
            list.filter { isSentEmergencyForUser(it, userId) }
        }
    }

    /**
     * Retrieves the currently ACTIVE emergency incident for the authenticated user, or null if none.
     */
    suspend fun getActiveEmergencyIncident(
        explicitUserId: String? = null
    ): EmergencyIncidentEntity? {
        val dao = emergencyIncidentDao ?: return null
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId) ?: return null
        return dao.getActiveIncidentForUser(userId)
    }

    /**
     * Retrieves a single emergency incident by [id] scoped to the authenticated user.
     */
    suspend fun getEmergencyIncident(
        id: String,
        explicitUserId: String? = null
    ): EmergencyIncidentEntity? {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return null
        val dao = emergencyIncidentDao ?: return null
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId) ?: return null
        return dao.getIncidentByIdForUser(cleanId, userId)
    }

    // --- Phase 3: Paired Guardian Identification & Authorization ---

    /**
     * Identifies all paired AIZA guardian Supabase Auth UUIDs (`emergency_contacts.guardian_user_id`)
     * for the given authenticated sender ([explicitSenderUserId]).
     *
     * Rules enforced:
     * - Only returns contacts with a valid authenticated UUID in `guardianUserId` ([ContactEntity.pairedGuardianUserId]).
     * - Never returns unpaired contacts, phone numbers, email addresses, contact IDs, placeholder UUIDs,
     *   or the sender's own UUID.
     * - Deduplicates guardian UUIDs so multiple contacts pointing to the same guardian produce a single recipient.
     */
    suspend fun resolvePairedGuardianUserIds(
        explicitSenderUserId: String? = null
    ): List<String> {
        val senderUserId = resolveAuthenticatedIncidentUserId(explicitSenderUserId) ?: return emptyList()
        val currentSessionUid = resolveAuthenticatedIncidentUserId(null)
        val localContacts = try {
            allContacts.first()
        } catch (_: Exception) {
            emptyList()
        }

        val pairedGuardianIds = linkedMapOf<String, String>()

        for (contact in localContacts) {
            val pairedGuid = contact.pairedGuardianUserId?.trim() ?: continue
            if (!isRealAuthenticatedUserUuid(pairedGuid)) continue
            if (pairedGuid.equals(senderUserId, ignoreCase = true)) continue

            val contactOwnerId = contact.userId.trim()
            val belongsToSender = when {
                isRealAuthenticatedUserUuid(contactOwnerId) ->
                    contactOwnerId.equals(senderUserId, ignoreCase = true)
                else -> {
                    // Local contact created before userId was stamped: belongs to the active session user
                    val matchesSession = currentSessionUid != null &&
                        currentSessionUid.equals(senderUserId, ignoreCase = true)
                    if (matchesSession) {
                        try {
                            contactDao.updateContact(
                                encryptContact(contact.copy(userId = senderUserId))
                            )
                        } catch (_: Exception) {}
                    }
                    matchesSession
                }
            }

            if (belongsToSender) {
                val key = pairedGuid.lowercase(java.util.Locale.US)
                if (!pairedGuardianIds.containsKey(key)) {
                    pairedGuardianIds[key] = pairedGuid
                }
            }
        }

        try {
            val remoteGuardianIds = activeIncidentRemote.fetchPairedGuardianUserIdsForSender(senderUserId)
            for (rawGuid in remoteGuardianIds) {
                val guid = rawGuid.trim()
                if (isRealAuthenticatedUserUuid(guid) && !guid.equals(senderUserId, ignoreCase = true)) {
                    val key = guid.lowercase(java.util.Locale.US)
                    if (!pairedGuardianIds.containsKey(key)) {
                        pairedGuardianIds[key] = guid
                    }
                }
            }
        } catch (_: Exception) {}

        return pairedGuardianIds.values.toList()
    }

    /**
     * Verifies the canonical guardian pairing relationship between [senderUserId] and [guardianUserId]
     * through `emergency_contacts.guardian_user_id`.
     *
     * Never authorizes based on email text alone, contact name, phone number, or placeholder UUIDs.
     */
    suspend fun isGuardianAuthorizedForSender(
        senderUserId: String?,
        guardianUserId: String?
    ): Boolean {
        val cleanSender = senderUserId?.trim() ?: return false
        val cleanGuardian = guardianUserId?.trim() ?: return false
        if (!isRealAuthenticatedUserUuid(cleanSender) || !isRealAuthenticatedUserUuid(cleanGuardian)) {
            return false
        }
        if (cleanSender.equals(cleanGuardian, ignoreCase = true)) {
            return false
        }

        // 1. Check local emergency_contacts where user_id == senderUserId and guardian_user_id == guardianUserId
        val localContacts = try {
            allContacts.first()
        } catch (_: Exception) {
            emptyList()
        }
        val hasLocalContactPairing = localContacts.any { contact ->
            val pairedGuid = contact.pairedGuardianUserId?.trim()
            val ownerUid = contact.userId.trim()
            pairedGuid != null &&
                pairedGuid.equals(cleanGuardian, ignoreCase = true) &&
                isRealAuthenticatedUserUuid(ownerUid) &&
                ownerUid.equals(cleanSender, ignoreCase = true)
        }
        if (hasLocalContactPairing) {
            return true
        }

        // 2. Check local accepted guardian_pairing_requests linking requesterUserId -> guardianUserId
        val hasAcceptedPairingRequest = try {
            guardianPairingRequestDao?.observeAllRequests()?.first()?.any { req ->
                req.status.trim().equals(GuardianPairingRequestEntity.STATUS_ACCEPTED, ignoreCase = true) &&
                    req.requesterUserId.trim().equals(cleanSender, ignoreCase = true) &&
                    req.guardianUserId?.trim()?.equals(cleanGuardian, ignoreCase = true) == true &&
                    isRealAuthenticatedUserUuid(req.guardianUserId)
            } == true
        } catch (_: Exception) {
            false
        }
        if (hasAcceptedPairingRequest) {
            return true
        }

        // 3. Check remote public.emergency_contacts.guardian_user_id
        return try {
            activeIncidentRemote.isGuardianPairedWithSender(cleanSender, cleanGuardian)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates or reuses a single [EmergencyIncidentRecipientEntity] for `(incidentId, guardianUserId)`
     * after verifying that [guardianUserId] is an authenticated paired guardian of the incident owner.
     *
     * Enforces:
     * - Sender identity = authenticated Supabase UUID.
     * - Guardian identity = authenticated Supabase UUID paired via `emergency_contacts.guardian_user_id`.
     * - Composite uniqueness on `(incidentId, guardianUserId)`: reuses existing recipient row if present.
     */
    suspend fun createIncidentRecipientForGuardian(
        incidentId: String,
        guardianUserId: String,
        explicitSenderUserId: String? = null
    ): Result<EmergencyIncidentRecipientEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val senderUserId = resolveAuthenticatedIncidentUserId(explicitSenderUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated sender Supabase user UUID is required.")
            )
        val cleanIncidentId = incidentId.trim()
        if (!isRealAuthenticatedUserUuid(cleanIncidentId)) {
            return@withLock Result.failure(
                IllegalArgumentException("Emergency incident id must be a valid UUID.")
            )
        }
        val cleanGuardianId = guardianUserId.trim()
        if (!isRealAuthenticatedUserUuid(cleanGuardianId)) {
            return@withLock Result.failure(
                IllegalArgumentException("Guardian user id must be a valid authenticated Supabase UUID.")
            )
        }
        if (cleanGuardianId.equals(senderUserId, ignoreCase = true)) {
            return@withLock Result.failure(
                IllegalArgumentException("Sender cannot be their own guardian recipient.")
            )
        }
        val incident = dao.getIncidentByIdForUser(cleanIncidentId, senderUserId)
            ?: return@withLock Result.failure(
                IllegalStateException("Emergency incident not found for authenticated sender.")
            )

        val pairedGuardianIds = resolvePairedGuardianUserIds(senderUserId)
        val isPaired = pairedGuardianIds.any { it.equals(cleanGuardianId, ignoreCase = true) } ||
            isGuardianAuthorizedForSender(senderUserId, cleanGuardianId)
        if (!isPaired) {
            return@withLock Result.failure(
                SecurityException("Guardian '$cleanGuardianId' is not paired with sender '$senderUserId' in emergency_contacts.")
            )
        }

        val existing = dao.getRecipientForIncidentAndGuardian(cleanIncidentId, cleanGuardianId)
        if (existing != null) {
            return@withLock Result.success(existing)
        }

        val now = System.currentTimeMillis()
        val recipient = EmergencyIncidentRecipientEntity(
            id = UUID.randomUUID().toString(),
            incidentId = incident.id,
            senderUserId = senderUserId,
            guardianUserId = cleanGuardianId,
            deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_PENDING,
            deliveredAt = null,
            lastError = null,
            createdAt = now,
            updatedAt = now
        )
        dao.insertIncidentRecipient(recipient)
        return@withLock Result.success(recipient)
    }

    /**
     * Prepares and persists [EmergencyIncidentRecipientEntity] rows for all paired guardians of
     * the incident owner without creating duplicate `(incidentId, guardianUserId)` rows.
     */
    suspend fun prepareIncidentRecipientsForSender(
        incidentId: String,
        explicitSenderUserId: String? = null
    ): Result<List<EmergencyIncidentRecipientEntity>> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val senderUserId = resolveAuthenticatedIncidentUserId(explicitSenderUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated sender Supabase user UUID is required.")
            )
        val cleanIncidentId = incidentId.trim()
        val incident = dao.getIncidentByIdForUser(cleanIncidentId, senderUserId)
            ?: return@withLock Result.failure(
                IllegalStateException("Emergency incident not found for authenticated sender.")
            )
        val recipients = prepareIncidentRecipientsInternal(dao, incident.id, senderUserId)
        return@withLock Result.success(recipients)
    }

    private suspend fun prepareIncidentRecipientsInternal(
        dao: EmergencyIncidentDao,
        incidentId: String,
        senderUserId: String
    ): List<EmergencyIncidentRecipientEntity> {
        val pairedGuardianIds = resolvePairedGuardianUserIds(senderUserId)
        if (pairedGuardianIds.isEmpty()) {
            return dao.getRecipientsForIncident(incidentId)
        }
        val now = System.currentTimeMillis()
        for (guardianUid in pairedGuardianIds) {
            val existing = dao.getRecipientForIncidentAndGuardian(incidentId, guardianUid)
            if (existing == null) {
                dao.insertIncidentRecipient(
                    EmergencyIncidentRecipientEntity(
                        id = UUID.randomUUID().toString(),
                        incidentId = incidentId,
                        senderUserId = senderUserId,
                        guardianUserId = guardianUid,
                        deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_PENDING,
                        deliveredAt = null,
                        lastError = null,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
        return dao.getRecipientsForIncident(incidentId)
    }

    /**
     * Returns all local [EmergencyIncidentRecipientEntity] records for a given [incidentId].
     */
    suspend fun getIncidentRecipients(
        incidentId: String
    ): List<EmergencyIncidentRecipientEntity> {
        val dao = emergencyIncidentDao ?: return emptyList()
        val cleanId = incidentId.trim()
        if (cleanId.isEmpty()) return emptyList()
        return dao.getRecipientsForIncident(cleanId)
    }

    /**
     * Observes all local [EmergencyIncidentRecipientEntity] records for a given [incidentId].
     */
    fun observeIncidentRecipients(
        incidentId: String
    ): Flow<List<EmergencyIncidentRecipientEntity>> {
        val dao = emergencyIncidentDao ?: return flowOf(emptyList())
        val cleanId = incidentId.trim()
        if (cleanId.isEmpty()) return flowOf(emptyList())
        return dao.observeRecipientsForIncident(cleanId)
    }

    /**
     * Creates and persists a new local [EmergencyIncidentEntity] for the authenticated Supabase user,
     * OR reuses and updates the existing `ACTIVE` incident if one is already active for that user.
     *
     * Enforces:
     * - At most ONE `ACTIVE` emergency incident per authenticated user.
     * - [triggerSource] normalized strictly to `MANUAL`, `SHAKE`, `VOICE`, or `BLUETOOTH`.
     * - [audioStoragePath] remains `null` in Phase 2/3 (no audio upload yet).
     * - Automatically prepares recipient records for any paired AIZA guardians on the sender's account.
     */
    suspend fun createEmergencyIncident(
        triggerSource: String = EmergencyIncidentEntity.TRIGGER_MANUAL,
        latitude: Double? = null,
        longitude: Double? = null,
        locationTimestamp: Long? = null,
        locationStatus: String? = EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN,
        batteryPercent: Int? = null,
        networkStatus: String? = null,
        medicalSharingEnabled: Boolean = false,
        medicalSummary: String? = null,
        audioRecordingStatus: String = EmergencyIncidentEntity.AUDIO_STATUS_NOT_STARTED,
        audioDurationMs: Long? = null,
        audioStoragePath: String? = null,
        explicitUserId: String? = null,
        incidentId: String = UUID.randomUUID().toString(),
        startedAt: Long = System.currentTimeMillis()
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to create an emergency incident.")
            )
        val normalizedTrigger = normalizeIncidentTriggerSource(triggerSource)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Unsupported SOS trigger source: '$triggerSource'.")
            )
        val cleanId = incidentId.trim()
        if (!isRealAuthenticatedUserUuid(cleanId)) {
            return@withLock Result.failure(
                IllegalArgumentException("Emergency incident id must be a valid UUID.")
            )
        }
        val now = System.currentTimeMillis()
        if (preferencesManager.supabaseUserId.trim().equals(userId, ignoreCase = true)) {
            registerKnownSenderName(userId, preferencesManager.userName)
        }
        val sanitizedMedicalSummary = if (medicalSharingEnabled) {
            medicalSummary?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        // Prevent duplicate ACTIVE incidents for the same authenticated user: reuse/update existing active incident
        val existingActive = dao.getActiveIncidentForUser(userId)
        if (existingActive != null) {
            val reused = existingActive.copy(
                latitude = latitude ?: existingActive.latitude,
                longitude = longitude ?: existingActive.longitude,
                locationTimestamp = locationTimestamp ?: existingActive.locationTimestamp,
                locationStatus = if (latitude != null && longitude != null) {
                    locationStatus ?: existingActive.locationStatus
                } else {
                    existingActive.locationStatus ?: locationStatus
                },
                batteryPercent = batteryPercent ?: existingActive.batteryPercent,
                networkStatus = networkStatus ?: existingActive.networkStatus,
                medicalSharingEnabled = if (medicalSharingEnabled) true else existingActive.medicalSharingEnabled,
                medicalSummary = sanitizedMedicalSummary ?: existingActive.medicalSummary,
                audioDurationMs = audioDurationMs ?: existingActive.audioDurationMs,
                audioStoragePath = null,
                updatedAt = now
            )
            dao.updateIncident(reused)
            prepareIncidentRecipientsInternal(dao, reused.id, userId)
            return@withLock Result.success(reused)
        }

        val entity = EmergencyIncidentEntity(
            id = cleanId,
            userId = userId,
            triggerSource = normalizedTrigger,
            status = EmergencyIncidentEntity.STATUS_ACTIVE,
            startedAt = startedAt,
            endedAt = null,
            latitude = latitude,
            longitude = longitude,
            locationTimestamp = locationTimestamp,
            locationStatus = locationStatus ?: EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN,
            batteryPercent = batteryPercent,
            networkStatus = networkStatus,
            medicalSharingEnabled = medicalSharingEnabled,
            medicalSummary = sanitizedMedicalSummary,
            audioRecordingStatus = audioRecordingStatus.trim().ifBlank { EmergencyIncidentEntity.AUDIO_STATUS_NOT_STARTED },
            audioDurationMs = audioDurationMs,
            audioStoragePath = null,
            deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_PENDING,
            lastDeliveryError = null,
            createdAt = now,
            updatedAt = now,
            recipientGuardianUserId = null
        )
        dao.insertIncident(entity)
        prepareIncidentRecipientsInternal(dao, entity.id, userId)
        return@withLock Result.success(entity)
    }

    /**
     * Creates and persists an [EmergencyIncidentEntity] directly after validating its authenticated [EmergencyIncidentEntity.userId].
     * Reuses existing active incident if one is already active for the same user.
     */
    suspend fun createEmergencyIncident(
        incident: EmergencyIncidentEntity
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        if (!isRealAuthenticatedUserUuid(incident.userId)) {
            return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to create an emergency incident.")
            )
        }
        if (!isRealAuthenticatedUserUuid(incident.id)) {
            return@withLock Result.failure(
                IllegalArgumentException("Emergency incident id must be a valid UUID.")
            )
        }
        val normalizedTrigger = normalizeIncidentTriggerSource(incident.triggerSource)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Unsupported SOS trigger source: '${incident.triggerSource}'.")
            )
        val cleanUserId = incident.userId.trim()
        val existingById = dao.getIncidentById(incident.id.trim())
        if (existingById != null && !existingById.userId.trim().equals(cleanUserId, ignoreCase = true)) {
            return@withLock Result.failure(
                SecurityException("Cannot overwrite an emergency incident belonging to a different user.")
            )
        }
        if (incident.status.equals(EmergencyIncidentEntity.STATUS_ACTIVE, ignoreCase = true)) {
            val existingActive = dao.getActiveIncidentForUser(cleanUserId)
            if (existingActive != null && existingActive.id != incident.id.trim()) {
                val now = System.currentTimeMillis()
                val reused = existingActive.copy(
                    latitude = incident.latitude ?: existingActive.latitude,
                    longitude = incident.longitude ?: existingActive.longitude,
                    locationTimestamp = incident.locationTimestamp ?: existingActive.locationTimestamp,
                    locationStatus = incident.locationStatus ?: existingActive.locationStatus,
                    batteryPercent = incident.batteryPercent ?: existingActive.batteryPercent,
                    networkStatus = incident.networkStatus ?: existingActive.networkStatus,
                    updatedAt = now
                )
                dao.updateIncident(reused)
                prepareIncidentRecipientsInternal(dao, reused.id, cleanUserId)
                return@withLock Result.success(reused)
            }
        }
        val sanitized = incident.copy(
            id = incident.id.trim(),
            userId = cleanUserId,
            triggerSource = normalizedTrigger,
            medicalSummary = if (incident.medicalSharingEnabled) {
                incident.medicalSummary?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
            audioStoragePath = null,
            recipientGuardianUserId = null
        )
        dao.insertIncident(sanitized)
        prepareIncidentRecipientsInternal(dao, sanitized.id, cleanUserId)
        return@withLock Result.success(sanitized)
    }

    /**
     * Updates an existing [EmergencyIncidentEntity] in local Room storage.
     * Enforces owner-only write access: guardians are strictly read-only and cannot modify
     * another user's emergency incident.
     */
    suspend fun updateEmergencyIncident(
        incident: EmergencyIncidentEntity,
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to update an emergency incident.")
            )
        if (!incident.userId.trim().equals(userId, ignoreCase = true)) {
            return@withLock Result.failure(
                SecurityException("Cannot update an emergency incident belonging to a different user.")
            )
        }
        val existing = dao.getIncidentById(incident.id.trim())
        if (existing != null && !existing.userId.trim().equals(userId, ignoreCase = true)) {
            return@withLock Result.failure(
                SecurityException("Guardians are read-only and cannot modify another user's emergency incident.")
            )
        }
        val updated = incident.copy(
            userId = userId,
            medicalSummary = if (incident.medicalSharingEnabled) {
                incident.medicalSummary?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
            updatedAt = System.currentTimeMillis(),
            recipientGuardianUserId = existing?.recipientGuardianUserId
        )
        val rows = dao.updateIncident(updated)
        if (rows == 0) {
            dao.insertIncident(updated)
        }
        return@withLock Result.success(updated)
    }

    /**
     * Updates the GPS coordinates, timestamp, and location status of an existing incident by [id].
     */
    suspend fun updateIncidentLocation(
        id: String,
        latitude: Double?,
        longitude: Double?,
        locationTimestamp: Long? = System.currentTimeMillis(),
        locationStatus: String? = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val cleanId = id.trim()
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to update incident location.")
            )
        val existing = dao.getIncidentById(cleanId)
        if (existing != null && !existing.userId.trim().equals(userId, ignoreCase = true)) {
            return@withLock Result.failure(
                SecurityException("Guardians are read-only and cannot update another user's incident location.")
            )
        }
        val now = System.currentTimeMillis()
        val rows = dao.updateLocation(
            id = cleanId,
            userId = userId,
            latitude = latitude,
            longitude = longitude,
            locationTimestamp = locationTimestamp,
            locationStatus = locationStatus,
            updatedAt = now
        )
        if (rows == 0) {
            return@withLock Result.failure(IllegalStateException("Emergency incident not found for current user."))
        }
        val updated = dao.getIncidentByIdForUser(cleanId, userId)
            ?: return@withLock Result.failure(IllegalStateException("Updated emergency incident could not be loaded."))
        return@withLock Result.success(updated)
    }

    /**
     * Updates the GPS coordinates, timestamp, and location status of the currently ACTIVE incident
     * for the authenticated user.
     */
    suspend fun updateActiveIncidentLocation(
        latitude: Double?,
        longitude: Double?,
        locationTimestamp: Long? = System.currentTimeMillis(),
        locationStatus: String? = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to update active incident location.")
            )
        val active = dao.getActiveIncidentForUser(userId)
            ?: return@withLock Result.failure(IllegalStateException("No active emergency incident for current user."))
        val now = System.currentTimeMillis()
        dao.updateLocation(
            id = active.id,
            userId = userId,
            latitude = latitude,
            longitude = longitude,
            locationTimestamp = locationTimestamp,
            locationStatus = locationStatus,
            updatedAt = now
        )
        val updated = dao.getIncidentByIdForUser(active.id, userId)
            ?: return@withLock Result.failure(IllegalStateException("Updated active incident could not be loaded."))
        return@withLock Result.success(updated)
    }

    /**
     * Updates the audio recording status, duration, and storage path of an existing incident by [id].
     */
    suspend fun updateIncidentAudioStatus(
        id: String,
        audioRecordingStatus: String,
        audioDurationMs: Long? = null,
        audioStoragePath: String? = null,
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val cleanId = id.trim()
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to update incident audio status.")
            )
        val existing = dao.getIncidentById(cleanId)
        if (existing != null && !existing.userId.trim().equals(userId, ignoreCase = true)) {
            return@withLock Result.failure(
                SecurityException("Guardians are read-only and cannot update another user's incident audio status.")
            )
        }
        val now = System.currentTimeMillis()
        val rows = dao.updateAudioStatus(
            id = cleanId,
            userId = userId,
            audioRecordingStatus = audioRecordingStatus.trim(),
            audioDurationMs = audioDurationMs,
            audioStoragePath = audioStoragePath,
            updatedAt = now
        )
        if (rows == 0) {
            return@withLock Result.failure(IllegalStateException("Emergency incident not found for current user."))
        }
        val updated = dao.getIncidentByIdForUser(cleanId, userId)
            ?: return@withLock Result.failure(IllegalStateException("Updated emergency incident could not be loaded."))
        return@withLock Result.success(updated)
    }

    /**
     * Updates the audio recording status and duration of the currently ACTIVE incident for the authenticated user.
     * Keeps `audioStoragePath = null` in Phase 2/3 (no audio upload yet).
     */
    suspend fun updateActiveIncidentAudioStatus(
        audioRecordingStatus: String,
        audioDurationMs: Long? = null,
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to update active incident audio status.")
            )
        val active = dao.getActiveIncidentForUser(userId)
            ?: return@withLock Result.failure(IllegalStateException("No active emergency incident for current user."))
        val now = System.currentTimeMillis()
        dao.updateAudioStatus(
            id = active.id,
            userId = userId,
            audioRecordingStatus = audioRecordingStatus.trim(),
            audioDurationMs = audioDurationMs,
            audioStoragePath = null,
            updatedAt = now
        )
        val updated = dao.getIncidentByIdForUser(active.id, userId)
            ?: return@withLock Result.failure(IllegalStateException("Updated active incident could not be loaded."))
        return@withLock Result.success(updated)
    }

    /**
     * Updates the delivery status and optional error message of an existing incident.
     */
    suspend fun updateIncidentDeliveryStatus(
        id: String,
        deliveryStatus: String,
        lastDeliveryError: String? = null,
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val cleanId = id.trim()
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to update incident delivery status.")
            )
        val existing = dao.getIncidentById(cleanId)
        if (existing != null && !existing.userId.trim().equals(userId, ignoreCase = true)) {
            return@withLock Result.failure(
                SecurityException("Guardians are read-only and cannot update another user's incident delivery status.")
            )
        }
        val now = System.currentTimeMillis()
        val rows = dao.updateDeliveryStatus(
            id = cleanId,
            userId = userId,
            deliveryStatus = deliveryStatus.trim(),
            lastDeliveryError = lastDeliveryError,
            updatedAt = now
        )
        if (rows == 0) {
            return@withLock Result.failure(IllegalStateException("Emergency incident not found for current user."))
        }
        val updated = dao.getIncidentByIdForUser(cleanId, userId)
            ?: return@withLock Result.failure(IllegalStateException("Updated emergency incident could not be loaded."))
        return@withLock Result.success(updated)
    }

    /**
     * Marks an emergency incident by [id] as `RESOLVED` (or `CANCELLED`) and records [endedAt].
     */
    suspend fun resolveEmergencyIncident(
        id: String,
        status: String = EmergencyIncidentEntity.STATUS_RESOLVED,
        endedAt: Long = System.currentTimeMillis(),
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val cleanId = id.trim()
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to resolve an emergency incident.")
            )
        val existing = dao.getIncidentById(cleanId)
        if (existing != null && !existing.userId.trim().equals(userId, ignoreCase = true)) {
            return@withLock Result.failure(
                SecurityException("Guardians are read-only and cannot resolve another user's emergency incident.")
            )
        }
        val now = System.currentTimeMillis()
        val rows = dao.markResolved(
            id = cleanId,
            userId = userId,
            status = status.trim().ifBlank { EmergencyIncidentEntity.STATUS_RESOLVED },
            endedAt = endedAt,
            updatedAt = now
        )
        if (rows == 0) {
            return@withLock Result.failure(IllegalStateException("Emergency incident not found for current user."))
        }
        val updated = dao.getIncidentByIdForUser(cleanId, userId)
            ?: return@withLock Result.failure(IllegalStateException("Resolved emergency incident could not be loaded."))
        return@withLock Result.success(updated)
    }

    /**
     * Resolves the currently ACTIVE emergency incident for the authenticated user (`status = RESOLVED`, `endedAt = now`).
     */
    suspend fun resolveActiveEmergencyIncident(
        status: String = EmergencyIncidentEntity.STATUS_RESOLVED,
        endedAt: Long = System.currentTimeMillis(),
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> = incidentMutationMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to resolve active emergency incident.")
            )
        val active = dao.getActiveIncidentForUser(userId)
            ?: return@withLock Result.failure(IllegalStateException("No active emergency incident for current user."))
        val now = System.currentTimeMillis()
        dao.markResolved(
            id = active.id,
            userId = userId,
            status = status.trim().ifBlank { EmergencyIncidentEntity.STATUS_RESOLVED },
            endedAt = endedAt,
            updatedAt = now
        )
        val updated = dao.getIncidentByIdForUser(active.id, userId)
            ?: return@withLock Result.failure(IllegalStateException("Resolved active incident could not be loaded."))
        return@withLock Result.success(updated)
    }

    /**
     * Synchronizes a single user-owned [EmergencyIncidentEntity] to Supabase (`public.emergency_incidents`)
     * AND synchronizes per-guardian recipient records (`public.emergency_incident_recipients`) for all
     * paired AIZA guardians of the sender.
     *
     * Delivery Status Semantics:
     * - `SENT` means the sender successfully synchronized the incident/recipient record to Supabase.
     * - Never marks `DELIVERED` merely because the sender uploaded to Supabase.
     * - `DELIVERED` is only set when a paired guardian's authenticated account retrieves and locally
     *   persists the incident.
     */
    suspend fun syncEmergencyIncidentToSupabase(
        incident: EmergencyIncidentEntity,
        explicitUserId: String? = null
    ): Result<EmergencyIncidentEntity> {
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId)
            ?: return Result.failure(
                IllegalArgumentException("Authenticated Supabase user UUID is required to sync an emergency incident.")
            )
        if (!isRealAuthenticatedUserUuid(incident.userId) ||
            !incident.userId.trim().equals(userId, ignoreCase = true)
        ) {
            return Result.failure(
                SecurityException("Incident userId must match the authenticated Supabase user UUID. Guardians cannot modify or sync another user's incident.")
            )
        }
        if (!isRealAuthenticatedUserUuid(incident.id)) {
            return Result.failure(
                IllegalArgumentException("Emergency incident id must be a valid UUID.")
            )
        }
        val existingLocal = emergencyIncidentDao?.getIncidentById(incident.id.trim())
        if (existingLocal != null && !existingLocal.userId.trim().equals(userId, ignoreCase = true)) {
            return Result.failure(
                SecurityException("Guardians are read-only and cannot modify another user's emergency incident.")
            )
        }

        val now = System.currentTimeMillis()
        val targetDeliveryStatus = if (
            incident.deliveryStatus.equals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, ignoreCase = true)
        ) {
            EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED
        } else {
            EmergencyIncidentEntity.DELIVERY_STATUS_SENT
        }

        val syncedCandidate = incident.copy(
            userId = userId,
            deliveryStatus = targetDeliveryStatus,
            lastDeliveryError = null,
            updatedAt = now
        )

        return try {
            activeIncidentRemote.upsertEmergencyIncident(syncedCandidate.toDto())
            emergencyIncidentDao?.updateDeliveryStatus(
                id = syncedCandidate.id,
                userId = userId,
                deliveryStatus = targetDeliveryStatus,
                lastDeliveryError = null,
                updatedAt = now
            )

            // Phase 3: Prepare and synchronize recipient rows for all paired AIZA guardians
            val dao = emergencyIncidentDao
            if (dao != null) {
                val recipients = prepareIncidentRecipientsInternal(dao, syncedCandidate.id, userId)
                for (recipient in recipients) {
                    val recipientStatus = if (
                        recipient.deliveryStatus.equals(
                            EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
                            ignoreCase = true
                        )
                    ) {
                        EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED
                    } else {
                        EmergencyIncidentEntity.DELIVERY_STATUS_SENT
                    }
                    val recipientToSync = recipient.copy(
                        deliveryStatus = recipientStatus,
                        lastError = null,
                        updatedAt = now
                    )
                    try {
                        activeIncidentRemote.upsertIncidentRecipient(recipientToSync.toDto())
                        dao.updateRecipientDeliveryStatus(
                            incidentId = recipientToSync.incidentId,
                            guardianUserId = recipientToSync.guardianUserId,
                            deliveryStatus = recipientStatus,
                            deliveredAt = recipientToSync.deliveredAt,
                            lastError = null,
                            updatedAt = now
                        )
                    } catch (recipientErr: Exception) {
                        val recErrMsg = "${recipientErr.javaClass.simpleName}: ${recipientErr.message ?: "Recipient sync failed"}"
                        dao.updateRecipientDeliveryStatus(
                            incidentId = recipient.incidentId,
                            guardianUserId = recipient.guardianUserId,
                            deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_FAILED,
                            deliveredAt = recipient.deliveredAt,
                            lastError = recErrMsg,
                            updatedAt = now
                        )
                    }
                }
            }

            val persisted = emergencyIncidentDao?.getIncidentByIdForUser(syncedCandidate.id, userId)
                ?: syncedCandidate
            Result.success(persisted)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "Supabase sync failed"}"
            emergencyIncidentDao?.updateDeliveryStatus(
                id = incident.id,
                userId = userId,
                deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_FAILED,
                lastDeliveryError = errorMsg,
                updatedAt = now
            )
            Log.w("SafetyRepository", "syncEmergencyIncidentToSupabase failed for id=${incident.id}: $errorMsg")
            Result.failure(e)
        }
    }

    /**
     * Synchronizes all pending or failed emergency incidents belonging to the authenticated user
     * to `public.emergency_incidents`. Returns the number of successfully synced incidents.
     */
    suspend fun syncPendingEmergencyIncidents(
        explicitUserId: String? = null
    ): Int = incidentSyncMutex.withLock {
        val dao = emergencyIncidentDao ?: return@withLock 0
        val userId = resolveAuthenticatedIncidentUserId(explicitUserId) ?: return@withLock 0
        var syncedCount = 0
        try {
            val pendingList = dao.getPendingIncidentsForUser(userId)
            for (incident in pendingList) {
                val result = syncEmergencyIncidentToSupabase(
                    incident = incident,
                    explicitUserId = userId
                )
                if (result.isSuccess) {
                    syncedCount++
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SafetyRepository", "syncPendingEmergencyIncidents error: ${e.message}")
        }
        return@withLock syncedCount
    }

    // --- Phase 3: Guardian Emergency Incident Retrieval, Verification, & Acknowledgement ---

    /**
     * Observes all emergency incidents received and persisted locally for the authenticated guardian.
     *
     * Retains the original sender's `userId` and populates `recipientGuardianUserId` with the
     * authenticated guardian's Supabase UUID. Strictly excludes the user's own sent incidents.
     */
    fun observeReceivedEmergencyIncidents(
        explicitGuardianUserId: String? = null
    ): Flow<List<EmergencyIncidentEntity>> {
        val dao = emergencyIncidentDao ?: return flowOf(emptyList())
        val guardianUserId = resolveAuthenticatedIncidentUserId(explicitGuardianUserId)
            ?: return flowOf(emptyList())
        return dao.observeReceivedIncidents(guardianUserId).map { list ->
            list.filter { isReceivedEmergencyForGuardian(it, guardianUserId) }
        }
    }

    /**
     * Observes currently ACTIVE emergency incidents received and persisted locally for the authenticated guardian.
     */
    fun observeActiveReceivedEmergencyIncidents(
        explicitGuardianUserId: String? = null
    ): Flow<List<EmergencyIncidentEntity>> {
        val dao = emergencyIncidentDao ?: return flowOf(emptyList())
        val guardianUserId = resolveAuthenticatedIncidentUserId(explicitGuardianUserId)
            ?: return flowOf(emptyList())
        return dao.observeActiveReceivedIncidents(guardianUserId).map { list ->
            list.filter { isReceivedEmergencyForGuardian(it, guardianUserId) }
        }
    }

    /**
     * Observes all recipient delivery tracking rows addressed to the authenticated guardian.
     */
    fun observeGuardianIncidentRecipients(
        explicitGuardianUserId: String? = null
    ): Flow<List<EmergencyIncidentRecipientEntity>> {
        val dao = emergencyIncidentDao ?: return flowOf(emptyList())
        val guardianUserId = resolveAuthenticatedIncidentUserId(explicitGuardianUserId)
            ?: return flowOf(emptyList())
        return dao.observeRecipientsForGuardian(guardianUserId)
    }

    /**
     * Retrieves and synchronizes emergency incidents addressed to the authenticated paired guardian,
     * returning the guardian's locally persisted received incidents.
     */
    suspend fun fetchReceivedEmergencyIncidents(
        explicitGuardianUserId: String? = null
    ): Result<List<EmergencyIncidentEntity>> {
        return syncReceivedEmergencyIncidents(explicitGuardianUserId)
    }

    /**
     * Foreground synchronization for a paired guardian's AIZA account:
     * 1. Resolves the authenticated guardian Supabase UUID strictly.
     * 2. Queries recipient rows (`emergency_incident_recipients`) for `guardian_user_id`.
     * 3. Validates the pairing relationship via `emergency_contacts.guardian_user_id`.
     * 4. Fetches the associated `emergency_incidents` records.
     * 5. Stores/updates them locally in Room (retaining the sender's original `userId`).
     * 6. Marks the recipient row as `DELIVERED` (with `deliveredAt`) ONLY after successful local Room persistence.
     */
    suspend fun syncReceivedEmergencyIncidents(
        explicitGuardianUserId: String? = null
    ): Result<List<EmergencyIncidentEntity>> = incidentSyncMutex.withLock {
        val dao = emergencyIncidentDao
            ?: return@withLock Result.failure(IllegalStateException("EmergencyIncidentDao is not initialized."))
        val guardianUserId = resolveAuthenticatedIncidentUserId(explicitGuardianUserId)
            ?: return@withLock Result.failure(
                IllegalArgumentException("Authenticated guardian Supabase user UUID is required to sync received incidents.")
            )

        val remoteRecipients = try {
            activeIncidentRemote.fetchRecipientRowsForGuardian(guardianUserId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock Result.failure(e)
        }

        // Merge remote recipient rows with any existing local recipient rows for this guardian
        val mergedRecipientsByIncident = linkedMapOf<String, EmergencyIncidentRecipientDto>()
        for (dto in remoteRecipients) {
            if (dto.guardianUserId.trim().equals(guardianUserId, ignoreCase = true) &&
                isRealAuthenticatedUserUuid(dto.incidentId)
            ) {
                mergedRecipientsByIncident[dto.incidentId.trim()] = dto
            }
        }
        val localRecipients = dao.getRecipientsForGuardian(guardianUserId)
        for (localRec in localRecipients) {
            val key = localRec.incidentId.trim()
            if (!mergedRecipientsByIncident.containsKey(key) && isRealAuthenticatedUserUuid(key)) {
                mergedRecipientsByIncident[key] = localRec.toDto()
            }
        }

        var firstRetrievalError: Throwable? = null

        for ((_, recipientDto) in mergedRecipientsByIncident) {
            val cleanIncidentId = recipientDto.incidentId.trim()
            val cleanSenderId = recipientDto.senderUserId.trim()
            val cleanGuardianId = recipientDto.guardianUserId.trim()

            // Strictly validate UUIDs and prevent self-delivery
            if (!isRealAuthenticatedUserUuid(cleanIncidentId) ||
                !isRealAuthenticatedUserUuid(cleanSenderId) ||
                !isRealAuthenticatedUserUuid(cleanGuardianId) ||
                !cleanGuardianId.equals(guardianUserId, ignoreCase = true) ||
                cleanSenderId.equals(guardianUserId, ignoreCase = true)
            ) {
                continue
            }

            // Canonical pairing check via emergency_contacts.guardian_user_id
            val isAuthorized = isGuardianAuthorizedForSender(
                senderUserId = cleanSenderId,
                guardianUserId = guardianUserId
            )
            if (!isAuthorized) {
                Log.w(
                    "SafetyRepository",
                    "Skipping unauthorized incident recipient row: guardian=$guardianUserId is not paired with sender=$cleanSenderId"
                )
                continue
            }

            val now = System.currentTimeMillis()
            val existingLocalRecipient = dao.getRecipientForIncidentAndGuardian(cleanIncidentId, guardianUserId)

            val fetchedIncidentDto = try {
                activeIncidentRemote.fetchEmergencyIncidentByIdForGuardian(
                    incidentId = cleanIncidentId,
                    guardianUserId = guardianUserId
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val errMsg = "${e.javaClass.simpleName}: ${e.message ?: "Incident retrieval failed"}"
                val failedRecipient = (existingLocalRecipient ?: recipientDto.toEntity()).copy(
                    deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_FAILED,
                    deliveredAt = null,
                    lastError = errMsg,
                    updatedAt = now
                )
                dao.insertIncidentRecipient(failedRecipient)
                if (firstRetrievalError == null) {
                    firstRetrievalError = e
                }
                continue
            }

            if (fetchedIncidentDto == null) {
                val errMsg = "Emergency incident $cleanIncidentId could not be retrieved for guardian."
                val failedRecipient = (existingLocalRecipient ?: recipientDto.toEntity()).copy(
                    deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_FAILED,
                    deliveredAt = null,
                    lastError = errMsg,
                    updatedAt = now
                )
                dao.insertIncidentRecipient(failedRecipient)
                if (firstRetrievalError == null) {
                    firstRetrievalError = IllegalStateException(errMsg)
                }
                continue
            }

            // Verify the fetched incident's owner matches the paired sender and is not the guardian
            if (!fetchedIncidentDto.id.trim().equals(cleanIncidentId, ignoreCase = true) ||
                !fetchedIncidentDto.userId.trim().equals(cleanSenderId, ignoreCase = true) ||
                fetchedIncidentDto.userId.trim().equals(guardianUserId, ignoreCase = true)
            ) {
                val errMsg = "Fetched incident ownership mismatch for incident $cleanIncidentId."
                val failedRecipient = (existingLocalRecipient ?: recipientDto.toEntity()).copy(
                    deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_FAILED,
                    deliveredAt = null,
                    lastError = errMsg,
                    updatedAt = now
                )
                dao.insertIncidentRecipient(failedRecipient)
                if (firstRetrievalError == null) {
                    firstRetrievalError = SecurityException(errMsg)
                }
                continue
            }

            // Persist the received incident in local Room, preserving original sender userId
            val existingIncidentRow = dao.getIncidentById(cleanIncidentId)
            val otherRecipientsPending = dao.getRecipientsForIncident(cleanIncidentId)
                .any {
                    !it.guardianUserId.equals(guardianUserId, ignoreCase = true) &&
                        !it.deliveryStatus.equals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, ignoreCase = true)
                }
            val incidentRowStatus = if (existingIncidentRow != null && otherRecipientsPending) {
                existingIncidentRow.deliveryStatus
            } else {
                EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED
            }

            val receivedEntity = fetchedIncidentDto.toReceivedEntityForGuardian(
                guardianUserId = guardianUserId,
                guardianDeliveryStatus = incidentRowStatus,
                guardianDeliveryError = null
            )
            dao.insertIncident(receivedEntity)

            // Confirm local Room persistence succeeded before marking DELIVERED
            val persistedIncident = dao.getIncidentById(cleanIncidentId)
            if (persistedIncident == null) {
                val errMsg = "Local Room persistence verification failed for incident $cleanIncidentId."
                val failedRecipient = (existingLocalRecipient ?: recipientDto.toEntity()).copy(
                    deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_FAILED,
                    deliveredAt = null,
                    lastError = errMsg,
                    updatedAt = now
                )
                dao.insertIncidentRecipient(failedRecipient)
                if (firstRetrievalError == null) {
                    firstRetrievalError = IllegalStateException(errMsg)
                }
                continue
            }

            val deliveredAtTs = existingLocalRecipient?.deliveredAt
                ?: recipientDto.deliveredAt
                ?: now
            val deliveredRecipient = (existingLocalRecipient ?: recipientDto.toEntity()).copy(
                id = existingLocalRecipient?.id ?: recipientDto.id,
                incidentId = cleanIncidentId,
                senderUserId = cleanSenderId,
                guardianUserId = guardianUserId,
                deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
                deliveredAt = deliveredAtTs,
                lastError = null,
                updatedAt = now
            )
            dao.insertIncidentRecipient(deliveredRecipient)

            // If all recipients for this incident are now DELIVERED, also mark the incident row DELIVERED
            val allRecipientsForIncident = dao.getRecipientsForIncident(cleanIncidentId)
            if (allRecipientsForIncident.isNotEmpty() &&
                allRecipientsForIncident.all {
                    it.deliveryStatus.equals(EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED, ignoreCase = true)
                }
            ) {
                dao.updateDeliveryStatus(
                    id = cleanIncidentId,
                    userId = cleanSenderId,
                    deliveryStatus = EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
                    lastDeliveryError = null,
                    updatedAt = now
                )
            }

            try {
                activeIncidentRemote.acknowledgeRecipientDelivery(
                    recipientId = deliveredRecipient.id,
                    incidentId = cleanIncidentId,
                    guardianUserId = guardianUserId,
                    deliveredAt = deliveredAtTs,
                    updatedAt = now
                )
            } catch (ackErr: Exception) {
                Log.w("SafetyRepository", "Guardian delivery acknowledgement sync note: ${ackErr.message}")
            }
        }

        val receivedList = dao.getReceivedIncidentsForGuardian(guardianUserId)
        try {
            val senderNamesByUid = linkedMapOf<String, String>()
            for (received in receivedList) {
                val senderKey = received.userId.trim().lowercase(java.util.Locale.US)
                if (!senderNamesByUid.containsKey(senderKey)) {
                    val resolvedName = resolveSenderNameForReceivedIncident(
                        senderUserId = received.userId,
                        guardianUserId = guardianUserId
                    )
                    if (!resolvedName.isNullOrBlank()) {
                        senderNamesByUid[senderKey] = resolvedName
                    }
                }
            }
            emergencyNotificationManager.syncNotificationsForReceivedIncidents(
                authenticatedGuardianUserId = guardianUserId,
                receivedIncidents = receivedList,
                senderNamesByUserId = senderNamesByUid
            )
        } catch (notifErr: Exception) {
            Log.w("SafetyRepository", "Emergency notification sync note: ${notifErr.message}")
        }

        if (firstRetrievalError != null && receivedList.isEmpty()) {
            return@withLock Result.failure(firstRetrievalError!!)
        }
        return@withLock Result.success(receivedList)
    }

    /**
     * Resolves a human-readable sender display name for a received [EmergencyIncidentEntity]
     * from pairing requests, contacts, remote profile, or registered sender metadata.
     */
    suspend fun resolveSenderNameForReceivedIncident(
        senderUserId: String?,
        guardianUserId: String? = null
    ): String? {
        val cleanSender = senderUserId?.trim() ?: return null
        if (!isRealAuthenticatedUserUuid(cleanSender)) return null

        val cached = knownSenderNamesByUserId[cleanSender.lowercase(java.util.Locale.US)]
        if (cached != null) return cached

        // 1. Check local guardian_pairing_requests where requesterUserId == senderUserId
        try {
            val requests = guardianPairingRequestDao?.observeAllRequests()?.first().orEmpty()
            val matchingRequest = requests.firstOrNull { req ->
                req.requesterUserId.trim().equals(cleanSender, ignoreCase = true) &&
                    (guardianUserId.isNullOrBlank() ||
                        req.guardianUserId.isNullOrBlank() ||
                        req.guardianUserId.trim().equals(guardianUserId.trim(), ignoreCase = true)) &&
                    AizaEmergencyNotificationManager.sanitizeSenderName(req.requesterName) != null
            }
            val fromPairing = AizaEmergencyNotificationManager.sanitizeSenderName(matchingRequest?.requesterName)
            if (fromPairing != null) {
                registerKnownSenderName(cleanSender, fromPairing)
                return fromPairing
            }
        } catch (_: Exception) {}

        // 2. Check local contacts where pairedGuardianUserId == senderUserId
        try {
            val contacts = allContacts.first()
            val matchingContact = contacts.firstOrNull { contact ->
                contact.pairedGuardianUserId?.trim()?.equals(cleanSender, ignoreCase = true) == true &&
                    AizaEmergencyNotificationManager.sanitizeSenderName(contact.name) != null
            }
            val fromContact = AizaEmergencyNotificationManager.sanitizeSenderName(matchingContact?.name)
            if (fromContact != null) {
                registerKnownSenderName(cleanSender, fromContact)
                return fromContact
            }
        } catch (_: Exception) {}

        // 3. Check remote sender display name if available
        try {
            val fromRemote = AizaEmergencyNotificationManager.sanitizeSenderName(
                activeIncidentRemote.fetchSenderDisplayName(cleanSender)
            )
            if (fromRemote != null) {
                registerKnownSenderName(cleanSender, fromRemote)
                return fromRemote
            }
        } catch (_: Exception) {}

        // 4. Check if preferencesManager currently holds this sender's profile name
        if (preferencesManager.supabaseUserId.trim().equals(cleanSender, ignoreCase = true)) {
            val fromPrefs = AizaEmergencyNotificationManager.sanitizeSenderName(preferencesManager.userName)
            if (fromPrefs != null) {
                registerKnownSenderName(cleanSender, fromPrefs)
                return fromPrefs
            }
        }

        return null
    }

    /**
     * Retrieves a specific received or owned [EmergencyIncidentEntity] by its exact [incidentId]
     * for the authenticated user/guardian.
     *
     * If the incident is not yet available locally in Room, invokes [syncReceivedEmergencyIncidents]
     * using the existing guardian emergency sync mechanism and re-queries by the exact [incidentId].
     * Never returns a generic, random, or unauthorized incident.
     */
    suspend fun getEmergencyIncidentForDetailsFlow(
        incidentId: String?,
        explicitUserId: String? = null
    ): EmergencyIncidentEntity? {
        val cleanIncidentId = incidentId?.trim() ?: return null
        if (!isRealAuthenticatedUserUuid(cleanIncidentId)) return null
        val dao = emergencyIncidentDao ?: return null
        val currentUid = resolveAuthenticatedIncidentUserId(explicitUserId)
        if (currentUid != null) {
            dao.getReceivedIncidentByIdForGuardian(cleanIncidentId, currentUid)?.let { return it }
            dao.getIncidentByIdForUser(cleanIncidentId, currentUid)?.let { return it }
            try {
                syncReceivedEmergencyIncidents(currentUid)
            } catch (_: Exception) {}
            dao.getReceivedIncidentByIdForGuardian(cleanIncidentId, currentUid)?.let { return it }
            dao.getIncidentByIdForUser(cleanIncidentId, currentUid)?.let { return it }
            return null
        }
        return dao.getIncidentById(cleanIncidentId)
    }

    /**
     * Retrieves the [EmergencyIncidentRecipientEntity] delivery record for [incidentId] and
     * the authenticated guardian ([explicitGuardianUserId]).
     */
    suspend fun getRecipientForIncidentDetails(
        incidentId: String?,
        explicitGuardianUserId: String? = null
    ): EmergencyIncidentRecipientEntity? {
        val cleanIncidentId = incidentId?.trim() ?: return null
        if (!isRealAuthenticatedUserUuid(cleanIncidentId)) return null
        val dao = emergencyIncidentDao ?: return null
        val currentUid = resolveAuthenticatedIncidentUserId(explicitGuardianUserId)
        if (currentUid != null) {
            dao.getRecipientForIncidentAndGuardian(cleanIncidentId, currentUid)?.let { return it }
        }
        return dao.getRecipientsForIncident(cleanIncidentId).firstOrNull()
    }

    /**
     * Resolves the connected [ContactEntity] representing the sender ([senderUserId]) of a
     * received emergency incident for calling or opening 1-to-1 Guardian Chat.
     *
     * Never fabricates a phone number if none exists in local contacts.
     */
    suspend fun resolveConnectedContactForIncidentSender(
        senderUserId: String?,
        explicitGuardianUserId: String? = null
    ): ContactEntity? {
        val cleanSender = senderUserId?.trim() ?: return null
        if (!isRealAuthenticatedUserUuid(cleanSender)) return null
        val currentGuardianUid = resolveAuthenticatedIncidentUserId(explicitGuardianUserId)
        if (currentGuardianUid != null && cleanSender.equals(currentGuardianUid, ignoreCase = true)) {
            return null
        }

        val contacts = try {
            allContacts.first()
        } catch (_: Exception) {
            emptyList()
        }

        // 1. Direct contact where pairedGuardianUserId == senderUserId
        val directPairedContact = contacts.firstOrNull { contact ->
            contact.pairedGuardianUserId?.trim()?.equals(cleanSender, ignoreCase = true) == true
        }
        if (directPairedContact != null) {
            return directPairedContact
        }

        // 2. Contact created by sender where pairedGuardianUserId == currentGuardianUid
        if (currentGuardianUid != null) {
            val inverseContact = contacts.firstOrNull { contact ->
                contact.userId.trim().equals(cleanSender, ignoreCase = true) &&
                    contact.pairedGuardianUserId?.trim()?.equals(currentGuardianUid, ignoreCase = true) == true
            }
            if (inverseContact != null) {
                val resolvedSenderName = resolveSenderNameForReceivedIncident(cleanSender, currentGuardianUid)
                    ?: inverseContact.name.ifBlank { "Connected AIZA User" }
                return ContactEntity(
                    id = inverseContact.id,
                    contactId = inverseContact.contactId,
                    userId = currentGuardianUid,
                    guardianUserId = cleanSender,
                    name = resolvedSenderName,
                    phone = "",
                    relation = "Connected AIZA User"
                )
            }
        }

        // 3. Accepted pairing request linking requesterUserId == senderUserId
        val acceptedRequest = try {
            guardianPairingRequestDao?.observeAllRequests()?.first()?.firstOrNull { req ->
                req.requesterUserId.trim().equals(cleanSender, ignoreCase = true) &&
                    (currentGuardianUid == null ||
                        req.guardianUserId.isNullOrBlank() ||
                        req.guardianUserId.trim().equals(currentGuardianUid, ignoreCase = true))
            }
        } catch (_: Exception) {
            null
        }

        val resolvedName = resolveSenderNameForReceivedIncident(cleanSender, currentGuardianUid)
            ?: acceptedRequest?.requesterName?.takeIf { it.isNotBlank() }
            ?: "Connected AIZA User"
        val resolvedContactId = acceptedRequest?.emergencyContactId?.trim()
            ?.takeIf { isRealAuthenticatedUserUuid(it) }
            ?: UUID.nameUUIDFromBytes("paired_sender_${cleanSender.lowercase(java.util.Locale.US)}".toByteArray()).toString()

        return ContactEntity(
            contactId = resolvedContactId,
            userId = currentGuardianUid ?: "",
            guardianUserId = cleanSender,
            name = resolvedName,
            phone = "",
            relation = "Connected AIZA User"
        )
    }

    /**
     * Subscribes to Supabase Realtime changes on `public.emergency_incident_recipients` for the
     * authenticated guardian and triggers [onIncomingIncidentSynced] after synchronizing received incidents.
     */
    suspend fun subscribeToGuardianIncidentsRealtime(
        explicitGuardianUserId: String? = null,
        onIncomingIncidentSynced: (List<EmergencyIncidentEntity>) -> Unit = {}
    ): (suspend () -> Unit)? {
        val guardianUserId = resolveAuthenticatedIncidentUserId(explicitGuardianUserId) ?: return null
        val channelName = "guardian_incidents_${guardianUserId.lowercase(java.util.Locale.US)}"
        return try {
            activeIncidentRemote.subscribeToGuardianIncidentRecipients(
                channelName = channelName,
                guardianUserId = guardianUserId
            ) { dto ->
                if (dto.guardianUserId.trim().equals(guardianUserId, ignoreCase = true)) {
                    val syncRes = syncReceivedEmergencyIncidents(guardianUserId)
                    syncRes.getOrNull()?.let { onIncomingIncidentSynced(it) }
                }
            }
        } catch (e: Exception) {
            Log.w("SafetyRepository", "subscribeToGuardianIncidentsRealtime warning: ${e.message}")
            null
        }
    }

    /**
     * Phase 4D: Fetches the list of AIZA users who have saved/added the current user
     * as an emergency contact or paired guardian.
     *
     * Privacy & Security Guarantees:
     * - Only queries records where guardian_user_id == current authenticated user UUID.
     * - Rejects unauthenticated/invalid/placeholder UUIDs.
     * - Never uses email or phone as the sole authorization identity.
     * - Does NOT expose other users' full contact lists, location, medical notes, or SOS history.
     */
    suspend fun fetchPeopleWhoSavedMe(explicitUserId: String? = null): List<PersonWhoSavedMe> {
        val currentUserId = resolveAuthenticatedIncidentUserId(explicitUserId) ?: return emptyList()
        if (!isRealAuthenticatedUserUuid(currentUserId)) return emptyList()

        // 1. Fetch remote contacts where guardian_user_id == currentUserId
        val remoteContacts = try {
            activePeopleWhoSavedMeRemote.fetchContactsWhoSavedUser(currentUserId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SafetyRepository", "fetchPeopleWhoSavedMe remote contacts warning: ${e.message}")
            emptyList()
        }

        // 2. Fetch accepted pairing requests where guardian_user_id == currentUserId
        val remotePairingRequests = try {
            activePeopleWhoSavedMeRemote.fetchAcceptedPairingRequestsForGuardian(currentUserId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

        // 3. Local accepted pairing requests
        val localPairingRequests = try {
            guardianPairingRequestDao?.observeAllRequests()?.first()?.filter { req ->
                req.status.equals(GuardianPairingRequestEntity.STATUS_ACCEPTED, ignoreCase = true) &&
                    (req.guardianUserId?.trim()?.equals(currentUserId, ignoreCase = true) == true)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 4. Local contacts to check if we have any inverse records or sender name mappings
        val localContacts = try {
            allContacts.first()
        } catch (e: Exception) {
            emptyList()
        }

        val distinctSenders = linkedMapOf<String, PersonWhoSavedMe>()

        // Process remote contacts where current user is the saved contact (guardianUserId == currentUserId)
        for (contact in remoteContacts) {
            val senderUid = contact.userId.trim()
            if (!isRealAuthenticatedUserUuid(senderUid)) continue
            if (senderUid.equals(currentUserId, ignoreCase = true)) continue

            val relationship = contact.relationship.trim().ifBlank { "Emergency Contact" }
            distinctSenders[senderUid.lowercase(java.util.Locale.US)] = PersonWhoSavedMe(
                userId = senderUid,
                name = "",
                relationship = relationship,
                isConnectedUser = true,
                contactRowId = contact.id
            )
        }

        // Process pairing requests (both remote and local)
        val allAcceptedPairings = (remotePairingRequests.map { it.toEntity() } + localPairingRequests)
        for (pairing in allAcceptedPairings) {
            val senderUid = pairing.requesterUserId.trim()
            if (!isRealAuthenticatedUserUuid(senderUid)) continue
            if (senderUid.equals(currentUserId, ignoreCase = true)) continue

            val key = senderUid.lowercase(java.util.Locale.US)
            val existing = distinctSenders[key]
            val relationship = existing?.relationship ?: pairing.relationship.trim().ifBlank { "Guardian" }
            val preferredName = pairing.requesterName.trim()

            distinctSenders[key] = PersonWhoSavedMe(
                userId = senderUid,
                name = if (preferredName.isNotBlank()) preferredName else (existing?.name ?: ""),
                relationship = relationship,
                isConnectedUser = true,
                contactRowId = existing?.contactRowId ?: pairing.emergencyContactId
            )
        }

        // Now resolve display names for each sender
        val result = distinctSenders.values.map { person ->
            var resolvedName = person.name.trim()

            if (resolvedName.isBlank()) {
                val known = knownSenderNamesByUserId[person.userId.lowercase(java.util.Locale.US)]
                if (!known.isNullOrBlank()) {
                    resolvedName = known.trim()
                }
            }

            if (resolvedName.isBlank()) {
                val pairingMatch = allAcceptedPairings.firstOrNull {
                    it.requesterUserId.trim().equals(person.userId, ignoreCase = true) &&
                        it.requesterName.trim().isNotBlank()
                }
                if (pairingMatch != null) {
                    resolvedName = pairingMatch.requesterName.trim()
                }
            }

            if (resolvedName.isBlank()) {
                val remoteProfileName = try {
                    activePeopleWhoSavedMeRemote.fetchSenderDisplayName(person.userId)
                } catch (e: Exception) {
                    null
                }
                if (!remoteProfileName.isNullOrBlank()) {
                    resolvedName = remoteProfileName.trim()
                }
            }

            if (resolvedName.isBlank()) {
                val localMatch = localContacts.firstOrNull {
                    it.pairedGuardianUserId?.trim()?.equals(person.userId, ignoreCase = true) == true &&
                        it.name.trim().isNotBlank()
                }
                if (localMatch != null) {
                    resolvedName = localMatch.name.trim()
                }
            }

            if (resolvedName.isBlank()) {
                resolvedName = "Connected AIZA User"
            }

            person.copy(name = resolvedName)
        }

        return result.sortedBy { it.name.lowercase(java.util.Locale.US) }
    }

    companion object {
        const val TAG_CHAT_SYNC = "GuardianChatSync"
        const val TAG_CHAT_RUNTIME = "GuardianChatRuntime"
        const val RECENT_LOCATION_MAX_AGE_MS = 5 * 60 * 1000L

        private val knownSenderNamesByUserId = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun registerKnownSenderName(senderUserId: String?, senderName: String?) {
            val cleanUid = senderUserId?.trim() ?: return
            if (!isRealAuthenticatedUserUuid(cleanUid)) return
            val cleanName = AizaEmergencyNotificationManager.sanitizeSenderName(senderName) ?: return
            knownSenderNamesByUserId[cleanUid.lowercase(java.util.Locale.US)] = cleanName
        }

        fun clearKnownSenderNames() {
            knownSenderNamesByUserId.clear()
        }

        /**
         * Returns true if and only if [incident] is a SENT emergency owned by [authenticatedUserId]:
         * - [authenticatedUserId], [incident.userId], and [incident.id] must be real authenticated UUIDs
         *   (never emails, phone numbers, "default_user", or placeholder UUIDs).
         * - `incident.userId == authenticatedUserId`.
         * - Not a received-incident projection for [authenticatedUserId].
         */
        fun isSentEmergencyForUser(
            incident: EmergencyIncidentEntity,
            authenticatedUserId: String?
        ): Boolean {
            if (!isRealAuthenticatedUserUuid(authenticatedUserId)) return false
            if (!isRealAuthenticatedUserUuid(incident.userId)) return false
            if (!isRealAuthenticatedUserUuid(incident.id)) return false
            if (normalizeIncidentTriggerSource(incident.triggerSource) == null) return false
            val cleanAuthUid = authenticatedUserId!!.trim()
            if (!incident.userId.trim().equals(cleanAuthUid, ignoreCase = true)) return false
            val recGuid = incident.recipientGuardianUserId?.trim()
            if (!recGuid.isNullOrEmpty() && recGuid.equals(cleanAuthUid, ignoreCase = true)) {
                return false
            }
            return true
        }

        /**
         * Returns true if and only if [incident] is a RECEIVED emergency addressed to
         * [authenticatedGuardianUserId]:
         * - [authenticatedGuardianUserId], [incident.userId], and [incident.id] must be real authenticated UUIDs
         *   (never emails, phone numbers, "default_user", or placeholder UUIDs).
         * - `incident.userId != authenticatedGuardianUserId` (a user's own incident is NEVER received).
         * - Addressed to [authenticatedGuardianUserId] via [recipientRow] (`DELIVERED`) or
         *   `incident.recipientGuardianUserId`.
         */
        fun isReceivedEmergencyForGuardian(
            incident: EmergencyIncidentEntity,
            authenticatedGuardianUserId: String?,
            recipientRow: EmergencyIncidentRecipientEntity? = null
        ): Boolean {
            if (!isRealAuthenticatedUserUuid(authenticatedGuardianUserId)) return false
            if (!isRealAuthenticatedUserUuid(incident.userId)) return false
            if (!isRealAuthenticatedUserUuid(incident.id)) return false
            if (normalizeIncidentTriggerSource(incident.triggerSource) == null) return false
            val cleanGuardianUid = authenticatedGuardianUserId!!.trim()
            val cleanSenderUid = incident.userId.trim()
            if (cleanSenderUid.equals(cleanGuardianUid, ignoreCase = true)) return false

            if (recipientRow != null) {
                return isRealAuthenticatedUserUuid(recipientRow.guardianUserId) &&
                    isRealAuthenticatedUserUuid(recipientRow.senderUserId) &&
                    recipientRow.incidentId.trim().equals(incident.id.trim(), ignoreCase = true) &&
                    recipientRow.senderUserId.trim().equals(cleanSenderUid, ignoreCase = true) &&
                    recipientRow.guardianUserId.trim().equals(cleanGuardianUid, ignoreCase = true) &&
                    recipientRow.deliveryStatus.trim().equals(
                        EmergencyIncidentEntity.DELIVERY_STATUS_DELIVERED,
                        ignoreCase = true
                    )
            }

            val recGuid = incident.recipientGuardianUserId?.trim() ?: return false
            if (!isRealAuthenticatedUserUuid(recGuid)) return false
            return recGuid.equals(cleanGuardianUid, ignoreCase = true)
        }

        /**
         * Normalizes a trigger source string strictly to one of the four supported SOS triggers:
         * `MANUAL`, `SHAKE`, `VOICE`, or `BLUETOOTH`. Returns `null` for any other input.
         */
        fun normalizeIncidentTriggerSource(rawSource: String?): String? {
            return when (rawSource?.trim()?.uppercase(java.util.Locale.US)) {
                "MANUAL" -> EmergencyIncidentEntity.TRIGGER_MANUAL
                "SHAKE" -> EmergencyIncidentEntity.TRIGGER_SHAKE
                "VOICE" -> EmergencyIncidentEntity.TRIGGER_VOICE
                "BLUETOOTH", "BLE" -> EmergencyIncidentEntity.TRIGGER_BLUETOOTH
                else -> null
            }
        }

        /**
         * Classifies the SOS location freshness into `LIVE`, `RECENT`, `STALE`, or `UNKNOWN`.
         */
        fun classifyLocationStatus(
            hasLiveFix: Boolean,
            hasCachedFix: Boolean,
            locationTimestamp: Long?,
            nowMs: Long = System.currentTimeMillis()
        ): String {
            if (hasLiveFix) {
                return EmergencyIncidentEntity.LOCATION_STATUS_LIVE
            }
            if (hasCachedFix) {
                if (locationTimestamp != null && locationTimestamp > 0L) {
                    val ageMs = (nowMs - locationTimestamp).coerceAtLeast(0L)
                    return if (ageMs <= RECENT_LOCATION_MAX_AGE_MS) {
                        EmergencyIncidentEntity.LOCATION_STATUS_RECENT
                    } else {
                        EmergencyIncidentEntity.LOCATION_STATUS_STALE
                    }
                }
                return EmergencyIncidentEntity.LOCATION_STATUS_RECENT
            }
            return EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN
        }

        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        private val PLACEHOLDER_UUIDS = setOf(
            "00000000-0000-0000-0000-000000000000",
            "11111111-1111-1111-1111-111111111111",
            "ffffffff-ffff-ffff-ffff-ffffffffffff"
        )

        private val EMAIL_REGEX = Regex(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        )

        fun isValidUuid(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            return UUID_REGEX.matches(value.trim())
        }

        /**
         * Validates that [value] is a real Supabase UUID and NOT a placeholder UUID,
         * NOT "default_user", and NOT a phone number.
         */
        fun isRealAuthenticatedUserUuid(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val trimmed = value.trim()
            if (trimmed.equals("default_user", ignoreCase = true)) return false
            if (trimmed.all { it.isDigit() || it == '+' }) return false
            if (!UUID_REGEX.matches(trimmed)) return false
            if (PLACEHOLDER_UUIDS.contains(trimmed.lowercase(java.util.Locale.US))) return false
            return true
        }

        /**
         * Validates that [email] is a real email address and NOT:
         * - "default_user"
         * - a raw phone number
         * - a phone-derived synthetic email (e.g. +919876543210@aiza.app or 9876543210@...)
         * - an @example.com placeholder email
         */
        fun isValidPairingEmail(email: String?): Boolean {
            if (email.isNullOrBlank()) return false
            val trimmed = email.trim()
            if (trimmed.equals("default_user", ignoreCase = true)) return false
            if (trimmed.all { it.isDigit() || it == '+' }) return false
            if (!EMAIL_REGEX.matches(trimmed)) return false
            val lower = trimmed.lowercase(java.util.Locale.US)
            if (lower.endsWith("@example.com") ||
                lower.endsWith("@example.org") ||
                lower.endsWith("@example.net") ||
                lower.endsWith("@phone.local") ||
                lower.endsWith("@aiza.local") ||
                lower.endsWith("@synthetic.local")
            ) {
                return false
            }
            val localPart = lower.substringBefore("@")
            if (localPart.isNotEmpty() && localPart.all { it.isDigit() || it == '+' || it == '-' }) {
                return false
            }
            return true
        }

        /**
         * Returns true if and only if [request] belongs in the authenticated guardian's
         * incoming-request list:
         * `LOWER(request.guardianEmail) == LOWER(authenticatedGuardianEmail)` AND `status == 'PENDING'`.
         */
        fun isIncomingPendingRequestForGuardian(
            request: GuardianPairingRequestEntity,
            authenticatedGuardianEmail: String?
        ): Boolean {
            if (!isValidPairingEmail(authenticatedGuardianEmail)) return false
            val cleanAuthEmail = authenticatedGuardianEmail!!.trim().lowercase(java.util.Locale.US)
            val cleanRequestEmail = request.guardianEmail.trim().lowercase(java.util.Locale.US)
            return cleanRequestEmail == cleanAuthEmail &&
                request.status.trim().equals(GuardianPairingRequestEntity.STATUS_PENDING, ignoreCase = true)
        }

        /**
         * Validates and returns the paired guardian's Supabase Auth user UUID (`auth.users.id`)
         * if and only if [ContactEntity.guardianUserId] is a valid UUID that is neither the
         * guardian's phone number nor the emergency-contact UUID ([ContactEntity.contactId]).
         * Returns `null` if the guardian does not have a valid paired account.
         */
        fun resolvePairedGuardianUserId(contact: ContactEntity): String? {
            return contact.pairedGuardianUserId
        }

        /**
         * Determines the `recipient_id` for a Guardian Chat message to [contact]:
         * - When [contact] has a valid paired Supabase Auth UUID (`guardianUserId`), returns that UUID.
         * - When `guardianUserId` is null or invalid, returns `""` (empty string) so `recipient_id`
         *   never uses a phone number, never invents a fake UUID, and never uses [ContactEntity.contactId].
         */
        fun resolveGuardianChatRecipientId(contact: ContactEntity): String {
            return resolvePairedGuardianUserId(contact) ?: ""
        }

        private fun parseIsoTimestampOrDefault(raw: String): Long {
            if (raw.isEmpty()) return System.currentTimeMillis()
            val asLong = raw.toLongOrNull()
            if (asLong != null) return asLong
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            for (pattern in formats) {
                try {
                    val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val parsed = sdf.parse(raw)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {}
            }
            return System.currentTimeMillis()
        }

        private fun formatIsoTimestamp(epochMillis: Long): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return sdf.format(java.util.Date(epochMillis))
        }
    }
}
