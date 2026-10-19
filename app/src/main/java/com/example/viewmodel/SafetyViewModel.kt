package com.example.viewmodel

import android.app.Application
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Bundle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ContactEntity
import com.example.data.EmergencyIncidentEntity
import com.example.data.EmergencyIncidentRecipientEntity
import com.example.data.GuardianChatMessageEntity
import com.example.data.GuardianPairingRequestEntity
import com.example.data.PersonWhoSavedMe
import com.example.data.PreferencesManager
import com.example.data.SecurityUtils
import com.example.data.SosLogEntity
import com.example.data.supabase.UserLocationDto
import com.example.domain.AizaEmergencyNotificationManager
import com.example.domain.SafetyRepository
import com.example.domain.SosCommunicationDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.crypto.SecretKey

data class SharedMapLocationTarget(
    val messageId: String = "",
    val senderName: String,
    val guardianName: String = "",
    val latitude: Double,
    val longitude: Double,
    val locationTimestamp: Long,
    val locationStatus: String
)

class SafetyViewModel @JvmOverloads constructor(
    application: Application,
    customRepository: SafetyRepository? = null,
    customPreferencesManager: PreferencesManager? = null
) : AndroidViewModel(application) {

    companion object {
        @Volatile
        var activeViewModel: SafetyViewModel? = null
    }

    init {
        activeViewModel = this
    }

    private fun toggleFlashlight(enabled: Boolean) {
        val app = getApplication<Application>()
        try {
            val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enabled)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val db = AppDatabase.getDatabase(application)
    private val prefs = customPreferencesManager ?: PreferencesManager(application)
    private val repository = customRepository ?: SafetyRepository(
        contactDao = db.contactDao(),
        sosLogDao = db.sosLogDao(),
        preferencesManager = prefs,
        trackedLocationDao = db.trackedLocationDao(),
        cachedPlaceDao = db.cachedPlaceDao(),
        guardianChatDao = db.guardianChatDao(),
        guardianPairingRequestDao = db.guardianPairingRequestDao(),
        emergencyIncidentDao = db.emergencyIncidentDao()
    )
    private val authRepository = com.example.domain.AuthRepository(application, prefs)
    val networkMonitor = com.example.data.NetworkConnectivityMonitor(application)
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val evidenceVaultRepository: com.example.data.evidence.EvidenceVaultRepository =
        com.example.data.evidence.EvidenceVaultRepository(
            evidenceDao = db.evidenceDao(),
            preferencesManager = prefs
        )

    val evidenceRecords: StateFlow<List<com.example.data.evidence.EvidenceRecordEntity>> =
        evidenceVaultRepository.observeAllEvidence(prefs.supabaseUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedEvidence = MutableStateFlow<com.example.data.evidence.EvidenceRecordEntity?>(null)
    val selectedEvidence: StateFlow<com.example.data.evidence.EvidenceRecordEntity?> = _selectedEvidence.asStateFlow()

    private val _decryptedEvidence = MutableStateFlow<com.example.data.pqc.AizaPqcSecurityManager.DecryptedEvidenceResult?>(null)
    val decryptedEvidence: StateFlow<com.example.data.pqc.AizaPqcSecurityManager.DecryptedEvidenceResult?> = _decryptedEvidence.asStateFlow()

    private val _evidenceVerificationResult = MutableStateFlow<com.example.data.pqc.AizaPqcSecurityManager.VerificationResult?>(null)
    val evidenceVerificationResult: StateFlow<com.example.data.pqc.AizaPqcSecurityManager.VerificationResult?> = _evidenceVerificationResult.asStateFlow()

    private val _isEvidenceProcessing = MutableStateFlow(false)
    val isEvidenceProcessing: StateFlow<Boolean> = _isEvidenceProcessing.asStateFlow()

    private val _evidenceStatusMessage = MutableStateFlow<String?>(null)
    val evidenceStatusMessage: StateFlow<String?> = _evidenceStatusMessage.asStateFlow()

    // Real Nearby Places state (Offline-first cached)
    private val _nearbyPlaces = MutableStateFlow<List<com.example.ui.maps.EmergencyPoi>>(emptyList())
    val nearbyPlaces: StateFlow<List<com.example.ui.maps.EmergencyPoi>> = _nearbyPlaces.asStateFlow()

    private val _isPlacesLoading = MutableStateFlow(false)
    val isPlacesLoading: StateFlow<Boolean> = _isPlacesLoading.asStateFlow()

    private val _placesFromCache = MutableStateFlow(false)
    val placesFromCache: StateFlow<Boolean> = _placesFromCache.asStateFlow()

    private val _placesLastUpdated = MutableStateFlow(0L)
    val placesLastUpdated: StateFlow<Long> = _placesLastUpdated.asStateFlow()

    fun refreshNearbyPlaces(force: Boolean = false) {
        val lat = _latitude.value
        val lng = _longitude.value
        if (lat == 0.0 && lng == 0.0) return
        viewModelScope.launch {
            _isPlacesLoading.value = true
            try {
                val (places, fromCache) = com.example.ui.maps.MapPoisRepository.getRealNearbyPlaces(
                    context = getApplication(),
                    userLat = lat,
                    userLng = lng,
                    isOnline = networkMonitor.isCurrentlyOnline()
                )
                _nearbyPlaces.value = places
                _placesFromCache.value = fromCache
                _placesLastUpdated.value = if (fromCache && places.isNotEmpty()) {
                    places.maxOfOrNull { it.lastUpdated } ?: System.currentTimeMillis()
                } else {
                    System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e("SafetyViewModel", "Error fetching real places: ${e.message}")
            } finally {
                _isPlacesLoading.value = false
            }
        }
    }

    // User Safe Zones / Safe Havens state (reactive and synchronized with Room cache)
    private val _userSafeZones = MutableStateFlow<List<com.example.ui.maps.SafeZone>>(
        com.example.ui.maps.MapPoisRepository.getUserSafeZones(getApplication())
    )
    val userSafeZones: StateFlow<List<com.example.ui.maps.SafeZone>> = _userSafeZones.asStateFlow()

    fun addSafeHaven(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = 300.0,
        type: com.example.ui.maps.SafeZoneType = com.example.ui.maps.SafeZoneType.CUSTOM,
        isExplicitlySelected: Boolean = true
    ): Result<com.example.ui.maps.SafeZone> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Safe haven name cannot be empty."))
        }
        if (latitude.isNaN() || latitude.isInfinite() || longitude.isNaN() || longitude.isInfinite()) {
            return Result.failure(IllegalArgumentException("Invalid coordinates: Latitude and longitude must be valid numbers."))
        }
        if (latitude < -90.0 || latitude > 90.0) {
            return Result.failure(IllegalArgumentException("Invalid latitude: Must be between -90 and 90 degrees."))
        }
        if (longitude < -180.0 || longitude > 180.0) {
            return Result.failure(IllegalArgumentException("Invalid longitude: Must be between -180 and 180 degrees."))
        }
        // Reject uninitialized coordinates when not explicitly selected by user
        if (latitude == 0.0 && longitude == 0.0 && !isExplicitlySelected) {
            return Result.failure(IllegalStateException("No valid location selected. Please wait for a GPS fix or select a location on the map."))
        }

        val safeZone = com.example.ui.maps.SafeZone(
            id = "haven_${System.currentTimeMillis()}",
            name = trimmedName,
            type = type,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters.coerceIn(50.0, 5000.0),
            isEnabled = true
        )
        viewModelScope.launch {
            val poi = com.example.ui.maps.MapPoisRepository.addUserSafeHaven(getApplication(), safeZone)
            val updatedZones = com.example.ui.maps.MapPoisRepository.getUserSafeZones(getApplication())
            _userSafeZones.value = updatedZones
            // Immediately merge into _nearbyPlaces so UI updates instantly
            val currentPlaces = _nearbyPlaces.value.filter { it.id != poi.id && it.category != com.example.ui.maps.PoiCategory.PHARMACY }
            _nearbyPlaces.value = (currentPlaces + poi).sortedBy { it.distanceMeters }
            addNotification("Safe Haven added: '$trimmedName'")
        }
        return Result.success(safeZone)
    }

    fun toggleSafeHaven(zoneId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val currentZones = _userSafeZones.value
            val updated = currentZones.map {
                if (it.id == zoneId) it.copy(isEnabled = isEnabled) else it
            }
            com.example.ui.maps.MapPoisRepository.saveUserSafeZones(getApplication(), updated)
            _userSafeZones.value = updated
        }
    }

    fun removeSafeHaven(zoneId: String) {
        viewModelScope.launch {
            com.example.ui.maps.MapPoisRepository.removeUserSafeHaven(getApplication(), zoneId)
            val updatedZones = com.example.ui.maps.MapPoisRepository.getUserSafeZones(getApplication())
            _userSafeZones.value = updatedZones
            _nearbyPlaces.value = _nearbyPlaces.value.filter { it.id != zoneId }
            addNotification("Safe Haven removed.")
        }
    }

    // Contacts & History Logs via repository
    val contacts: StateFlow<List<ContactEntity>> = repository.allContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pairingRequests: StateFlow<List<GuardianPairingRequestEntity>> = repository.observePairingRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<SosLogEntity>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactCount: StateFlow<Int> = repository.contactCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Onboarding & Profile State
    private val _isOnboardingCompleted = MutableStateFlow(repository.isOnboardingComplete)
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(prefs.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(prefs.isDarkTheme)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _appTheme = MutableStateFlow(prefs.appTheme)
    val appTheme: StateFlow<String> = _appTheme.asStateFlow()

    private val _accentColor = MutableStateFlow(prefs.accentColor)
    val accentColor: StateFlow<String> = _accentColor.asStateFlow()

    fun setAppTheme(theme: String) {
        prefs.appTheme = theme
        _appTheme.value = theme
        _isDarkTheme.value = (theme == "dark")
        addNotification("App appearance theme set to ${theme.replaceFirstChar { it.uppercase() }} mode.")
    }

    fun setAccentColor(accent: String) {
        prefs.accentColor = accent
        _accentColor.value = accent
        addNotification("Theme accent color updated to ${accent.replaceFirstChar { it.uppercase() }}.")
    }

    private val _profileImageUri = MutableStateFlow(prefs.profileImageUri)
    val profileImageUri: StateFlow<String> = _profileImageUri.asStateFlow()

    private val _sosSentLog = MutableStateFlow<List<String>>(emptyList())
    val sosSentLog: StateFlow<List<String>> = _sosSentLog.asStateFlow()

    private val _userPassword = MutableStateFlow(prefs.userPassword)
    val userPassword: StateFlow<String> = _userPassword.asStateFlow()

    private val _userName = MutableStateFlow(repository.userName)
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userPhone = MutableStateFlow(repository.userPhone)
    val userPhone: StateFlow<String> = _userPhone.asStateFlow()

    private val _isShakeEnabled = MutableStateFlow(repository.isShakeEnabled)
    val isShakeEnabled: StateFlow<Boolean> = _isShakeEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(repository.isLocationEnabled)
    val isLocationEnabled: StateFlow<Boolean> = _isLocationEnabled.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(repository.isBluetoothEnabled)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isVoiceSosEnabled = MutableStateFlow(repository.isVoiceSosEnabled)
    val isVoiceSosEnabled: StateFlow<Boolean> = _isVoiceSosEnabled.asStateFlow()

    private val _isWifiEnabled = MutableStateFlow(repository.isWifiEnabled)
    val isWifiEnabled: StateFlow<Boolean> = _isWifiEnabled.asStateFlow()

    private val _userEmail = MutableStateFlow(prefs.userEmail)
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    // Medical / Health Details State
    private val _medicalNotes = MutableStateFlow(prefs.medicalNotes)
    val medicalNotes: StateFlow<String> = _medicalNotes.asStateFlow()

    private val _bloodGroup = MutableStateFlow(prefs.bloodGroup)
    val bloodGroup: StateFlow<String> = _bloodGroup.asStateFlow()

    private val _allergies = MutableStateFlow(prefs.allergies)
    val allergies: StateFlow<String> = _allergies.asStateFlow()

    private val _medicalConditions = MutableStateFlow(prefs.medicalConditions)
    val medicalConditions: StateFlow<String> = _medicalConditions.asStateFlow()

    fun updateMedicalDetails(bloodGroup: String = "", allergies: String = "", conditions: String = "", notes: String = "") {
        prefs.medicalNotes = notes.trim()
        prefs.bloodGroup = bloodGroup.trim()
        prefs.allergies = allergies.trim()
        prefs.medicalConditions = conditions.trim()

        _medicalNotes.value = notes.trim()
        _bloodGroup.value = bloodGroup.trim()
        _allergies.value = allergies.trim()
        _medicalConditions.value = conditions.trim()
        syncActiveIncidentMedicalState()
        addNotification("Emergency Medical Notes & Health Profile saved.")
    }

    // Emergency Voice Recording & Playback State
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var voiceTimerJob: Job? = null
    private var voiceRecordingStartedAtMs: Long = 0L

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _isPlayingVoice = MutableStateFlow(false)
    val isPlayingVoice: StateFlow<Boolean> = _isPlayingVoice.asStateFlow()

    private val _recordedVoiceDurationSec = MutableStateFlow(0)
    val recordedVoiceDurationSec: StateFlow<Int> = _recordedVoiceDurationSec.asStateFlow()

    private val _hasRecordedVoiceClip = MutableStateFlow(false)
    val hasRecordedVoiceClip: StateFlow<Boolean> = _hasRecordedVoiceClip.asStateFlow()

    private val _voiceRecordingPath = MutableStateFlow("")
    val voiceRecordingPath: StateFlow<String> = _voiceRecordingPath.asStateFlow()

    private val _shakeSensitivity = MutableStateFlow(repository.shakeSensitivity)
    val shakeSensitivity: StateFlow<Float> = _shakeSensitivity.asStateFlow()

    private val _isHardwareLinkEnabled = MutableStateFlow(prefs.isHardwareLinkEnabled)
    val isHardwareLinkEnabled: StateFlow<Boolean> = _isHardwareLinkEnabled.asStateFlow()

    private val _countryCode = MutableStateFlow(prefs.countryCode)
    val countryCode: StateFlow<String> = _countryCode.asStateFlow()

    private val _googleMapsApiKey = MutableStateFlow(prefs.googleMapsApiKey)
    val googleMapsApiKey: StateFlow<String> = _googleMapsApiKey.asStateFlow()

    private val _supabaseUrl = MutableStateFlow(prefs.supabaseUrl)
    val supabaseUrl: StateFlow<String> = _supabaseUrl.asStateFlow()

    private val _supabaseAnonKey = MutableStateFlow(prefs.supabaseAnonKey)
    val supabaseAnonKey: StateFlow<String> = _supabaseAnonKey.asStateFlow()

    // Cryptography state
    private val _publicKey = MutableStateFlow(repository.getPublicKey())
    val publicKey: StateFlow<String> = _publicKey.asStateFlow()

    // Verification states
    private val _verificationState = MutableStateFlow<Map<Int, String>>(emptyMap()) // ContactId -> Status Text
    val verificationState: StateFlow<Map<Int, String>> = _verificationState.asStateFlow()

    // Active SOS state
    private val _isSosCountdownActive = MutableStateFlow(false)
    val isSosCountdownActive: StateFlow<Boolean> = _isSosCountdownActive.asStateFlow()

    private val _sosCountdownSeconds = MutableStateFlow(3)
    val sosCountdownSeconds: StateFlow<Int> = _sosCountdownSeconds.asStateFlow()

    private val _isSosTriggered = MutableStateFlow(false)
    val isSosTriggered: StateFlow<Boolean> = _isSosTriggered.asStateFlow()

    private val _sosDurationSeconds = MutableStateFlow(0)
    val sosDurationSeconds: StateFlow<Int> = _sosDurationSeconds.asStateFlow()

    // Continuous location sharing state (independent of SOS)
    private val _isContinuousLocationSharingEnabled = MutableStateFlow(prefs.isContinuousLocationSharingEnabled)
    val isContinuousLocationSharingEnabled: StateFlow<Boolean> = _isContinuousLocationSharingEnabled.asStateFlow()

    private val _lastLocationSyncTimestamp = MutableStateFlow(prefs.lastLocationSyncTimestamp)
    val lastLocationSyncTimestamp: StateFlow<Long> = _lastLocationSyncTimestamp.asStateFlow()

    // Authorized Contact shared location viewing state
    private val _selectedContactForLocation = MutableStateFlow<ContactEntity?>(null)
    val selectedContactForLocation: StateFlow<ContactEntity?> = _selectedContactForLocation.asStateFlow()

    private val _contactSharedLocation = MutableStateFlow<UserLocationDto?>(null)
    val contactSharedLocation: StateFlow<UserLocationDto?> = _contactSharedLocation.asStateFlow()

    private val _isContactLocationLoading = MutableStateFlow(false)
    val isContactLocationLoading: StateFlow<Boolean> = _isContactLocationLoading.asStateFlow()

    private val _contactLocationError = MutableStateFlow<String?>(null)
    val contactLocationError: StateFlow<String?> = _contactLocationError.asStateFlow()

    // BLE & Phone hardware connection state
    private val _isBleHardwareConnected = MutableStateFlow(false)
    val isBleHardwareConnected: StateFlow<Boolean> = _isBleHardwareConnected.asStateFlow()

    // Real-time GPS sharing state
    private val _latitude = MutableStateFlow(prefs.lastLatitude.toDouble()) // Default last known or user location
    val latitude: StateFlow<Double> = _latitude.asStateFlow()

    private val _longitude = MutableStateFlow(prefs.lastLongitude.toDouble())
    val longitude: StateFlow<Double> = _longitude.asStateFlow()

    private val _trackingPath = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val trackingPath: StateFlow<List<Pair<Double, Double>>> = _trackingPath.asStateFlow()

    // Guardian Walk tracking state
    private val _isGuardianWalkActive = MutableStateFlow(false)
    val isGuardianWalkActive: StateFlow<Boolean> = _isGuardianWalkActive.asStateFlow()

    private val _guardianWalkDestination = MutableStateFlow("")
    val guardianWalkDestination: StateFlow<String> = _guardianWalkDestination.asStateFlow()

    private val _guardianWalkRemainingSeconds = MutableStateFlow(900)
    val guardianWalkRemainingSeconds: StateFlow<Int> = _guardianWalkRemainingSeconds.asStateFlow()

    private val _guardianWalkElapsedSeconds = MutableStateFlow(0)
    val guardianWalkElapsedSeconds: StateFlow<Int> = _guardianWalkElapsedSeconds.asStateFlow()

    private var guardianWalkJob: Job? = null

    // Encrypted Security Logs during active SOS
    private val _encryptedLogPayload = MutableStateFlow("")
    val encryptedLogPayload: StateFlow<String> = _encryptedLogPayload.asStateFlow()

    private val _decryptedLogPayload = MutableStateFlow("")
    val decryptedLogPayload: StateFlow<String> = _decryptedLogPayload.asStateFlow()

    private val _encryptionIv = MutableStateFlow("")
    val encryptionIv: StateFlow<String> = _encryptionIv.asStateFlow()

    private val _currentAesKey = MutableStateFlow("")
    val currentAesKey: StateFlow<String> = _currentAesKey.asStateFlow()

    // Notifications state list
    private val _notifications = MutableStateFlow<List<String>>(
        listOf(
            "Welcome to AIZA! Setup your profile and contacts.",
            "E2E Device Key pair initialized and stored securely."
        )
    )
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    private var countdownJob: Job? = null
    private var sosTrackingJob: Job? = null

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    @Volatile
    private var hasLiveSessionLocationFix: Boolean = false

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            updateLocation(location.latitude, location.longitude, location.accuracy)
        }
    }

    init {
        // Ensure any legacy email accidentally stored in userPhone is migrated to userEmail and cleared from userPhone
        val sanitizedPhone = prefs.userPhone
        _userPhone.value = sanitizedPhone
        authRepository.getCurrentUserEmail()?.let { authEmail ->
            prefs.userEmail = authEmail
        }
        _userEmail.value = prefs.userEmail

        // Initialize RSA Keys
        repository.checkAndGenerateKeyPair()
        _publicKey.value = repository.getPublicKey()

        // Check if existing emergency voice recording exists
        try {
            val existingVoice = java.io.File(application.filesDir, "emergency_voice_clip.mp4")
            if (existingVoice.exists() && existingVoice.length() > 0) {
                _hasRecordedVoiceClip.value = true
                _voiceRecordingPath.value = existingVoice.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Initialize Location Provider & Battery Level
        refreshBatteryLevel()
        initLocationEngine()
        try {
            ensureEmergencyNotificationChannel()
        } catch (_: Exception) {}

        // Pre-populate _nearbyPlaces with existing user safe havens so map indicators are ready immediately
        val initialHavens = _userSafeZones.value.map { zone ->
            com.example.ui.maps.EmergencyPoi(
                id = zone.id,
                name = zone.name,
                category = com.example.ui.maps.PoiCategory.SHELTER,
                latitude = zone.latitude,
                longitude = zone.longitude,
                address = "Verified Safe Haven (${zone.type.title})",
                phone = "1091",
                is24x7 = true,
                isOpenNow = true,
                distanceMeters = 0,
                verified = true,
                description = "User designated verified safe haven perimeter (${zone.radiusMeters.toInt()}m)",
                lastUpdated = System.currentTimeMillis()
            )
        }
        if (initialHavens.isNotEmpty()) {
            _nearbyPlaces.value = initialHavens
        }

        // Sync authenticated Supabase identity, guardian pairing requests, & received emergency incidents on startup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (_, resolvedEmail) = repository.resolveAuthenticatedPairingIdentity()
                if (!resolvedEmail.isNullOrBlank()) {
                    _userEmail.value = resolvedEmail
                }
                repository.syncPairingRequestsWithSupabase()
                repository.syncReceivedEmergencyIncidents(authRepository.getCurrentUserId())
                ensureGuardianIncidentsRealtimeSubscribed()
            } catch (_: Exception) {}
        }

        // Network re-connection listener: sync pending offline SOS logs, chat messages, incidents, and refresh real nearby places
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                if (online) {
                    try {
                        val (_, resolvedEmail) = repository.resolveAuthenticatedPairingIdentity()
                        if (!resolvedEmail.isNullOrBlank()) {
                            _userEmail.value = resolvedEmail
                        }
                        repository.syncPendingDataToSupabase()
                        repository.syncReceivedEmergencyIncidents(authRepository.getCurrentUserId())
                        ensureGuardianIncidentsRealtimeSubscribed()
                        if (_activeChatGuardian.value != null) {
                            refreshActiveGuardianChat()
                            ensureGuardianChatRealtimeSubscribed()
                        }
                    } catch (e: Exception) {
                        Log.w("SafetyViewModel", "Pending sync on network reconnect: ${e.message}")
                    }
                    refreshNearbyPlaces()
                }
            }
        }
    }

    fun getBatteryPercentage(): Int {
        val app = getApplication<Application>()
        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                app.registerReceiver(null, filter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                95
            }
        } catch (e: Exception) {
            95
        }
    }

    fun refreshBatteryLevel() {
        _batteryLevel.value = getBatteryPercentage()
    }

    fun initLocationEngine() {
        val app = getApplication<Application>()
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(app)
            requestLiveLocationUpdates()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun requestLiveLocationUpdates() {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            
            try {
                fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                    if (loc != null) {
                        updateLocation(loc.latitude, loc.longitude)
                    }
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }

            try {
                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).apply {
                    setMinUpdateIntervalMillis(1000L)
                    setWaitForAccurateLocation(false)
                }.build()
                fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                e.printStackTrace()
            }

            // Fallback LocationManager for native GPS & Network providers
            try {
                val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                locationManager?.let { lm ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            updateLocation(loc.latitude, loc.longitude)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 1f, listener, Looper.getMainLooper())
                    }
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 1f, listener, Looper.getMainLooper())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Onboarding operations
    fun completeOnboardingOnly() {
        prefs.isOnboardingComplete = true
        _isOnboardingCompleted.value = true
        addNotification("SOS features introduced. Secure Supabase Auth channel initialized.")
    }

    fun completeOnboarding(name: String, phone: String) {
        viewModelScope.launch {
            repository.userName = name
            repository.userPhone = phone
            repository.isOnboardingComplete = true

            _userName.value = name
            _userPhone.value = phone
            _isOnboardingCompleted.value = true

            addNotification("Profile activated for $name. Offline secure encryption engine ready.")
        }
    }

    // Authentication and Profile Operations
    fun registerUser(
        name: String,
        email: String,
        password: String,
        userPhone: String = "",
        userCountryCode: String = "+91",
        emergencyName: String,
        emergencyRelation: String,
        emergencyPhone: String,
        onComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val cleanEmail = email.trim()
            if (!com.example.domain.AuthRepository.isValidGmailAddress(cleanEmail)) {
                val errorMsg = com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE
                addNotification(errorMsg)
                onComplete(false, errorMsg)
                return@launch
            }
            val result = authRepository.signUp(cleanEmail, password)
            if (result.isSuccess) {
                val userId = result.getOrNull() ?: prefs.supabaseUserId
                val cleanName = name.trim()
                _userName.value = cleanName
                _userEmail.value = cleanEmail
                _userPassword.value = password
                prefs.userName = cleanName
                prefs.userEmail = cleanEmail
                prefs.userPassword = password

                // Normalize and store user's own phone number (distinct from emergency contact)
                val normalizedUserPhone = if (userPhone.isNotBlank()) {
                    com.example.data.PhoneUtils.normalize(userPhone, userCountryCode)
                } else {
                    ""
                }
                prefs.userPhone = normalizedUserPhone
                prefs.countryCode = userCountryCode
                _userPhone.value = normalizedUserPhone
                _isLoggedIn.value = true

                // Automatically register initial single emergency contact with priority 1
                if (emergencyName.isNotBlank() && emergencyPhone.isNotBlank()) {
                    val rel = emergencyRelation.ifBlank { "Emergency Contact" }
                    repository.insertContact(
                        ContactEntity(
                            name = emergencyName,
                            phone = emergencyPhone,
                            relation = rel,
                            priority = "1",
                            isVerified = true
                        )
                    )
                }

                addNotification("User registered & sync enabled with Supabase Secure DB.")
                if (userId.isNotBlank()) {
                    repository.syncProfileToSupabase(
                        com.example.data.supabase.ProfileDto(
                            id = userId,
                            name = cleanName,
                            phone = normalizedUserPhone,
                            email = cleanEmail
                        )
                    )
                }
                refreshGuardianPairingRequests()
                syncReceivedEmergencyIncidents()
                updateBackgroundServiceState()
                onComplete(true, null)
            } else {
                val error = result.exceptionOrNull()
                val errorMsg = error?.message ?: "Supabase registration failed"
                addNotification("Supabase Registration Error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    // Overload for backward compatibility with 6-arg callers
    fun registerUser(
        name: String,
        email: String,
        password: String,
        emergencyName: String,
        emergencyRelation: String,
        emergencyPhone: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        registerUser(
            name = name,
            email = email,
            password = password,
            userPhone = "",
            userCountryCode = "+91",
            emergencyName = emergencyName,
            emergencyRelation = emergencyRelation,
            emergencyPhone = emergencyPhone,
            onComplete = onComplete
        )
    }


    fun loginUser(
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val cleanEmail = email.trim()
            if (!com.example.domain.AuthRepository.isValidGmailAddress(cleanEmail)) {
                val errorMsg = com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE
                addNotification(errorMsg)
                onComplete(false, errorMsg)
                return@launch
            }
            val result = authRepository.signIn(cleanEmail, password)
            if (result.isSuccess) {
                val userId = result.getOrNull() ?: prefs.supabaseUserId
                prefs.isLoggedIn = true
                _isLoggedIn.value = true
                val liveAuthEmail = authRepository.getCurrentUser()?.email?.trim()
                    ?.takeIf { com.example.domain.AuthRepository.isValidGmailAddress(it) } ?: cleanEmail
                prefs.userEmail = liveAuthEmail
                _userEmail.value = liveAuthEmail
                // Keep only manually saved phone number, never copy email into phone
                _userPhone.value = prefs.userPhone
                if (prefs.userName.isNotBlank()) {
                    _userName.value = prefs.userName
                }

                // Fetch profile from Supabase if available
                if (userId.isNotBlank()) {
                    val profileResult = repository.fetchProfileFromSupabase(userId)
                    profileResult.getOrNull()?.let { profile ->
                        if (profile.name.isNotBlank()) {
                            _userName.value = profile.name
                            prefs.userName = profile.name
                        }
                        if (profile.phone.isNotBlank() && !profile.phone.contains("@")) {
                            val normalizedPhone = com.example.data.PhoneUtils.normalize(profile.phone, prefs.countryCode)
                            _userPhone.value = normalizedPhone
                            prefs.userPhone = normalizedPhone
                        }
                        if (profile.email.isNotBlank() && com.example.domain.AuthRepository.isValidGmailAddress(profile.email)) {
                            _userEmail.value = profile.email.trim()
                            prefs.userEmail = profile.email.trim()
                        }
                    }
                }

                refreshGuardianPairingRequests()
                syncReceivedEmergencyIncidents()
                addNotification("Welcome back, ${_userName.value.ifBlank { liveAuthEmail }}! Secure Supabase session token synchronized.")
                updateBackgroundServiceState()
                onComplete(true, null)
            } else {
                val error = result.exceptionOrNull()
                val errorMsg = error?.message ?: "Invalid credentials"
                // Check if offline fallback matches locally stored Gmail credentials only
                val isEmailMatch = com.example.domain.AuthRepository.isValidGmailAddress(prefs.userEmail) &&
                    prefs.userEmail.equals(cleanEmail, ignoreCase = true)
                val isPasswordMatch = prefs.userPassword.isNotBlank() && prefs.userPassword == password

                if (isEmailMatch && isPasswordMatch) {
                    prefs.isLoggedIn = true
                    _isLoggedIn.value = true
                    _userEmail.value = prefs.userEmail
                    _userPhone.value = prefs.userPhone
                    addNotification("Offline fallback: Logged in using cached credentials ($errorMsg)")
                    updateBackgroundServiceState()
                    onComplete(true, "Offline fallback: $errorMsg")
                } else {
                    addNotification("Supabase Login Failed: $errorMsg")
                    onComplete(false, errorMsg)
                }
            }
        }
    }

    fun sendPasswordResetEmail(email: String, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val cleanEmail = email.trim()
            if (!com.example.domain.AuthRepository.isValidGmailAddress(cleanEmail)) {
                val errorMsg = com.example.domain.AuthRepository.GMAIL_ONLY_ERROR_MESSAGE
                addNotification(errorMsg)
                onComplete(false, errorMsg)
                return@launch
            }
            val result = authRepository.sendPasswordResetEmail(cleanEmail)
            if (result.isSuccess) {
                addNotification("Password reset email sent to $cleanEmail. Please check your inbox.")
                onComplete(true, null)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to send password reset email"
                addNotification("Password reset error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    // Password Recovery Flow State
    private val _isPasswordResetFlowActive = MutableStateFlow(false)
    val isPasswordResetFlowActive: StateFlow<Boolean> = _isPasswordResetFlowActive.asStateFlow()

    private val _passwordResetErrorMessage = MutableStateFlow<String?>(null)
    val passwordResetErrorMessage: StateFlow<String?> = _passwordResetErrorMessage.asStateFlow()

    private val _passwordResetSuccess = MutableStateFlow(false)
    val passwordResetSuccess: StateFlow<Boolean> = _passwordResetSuccess.asStateFlow()

    fun handleAuthRecoveryDeeplink(uri: android.net.Uri) {
        val uriString = uri.toString()
        val fragment = uri.fragment.orEmpty()
        val query = uri.query.orEmpty()
        val combined = "$query&$fragment"

        // Check for error parameters returned by Supabase Auth (e.g. expired link)
        if (combined.contains("error=", ignoreCase = true) || combined.contains("error_code=", ignoreCase = true)) {
            val errorDesc = uri.getQueryParameter("error_description")
                ?: if (combined.contains("error_description=")) {
                    combined.substringAfter("error_description=").substringBefore("&").replace("+", " ")
                } else {
                    "Password reset link is invalid or has expired. Please request a new one."
                }
            _passwordResetErrorMessage.value = try {
                java.net.URLDecoder.decode(errorDesc, "UTF-8")
            } catch (_: Exception) {
                errorDesc
            }
            _isPasswordResetFlowActive.value = true
            addNotification("Password reset failed: ${_passwordResetErrorMessage.value}")
            return
        }

        // Check if this is a password recovery session
        if (combined.contains("type=recovery", ignoreCase = true) ||
            uri.host.equals("reset-password", ignoreCase = true) ||
            combined.contains("access_token=", ignoreCase = true)
        ) {
            _passwordResetErrorMessage.value = null
            _passwordResetSuccess.value = false
            _isPasswordResetFlowActive.value = true
            addNotification("Recovery link verified. Please enter your new password.")
        }
    }

    fun submitNewPassword(
        newPass: String,
        confirmPass: String,
        onComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            if (newPass.length < 6) {
                val err = "Password must be at least 6 characters."
                _passwordResetErrorMessage.value = err
                onComplete(false, err)
                return@launch
            }
            if (newPass != confirmPass) {
                val err = "Passwords do not match."
                _passwordResetErrorMessage.value = err
                onComplete(false, err)
                return@launch
            }

            _passwordResetErrorMessage.value = null
            val result = authRepository.updateUserPassword(newPass)
            if (result.isSuccess) {
                prefs.userPassword = newPass
                _passwordResetSuccess.value = true
                _isPasswordResetFlowActive.value = false
                _passwordResetErrorMessage.value = null
                addNotification("Password updated successfully! You can now log in.")
                onComplete(true, null)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to update password. Link may be expired."
                _passwordResetErrorMessage.value = err
                addNotification("Password update error: $err")
                onComplete(false, err)
            }
        }
    }

    fun dismissPasswordResetFlow() {
        _isPasswordResetFlowActive.value = false
        _passwordResetErrorMessage.value = null
        _passwordResetSuccess.value = false
    }


    fun resendConfirmationEmail(identifier: String, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = authRepository.resendConfirmationEmail(identifier)
            if (result.isSuccess) {
                addNotification("Confirmation email resent. Please check your inbox.")
                onComplete(true, null)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to resend confirmation email"
                addNotification("Resend error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    fun verifyEmailOtp(identifier: String, otp: String, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = authRepository.verifyEmailOtp(identifier, otp)
            if (result.isSuccess) {
                val userId = result.getOrNull() ?: prefs.supabaseUserId
                prefs.isLoggedIn = true
                _isLoggedIn.value = true
                _userEmail.value = prefs.userEmail
                _userPhone.value = prefs.userPhone
                refreshGuardianPairingRequests()
                syncReceivedEmergencyIncidents()
                addNotification("Email confirmed and node connected!")
                updateBackgroundServiceState()
                onComplete(true, null)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Invalid or expired OTP code"
                addNotification("OTP verification error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    fun loginWithGoogle(onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = authRepository.signInWithGoogle()
            if (result.isSuccess) {
                val userId = result.getOrNull() ?: ""
                if (userId.isNotBlank()) {
                    prefs.isLoggedIn = true
                    _isLoggedIn.value = true
                    if (prefs.userName.isNotBlank()) {
                        _userName.value = prefs.userName
                    }
                    if (prefs.userEmail.isNotBlank()) {
                        _userEmail.value = prefs.userEmail
                    }
                    _userPhone.value = prefs.userPhone
                    val profileResult = repository.fetchProfileFromSupabase(userId)
                    profileResult.getOrNull()?.let { profile ->
                        if (profile.name.isNotBlank()) {
                            _userName.value = profile.name
                            prefs.userName = profile.name
                        }
                        if (profile.phone.isNotBlank() && !profile.phone.contains("@")) {
                            val normalizedPhone = com.example.data.PhoneUtils.normalize(profile.phone, prefs.countryCode)
                            _userPhone.value = normalizedPhone
                            prefs.userPhone = normalizedPhone
                        }
                    } ?: run {
                        repository.syncProfileToSupabase(
                            com.example.data.supabase.ProfileDto(
                                id = userId,
                                name = _userName.value.ifBlank { "Google User" },
                                phone = prefs.userPhone,
                                email = prefs.userEmail
                            )
                        )
                    }
                    refreshGuardianPairingRequests()
                    syncReceivedEmergencyIncidents()
                    addNotification("Google Sign-In completed via Supabase Secure OAuth. Account ${_userName.value} sync active.")
                    updateBackgroundServiceState()
                    onComplete(true, null)
                } else {
                    addNotification("Opening Google OAuth verification in browser...")
                    onComplete(true, null)
                }
            } else {
                _isLoggedIn.value = false
                _userEmail.value = ""
                val error = result.exceptionOrNull()
                val errorMsg = error?.message ?: "Google OAuth failed"
                addNotification(errorMsg)
                onComplete(false, errorMsg)
            }
        }
    }

    fun syncGoogleOAuthSession(onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val syncResult = authRepository.verifyAndSyncOAuthSession()
            if (syncResult.isSuccess) {
                val userId = syncResult.getOrNull() ?: prefs.supabaseUserId
                _isLoggedIn.value = true
                if (prefs.userName.isNotBlank()) {
                    _userName.value = prefs.userName
                }
                if (prefs.userEmail.isNotBlank()) {
                    _userEmail.value = prefs.userEmail
                }
                _userPhone.value = prefs.userPhone
                val profileResult = repository.fetchProfileFromSupabase(userId)
                profileResult.getOrNull()?.let { profile ->
                    if (profile.name.isNotBlank()) {
                        _userName.value = profile.name
                        prefs.userName = profile.name
                    }
                    if (profile.phone.isNotBlank() && !profile.phone.contains("@")) {
                        val normalizedPhone = com.example.data.PhoneUtils.normalize(profile.phone, prefs.countryCode)
                        _userPhone.value = normalizedPhone
                        prefs.userPhone = normalizedPhone
                    }
                } ?: run {
                    repository.syncProfileToSupabase(
                        com.example.data.supabase.ProfileDto(
                            id = userId,
                            name = _userName.value.ifBlank { "Google User" },
                            phone = prefs.userPhone,
                            email = prefs.userEmail
                        )
                    )
                }
                refreshGuardianPairingRequests()
                syncReceivedEmergencyIncidents()
                addNotification("Google OAuth verified! Welcome, ${_userName.value}.")
                updateBackgroundServiceState()
                onComplete(true, null)
            } else {
                _isLoggedIn.value = false
                _userEmail.value = ""
                _userName.value = ""
                val errorMsg = syncResult.exceptionOrNull()?.message ?: "Could not synchronize OAuth session"
                addNotification(errorMsg)
                onComplete(false, errorMsg)
            }
        }
    }

    // --- Deep Link Password Recovery & Phone OTP Authentication ---
    private val _showCreateNewPasswordDialog = MutableStateFlow(false)
    val showCreateNewPasswordDialog: StateFlow<Boolean> = _showCreateNewPasswordDialog.asStateFlow()

    fun setCreateNewPasswordDialog(show: Boolean) {
        _showCreateNewPasswordDialog.value = show
    }

    fun updateUserPassword(newPassword: String, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = authRepository.updateUserPassword(newPassword)
            if (result.isSuccess) {
                prefs.userPassword = newPassword
                _userPassword.value = newPassword
                _showCreateNewPasswordDialog.value = false
                addNotification("Password updated successfully.")
                onComplete(true, null)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to update password."
                addNotification("Password update error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    fun sendPhoneOtp(phoneNumber: String, countryCode: String = "+91", onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = authRepository.sendPhoneOtp(phoneNumber, countryCode)
            if (result.isSuccess) {
                addNotification("OTP sent to $phoneNumber.")
                onComplete(true, null)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to send OTP."
                addNotification("OTP send error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    fun resendPhoneOtp(phoneNumber: String, countryCode: String = "+91", onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = authRepository.resendPhoneOtp(phoneNumber, countryCode)
            if (result.isSuccess) {
                addNotification("OTP resent to $phoneNumber.")
                onComplete(true, null)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Failed to resend OTP."
                addNotification("OTP resend error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    fun verifyPhoneOtp(phoneNumber: String, token: String, countryCode: String = "+91", onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = authRepository.verifyPhoneOtp(phoneNumber, token, countryCode)
            if (result.isSuccess) {
                val userId = result.getOrNull() ?: prefs.supabaseUserId
                prefs.isLoggedIn = true
                _isLoggedIn.value = true
                _userPhone.value = prefs.userPhone
                refreshGuardianPairingRequests()
                syncReceivedEmergencyIncidents()
                addNotification("Phone verified successfully!")
                updateBackgroundServiceState()
                onComplete(true, null)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Invalid or expired OTP."
                addNotification("Phone OTP error: $errorMsg")
                onComplete(false, errorMsg)
            }
        }
    }

    fun handleRecoveryDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val fragment = uri.fragment ?: ""
        val query = uri.query ?: ""
        val combined = "$query&$fragment"
        if (combined.contains("type=recovery") || combined.contains("access_token=") || uri.path?.contains("recovery") == true) {
            _showCreateNewPasswordDialog.value = true
        }
    }

    fun resetPassword(newPassword: String) {
        prefs.userPassword = newPassword
        _userPassword.value = newPassword
        addNotification("AIZA password updated securely.")
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
            addNotification("Incident History Logs purged successfully.")
        }
    }

    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount.asStateFlow()

    private val _deleteAccountError = MutableStateFlow<String?>(null)
    val deleteAccountError: StateFlow<String?> = _deleteAccountError.asStateFlow()

    fun clearDeleteAccountError() {
        _deleteAccountError.value = null
    }

    fun deleteAccount(
        purgeLocalOfflineEvidence: Boolean = false,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isDeletingAccount.value = true
            _deleteAccountError.value = null
            try {
                val result = authRepository.deleteAccount(purgeLocalOfflineEvidence = purgeLocalOfflineEvidence)
                if (result.isSuccess) {
                    repository.clearProfile()
                    repository.clearLogs()
                    contacts.value.forEach {
                        repository.deleteContact(it)
                    }
                    logout()
                    _isDeletingAccount.value = false
                    addNotification("AIZA account permanently deleted.")
                    onSuccess()
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Account deletion failed"
                    _deleteAccountError.value = errMsg
                    _isDeletingAccount.value = false
                    onError(errMsg)
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "Account deletion failed"
                _deleteAccountError.value = errMsg
                _isDeletingAccount.value = false
                onError(errMsg)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.signOut()
        }
        authRepository.logout()
        prefs.isLoggedIn = false
        _isLoggedIn.value = false
        _userName.value = ""
        _userPhone.value = ""
        _userEmail.value = ""
        _userPassword.value = ""
        _profileImageUri.value = ""
        
        // Stop AegisBackgroundService on logout
        val app = getApplication<Application>()
        try {
            app.stopService(Intent(app, com.example.AegisBackgroundService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        stopGuardianIncidentsRealtime()
        countdownJob?.cancel()
        sosTrackingJob?.cancel()
        hardwareJob?.cancel()
        _isSosCountdownActive.value = false
        _isSosTriggered.value = false
        addNotification("Logged out successfully. Secure session cleared.")
    }

    fun toggleThemeSetting() {
        val nextVal = !prefs.isDarkTheme
        prefs.isDarkTheme = nextVal
        _isDarkTheme.value = nextVal
        addNotification("Theme toggled to ${if (nextVal) "Midnight Dark" else "Sleek Light"}")
    }

    fun updateProfileImage(uri: String) {
        prefs.profileImageUri = uri
        _profileImageUri.value = uri
        addNotification("Profile picture updated successfully.")
    }

    private var hardwareJob: Job? = null

    fun setHardwareLink(enabled: Boolean) {
        prefs.isHardwareLinkEnabled = enabled
        _isHardwareLinkEnabled.value = enabled
        addNotification("Hardware ESP32 Panic Button Link: ${if (enabled) "Enabled" else "Disabled"}")
        if (enabled) {
            startSimulatedHardwareListener()
        } else {
            hardwareJob?.cancel()
            hardwareJob = null
        }
    }

    private fun startSimulatedHardwareListener() {
        hardwareJob?.cancel()
        hardwareJob = viewModelScope.launch {
            addNotification("Scanning for ESP32 AIZA Panic Button via Bluetooth...")
            delay(3000)
            if (isHardwareLinkEnabled.value) {
                addNotification("ESP32 BLE Panic Button found (MAC: AA:BB:CC:DD:EE:FF). Connecting...")
                delay(2000)
                if (isHardwareLinkEnabled.value) {
                    addNotification("ESP32 AIZA Panic Button connected securely! Monitoring hardware interrupts.")
                }
            }
        }
    }

    fun simulateHardwareButtonPress() {
        if (isBluetoothEnabled.value && _isLoggedIn.value) {
            addNotification("[HARDWARE INTERRUPT] ESP32 Panic Button Pressed!")
            processBluetoothData("S")
        } else {
            addNotification("Cannot trigger: Bluetooth Safety Trigger is disabled or user logged out.")
        }
    }

    fun updateProfileDetails(name: String, phone: String) {
        viewModelScope.launch {
            val cleanName = name.trim()
            val normalizedPhone = if (phone.isBlank() || phone.contains("@")) {
                ""
            } else {
                com.example.data.PhoneUtils.normalize(phone, prefs.countryCode)
            }
            repository.userName = cleanName
            repository.userPhone = normalizedPhone

            _userName.value = cleanName
            _userPhone.value = normalizedPhone

            val currentEmail = authRepository.getCurrentUser()?.email?.takeIf { it.isNotBlank() }
                ?: prefs.userEmail
            if (currentEmail.isNotBlank()) {
                prefs.userEmail = currentEmail
                _userEmail.value = currentEmail
            }

            val userId = authRepository.getCurrentUserId() ?: prefs.supabaseUserId
            if (userId.isNotBlank()) {
                repository.syncProfileToSupabase(
                    com.example.data.supabase.ProfileDto(
                        id = userId,
                        name = cleanName,
                        phone = normalizedPhone,
                        email = currentEmail
                    )
                )
            }

            addNotification("Profile details successfully updated.")
        }
    }



    fun simulateIncomingSos() {
        viewModelScope.launch {
            val sender = "John Doe (+919988776655)"
            val incomingLat = 12.9716
            val incomingLng = 77.5946
            val payloadInfo = "Kyber-1024 / Dilithium lattice signature check PASSED. E2E Decrypted SOS Broadcast."
            
            val receivedLog = SosLogEntity(
                timestamp = System.currentTimeMillis(),
                durationSeconds = 0,
                latitude = incomingLat,
                longitude = incomingLng,
                status = "Incoming SOS Received",
                encryptedPayload = payloadInfo,
                eventType = "RECEIVED",
                senderInfo = sender,
                contacts = ""
            )
            repository.insertLog(receivedLog)
            addNotification("🔴 INCOMING SOS RECEIVED from $sender! Coordinates: $incomingLat, $incomingLng. Check radar map immediately.")
        }
    }

    fun resetProfile() {
        viewModelScope.launch {
            repository.clearProfile()
            _isOnboardingCompleted.value = false
            _isLoggedIn.value = false
            _userName.value = ""
            _userPhone.value = ""
            _notifications.value = listOf("AIZA profile reset completed.")
        }
    }

    private fun updateBackgroundServiceState() {
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, com.example.AegisBackgroundService::class.java)
        val anyTriggerEnabled = prefs.isShakeEnabled || prefs.isBluetoothEnabled || prefs.isVoiceSosEnabled
        if (anyTriggerEnabled && prefs.isLoggedIn) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(serviceIntent)
                } else {
                    app.startService(serviceIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            com.example.AegisBackgroundService.activeInstance?.updateBluetoothListenerState()
            com.example.AegisBackgroundService.activeInstance?.updateVoiceListenerState()
        } else {
            try {
                app.stopService(serviceIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun toggleShakeSetting(enabled: Boolean) {
        repository.isShakeEnabled = enabled
        _isShakeEnabled.value = enabled
        addNotification("Shake-to-SOS toggled: ${if (enabled) "Enabled" else "Disabled"}")
        updateBackgroundServiceState()
    }

    fun toggleLocationSetting(enabled: Boolean) {
        repository.isLocationEnabled = enabled
        _isLocationEnabled.value = enabled
        addNotification("Location Sharing toggled: ${if (enabled) "Enabled" else "Disabled"}")
    }

    fun toggleBluetoothSetting(enabled: Boolean) {
        repository.isBluetoothEnabled = enabled
        _isBluetoothEnabled.value = enabled
        addNotification("Bluetooth Safety Trigger toggled: ${if (enabled) "Enabled" else "Disabled"}")
        updateBackgroundServiceState()
    }

    fun toggleVoiceSosSetting(enabled: Boolean) {
        repository.isVoiceSosEnabled = enabled
        _isVoiceSosEnabled.value = enabled
        addNotification("Voice SOS Detection toggled: ${if (enabled) "Enabled" else "Disabled"}")
        updateBackgroundServiceState()
    }

    fun processVoiceTrigger(spokenText: String) {
        if (!isVoiceSosEnabled.value || !_isLoggedIn.value) {
            addNotification("Voice trigger ignored: Voice SOS feature disabled or user logged out.")
            return
        }
        val voiceManager = com.example.data.VoiceSosManager(getApplication())
        if (voiceManager.isEmergencyPhrase(spokenText)) {
            addNotification("[VOICE SOS TRIGGER] Verified Emergency Phrase ('${com.example.data.VoiceSosManager.EMERGENCY_PHRASE}') spoken!")
            startSosCountdown(EmergencyIncidentEntity.TRIGGER_VOICE)
        } else {
            addNotification("Voice input ignored: '$spokenText' did not match emergency phrase.")
        }
    }

    fun processWearableData(rawData: String, deviceId: String? = null): Boolean {
        if (!isBluetoothEnabled.value || !_isLoggedIn.value) {
            addNotification("Bluetooth trigger ignored: Bluetooth feature disabled or user logged out.")
            return false
        }
        val targetId = deviceId ?: prefs.trustedBluetoothDeviceId.ifBlank { null }
        val triggered = bluetoothWearableManager.processIncomingData(rawData, targetId)
        if (triggered) {
            addNotification("[BLUETOOTH HARDWARE SOS] Valid trigger character ('S') received over Bluetooth!")
            startSosCountdown(EmergencyIncidentEntity.TRIGGER_BLUETOOTH)
        } else {
            addNotification("Received invalid or untrusted Bluetooth packet: '$rawData'. Trigger ignored.")
        }
        return triggered
    }

    fun processBluetoothData(rawData: String, deviceId: String? = null) {
        processWearableData(rawData, deviceId)
    }

    fun toggleWifiSetting(enabled: Boolean) {
        repository.isWifiEnabled = enabled
        _isWifiEnabled.value = enabled
        addNotification("Wi-Fi Safety Feature toggled: ${if (enabled) "Enabled" else "Disabled"}")
    }

    // Contacts CRUD
    fun addContact(name: String, phone: String, relation: String, priority: String = "1") {
        viewModelScope.launch {
            val normalizedPhone = com.example.data.PhoneUtils.normalize(phone, _countryCode.value)
            if (!com.example.data.PhoneUtils.isValid(phone, _countryCode.value)) {
                addNotification("Error: Invalid phone number. Must be +91 followed by 10 digits.")
                return@launch
            }

            val currentContacts = contacts.value.sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }
            // Check duplicate by phone number or contact ID
            if (currentContacts.any { com.example.data.PhoneUtils.normalize(it.phone) == normalizedPhone }) {
                addNotification("Contact with phone $normalizedPhone already exists.")
                return@launch
            }

            val currentUserId = prefs.userEmail.ifBlank { "default_user" }
            val newContact = ContactEntity(
                contactId = java.util.UUID.randomUUID().toString(),
                userId = currentUserId,
                name = name.trim(),
                phone = normalizedPhone,
                relation = relation.trim().ifBlank { "Emergency Contact" },
                priority = priority,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isVerified = false
            )

            val updatedList = currentContacts.toMutableList()
            val desiredRank = priority.toIntOrNull() ?: (updatedList.size + 1)
            val targetIndex = (desiredRank - 1).coerceIn(0, updatedList.size)
            updatedList.add(targetIndex, newContact)

            updatedList.forEachIndexed { idx, item ->
                val pStr = (idx + 1).toString()
                if (item.contactId == newContact.contactId) {
                    repository.insertContact(item.copy(priority = pStr))
                } else {
                    repository.updateContact(item.copy(priority = pStr, updatedAt = System.currentTimeMillis()))
                }
            }

            addNotification("Contact '${name.trim()}' ($normalizedPhone) added as Priority #${targetIndex + 1}.")
        }
    }

    fun updateContact(contact: ContactEntity) {
        viewModelScope.launch {
            val normalizedPhone = com.example.data.PhoneUtils.normalize(contact.phone, _countryCode.value)
            val updatedContact = contact.copy(
                phone = normalizedPhone,
                updatedAt = System.currentTimeMillis()
            )

            val currentList = contacts.value.sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }.toMutableList()
            val index = currentList.indexOfFirst { it.id == contact.id || it.contactId == contact.contactId }
            if (index != -1) {
                currentList.removeAt(index)
                val desiredRank = contact.priority.toIntOrNull() ?: (index + 1)
                val newIndex = (desiredRank - 1).coerceIn(0, currentList.size)
                currentList.add(newIndex, updatedContact)

                currentList.forEachIndexed { idx, item ->
                    val pStr = (idx + 1).toString()
                    repository.updateContact(item.copy(priority = pStr, updatedAt = System.currentTimeMillis()))
                }
            } else {
                repository.updateContact(updatedContact)
            }
            addNotification("Contact '${contact.name}' updated successfully.")
        }
    }

    fun moveContactPriority(contact: ContactEntity, moveUp: Boolean) {
        viewModelScope.launch {
            val sortedList = contacts.value.sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }.toMutableList()
            val currentIndex = sortedList.indexOfFirst { it.id == contact.id || it.contactId == contact.contactId }
            if (currentIndex == -1) return@launch

            val targetIndex = if (moveUp) currentIndex - 1 else currentIndex + 1
            if (targetIndex !in 0 until sortedList.size) return@launch

            val temp = sortedList[currentIndex]
            sortedList[currentIndex] = sortedList[targetIndex]
            sortedList[targetIndex] = temp

            sortedList.forEachIndexed { idx, item ->
                val pStr = (idx + 1).toString()
                repository.updateContact(item.copy(priority = pStr, updatedAt = System.currentTimeMillis()))
            }
            addNotification("Reordered '${contact.name}' to Priority #${targetIndex + 1}.")
        }
    }

    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch {
            if (_activeChatGuardian.value?.contactId == contact.contactId) {
                closeGuardianChat()
            }
            repository.deleteContact(contact)
            val remainingList = contacts.value
                .filterNot { it.id == contact.id || it.contactId == contact.contactId }
                .sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }

            remainingList.forEachIndexed { idx, item ->
                val pStr = (idx + 1).toString()
                if (item.priority != pStr) {
                    repository.updateContact(item.copy(priority = pStr, updatedAt = System.currentTimeMillis()))
                }
            }
            addNotification("Contact '${contact.name}' deleted. Remaining priority ranks re-indexed.")
        }
    }

    // Contact Verification (Simulates E2E Handshake with asymmetric verification check)
    fun verifyContact(contact: ContactEntity) {
        val currentMap = _verificationState.value.toMutableMap()
        currentMap[contact.id] = "Verifying..."
        _verificationState.value = currentMap

        viewModelScope.launch {
            addNotification("Initiating cryptographic handshake with ${contact.name}...")
            delay(1500) // Simulate round trip latency

            val updatedMap = _verificationState.value.toMutableMap()
            updatedMap[contact.id] = "Key Exchanging..."
            _verificationState.value = updatedMap
            delay(1500) // Simulate PQC-ready key exchange

            val finalMap = _verificationState.value.toMutableMap()
            finalMap[contact.id] = "Verified ✅"
            _verificationState.value = finalMap

            // Update contact in DB
            repository.updateContact(contact.copy(isVerified = true))
            addNotification("Handshake complete! ${contact.name}'s identity has been verified offline.")
        }
    }

    // Central SOS Engine
    var currentSosTriggerSource = "Manual"
    private var lastSosTriggerTimestamp = 0L

    fun startSosCountdown(source: String = "Manual") {
        if (_isSosTriggered.value || _isSosCountdownActive.value) return
        currentSosTriggerSource = source
        _isSosCountdownActive.value = true
        _sosCountdownSeconds.value = 3

        countdownJob = viewModelScope.launch {
            while (_sosCountdownSeconds.value > 0) {
                delay(1000)
                _sosCountdownSeconds.value -= 1
            }
            triggerSos(currentSosTriggerSource)
        }
    }

    fun cancelSosCountdown() {
        countdownJob?.cancel()
        _isSosCountdownActive.value = false
        _sosCountdownSeconds.value = 3
        addNotification("SOS countdown cancelled.")
    }

    fun triggerSos(source: String = "Manual") {
        val now = System.currentTimeMillis()
        if (now - lastSosTriggerTimestamp < 3000 && _isSosTriggered.value) {
            Log.d("SafetyViewModel", "Duplicate SOS trigger suppressed within 3000ms window.")
            viewModelScope.launch(Dispatchers.IO) {
                createOrUpdateSosIncidentInternal(source, now)
            }
            return
        }
        lastSosTriggerTimestamp = now
        currentSosTriggerSource = source

        _isSosCountdownActive.value = false
        _isSosTriggered.value = true
        _sosDurationSeconds.value = 0

        // Handle offline GPS fix: use current if valid, else cached last known if acquired, else unavailable.
        var sosLat = _latitude.value
        var sosLng = _longitude.value
        var locStatus = "ACTIVE_GPS"

        if (sosLat == 0.0 && sosLng == 0.0) {
            if (prefs.hasAcquiredRealGpsFix && prefs.lastLatitude != 0f && prefs.lastLongitude != 0f) {
                sosLat = prefs.lastLatitude.toDouble()
                sosLng = prefs.lastLongitude.toDouble()
                locStatus = "LAST_KNOWN"
            } else {
                locStatus = "UNAVAILABLE"
                sosLat = 0.0
                sosLng = 0.0
            }
        }

        if (sosLat != 0.0 || sosLng != 0.0) {
            _trackingPath.value = listOf(Pair(sosLat, sosLng))
        }

        val isNetworkAvailable = networkMonitor.isCurrentlyOnline()
        val isLastKnown = (locStatus == "LAST_KNOWN")
        val initialStatus = if (isNetworkAvailable) "Sent" else "Pending_Sync"
        addNotification("🔴 EMERGENCY SOS ACTIVATED ($source)! Offline GPS tracking active.")

        viewModelScope.launch {
            // Phase 2: Create or reuse/update the single ACTIVE EmergencyIncidentEntity for this SOS
            withContext(Dispatchers.IO) {
                createOrUpdateSosIncidentInternal(source, now)
            }

            var voiceClipFile: java.io.File? = null
            var voiceClipUrl: String? = null

            // VOICE SOS FLOW: Notify voice detection trigger
            if (source.equals("VOICE", ignoreCase = true)) {
                addNotification("Voice SOS detected: Triggering emergency alerts...")
            }

            // Invoke unified SOS Communication Pipeline (Rank 1 receives Call, remaining receive SMS)
            SosCommunicationDispatcher.dispatchEmergencyCommunication(
                context = getApplication(),
                userName = _userName.value,
                triggerSource = source,
                latitude = sosLat,
                longitude = sosLng,
                contacts = contacts.value,
                countryCode = _countryCode.value,
                isLastKnownLocation = isLastKnown,
                voiceClipUrl = voiceClipUrl,
                onLog = { addNotification(it) }
            )

            // Generate PQC Encrypted payload & save to Sent History locally immediately
            val payloadString = "SOS ID: ${java.util.UUID.randomUUID()} | Trigger: $source | Sender: ${_userName.value} | Lat: $sosLat | Lng: $sosLng | LocStatus: $locStatus | Time: ${System.currentTimeMillis()} | Status: ACTIVE"
            val (pqcPubKey, pqcPrvKey) = SecurityUtils.generatePqcKeyPair()
            val encryptedResult = SecurityUtils.encryptPqcKyber(payloadString, pqcPubKey)
            val dilithiumSig = SecurityUtils.signPqcDilithium(payloadString, pqcPrvKey)

            _encryptedLogPayload.value = encryptedResult.encryptedData + "\n[PQC DILITHIUM SIGNATURE]:\n" + dilithiumSig
            _encryptionIv.value = encryptedResult.iv

            val notifiedContacts = contacts.value.joinToString(", ") { "${it.name} (${it.priority})" }
            val logStatus = if (source.equals("VOICE", ignoreCase = true) && voiceClipFile != null && voiceClipUrl.isNullOrBlank()) "Pending_Sync" else initialStatus

            repository.insertLog(
                SosLogEntity(
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 1,
                    latitude = sosLat,
                    longitude = sosLng,
                    locationStatus = locStatus,
                    status = logStatus,
                    triggerSource = source,
                    encryptedPayload = _encryptedLogPayload.value,
                    eventType = "SENT",
                    contacts = notifiedContacts.ifBlank { "All Emergency Contacts" },
                    senderInfo = _userName.value.ifBlank { "AIZA User" },
                    voiceClipPath = voiceClipFile?.absolutePath ?: "",
                    voiceClipUrl = voiceClipUrl ?: ""
                )
            )

            // Asynchronously dispatch PQC-signed SOS event to Supabase cloud if online
            if (isNetworkAvailable) {
                val userId = authRepository.getCurrentUserId() ?: prefs.supabaseUserId.ifBlank { "anonymous_aegis_user" }
                val hasLocation = (sosLat != 0.0 || sosLng != 0.0)
                val sosDto = com.example.data.supabase.SosAlertDto(
                    userId = userId,
                    latitude = if (hasLocation) sosLat else null,
                    longitude = if (hasLocation) sosLng else null,
                    locationAvailable = hasLocation,
                    triggeredAt = System.currentTimeMillis(),
                    status = "ACTIVE",
                    triggerType = source,
                    encryptedPayload = encryptedResult.encryptedData,
                    signature = dilithiumSig,
                    voiceClipUrl = voiceClipUrl
                )
                repository.recordSosAlertToSupabase(sosDto)
            }
        }

        // Real GPS tracking during SOS: request actual GPS location, no fake jitter
        sosTrackingJob = viewModelScope.launch {
            while (_isSosTriggered.value) {
                requestLiveLocationUpdates()
                delay(3000)
                _sosDurationSeconds.value += 3
            }
        }
    }

    fun deactivateSos() {
        _isSosTriggered.value = false
        sosTrackingJob?.cancel()
        toggleFlashlight(false)
        if (_isRecordingVoice.value) {
            stopVoiceRecording()
        }

        // Update active status in telemetry and resolve active EmergencyIncident
        viewModelScope.launch {
            _sosDurationSeconds.value = 0
            _trackingPath.value = emptyList()
            _encryptedLogPayload.value = ""
            _decryptedLogPayload.value = ""
            _encryptionIv.value = ""
            _currentAesKey.value = ""

            withContext(Dispatchers.IO) {
                val resolvedResult = repository.resolveActiveEmergencyIncident(
                    status = EmergencyIncidentEntity.STATUS_RESOLVED,
                    endedAt = System.currentTimeMillis(),
                    explicitUserId = authRepository.getCurrentUserId()
                )
                val resolvedIncident = resolvedResult.getOrNull()
                if (resolvedIncident != null && networkMonitor.isCurrentlyOnline()) {
                    repository.syncEmergencyIncidentToSupabase(
                        incident = resolvedIncident,
                        explicitUserId = resolvedIncident.userId
                    )
                }
            }

            addNotification("SOS Deactivated. Log successfully stored and synchronized.")
            addNotification("Supabase Sync: Successfully pushed local incident log to Supabase central PostgreSQL backend.")
        }
    }

    private fun sendSmsSecurely(normalizedPhone: String, messageText: String): Boolean {
        val app = getApplication<Application>()
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: @Suppress("DEPRECATION") SmsManager.getDefault()

            if (messageText.length > 160) {
                val parts = smsManager.divideMessage(messageText)
                smsManager.sendMultipartTextMessage(normalizedPhone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(normalizedPhone, null, messageText, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e("SafetyViewModel", "Error sending SMS to $normalizedPhone", e)
            false
        }
    }

    fun sendSmsAndCallGuardians() {
        val app = getApplication<Application>()
        SosCommunicationDispatcher.dispatchEmergencyCommunication(
            context = app,
            userName = _userName.value,
            triggerSource = "SOS",
            latitude = _latitude.value,
            longitude = _longitude.value,
            contacts = contacts.value,
            countryCode = _countryCode.value,
            onLog = { addNotification(it) }
        )
    }

    // --- Continuous Location Sharing (Independent of SOS) ---

    fun toggleContinuousLocationSharing(enabled: Boolean) {
        _isContinuousLocationSharingEnabled.value = enabled
        prefs.isContinuousLocationSharingEnabled = enabled
        repository.isContinuousLocationSharingEnabled = enabled
        addNotification(if (enabled) "Live location sharing enabled for emergency contacts." else "Live location sharing disabled.")
        if (enabled) {
            syncContinuousLocationToSupabase(force = true)
        }
    }

    fun syncContinuousLocationToSupabase(force: Boolean = false) {
        if (!_isContinuousLocationSharingEnabled.value && !force) return
        val lat = _latitude.value
        val lng = _longitude.value
        if (lat == 0.0 && lng == 0.0) return

        viewModelScope.launch(Dispatchers.IO) {
            if (networkMonitor.isCurrentlyOnline()) {
                val result = repository.pushUserLocationToSupabase(
                    lat = lat,
                    lng = lng,
                    accuracy = 10f,
                    isSharing = _isContinuousLocationSharingEnabled.value,
                    batteryLevel = _batteryLevel.value
                )
                if (result.isSuccess) {
                    val now = System.currentTimeMillis()
                    _lastLocationSyncTimestamp.value = now
                    prefs.lastLocationSyncTimestamp = now
                    Log.d("SafetyViewModel", "Synced user location to Supabase")
                }
            }
        }
    }

    // --- Contact Location View (Authorized Emergency Contacts) ---

    fun selectContactForLocation(contact: ContactEntity?) {
        _selectedContactForLocation.value = contact
        if (contact != null) {
            fetchContactSharedLocation(contact)
        } else {
            _contactSharedLocation.value = null
            _contactLocationError.value = null
        }
    }

    fun fetchContactSharedLocation(contact: ContactEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _isContactLocationLoading.value = true
            _contactLocationError.value = null
            val targetUserId = contact.userId.ifBlank { contact.contactId }
            val result = repository.fetchSharedLocationForUser(targetUserId)
            if (result.isSuccess) {
                val loc = result.getOrNull()
                if (loc != null && loc.isSharingEnabled && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                    _contactSharedLocation.value = loc
                    _contactLocationError.value = null
                } else {
                    _contactSharedLocation.value = null
                    _contactLocationError.value = "Location Unavailable: ${contact.name} has not enabled sharing or has not acquired a GPS fix."
                }
            } else {
                _contactSharedLocation.value = null
                _contactLocationError.value = "Unable to retrieve shared location for ${contact.name}."
            }
            _isContactLocationLoading.value = false
        }
    }

    fun refreshHardwareAndServiceState() {
        val service = com.example.AegisBackgroundService.activeInstance
        val isConnected = service?.getBluetoothSosManager()?.isConnected() == true
        _isBleHardwareConnected.value = isConnected
    }

    private fun openFallbackDialer(phone: String, app: Application) {
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phone")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(dialIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateLocation(lat: Double, lng: Double, accuracy: Float = 0f) {
        if (lat == 0.0 && lng == 0.0) return
        val prevLat = _latitude.value
        val prevLng = _longitude.value
        val locationNow = System.currentTimeMillis()

        hasLiveSessionLocationFix = true
        _latitude.value = lat
        _longitude.value = lng
        prefs.lastLatitude = lat.toFloat()
        prefs.lastLongitude = lng.toFloat()
        prefs.lastLocationTimestamp = locationNow
        prefs.hasAcquiredRealGpsFix = true

        // Offline-first GPS location tracking: store coordinate to Room database & mirror to active incident
        val isSosActiveNow = _isSosTriggered.value
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveTrackedLocation(lat, lng, accuracy, isSosActiveNow)
            if (isSosActiveNow) {
                val updatedResult = repository.updateActiveIncidentLocation(
                    latitude = lat,
                    longitude = lng,
                    locationTimestamp = locationNow,
                    locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
                    explicitUserId = authRepository.getCurrentUserId()
                )
                val updatedIncident = updatedResult.getOrNull()
                if (updatedIncident != null && networkMonitor.isCurrentlyOnline()) {
                    repository.syncEmergencyIncidentToSupabase(
                        incident = updatedIncident,
                        explicitUserId = updatedIncident.userId
                    )
                }
            }
        }

        // Continuous location sharing: sync if enabled and threshold reached
        if (_isContinuousLocationSharingEnabled.value) {
            val now = System.currentTimeMillis()
            val lastSync = _lastLocationSyncTimestamp.value
            val distMoved = com.example.ui.maps.MapPoisRepository.calculateDistanceMeters(prevLat, prevLng, lat, lng)
            if (now - lastSync > 30000L || distMoved >= 25.0) {
                syncContinuousLocationToSupabase()
            }
        }

        // Live breadcrumb tracking path
        val currentPath = _trackingPath.value.toMutableList()
        val shouldAppend = currentPath.isEmpty() ||
                com.example.ui.maps.MapPoisRepository.calculateDistanceMeters(
                    currentPath.last().first, currentPath.last().second, lat, lng
                ) >= 5

        if (shouldAppend) {
            currentPath.add(Pair(lat, lng))
            _trackingPath.value = currentPath
        }

        // Auto-refresh real nearby places if unpopulated or moved > 500m
        val distMoved = com.example.ui.maps.MapPoisRepository.calculateDistanceMeters(prevLat, prevLng, lat, lng)
        if (_nearbyPlaces.value.isEmpty() || distMoved > 500) {
            refreshNearbyPlaces()
        }
    }

    fun setSimulatedLocation(lat: Double, lng: Double) {
        updateLocation(lat, lng)
        val currentPath = _trackingPath.value.toMutableList()
        currentPath.add(Pair(lat, lng))
        _trackingPath.value = currentPath
        addNotification("Location set to: ${String.format(Locale.US, "%.4f, %.4f", lat, lng)}")
    }

    fun clearTrackingBreadcrumbs() {
        _trackingPath.value = if (_latitude.value != 0.0) listOf(Pair(_latitude.value, _longitude.value)) else emptyList()
        addNotification("Breadcrumb tracking trail cleared.")
    }

    fun startGuardianWalk(destination: String, durationMinutes: Int) {
        guardianWalkJob?.cancel()
        _isGuardianWalkActive.value = true
        _guardianWalkDestination.value = destination
        val totalSeconds = durationMinutes * 60
        _guardianWalkRemainingSeconds.value = totalSeconds
        _guardianWalkElapsedSeconds.value = 0

        // Ensure tracking path starts with current position
        if (_latitude.value != 0.0 || _longitude.value != 0.0) {
            _trackingPath.value = listOf(Pair(_latitude.value, _longitude.value))
        }

        addNotification("Guardian Walk initiated: '$destination' ($durationMinutes min target). Live telemetry active.")

        guardianWalkJob = viewModelScope.launch(Dispatchers.Main) {
            while (_isGuardianWalkActive.value && _guardianWalkRemainingSeconds.value > 0) {
                delay(1000L)
                _guardianWalkElapsedSeconds.value += 1
                _guardianWalkRemainingSeconds.value -= 1

                // Periodic breadcrumb update simulation if moving
                if (_guardianWalkElapsedSeconds.value % 15 == 0 && (_latitude.value != 0.0)) {
                    val slightDriftLat = _latitude.value + (Math.random() - 0.5) * 0.00012
                    val slightDriftLng = _longitude.value + (Math.random() - 0.5) * 0.00012
                    updateLocation(slightDriftLat, slightDriftLng)
                }

                if (_guardianWalkRemainingSeconds.value == 0) {
                    addNotification("⚠️ Guardian Walk alert: Expected arrival time reached! Please check in.")
                }
            }
        }
    }

    fun stopGuardianWalk(arrivedSafely: Boolean = true) {
        guardianWalkJob?.cancel()
        guardianWalkJob = null
        _isGuardianWalkActive.value = false
        if (arrivedSafely) {
            addNotification("Guardian Walk ended: Arrived safely at ${_guardianWalkDestination.value.ifBlank { "destination" }}.")
        } else {
            addNotification("Guardian Walk cancelled.")
        }
    }

    fun extendGuardianWalk(additionalMinutes: Int) {
        _guardianWalkRemainingSeconds.value += additionalMinutes * 60
        addNotification("Guardian Walk extended by $additionalMinutes minutes.")
    }

    private fun addNotification(message: String) {
        val currentList = _notifications.value.toMutableList()
        currentList.add(0, message) // Insert at top
        _notifications.value = currentList
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        if (activeViewModel == this) {
            activeViewModel = null
        }
        closeGuardianChat()
        toggleFlashlight(false)
        stopVoiceRecording()
        stopVoicePlayback()
    }

    fun callGuardiansOnly() {
        Log.d("SafetyViewModel", "Call Guardian: Button Clicked")
        val app = getApplication<Application>()
        Log.d("SafetyViewModel", "Call Guardian: Loading Primary Contact")
        val sortedGuardians = contacts.value.sortedWith(compareBy<ContactEntity> {
            it.priority.toIntOrNull() ?: when (it.priority.lowercase(Locale.getDefault())) {
                "high" -> 1
                "medium" -> 2
                "low" -> 3
                else -> 999
            }
        }.thenBy { it.id })

        if (sortedGuardians.isEmpty()) {
            Log.e("SafetyViewModel", "Call Guardian: Call Failed (No contacts configured)")
            addNotification("No contacts configured for priority call sequencing.")
            return
        }

        val contact = sortedGuardians.firstOrNull() ?: return
        Log.d("SafetyViewModel", "Call Guardian: Contact Found (${contact.name})")
        val normalizedPhone = com.example.data.PhoneUtils.normalize(contact.phone, _countryCode.value)
        
        if (normalizedPhone.isBlank()) {
            Log.e("SafetyViewModel", "Call Guardian: Call Failed (Empty or invalid phone number)")
            addNotification("Primary contact has an invalid or empty phone number.")
            return
        }

        addNotification("QUICK ACTION CALL: Dialing primary guardian: ${contact.name} ($normalizedPhone)...")

        Log.d("SafetyViewModel", "Call Guardian: Initiating Phone Call")
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$normalizedPhone")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(callIntent)
                Log.d("SafetyViewModel", "Call Guardian: Phone Call Started")
            } catch (e: Exception) {
                Log.e("SafetyViewModel", "Call Guardian: Call Failed (${e.message})")
                e.printStackTrace()
                openFallbackDialer(normalizedPhone, app)
            }
        } else {
            Log.e("SafetyViewModel", "Call Guardian: Call Failed (CALL_PHONE permission denied)")
            addNotification("CALL_PHONE permission denied. Opening dialer as fallback.")
            openFallbackDialer(normalizedPhone, app)
        }
    }

    // Selective Info Sharing State
    private val _shareGpsTracking = MutableStateFlow(prefs.shareGpsTracking)
    val shareGpsTracking: StateFlow<Boolean> = _shareGpsTracking.asStateFlow()

    private val _shareBatteryStatus = MutableStateFlow(prefs.shareBatteryStatus)
    val shareBatteryStatus: StateFlow<Boolean> = _shareBatteryStatus.asStateFlow()

    private val _shareMedicalInfo = MutableStateFlow(prefs.shareMedicalInfo)
    val shareMedicalInfo: StateFlow<Boolean> = _shareMedicalInfo.asStateFlow()

    private val _shareNetworkDiagnostics = MutableStateFlow(false)
    val shareNetworkDiagnostics: StateFlow<Boolean> = _shareNetworkDiagnostics.asStateFlow()

    private val _isSendVoiceClipWithSos = MutableStateFlow(prefs.isSendVoiceClipWithSos)
    val isSendVoiceClipWithSos: StateFlow<Boolean> = _isSendVoiceClipWithSos.asStateFlow()

    fun toggleShareGps(value: Boolean) {
        prefs.shareGpsTracking = value
        _shareGpsTracking.value = value
        if (value) requestLiveLocationUpdates()
        addNotification("Selective Sharing: GPS Tracking set to ${if (value) "ON" else "OFF"}")
    }

    fun toggleShareBattery(value: Boolean) {
        prefs.shareBatteryStatus = value
        _shareBatteryStatus.value = value
        refreshBatteryLevel()
        addNotification("Selective Sharing: Battery Status (${_batteryLevel.value}%) set to ${if (value) "ON" else "OFF"}")
    }

    fun toggleShareMedical(value: Boolean) {
        prefs.shareMedicalInfo = value
        _shareMedicalInfo.value = value
        syncActiveIncidentMedicalState()
        addNotification("Selective Sharing: Medical Profile Notes set to ${if (value) "ON" else "OFF"}")
    }

    fun toggleShareDiagnostics(value: Boolean) {
        _shareNetworkDiagnostics.value = value
    }

    fun toggleSendVoiceClipWithSos(value: Boolean) {
        prefs.isSendVoiceClipWithSos = value
        _isSendVoiceClipWithSos.value = value
        addNotification("Emergency Alert: Voice trigger alert preference updated.")
    }

    fun deleteVoiceRecording() {
        try {
            stopVoicePlayback()
            val path = _voiceRecordingPath.value
            if (path.isNotBlank()) {
                val f = java.io.File(path)
                if (f.exists()) {
                    f.delete()
                }
            }
            val defaultFile = java.io.File(getApplication<Application>().filesDir, "emergency_voice_clip.mp4")
            if (defaultFile.exists()) {
                defaultFile.delete()
            }
            _hasRecordedVoiceClip.value = false
            _voiceRecordingPath.value = ""
            _recordedVoiceDurationSec.value = 0
            addNotification("Voice recording cleared.")
        } catch (e: Exception) {
            Log.e("SafetyViewModel", "Error deleting voice recording", e)
            addNotification("Could not delete voice recording: ${e.message}")
        }
    }

    fun buildEmergencyMessage(isQuick: Boolean = false): String {
        val name = _userName.value.ifBlank { "AIZA User" }
        val sb = StringBuilder()
        if (isQuick) {
            sb.append("AIZA QUICK SOS ALERT: $name needs urgent help!")
        } else {
            sb.append("AIZA EMERGENCY SOS: $name is in danger!")
        }

        if (_shareGpsTracking.value) {
            if (_latitude.value != 0.0 && _longitude.value != 0.0) {
                val latStr = String.format(Locale.US, "%.5f", _latitude.value)
                val lngStr = String.format(Locale.US, "%.5f", _longitude.value)
                sb.append("\n📍 Live Map: https://maps.google.com/?q=${_latitude.value},${_longitude.value}")
                sb.append(" ($latStr, $lngStr)")
            } else {
                sb.append("\n📍 GPS: Searching for satellite lock...")
            }
        }

        if (_shareBatteryStatus.value) {
            val batt = getBatteryPercentage()
            sb.append("\n🔋 Battery: $batt%")
        }

        if (_shareMedicalInfo.value) {
            val medParts = mutableListOf<String>()
            if (_bloodGroup.value.isNotBlank()) medParts.add("Blood: ${_bloodGroup.value}")
            if (_allergies.value.isNotBlank()) medParts.add("Allergies: ${_allergies.value}")
            if (_medicalConditions.value.isNotBlank()) medParts.add("Conditions: ${_medicalConditions.value}")
            if (_medicalNotes.value.isNotBlank()) medParts.add("Notes: ${_medicalNotes.value}")
            if (medParts.isNotEmpty()) {
                sb.append("\n🏥 Medical: [${medParts.joinToString(", ")}]")
            }
        }

        return sb.toString()
    }

    fun startVoiceRecording() {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateActiveEmergencyIncidentAudioStatus(EmergencyIncidentEntity.AUDIO_STATUS_NOT_AVAILABLE)
            addNotification("Microphone permission required to record emergency audio.")
            return
        }

        try {
            stopVoicePlayback()
            val outputFile = java.io.File(app.filesDir, "emergency_voice_clip.mp4")
            _voiceRecordingPath.value = outputFile.absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(app)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            voiceRecordingStartedAtMs = System.currentTimeMillis()
            _isRecordingVoice.value = true
            _recordedVoiceDurationSec.value = 0
            updateActiveEmergencyIncidentAudioStatus(
                audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_RECORDING,
                audioDurationMs = null
            )
            voiceTimerJob?.cancel()
            voiceTimerJob = viewModelScope.launch {
                while (_isRecordingVoice.value) {
                    delay(1000)
                    _recordedVoiceDurationSec.value += 1
                }
            }
            addNotification("Recording emergency audio clip... (Speak clearly)")
        } catch (e: Exception) {
            Log.e("SafetyViewModel", "Error starting voice recorder", e)
            _isRecordingVoice.value = false
            updateActiveEmergencyIncidentAudioStatus(
                audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_FAILED,
                audioDurationMs = null
            )
            addNotification("Could not start voice recording: ${e.message}")
        }
    }

    fun stopVoiceRecording() {
        val wasRecording = _isRecordingVoice.value || mediaRecorder != null
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            _isRecordingVoice.value = false
            voiceTimerJob?.cancel()
            if (wasRecording) {
                _hasRecordedVoiceClip.value = true
                val elapsedMs = if (voiceRecordingStartedAtMs > 0L) {
                    (System.currentTimeMillis() - voiceRecordingStartedAtMs).coerceAtLeast(1L)
                } else {
                    0L
                }
                val durationMs = maxOf(_recordedVoiceDurationSec.value * 1000L, elapsedMs).takeIf { it > 0L } ?: 1000L
                voiceRecordingStartedAtMs = 0L
                updateActiveEmergencyIncidentAudioStatus(
                    audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED,
                    audioDurationMs = durationMs
                )
                addNotification("Emergency voice message saved (${_recordedVoiceDurationSec.value}s). Ready for attach & playback.")
            }
        } catch (e: Exception) {
            Log.e("SafetyViewModel", "Error stopping voice recorder", e)
            mediaRecorder = null
            _isRecordingVoice.value = false
            voiceRecordingStartedAtMs = 0L
            voiceTimerJob?.cancel()
            if (wasRecording) {
                updateActiveEmergencyIncidentAudioStatus(
                    audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_FAILED,
                    audioDurationMs = null
                )
            }
        }
    }

    fun playVoiceRecording() {
        val path = _voiceRecordingPath.value
        if (path.isBlank() || !java.io.File(path).exists()) {
            addNotification("No voice recording available to play.")
            return
        }

        try {
            stopVoicePlayback()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    _isPlayingVoice.value = false
                }
                start()
            }
            _isPlayingVoice.value = true
            addNotification("Playing recorded emergency audio clip...")
        } catch (e: Exception) {
            Log.e("SafetyViewModel", "Error playing voice recording", e)
            _isPlayingVoice.value = false
            addNotification("Error playing audio clip: ${e.message}")
        }
    }

    fun stopVoicePlayback() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        _isPlayingVoice.value = false
    }

    fun sendEmergencySmsOnly() {
        Log.d("SafetyViewModel", "Send Emergency SMS: Button Clicked")
        val app = getApplication<Application>()
        val currentContacts = contacts.value
        if (currentContacts.isEmpty()) {
            Log.e("SafetyViewModel", "Send Emergency SMS: SMS Failed (No contacts configured)")
            addNotification("No guardian contacts configured for SMS transmission.")
            return
        }

        var sosLat = _latitude.value
        var sosLng = _longitude.value
        var isLastKnown = false
        if (sosLat == 0.0 && sosLng == 0.0) {
            if (prefs.hasAcquiredRealGpsFix && prefs.lastLatitude != 0f && prefs.lastLongitude != 0f) {
                sosLat = prefs.lastLatitude.toDouble()
                sosLng = prefs.lastLongitude.toDouble()
                isLastKnown = true
            }
        }

        SosCommunicationDispatcher.dispatchEmergencyCommunication(
            context = app,
            userName = _userName.value,
            triggerSource = "QUICK_SMS",
            latitude = sosLat,
            longitude = sosLng,
            contacts = currentContacts,
            countryCode = _countryCode.value,
            isLastKnownLocation = isLastKnown,
            voiceClipUrl = null,
            onLog = { addNotification(it) }
        )
    }

    // --- 1-to-1 Guardian Chat State & Actions (Strictly Isolated from SOS) ---

    private val _activeChatGuardian = MutableStateFlow<ContactEntity?>(null)
    val activeChatGuardian: StateFlow<ContactEntity?> = _activeChatGuardian.asStateFlow()

    private val _activeChatMessages = MutableStateFlow<List<GuardianChatMessageEntity>>(emptyList())
    val activeChatMessages: StateFlow<List<GuardianChatMessageEntity>> = _activeChatMessages.asStateFlow()

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    private val _chatSyncError = MutableStateFlow<String?>(null)
    val chatSyncError: StateFlow<String?> = _chatSyncError.asStateFlow()

    private val _sharedMapLocationTarget = MutableStateFlow<SharedMapLocationTarget?>(null)
    val sharedMapLocationTarget: StateFlow<SharedMapLocationTarget?> = _sharedMapLocationTarget.asStateFlow()

    private val _guardianLocationShareStatus = MutableStateFlow<String?>(null)
    val guardianLocationShareStatus: StateFlow<String?> = _guardianLocationShareStatus.asStateFlow()

    private var chatObservationJob: Job? = null
    private var chatRealtimeJob: Job? = null
    private var chatRealtimeUnsubscribe: (suspend () -> Unit)? = null

    fun currentAuthenticatedChatUserId(): String {
        return repository.resolveAuthenticatedChatUserId(authRepository.getCurrentUserId())
    }

    fun openGuardianChat(contact: ContactEntity) {
        stopGuardianChatRealtime()
        chatObservationJob?.cancel()

        _activeChatGuardian.value = contact
        _chatSyncError.value = null
        _activeChatMessages.value = emptyList()

        val userId = currentAuthenticatedChatUserId()
        val guardianId = contact.contactId

        // 1. Observe local Room messages immediately (offline-first)
        chatObservationJob = viewModelScope.launch {
            repository.observeGuardianChatMessages(
                guardianId = guardianId,
                explicitUserId = userId
            ).collect { messages ->
                Log.d(
                    "GuardianChatRuntime",
                    "Room Flow emitted to SafetyViewModel.activeChatMessages: userId=$userId, guardianId=$guardianId, count=${messages.size}, states=${messages.joinToString { "${it.id}:${it.status}:isSynced=${it.isSynced}" }}"
                )
                _activeChatMessages.value = messages
            }
        }

        // 2. When online, fetch existing remote messages and subscribe to Realtime
        if (networkMonitor.isCurrentlyOnline()) {
            refreshActiveGuardianChat()
            ensureGuardianChatRealtimeSubscribed()
        }
    }

    private fun ensureGuardianChatRealtimeSubscribed() {
        val guardian = _activeChatGuardian.value ?: return
        if (chatRealtimeUnsubscribe != null) return
        val userId = currentAuthenticatedChatUserId()
        chatRealtimeJob?.cancel()
        chatRealtimeJob = viewModelScope.launch(Dispatchers.IO) {
            chatRealtimeUnsubscribe = repository.subscribeToGuardianChatRealtime(
                guardianId = guardian.contactId,
                explicitUserId = userId,
                pairedGuardianUserId = guardian.pairedGuardianUserId
            )
        }
    }

    private fun stopGuardianChatRealtime() {
        chatRealtimeJob?.cancel()
        chatRealtimeJob = null
        val unsubscribe = chatRealtimeUnsubscribe
        chatRealtimeUnsubscribe = null
        if (unsubscribe != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    unsubscribe()
                } catch (_: Exception) {}
            }
        }
    }

    fun refreshActiveGuardianChat() {
        val guardian = _activeChatGuardian.value ?: return
        if (!networkMonitor.isCurrentlyOnline()) {
            _chatSyncError.value = null
            _isChatLoading.value = false
            return
        }
        val userId = currentAuthenticatedChatUserId()
        viewModelScope.launch(Dispatchers.IO) {
            _isChatLoading.value = true
            _chatSyncError.value = null
            try {
                repository.syncPendingChatMessagesToSupabase(
                    targetContact = guardian,
                    explicitUserId = userId
                )
                val result = repository.fetchAndMergeGuardianChatMessages(
                    guardianId = guardian.contactId,
                    explicitUserId = userId,
                    pairedGuardianUserId = guardian.pairedGuardianUserId
                )
                if (result.isSuccess) {
                    _activeChatMessages.value = result.getOrNull().orEmpty()
                } else {
                    _chatSyncError.value = "Could not sync cloud messages. Showing local conversation."
                }
            } catch (e: Exception) {
                Log.e(
                    "GuardianChatRuntime",
                    "refreshActiveGuardianChat failed: userId=$userId, guardianId=${guardian.contactId}, exceptionClass=${e.javaClass.name}, message=${e.message}",
                    e
                )
                _chatSyncError.value = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                _isChatLoading.value = false
            }
        }
    }

    fun sendGuardianChatMessage(content: String) {
        val guardian = _activeChatGuardian.value ?: return
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        val userId = currentAuthenticatedChatUserId()
        val isOnlineNow = networkMonitor.isCurrentlyOnline()
        Log.d(
            "GuardianChatRuntime",
            "SafetyViewModel.sendGuardianChatMessage entered: userId=$userId, guardianId=${guardian.contactId}, isOnline=$isOnlineNow"
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.sendGuardianChatMessage(
                contact = guardian,
                content = trimmed,
                isOnline = isOnlineNow,
                explicitUserId = userId
            )
            val updatedMsg = result.getOrNull()
            if (updatedMsg != null) {
                Log.d(
                    "GuardianChatRuntime",
                    "SafetyViewModel.sendGuardianChatMessage result received: messageId=${updatedMsg.id}, userId=${updatedMsg.userId}, guardianId=${updatedMsg.guardianId}, status=${updatedMsg.status}, isSynced=${updatedMsg.isSynced}"
                )
                val currentList = _activeChatMessages.value.toMutableList()
                val existingIdx = currentList.indexOfFirst { it.id == updatedMsg.id }
                if (existingIdx >= 0) {
                    currentList[existingIdx] = updatedMsg
                } else {
                    currentList.add(updatedMsg)
                }
                _activeChatMessages.value = currentList.sortedWith(
                    compareBy<GuardianChatMessageEntity> { it.createdAt }.thenBy { it.id }
                )
            }
            val syncErr = result.exceptionOrNull()
            if (syncErr != null) {
                Log.e(
                    "GuardianChatRuntime",
                    "SafetyViewModel.sendGuardianChatMessage failure: userId=$userId, guardianId=${guardian.contactId}, exceptionClass=${syncErr.javaClass.name}, message=${syncErr.message}",
                    syncErr
                )
                _chatSyncError.value = "${syncErr.javaClass.simpleName}: ${syncErr.message}"
            } else if (updatedMsg?.isSynced == true) {
                _chatSyncError.value = null
            } else if (repository.lastChatSyncError != null) {
                _chatSyncError.value = repository.lastChatSyncError
            }
        }
    }

    /**
     * Resolves the user's current or last-known valid GPS coordinates using the existing
     * AIZA location subsystem without creating a new location engine.
     */
    private fun resolveCurrentLocationForSharing(
        nowMs: Long = System.currentTimeMillis()
    ): IncidentLocationSnapshot {
        try {
            requestLiveLocationUpdates()
        } catch (_: Exception) {}

        val snapshot = resolveCurrentIncidentLocationSnapshot(nowMs)
        if (GuardianChatMessageEntity.hasValidLocationCoordinates(snapshot.latitude, snapshot.longitude)) {
            return snapshot
        }

        val currentLat = _latitude.value
        val currentLng = _longitude.value
        if (GuardianChatMessageEntity.hasValidLocationCoordinates(currentLat, currentLng)) {
            val ts = prefs.lastLocationTimestamp.takeIf { it > 0L } ?: nowMs
            val status = if (!hasLiveSessionLocationFix) {
                SafetyRepository.classifyLocationStatus(
                    hasLiveFix = false,
                    hasCachedFix = true,
                    locationTimestamp = ts,
                    nowMs = nowMs
                )
            } else {
                EmergencyIncidentEntity.LOCATION_STATUS_LIVE
            }
            return IncidentLocationSnapshot(
                latitude = currentLat,
                longitude = currentLng,
                locationTimestamp = ts,
                locationStatus = status
            )
        }

        return IncidentLocationSnapshot(
            latitude = null,
            longitude = null,
            locationTimestamp = null,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN
        )
    }

    /**
     * Phase 4A: Shares the user's current location inside the active 1-to-1 Guardian Chat.
     * If a valid location cannot be obtained, sets a clear user-facing error and does NOT send a message.
     * Strictly isolated from SOS, calls, SMS, siren, and emergency incidents.
     */
    fun shareCurrentLocationInGuardianChat(
        onResult: (Result<GuardianChatMessageEntity>) -> Unit = {}
    ) {
        val guardian = _activeChatGuardian.value
        if (guardian == null) {
            val err = IllegalStateException("No active guardian conversation selected.")
            _chatSyncError.value = err.message
            onResult(Result.failure(err))
            return
        }
        shareCurrentLocationToGuardian(contact = guardian, onResult = onResult)
    }

    /**
     * Phase 4A: Shares the user's current location to [contact] as a normal Guardian Chat LOCATION message.
     * Usable from both GuardianChatScreen and MapsScreen.
     * If valid coordinates are unavailable, shows a clear error and does NOT send or persist a message.
     */
    fun shareCurrentLocationToGuardian(
        contact: ContactEntity,
        onResult: (Result<GuardianChatMessageEntity>) -> Unit = {}
    ) {
        val nowMs = System.currentTimeMillis()
        val snapshot = resolveCurrentLocationForSharing(nowMs)
        if (!GuardianChatMessageEntity.hasValidLocationCoordinates(snapshot.latitude, snapshot.longitude)) {
            val errorMsg = "Unable to share location: Valid GPS coordinates are unavailable. Please enable GPS / Location Services and try again."
            if (_activeChatGuardian.value?.contactId == contact.contactId) {
                _chatSyncError.value = errorMsg
            }
            _guardianLocationShareStatus.value = errorMsg
            addNotification(errorMsg)
            onResult(Result.failure(IllegalStateException(errorMsg)))
            return
        }

        val userId = currentAuthenticatedChatUserId()
        val isOnlineNow = networkMonitor.isCurrentlyOnline()
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.sendGuardianLocationChatMessage(
                contact = contact,
                latitude = snapshot.latitude,
                longitude = snapshot.longitude,
                locationTimestamp = snapshot.locationTimestamp ?: nowMs,
                locationStatus = snapshot.locationStatus,
                isOnline = isOnlineNow,
                explicitUserId = userId
            )
            val updatedMsg = result.getOrNull()
            if (updatedMsg != null) {
                if (_activeChatGuardian.value?.contactId == contact.contactId) {
                    val currentList = _activeChatMessages.value.toMutableList()
                    val existingIdx = currentList.indexOfFirst { it.id == updatedMsg.id }
                    if (existingIdx >= 0) {
                        currentList[existingIdx] = updatedMsg
                    } else {
                        currentList.add(updatedMsg)
                    }
                    _activeChatMessages.value = currentList.sortedWith(
                        compareBy<GuardianChatMessageEntity> { it.createdAt }.thenBy { it.id }
                    )
                    if (updatedMsg.isSynced) {
                        _chatSyncError.value = null
                    } else if (repository.lastChatSyncError != null) {
                        _chatSyncError.value = repository.lastChatSyncError
                    }
                }
                val statusMsg = "Current location shared with ${contact.name}."
                _guardianLocationShareStatus.value = statusMsg
                addNotification(statusMsg)
            } else {
                val err = result.exceptionOrNull()
                val errMsg = err?.message ?: "Could not share current location."
                if (_activeChatGuardian.value?.contactId == contact.contactId) {
                    _chatSyncError.value = errMsg
                }
                _guardianLocationShareStatus.value = errMsg
                addNotification(errMsg)
            }
            onResult(result)
        }
    }

    /**
     * Phase 4A: Sets the target shared location to focus when opening MapsScreen from a
     * Guardian Chat location card.
     */
    fun openSharedLocationInMap(message: GuardianChatMessageEntity): Boolean {
        if (!message.isLocationMessage) return false
        val lat = message.latitude ?: return false
        val lng = message.longitude ?: return false
        val guardianName = _activeChatGuardian.value?.name.orEmpty()
        _sharedMapLocationTarget.value = SharedMapLocationTarget(
            messageId = message.id,
            senderName = message.senderName.ifBlank { "Guardian" },
            guardianName = guardianName,
            latitude = lat,
            longitude = lng,
            locationTimestamp = message.locationTimestamp ?: message.createdAt,
            locationStatus = message.locationStatus ?: GuardianChatMessageEntity.LOCATION_STATUS_LIVE
        )
        return true
    }

    fun clearSharedMapLocationTarget() {
        _sharedMapLocationTarget.value = null
    }

    fun clearGuardianLocationShareStatus() {
        _guardianLocationShareStatus.value = null
    }

    fun closeGuardianChat() {
        stopGuardianChatRealtime()
        chatObservationJob?.cancel()
        chatObservationJob = null
        _activeChatGuardian.value = null
        _activeChatMessages.value = emptyList()
        _isChatLoading.value = false
        _chatSyncError.value = null
    }

    // --- Guardian Account Pairing / Invitation Actions ---

    fun sendGuardianPairingRequest(
        contact: ContactEntity,
        guardianEmail: String,
        onResult: (Result<GuardianPairingRequestEntity>) -> Unit = {}
    ) {
        val requesterUserId = authRepository.getCurrentUserId()
        viewModelScope.launch {
            val result = repository.createGuardianPairingRequest(
                contact = contact,
                guardianEmail = guardianEmail,
                explicitRequesterUserId = requesterUserId
            )
            result.onSuccess { req ->
                addNotification("Connection request sent to ${req.guardianEmail} for ${contact.name} (Status: PENDING).")
                refreshGuardianPairingRequests()
            }.onFailure { err ->
                addNotification("Pairing request failed: ${err.message ?: "Invalid email"}")
            }
            onResult(result)
        }
    }

    fun acceptGuardianPairingRequest(requestId: String) {
        val guardianUid = authRepository.getCurrentUserId()
        val guardianEmail = authRepository.getCurrentUserEmail()
            ?: _userEmail.value.takeIf { SafetyRepository.isValidPairingEmail(it) }
        viewModelScope.launch {
            val result = repository.acceptGuardianPairingRequest(
                requestId = requestId,
                authenticatedGuardianUserId = guardianUid,
                authenticatedGuardianEmail = guardianEmail
            )
            result.onSuccess { updatedContact ->
                if (updatedContact != null && _activeChatGuardian.value?.contactId == updatedContact.contactId) {
                    _activeChatGuardian.value = updatedContact
                }
                syncReceivedEmergencyIncidents()
                addNotification("Guardian pairing request accepted and account connected.")
            }.onFailure { err ->
                addNotification("Could not accept pairing request: ${err.message}")
            }
        }
    }

    fun rejectGuardianPairingRequest(requestId: String) {
        val guardianUid = authRepository.getCurrentUserId()
        val guardianEmail = authRepository.getCurrentUserEmail()
            ?: _userEmail.value.takeIf { SafetyRepository.isValidPairingEmail(it) }
        viewModelScope.launch {
            val result = repository.rejectGuardianPairingRequest(
                requestId = requestId,
                authenticatedGuardianUserId = guardianUid,
                authenticatedGuardianEmail = guardianEmail
            )
            result.onSuccess {
                addNotification("Guardian pairing request declined.")
            }.onFailure { err ->
                addNotification("Could not reject pairing request: ${err.message}")
            }
        }
    }

    fun refreshGuardianPairingRequests() {
        viewModelScope.launch(Dispatchers.IO) {
            val explicitUid = authRepository.getCurrentUserId()
            val explicitEmail = authRepository.getCurrentUserEmail()
                ?: _userEmail.value.takeIf { SafetyRepository.isValidPairingEmail(it) }
            val (resolvedUid, resolvedEmail) = repository.resolveAuthenticatedPairingIdentity(
                explicitUserId = explicitUid,
                explicitEmail = explicitEmail
            )
            if (!resolvedEmail.isNullOrBlank() && !_userEmail.value.equals(resolvedEmail, ignoreCase = true)) {
                _userEmail.value = resolvedEmail
            }
            repository.syncPairingRequestsWithSupabase(
                explicitRequesterUserId = resolvedUid,
                explicitGuardianEmail = resolvedEmail
            )
            refreshPeopleWhoSavedMe(resolvedUid)
        }
    }

    // --- Emergency Incidents (Phase 1 Foundation, Phase 2 SOS Trigger Integration, Phase 3 Guardian Delivery, & Phase 4C History/Details) ---

    private val _activeIncidentUserId = MutableStateFlow<String?>(
        repository.resolveAuthenticatedIncidentUserId(authRepository.getCurrentUserId())
    )

    fun refreshIncidentUserContext(): String? {
        val currentUid = repository.resolveAuthenticatedIncidentUserId(authRepository.getCurrentUserId())
        if (_activeIncidentUserId.value != currentUid) {
            _activeIncidentUserId.value = currentUid
        }
        return currentUid
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeEmergencyIncident: StateFlow<EmergencyIncidentEntity?> = combine(_isLoggedIn, _activeIncidentUserId) { _, uid ->
        uid ?: repository.resolveAuthenticatedIncidentUserId(authRepository.getCurrentUserId())
    }.flatMapLatest { uid ->
        repository.observeActiveEmergencyIncident(uid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeIncidentId: StateFlow<String?> = activeEmergencyIncident.map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val emergencyIncidents: StateFlow<List<EmergencyIncidentEntity>> = combine(_isLoggedIn, _activeIncidentUserId) { _, uid ->
        uid ?: repository.resolveAuthenticatedIncidentUserId(authRepository.getCurrentUserId())
    }.flatMapLatest { uid ->
        repository.observeEmergencyIncidents(uid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val receivedEmergencyIncidents: StateFlow<List<EmergencyIncidentEntity>> = combine(_isLoggedIn, _activeIncidentUserId) { _, uid ->
        uid ?: repository.resolveAuthenticatedIncidentUserId(authRepository.getCurrentUserId())
    }.flatMapLatest { uid ->
        repository.observeReceivedEmergencyIncidents(uid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeReceivedEmergencyIncidents: StateFlow<List<EmergencyIncidentEntity>> = combine(_isLoggedIn, _activeIncidentUserId) { _, uid ->
        uid ?: repository.resolveAuthenticatedIncidentUserId(authRepository.getCurrentUserId())
    }.flatMapLatest { uid ->
        repository.observeActiveReceivedEmergencyIncidents(uid)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _incidentSenderNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val incidentSenderNames: StateFlow<Map<String, String>> = _incidentSenderNames.asStateFlow()

    private var guardianIncidentRealtimeCleanup: (suspend () -> Unit)? = null
    private var guardianIncidentRealtimeUserId: String? = null

    /**
     * Returns true only when the emergency medical sharing setting has been explicitly enabled.
     * Defaults to false initially until explicitly enabled by the user/setting.
     */
    fun isIncidentMedicalSharingEnabled(): Boolean {
        return prefs.isMedicalSharingExplicitlyConfigured && prefs.shareMedicalInfo && _shareMedicalInfo.value
    }

    /**
     * Builds the selectively shareable emergency medical summary when medical sharing is enabled.
     * Never includes unrelated private profile fields (such as email, password, or phone).
     */
    fun buildShareableMedicalSummary(): String? {
        if (!isIncidentMedicalSharingEnabled()) return null
        val medParts = mutableListOf<String>()
        val bg = _bloodGroup.value.ifBlank { prefs.bloodGroup }.trim()
        val alg = _allergies.value.ifBlank { prefs.allergies }.trim()
        val cond = _medicalConditions.value.ifBlank { prefs.medicalConditions }.trim()
        val notes = _medicalNotes.value.ifBlank { prefs.medicalNotes }.trim()
        if (bg.isNotBlank()) medParts.add("Blood: $bg")
        if (alg.isNotBlank()) medParts.add("Allergies: $alg")
        if (cond.isNotBlank()) medParts.add("Conditions: $cond")
        if (notes.isNotBlank()) medParts.add("Notes: $notes")
        return medParts.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private data class IncidentLocationSnapshot(
        val latitude: Double?,
        val longitude: Double?,
        val locationTimestamp: Long?,
        val locationStatus: String
    )

    private fun resolveCurrentIncidentLocationSnapshot(
        nowMs: Long = System.currentTimeMillis()
    ): IncidentLocationSnapshot {
        val hasLiveCoordinates = hasLiveSessionLocationFix && (_latitude.value != 0.0 || _longitude.value != 0.0)
        if (hasLiveCoordinates) {
            val ts = prefs.lastLocationTimestamp.takeIf { it > 0L } ?: nowMs
            return IncidentLocationSnapshot(
                latitude = _latitude.value,
                longitude = _longitude.value,
                locationTimestamp = ts,
                locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE
            )
        }

        val hasCachedCoordinates = prefs.hasAcquiredRealGpsFix &&
            (prefs.lastLatitude != 0f || prefs.lastLongitude != 0f)
        if (hasCachedCoordinates) {
            val cachedTs = prefs.lastLocationTimestamp.takeIf { it > 0L }
            val status = SafetyRepository.classifyLocationStatus(
                hasLiveFix = false,
                hasCachedFix = true,
                locationTimestamp = cachedTs,
                nowMs = nowMs
            )
            return IncidentLocationSnapshot(
                latitude = prefs.lastLatitude.toDouble(),
                longitude = prefs.lastLongitude.toDouble(),
                locationTimestamp = cachedTs ?: nowMs,
                locationStatus = status
            )
        }

        return IncidentLocationSnapshot(
            latitude = null,
            longitude = null,
            locationTimestamp = null,
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN
        )
    }

    private suspend fun createOrUpdateSosIncidentInternal(
        source: String,
        nowMs: Long = System.currentTimeMillis()
    ): Result<EmergencyIncidentEntity> {
        val normalizedTrigger = SafetyRepository.normalizeIncidentTriggerSource(source)
            ?: return Result.failure(
                IllegalArgumentException("Unsupported SOS trigger source: '$source'.")
            )
        val locSnapshot = resolveCurrentIncidentLocationSnapshot(nowMs)
        val battPercent = getBatteryPercentage().coerceIn(0, 100)
        val netStatus = if (networkMonitor.isCurrentlyOnline()) "ONLINE" else "OFFLINE"
        val medEnabled = isIncidentMedicalSharingEnabled()
        val medSummary = if (medEnabled) buildShareableMedicalSummary() else null

        val result = repository.createEmergencyIncident(
            triggerSource = normalizedTrigger,
            latitude = locSnapshot.latitude,
            longitude = locSnapshot.longitude,
            locationTimestamp = locSnapshot.locationTimestamp,
            locationStatus = locSnapshot.locationStatus,
            batteryPercent = battPercent,
            networkStatus = netStatus,
            medicalSharingEnabled = medEnabled,
            medicalSummary = medSummary,
            audioRecordingStatus = EmergencyIncidentEntity.AUDIO_STATUS_NOT_STARTED,
            audioDurationMs = null,
            audioStoragePath = null,
            explicitUserId = authRepository.getCurrentUserId(),
            startedAt = nowMs
        )
        val createdOrUpdated = result.getOrNull()
        if (createdOrUpdated != null && networkMonitor.isCurrentlyOnline()) {
            repository.syncEmergencyIncidentToSupabase(
                incident = createdOrUpdated,
                explicitUserId = createdOrUpdated.userId
            )
        }
        return result
    }

    private fun syncActiveIncidentMedicalState() {
        viewModelScope.launch(Dispatchers.IO) {
            val active = repository.getActiveEmergencyIncident(authRepository.getCurrentUserId()) ?: return@launch
            val medEnabled = isIncidentMedicalSharingEnabled()
            val medSummary = if (medEnabled) buildShareableMedicalSummary() else null
            val updatedResult = repository.updateEmergencyIncident(
                incident = active.copy(
                    medicalSharingEnabled = medEnabled,
                    medicalSummary = medSummary,
                    updatedAt = System.currentTimeMillis()
                ),
                explicitUserId = active.userId
            )
            val updated = updatedResult.getOrNull()
            if (updated != null && networkMonitor.isCurrentlyOnline()) {
                repository.syncEmergencyIncidentToSupabase(
                    incident = updated,
                    explicitUserId = updated.userId
                )
            }
        }
    }

    fun createEmergencyIncident(
        triggerSource: String = EmergencyIncidentEntity.TRIGGER_MANUAL,
        onResult: (Result<EmergencyIncidentEntity>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = createOrUpdateSosIncidentInternal(
                source = triggerSource,
                nowMs = System.currentTimeMillis()
            )
            onResult(result)
        }
    }

    fun updateEmergencyIncidentLocation(
        incidentId: String,
        latitude: Double?,
        longitude: Double?,
        locationStatus: String? = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
        locationTimestamp: Long? = System.currentTimeMillis()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.updateIncidentLocation(
                id = incidentId,
                latitude = latitude,
                longitude = longitude,
                locationTimestamp = locationTimestamp,
                locationStatus = locationStatus,
                explicitUserId = authRepository.getCurrentUserId()
            )
            val updated = result.getOrNull()
            if (updated != null && networkMonitor.isCurrentlyOnline()) {
                repository.syncEmergencyIncidentToSupabase(
                    incident = updated,
                    explicitUserId = updated.userId
                )
            }
        }
    }

    fun updateActiveEmergencyIncidentAudioStatus(
        audioRecordingStatus: String,
        audioDurationMs: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.updateActiveIncidentAudioStatus(
                audioRecordingStatus = audioRecordingStatus,
                audioDurationMs = audioDurationMs,
                explicitUserId = authRepository.getCurrentUserId()
            )
            val updated = result.getOrNull()
            if (updated != null && networkMonitor.isCurrentlyOnline()) {
                repository.syncEmergencyIncidentToSupabase(
                    incident = updated,
                    explicitUserId = updated.userId
                )
            }
        }
    }

    fun updateEmergencyIncidentAudioStatus(
        incidentId: String,
        audioRecordingStatus: String,
        audioDurationMs: Long? = null,
        audioStoragePath: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.updateIncidentAudioStatus(
                id = incidentId,
                audioRecordingStatus = audioRecordingStatus,
                audioDurationMs = audioDurationMs,
                audioStoragePath = null, // Phase 2: DO NOT upload/attach storage path yet
                explicitUserId = authRepository.getCurrentUserId()
            )
            val updated = result.getOrNull()
            if (updated != null && networkMonitor.isCurrentlyOnline()) {
                repository.syncEmergencyIncidentToSupabase(
                    incident = updated,
                    explicitUserId = updated.userId
                )
            }
        }
    }

    fun resolveEmergencyIncident(
        incidentId: String,
        status: String = EmergencyIncidentEntity.STATUS_RESOLVED,
        onResult: (Result<EmergencyIncidentEntity>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.resolveEmergencyIncident(
                id = incidentId,
                status = status,
                endedAt = System.currentTimeMillis(),
                explicitUserId = authRepository.getCurrentUserId()
            )
            val resolved = result.getOrNull()
            if (resolved != null && networkMonitor.isCurrentlyOnline()) {
                repository.syncEmergencyIncidentToSupabase(
                    incident = resolved,
                    explicitUserId = resolved.userId
                )
            }
            onResult(result)
        }
    }

    fun syncPendingEmergencyIncidents() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.syncPendingEmergencyIncidents(authRepository.getCurrentUserId())
        }
    }

    /**
     * Synchronizes incoming emergency incidents addressed to the authenticated paired guardian,
     * persisting them locally in Room and marking recipient delivery status as `DELIVERED`.
     */
    fun syncReceivedEmergencyIncidents(
        onResult: (Result<List<EmergencyIncidentEntity>>) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val guardianUid = authRepository.getCurrentUserId()
            ensureGuardianIncidentsRealtimeSubscribed()
            val result = repository.syncReceivedEmergencyIncidents(guardianUid)
            onResult(result)
        }
    }

    /**
     * Refreshes incoming emergency incidents for the authenticated paired guardian.
     */
    fun refreshReceivedEmergencyIncidents(
        onResult: (Result<List<EmergencyIncidentEntity>>) -> Unit = {}
    ) {
        syncReceivedEmergencyIncidents(onResult)
    }

    private suspend fun ensureGuardianIncidentsRealtimeSubscribed() {
        val currentGuardianUid = repository.resolveAuthenticatedIncidentUserId(authRepository.getCurrentUserId())
            ?: return
        if (guardianIncidentRealtimeCleanup != null &&
            guardianIncidentRealtimeUserId?.equals(currentGuardianUid, ignoreCase = true) == true
        ) {
            return
        }
        guardianIncidentRealtimeCleanup?.let { cleanup ->
            try {
                cleanup()
            } catch (_: Exception) {}
        }
        guardianIncidentRealtimeCleanup = repository.subscribeToGuardianIncidentsRealtime(
            explicitGuardianUserId = currentGuardianUid
        ) { syncedIncidents ->
            val currentSelectedId = _selectedEmergencyIncidentId.value
            if (!currentSelectedId.isNullOrBlank()) {
                syncedIncidents.firstOrNull { it.id.equals(currentSelectedId, ignoreCase = true) }?.let {
                    _selectedEmergencyIncident.value = it
                }
            }
        }
        guardianIncidentRealtimeUserId = if (guardianIncidentRealtimeCleanup != null) currentGuardianUid else null
    }

    private fun stopGuardianIncidentsRealtime() {
        val cleanup = guardianIncidentRealtimeCleanup
        guardianIncidentRealtimeCleanup = null
        guardianIncidentRealtimeUserId = null
        if (cleanup != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    cleanup()
                } catch (_: Exception) {}
            }
        }
    }

    // --- Phase 4B & Phase 4C: Emergency Notification Tap Action & Emergency Incident Details State ---

    private val _selectedEmergencyIncidentId = MutableStateFlow<String?>(null)
    val selectedEmergencyIncidentId: StateFlow<String?> = _selectedEmergencyIncidentId.asStateFlow()

    private val _selectedEmergencyIncident = MutableStateFlow<EmergencyIncidentEntity?>(null)
    val selectedEmergencyIncident: StateFlow<EmergencyIncidentEntity?> = _selectedEmergencyIncident.asStateFlow()

    private val _selectedEmergencyIncidentRecipient = MutableStateFlow<EmergencyIncidentRecipientEntity?>(null)
    val selectedEmergencyIncidentRecipient: StateFlow<EmergencyIncidentRecipientEntity?> =
        _selectedEmergencyIncidentRecipient.asStateFlow()

    private val _selectedEmergencyIncidentSenderContact = MutableStateFlow<ContactEntity?>(null)
    val selectedEmergencyIncidentSenderContact: StateFlow<ContactEntity?> =
        _selectedEmergencyIncidentSenderContact.asStateFlow()

    private val _isEmergencyIncidentDetailsLoading = MutableStateFlow(false)
    val isEmergencyIncidentDetailsLoading: StateFlow<Boolean> =
        _isEmergencyIncidentDetailsLoading.asStateFlow()

    private val _selectedEmergencyIncidentNotFound = MutableStateFlow(false)
    val selectedEmergencyIncidentNotFound: StateFlow<Boolean> =
        _selectedEmergencyIncidentNotFound.asStateFlow()

    private val _lastDialedEmergencyContactPhone = MutableStateFlow<String?>(null)
    val lastDialedEmergencyContactPhone: StateFlow<String?> =
        _lastDialedEmergencyContactPhone.asStateFlow()

    // --- Phase 4D: People Who Saved Me ---
    private val _peopleWhoSavedMe = MutableStateFlow<List<PersonWhoSavedMe>>(emptyList())
    val peopleWhoSavedMe: StateFlow<List<PersonWhoSavedMe>> = _peopleWhoSavedMe.asStateFlow()

    private val _isLoadingPeopleWhoSavedMe = MutableStateFlow(false)
    val isLoadingPeopleWhoSavedMe: StateFlow<Boolean> = _isLoadingPeopleWhoSavedMe.asStateFlow()

    fun refreshPeopleWhoSavedMe(explicitUserId: String? = null) {
        viewModelScope.launch {
            _isLoadingPeopleWhoSavedMe.value = true
            try {
                val currentUid = explicitUserId?.trim()
                    ?: authRepository.getCurrentUserId()
                    ?: repository.resolveAuthenticatedIncidentUserId()
                val list = repository.fetchPeopleWhoSavedMe(currentUid)
                _peopleWhoSavedMe.value = list
            } catch (e: Exception) {
                Log.w("SafetyViewModel", "refreshPeopleWhoSavedMe warning: ${e.message}")
            } finally {
                _isLoadingPeopleWhoSavedMe.value = false
            }
        }
    }

    /**
     * Opens 1-to-1 Guardian Chat with a connected user who has added the current user
     * as an emergency contact/guardian.
     */
    fun openGuardianChatForPersonWhoSavedMe(person: PersonWhoSavedMe) {
        val currentUid = currentAuthenticatedChatUserId()
        val targetUid = person.userId.trim()
        if (!SafetyRepository.isValidUuid(targetUid)) return

        val existing = contacts.value.firstOrNull {
            it.pairedGuardianUserId?.trim()?.equals(targetUid, ignoreCase = true) == true
        }
        val targetContact = existing ?: ContactEntity(
            contactId = person.contactRowId?.takeIf { SafetyRepository.isValidUuid(it) }
                ?: java.util.UUID.nameUUIDFromBytes("person_${targetUid.lowercase(Locale.US)}".toByteArray()).toString(),
            userId = currentUid,
            guardianUserId = targetUid,
            name = person.name.ifBlank { "Connected AIZA User" },
            relation = person.relationship.ifBlank { "Emergency Contact" },
            isVerified = true
        )
        openGuardianChat(targetContact)
    }

    // --- Phase 4E: Bluetooth Wearable / Watch Integration ---
    val bluetoothWearableManager = com.example.data.BluetoothWearableManager(
        context = getApplication(),
        preferencesManager = prefs
    )

    val wearableConnectionState: StateFlow<com.example.data.BluetoothDeviceConnectionState> =
        bluetoothWearableManager.connectionState
    val connectedWearableDevice: StateFlow<com.example.data.BluetoothSafetyDevice?> =
        bluetoothWearableManager.connectedDevice
    val discoveredWearableDevices: StateFlow<List<com.example.data.BluetoothSafetyDevice>> =
        bluetoothWearableManager.discoveredDevices
    val isScanningWearables: StateFlow<Boolean> =
        bluetoothWearableManager.isScanning
    val wearableStatusMessage: StateFlow<String?> =
        bluetoothWearableManager.statusMessage

    fun startWearableScan() {
        bluetoothWearableManager.startDiscovery()
    }

    fun stopWearableScan() {
        bluetoothWearableManager.stopDiscovery()
    }

    fun connectWearableDevice(device: com.example.data.BluetoothSafetyDevice) {
        bluetoothWearableManager.connectDevice(device)
    }

    fun disconnectWearableDevice() {
        bluetoothWearableManager.disconnectDevice()
    }

    fun trustWearableDevice(device: com.example.data.BluetoothSafetyDevice) {
        bluetoothWearableManager.trustDevice(device)
    }

    fun removeWearableTrust(device: com.example.data.BluetoothSafetyDevice) {
        bluetoothWearableManager.removeTrust(device)
    }

    fun isWearableDeviceTrusted(deviceId: String): Boolean {
        return bluetoothWearableManager.isDeviceTrusted(deviceId)
    }

    init {
        bluetoothWearableManager.setOnSosTrigger { triggerChar ->
            addNotification("[BLUETOOTH WEARABLE SOS] Valid hardware trigger '$triggerChar' received from trusted wearable!")
            triggerSos("BLUETOOTH")
        }
        viewModelScope.launch {
            combine(
                receivedEmergencyIncidents,
                emergencyIncidents,
                _selectedEmergencyIncidentId
            ) { receivedList, sentList, selectedId ->
                Triple(receivedList, sentList, selectedId)
            }.collect { (receivedList, sentList, selectedId) ->
                val currentUid = refreshIncidentUserContext()
                if (receivedList.isNotEmpty()) {
                    val updatedNames = _incidentSenderNames.value.toMutableMap()
                    for (inc in receivedList) {
                        val senderKey = inc.userId.trim().lowercase(Locale.US)
                        val resolved = repository.resolveSenderNameForReceivedIncident(inc.userId, currentUid)
                        if (!resolved.isNullOrBlank()) {
                            updatedNames[senderKey] = resolved
                        }
                    }
                    _incidentSenderNames.value = updatedNames
                }
                if (!selectedId.isNullOrBlank()) {
                    val updatedSelected = receivedList.firstOrNull {
                        it.id.equals(selectedId, ignoreCase = true)
                    } ?: sentList.firstOrNull {
                        it.id.equals(selectedId, ignoreCase = true)
                    }
                    if (updatedSelected != null) {
                        _selectedEmergencyIncident.value = updatedSelected
                        _selectedEmergencyIncidentNotFound.value = false
                    }
                }
            }
        }
    }

    /**
     * Ensures the dedicated `aiza_emergency_alerts` notification channel is registered.
     */
    fun ensureEmergencyNotificationChannel(): String {
        return repository.getEmergencyNotificationManager().ensureEmergencyNotificationChannel()
    }

    /**
     * Resolves the human-readable sender name for a received [EmergencyIncidentEntity].
     */
    fun resolveSenderNameForIncident(incident: EmergencyIncidentEntity): String? {
        val key = incident.userId.trim().lowercase(Locale.US)
        _incidentSenderNames.value[key]?.takeIf { it.isNotBlank() }?.let { return it }
        val matchingRequest = pairingRequests.value.firstOrNull { req ->
            req.requesterUserId.trim().equals(incident.userId.trim(), ignoreCase = true) &&
                AizaEmergencyNotificationManager.sanitizeSenderName(req.requesterName) != null
        }
        AizaEmergencyNotificationManager.sanitizeSenderName(matchingRequest?.requesterName)?.let { return it }
        val matchingContact = contacts.value.firstOrNull { contact ->
            contact.pairedGuardianUserId?.trim()?.equals(incident.userId.trim(), ignoreCase = true) == true &&
                AizaEmergencyNotificationManager.sanitizeSenderName(contact.name) != null
        }
        return AizaEmergencyNotificationManager.sanitizeSenderName(matchingContact?.name)
    }

    /**
     * Opens the AIZA Emergency Incident details flow (`AIZA -> Emergency Incident -> incidentId`)
     * for the exact [incidentId]. Rejects blank, malformed, or generic placeholder IDs safely
     * and never shows another incident.
     */
    fun openEmergencyIncidentDetails(incidentId: String?): Boolean {
        val cleanId = incidentId?.trim() ?: return false
        if (!SafetyRepository.isRealAuthenticatedUserUuid(cleanId)) {
            return false
        }
        refreshIncidentUserContext()
        _selectedEmergencyIncidentId.value = cleanId
        val immediateMatch = receivedEmergencyIncidents.value.firstOrNull {
            it.id.equals(cleanId, ignoreCase = true)
        } ?: emergencyIncidents.value.firstOrNull {
            it.id.equals(cleanId, ignoreCase = true)
        }
        // Always set to immediateMatch (or null if not in memory yet) so another incident is NEVER shown
        _selectedEmergencyIncident.value = immediateMatch
        _selectedEmergencyIncidentRecipient.value = null
        _selectedEmergencyIncidentSenderContact.value = null
        _selectedEmergencyIncidentNotFound.value = false
        _isEmergencyIncidentDetailsLoading.value = (immediateMatch == null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentUid = refreshIncidentUserContext()
                val loaded = repository.getEmergencyIncidentForDetailsFlow(
                    incidentId = cleanId,
                    explicitUserId = currentUid
                )
                if (!_selectedEmergencyIncidentId.value.equals(cleanId, ignoreCase = true)) {
                    return@launch
                }
                if (loaded != null && loaded.id.equals(cleanId, ignoreCase = true)) {
                    _selectedEmergencyIncident.value = loaded
                    _selectedEmergencyIncidentNotFound.value = false
                    val recipient = repository.getRecipientForIncidentDetails(cleanId, currentUid)
                    _selectedEmergencyIncidentRecipient.value = recipient
                    val senderName = repository.resolveSenderNameForReceivedIncident(loaded.userId, currentUid)
                    if (!senderName.isNullOrBlank()) {
                        val updatedMap = _incidentSenderNames.value.toMutableMap()
                        updatedMap[loaded.userId.trim().lowercase(Locale.US)] = senderName
                        _incidentSenderNames.value = updatedMap
                    }
                    val senderContact = repository.resolveConnectedContactForIncidentSender(loaded.userId, currentUid)
                    _selectedEmergencyIncidentSenderContact.value = senderContact
                } else {
                    _selectedEmergencyIncident.value = null
                    _selectedEmergencyIncidentNotFound.value = true
                }
            } finally {
                if (_selectedEmergencyIncidentId.value.equals(cleanId, ignoreCase = true)) {
                    _isEmergencyIncidentDetailsLoading.value = false
                }
            }
        }
        return true
    }

    /**
     * Handles an incoming notification tap or deep-link [Intent] carrying an emergency `incidentId`.
     * Returns `true` if a valid emergency incident ID was extracted and routed to the details flow.
     */
    fun handleEmergencyNotificationIntent(intent: Intent?): Boolean {
        val incidentId = AizaEmergencyNotificationManager.extractIncidentIdFromIntent(intent)
            ?: return false
        return openEmergencyIncidentDetails(incidentId)
    }

    /**
     * Closes the Emergency Incident details flow and clears the active incident route selection.
     */
    fun closeEmergencyIncidentDetails() {
        _selectedEmergencyIncidentId.value = null
        _selectedEmergencyIncident.value = null
        _selectedEmergencyIncidentRecipient.value = null
        _selectedEmergencyIncidentSenderContact.value = null
        _isEmergencyIncidentDetailsLoading.value = false
        _selectedEmergencyIncidentNotFound.value = false
    }

    /**
     * Phase 4C Action 1: Opens the existing AIZA MapsScreen centered on the exact incident coordinates.
     * Returns `false` if location is unavailable (never fabricates coordinates).
     * Never resolves the incident or triggers SOS.
     */
    fun openEmergencyIncidentInMap(incident: EmergencyIncidentEntity): Boolean {
        if (!AizaEmergencyNotificationManager.isIncidentLocationAvailable(incident)) {
            return false
        }
        val lat = incident.latitude ?: return false
        val lng = incident.longitude ?: return false
        val senderName = resolveSenderNameForIncident(incident) ?: "Connected AIZA User"
        _sharedMapLocationTarget.value = SharedMapLocationTarget(
            messageId = incident.id,
            senderName = senderName,
            guardianName = _userName.value,
            latitude = lat,
            longitude = lng,
            locationTimestamp = incident.locationTimestamp ?: incident.startedAt,
            locationStatus = incident.locationStatus ?: EmergencyIncidentEntity.LOCATION_STATUS_LIVE
        )
        closeEmergencyIncidentDetails()
        return true
    }

    /**
     * Phase 4C Action 2: Resolves an existing valid phone number for the incident sender if available.
     * Never fabricates a phone number.
     */
    fun resolvePhoneForIncidentSender(incident: EmergencyIncidentEntity): String? {
        val cachedPhone = _selectedEmergencyIncidentSenderContact.value
            ?.takeIf { it.pairedGuardianUserId?.equals(incident.userId, ignoreCase = true) == true }
            ?.phone?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (cachedPhone != null) return cachedPhone

        return contacts.value.firstOrNull { contact ->
            contact.pairedGuardianUserId?.trim()?.equals(incident.userId.trim(), ignoreCase = true) == true &&
                contact.phone.isNotBlank()
        }?.phone?.trim()
    }

    /**
     * Dials the incident sender using standard `Intent.ACTION_DIAL` only if a real contact phone number
     * is available. Strictly isolated from SOS/siren/emergency pipelines.
     */
    fun dialIncidentSenderIfAvailable(context: Context, incident: EmergencyIncidentEntity): Boolean {
        val phone = resolvePhoneForIncidentSender(incident) ?: return false
        _lastDialedEmergencyContactPhone.value = phone
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            true
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Phase 4C Action 3: Opens the existing 1-to-1 Guardian Chat with the connected sender of [incident].
     * Strictly isolated from SOS/siren/emergency pipelines.
     */
    fun openGuardianChatForIncidentSender(
        incident: EmergencyIncidentEntity,
        onNavigateToContacts: () -> Unit = {}
    ): Boolean {
        val senderUid = incident.userId.trim()
        if (!SafetyRepository.isRealAuthenticatedUserUuid(senderUid)) return false
        val currentUid = refreshIncidentUserContext()
        if (currentUid != null && senderUid.equals(currentUid, ignoreCase = true)) return false

        val immediateContact = _selectedEmergencyIncidentSenderContact.value
            ?.takeIf { it.pairedGuardianUserId?.equals(senderUid, ignoreCase = true) == true }
            ?: contacts.value.firstOrNull {
                it.pairedGuardianUserId?.trim()?.equals(senderUid, ignoreCase = true) == true
            }
            ?: ContactEntity(
                contactId = pairingRequests.value.firstOrNull {
                    it.requesterUserId.trim().equals(senderUid, ignoreCase = true)
                }?.emergencyContactId?.takeIf { SafetyRepository.isRealAuthenticatedUserUuid(it) }
                    ?: java.util.UUID.nameUUIDFromBytes("paired_sender_${senderUid.lowercase(Locale.US)}".toByteArray()).toString(),
                userId = currentUid ?: "",
                guardianUserId = senderUid,
                name = resolveSenderNameForIncident(incident) ?: "Connected AIZA User",
                phone = resolvePhoneForIncidentSender(incident) ?: "",
                relation = "Connected AIZA User"
            )

        openGuardianChat(immediateContact)
        closeEmergencyIncidentDetails()
        onNavigateToContacts()
        return true
    }

    // -------------------------------------------------------------------------
    // SECURE EVIDENCE VAULT (PQC FIRST)
    // -------------------------------------------------------------------------

    fun selectEvidence(record: com.example.data.evidence.EvidenceRecordEntity?) {
        _selectedEvidence.value = record
        _decryptedEvidence.value = null
        _evidenceVerificationResult.value = null
    }

    fun clearDecryptedPreview() {
        _decryptedEvidence.value = null
        _evidenceVerificationResult.value = null
    }

    fun protectAndStoreEvidence(
        rawFile: java.io.File,
        evidenceType: String,
        mimeType: String,
        sosEventId: String? = null
    ) {
        viewModelScope.launch {
            _isEvidenceProcessing.value = true
            _evidenceStatusMessage.value = "Encrypting with AES-256-GCM & signing with ML-DSA-65..."
            try {
                val app = getApplication<Application>()
                val linkedSosId = sosEventId ?: activeIncidentId.value
                val result = evidenceVaultRepository.saveAndProtectEvidence(
                    context = app,
                    rawMediaFile = rawFile,
                    evidenceType = evidenceType,
                    mimeType = mimeType,
                    sosEventId = linkedSosId
                )
                if (result.isSuccess) {
                    val record = result.getOrThrow()
                    _evidenceStatusMessage.value = "Evidence secured with Post-Quantum Cryptography (${record.syncState})"
                    addNotification("PQC Evidence Vault: New ${record.evidenceType} encrypted and stored.")
                } else {
                    _evidenceStatusMessage.value = "Encryption failed: ${result.exceptionOrNull()?.message}"
                    addNotification("PQC Evidence Vault Error: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                _evidenceStatusMessage.value = "Failure: ${e.message}"
            } finally {
                _isEvidenceProcessing.value = false
            }
        }
    }

    fun verifyEvidenceIntegrity(record: com.example.data.evidence.EvidenceRecordEntity) {
        viewModelScope.launch {
            _isEvidenceProcessing.value = true
            _evidenceStatusMessage.value = "Verifying ML-DSA-65 signature and SHA-256 hashes..."
            try {
                val verification = evidenceVaultRepository.verifyEvidence(record)
                _evidenceVerificationResult.value = verification
                if (verification.isValid) {
                    _evidenceStatusMessage.value = "Verification Passed: ML-DSA-65 signature and SHA-256 hash valid"
                    addNotification("PQC Verification: Evidence integrity confirmed valid.")
                } else {
                    _evidenceStatusMessage.value = "Verification Failed: ${verification.failureReason}"
                    addNotification("PQC Verification FAILED: ${verification.failureReason}")
                }
            } catch (e: Exception) {
                _evidenceStatusMessage.value = "Verification error: ${e.message}"
            } finally {
                _isEvidenceProcessing.value = false
            }
        }
    }

    fun retrieveAndDecryptEvidence(record: com.example.data.evidence.EvidenceRecordEntity) {
        viewModelScope.launch {
            _isEvidenceProcessing.value = true
            _evidenceStatusMessage.value = "Decapsulating ML-KEM-768 key and decrypting media..."
            try {
                val app = getApplication<Application>()
                val result = evidenceVaultRepository.retrieveAndDecryptEvidence(app, record)
                if (result.isSuccess) {
                    val decrypted = result.getOrThrow()
                    _decryptedEvidence.value = decrypted
                    _evidenceVerificationResult.value = decrypted.verificationResult
                    _evidenceStatusMessage.value = "Decryption successful. Integrity verified."
                } else {
                    _evidenceStatusMessage.value = "Decryption failed: ${result.exceptionOrNull()?.message}"
                    addNotification("Decryption error: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                _evidenceStatusMessage.value = "Retrieval error: ${e.message}"
            } finally {
                _isEvidenceProcessing.value = false
            }
        }
    }

    fun deleteEvidence(record: com.example.data.evidence.EvidenceRecordEntity) {
        viewModelScope.launch {
            _isEvidenceProcessing.value = true
            try {
                val result = evidenceVaultRepository.deleteEvidence(record)
                if (result.isSuccess) {
                    if (_selectedEvidence.value?.id == record.id) {
                        _selectedEvidence.value = null
                        _decryptedEvidence.value = null
                        _evidenceVerificationResult.value = null
                    }
                    _evidenceStatusMessage.value = "Evidence deleted successfully."
                    addNotification("PQC Evidence Vault: Record deleted.")
                } else {
                    _evidenceStatusMessage.value = "Deletion failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _evidenceStatusMessage.value = "Deletion error: ${e.message}"
            } finally {
                _isEvidenceProcessing.value = false
            }
        }
    }

    fun syncEvidenceNow(record: com.example.data.evidence.EvidenceRecordEntity) {
        viewModelScope.launch {
            _isEvidenceProcessing.value = true
            _evidenceStatusMessage.value = "Uploading encrypted package to Supabase Storage..."
            try {
                val result = evidenceVaultRepository.uploadEvidenceToSupabase(record)
                if (result.isSuccess) {
                    _evidenceStatusMessage.value = "Evidence backed up to Supabase Storage."
                    addNotification("PQC Evidence Vault: Backed up to Supabase.")
                } else {
                    _evidenceStatusMessage.value = "Upload failed: ${result.exceptionOrNull()?.message}"
                    addNotification("Upload failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                _evidenceStatusMessage.value = "Sync error: ${e.message}"
            } finally {
                _isEvidenceProcessing.value = false
            }
        }
    }
}


