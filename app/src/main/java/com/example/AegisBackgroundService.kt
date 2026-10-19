package com.example

import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.data.PreferencesManager
import kotlin.math.sqrt
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.os.Looper
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.example.viewmodel.SafetyViewModel
import com.example.data.AppDatabase
import com.example.data.SosLogEntity
import com.example.domain.SafetyRepository
import android.os.Bundle
import android.telephony.SmsManager
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

class AegisBackgroundService : Service(), SensorEventListener {

    companion object {
        var activeInstance: AegisBackgroundService? = null
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var connectivityManager: ConnectivityManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var bluetoothSosManager: com.example.data.BluetoothSosManager? = null
    private var voiceSosManager: com.example.data.VoiceSosManager? = null
    private var lastSosTriggerTime = 0L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            preferencesManager.lastLatitude = location.latitude.toFloat()
            preferencesManager.lastLongitude = location.longitude.toFloat()
            preferencesManager.lastLocationTimestamp = System.currentTimeMillis()
            preferencesManager.hasAcquiredRealGpsFix = true

            // Persist location breadcrumb to local database for offline tracking
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val isSos = com.example.viewmodel.SafetyViewModel.activeViewModel?.isSosTriggered?.value ?: false
                    db.trackedLocationDao().insertLocation(
                        com.example.data.TrackedLocationEntity(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = System.currentTimeMillis(),
                            isSosAssociated = isSos
                        )
                    )

                    // Continuous location sharing: push to Supabase if enabled and periodic window reached
                    if (preferencesManager.isContinuousLocationSharingEnabled) {
                        val now = System.currentTimeMillis()
                        val lastSync = preferencesManager.lastLocationSyncTimestamp
                        if (now - lastSync > 30000L) {
                            val contactRepository = com.example.domain.SafetyRepository(
                                db.contactDao(), db.sosLogDao(), preferencesManager,
                                db.trackedLocationDao(), db.cachedPlaceDao(),
                                db.guardianChatDao(), null,
                                db.guardianPairingRequestDao(), null,
                                db.emergencyIncidentDao()
                            )
                            contactRepository.pushUserLocationToSupabase(
                                lat = location.latitude,
                                lng = location.longitude,
                                accuracy = location.accuracy,
                                isSharing = true
                            )
                        }
                    }

                    // If ViewModel is not active, mirror live GPS fix into any active EmergencyIncidentEntity
                    if (com.example.viewmodel.SafetyViewModel.activeViewModel == null) {
                        val incidentRepo = com.example.domain.SafetyRepository(
                            db.contactDao(), db.sosLogDao(), preferencesManager,
                            emergencyIncidentDao = db.emergencyIncidentDao()
                        )
                        val updatedIncident = incidentRepo.updateActiveIncidentLocation(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            locationTimestamp = System.currentTimeMillis(),
                            locationStatus = com.example.data.EmergencyIncidentEntity.LOCATION_STATUS_LIVE
                        ).getOrNull()
                        val networkMon = com.example.data.NetworkConnectivityMonitor(applicationContext)
                        if (updatedIncident != null && networkMon.isCurrentlyOnline()) {
                            incidentRepo.syncEmergencyIncidentToSupabase(updatedIncident, updatedIncident.userId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AegisBackgroundService", "Error persisting tracked location: ${e.message}")
                }
            }

            com.example.viewmodel.SafetyViewModel.activeViewModel?.updateLocation(
                location.latitude, location.longitude, location.accuracy
            )
        }
    }
    private var backupNetworkListener: android.location.LocationListener? = null
    private var backupGpsListener: android.location.LocationListener? = null

    private val gravity = FloatArray(3)
    private var eventCount = 0L

    private var lastShakeTime = 0L
    private var lastPulseTime = 0L
    private val shakeTimes = mutableListOf<Long>()

    private var lastDirectionX = 0
    private var directionChanges = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            syncPendingDataToSupabase()
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
                sensorManager.unregisterListener(this@AegisBackgroundService)
                accelerometer?.let {
                    val registered = sensorManager.registerListener(this@AegisBackgroundService, it, SensorManager.SENSOR_DELAY_GAME)
                    if (registered) {
                        Log.d("AegisBackgroundService", "Sensor Registered (Screen state change: $action)")
                    } else {
                        Log.e("AegisBackgroundService", "Sensor Registration Failed (Screen state change: $action)")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.d("AegisBackgroundService", "Service Started")
        preferencesManager = PreferencesManager(applicationContext)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Start foreground notification first to ensure the service has foreground privileges
        startForegroundServiceNotification()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AegisShield::SOSWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e("AegisBackgroundService", "Failed to acquire WakeLock", e)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)

        accelerometer?.let {
            val registered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            if (registered) {
                Log.d("AegisBackgroundService", "Sensor Registered (onCreate)")
            } else {
                Log.e("AegisBackgroundService", "Sensor Registration Failed (onCreate)")
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
        startBackgroundLocationUpdates()

        registerConnectivityMonitor()
        updateBluetoothListenerState()
        updateVoiceListenerState()
    }

    private fun startBackgroundLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                backupNetworkListener?.let {
                    locationManager.removeUpdates(it)
                }
                backupNetworkListener = null
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                backupGpsListener?.let {
                    locationManager.removeUpdates(it)
                }
                backupGpsListener = null
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).apply {
                setMinUpdateIntervalMillis(5000L)
                setWaitForAccurateLocation(true)
            }.build()

            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                e.printStackTrace()
            }

            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

                if (isNetworkEnabled) {
                    backupNetworkListener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            preferencesManager.lastLatitude = location.latitude.toFloat()
                            preferencesManager.lastLongitude = location.longitude.toFloat()
                            com.example.viewmodel.SafetyViewModel.activeViewModel?.updateLocation(location.latitude, location.longitude)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        10000L,
                        5f,
                        backupNetworkListener!!,
                        Looper.getMainLooper()
                    )
                }
                if (isGpsEnabled) {
                    backupGpsListener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            preferencesManager.lastLatitude = location.latitude.toFloat()
                            preferencesManager.lastLongitude = location.longitude.toFloat()
                            com.example.viewmodel.SafetyViewModel.activeViewModel?.updateLocation(location.latitude, location.longitude)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        10000L,
                        5f,
                        backupGpsListener!!,
                        Looper.getMainLooper()
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun registerConnectivityMonitor() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun syncPendingDataToSupabase() {
        // Automatically sync pending SOS logs and location breadcrumbs from local Room db to Supabase
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val safetyRepo = com.example.domain.SafetyRepository(
                    contactDao = db.contactDao(),
                    sosLogDao = db.sosLogDao(),
                    preferencesManager = preferencesManager,
                    trackedLocationDao = db.trackedLocationDao(),
                    cachedPlaceDao = db.cachedPlaceDao(),
                    guardianChatDao = db.guardianChatDao(),
                    guardianPairingRequestDao = db.guardianPairingRequestDao(),
                    emergencyIncidentDao = db.emergencyIncidentDao()
                )
                safetyRepo.syncPendingDataToSupabase()
                safetyRepo.syncReceivedEmergencyIncidents()

                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "aegis_bg_sync"
                val channelName = "AIZA Cloud Auto-Sync"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
                    notificationManager.createNotificationChannel(chan)
                }

                val syncNotification = NotificationCompat.Builder(this@AegisBackgroundService, channelId)
                    .setContentTitle("AIZA Cloud Sync Complete")
                    .setContentText("Network restored! Synchronized offline SOS logs & tracking with secure Supabase.")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

                notificationManager.notify(2, syncNotification)
            } catch (e: Exception) {
                Log.e("AegisBackgroundService", "Background sync error: ${e.message}")
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "aegis_bg_safety"
        val channelName = "AIZA Protection"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AIZA Protection Active")
            .setContentText("Background safety monitoring active (Shake, Voice & Bluetooth SOS).")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasLocationPermission) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (hasMicPermission) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notification, fgsType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasLocationPermission) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AegisBackgroundService", "Service Started/Restarted onStartCommand (flags: $flags, startId: $startId)")
        startForegroundServiceNotification()
        startBackgroundLocationUpdates()
        updateBluetoothListenerState()
        updateVoiceListenerState()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("AegisBackgroundService", "onTaskRemoved: App swiped away. Scheduling AegisBackgroundService restart...")
        val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
            setPackage(packageName)
        }
        try {
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext,
                1,
                restartServiceIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_ONE_SHOT
                }
            )
            val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmService.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
            Log.d("AegisBackgroundService", "Restart scheduled via AlarmManager successfully.")
        } catch (e: Exception) {
            Log.e("AegisBackgroundService", "Failed to schedule service restart onTaskRemoved", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstance = null
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        sensorManager.unregisterListener(this)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            backupNetworkListener?.let {
                locationManager.removeUpdates(it)
            }
            backupGpsListener?.let {
                locationManager.removeUpdates(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            bluetoothSosManager?.stopListening()
            bluetoothSosManager = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            voiceSosManager?.stopListening()
            voiceSosManager = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateBluetoothListenerState() {
        if (preferencesManager.isLoggedIn && preferencesManager.isBluetoothEnabled) {
            if (bluetoothSosManager == null) {
                bluetoothSosManager = com.example.data.BluetoothSosManager(applicationContext)
            }
            bluetoothSosManager?.startListening { triggerChar ->
                Log.d("AegisBackgroundService", "Bluetooth Panic Hardware Triggered with character: '$triggerChar'")
                triggerSOS("BLE")
            }
        } else {
            bluetoothSosManager?.stopListening()
            bluetoothSosManager = null
        }
    }

    fun getBluetoothSosManager(): com.example.data.BluetoothSosManager? = bluetoothSosManager

    fun updateVoiceListenerState() {
        if (preferencesManager.isLoggedIn && preferencesManager.isVoiceSosEnabled) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMicPermission) {
                if (voiceSosManager == null) {
                    voiceSosManager = com.example.data.VoiceSosManager(applicationContext)
                }
                voiceSosManager?.startListening { spokenPhrase ->
                    Log.d("AegisBackgroundService", "Voice SOS Emergency Phrase Detected: '$spokenPhrase'")
                    triggerSOS("VOICE")
                }
            } else {
                Log.w("AegisBackgroundService", "RECORD_AUDIO permission missing for Voice SOS Detection.")
                voiceSosManager?.stopListening()
                voiceSosManager = null
            }
        } else {
            voiceSosManager?.stopListening()
            voiceSosManager = null
        }
    }

   override fun onSensorChanged(event: SensorEvent?) {
     if (!preferencesManager.isShakeEnabled || !preferencesManager.isLoggedIn) {
         return
     }
     eventCount++
     if (eventCount % 100 == 0L) {
         Log.d("AegisBackgroundService", "Sensor Event Received (Count: $eventCount) - x=${event?.values?.getOrNull(0)}, y=${event?.values?.getOrNull(1)}, z=${event?.values?.getOrNull(2)}")
     }

    if (event == null) return

    if (event.sensor.type != Sensor.TYPE_ACCELEROMETER)
        return

    val linear = removeGravity(event.values)

    val x = linear[0]
    val y = linear[1]
    val z = linear[2]

    val magnitude = sqrt(
        x * x +
        y * y +
        z * z
    )

    val threshold =
        if (preferencesManager.isShakeEnabled)
            maxOf(preferencesManager.shakeSensitivity, 18f)
        else
            Float.MAX_VALUE

    val now = System.currentTimeMillis()

    if (magnitude > threshold) {
        Log.d("AegisBackgroundService", "Shake Pulse Detected - magnitude: $magnitude, threshold: $threshold, direction changes: $directionChanges")

        if (now - lastPulseTime < 180)
            return

        lastPulseTime = now

        val direction =
            if (x > 0) 1 else -1

        if (direction != lastDirectionX) {

            directionChanges++

            lastDirectionX = direction

        }

        shakeTimes.removeAll {

            now - it > 5000

        }

        shakeTimes.add(now)

        if (
            shakeTimes.size >= 5 &&
            directionChanges >= 4 &&
            now - lastShakeTime > 5000
        ) {

            Log.d("AegisBackgroundService", "Shake Sequence Completed (shake pulses count: ${shakeTimes.size}, direction changes: $directionChanges)")
            lastShakeTime = now

            directionChanges = 0

            shakeTimes.clear()

            triggerSOS("SHAKE")
        }
    }
}

    fun triggerSOS(source: String = "SHAKE") {
        if (!preferencesManager.isLoggedIn) {
            Log.d("AegisBackgroundService", "SOS Trigger ignored: User is not logged in.")
            return
        }

        val isSourceEnabled = when (source.uppercase()) {
            "SHAKE" -> preferencesManager.isShakeEnabled
            "BLUETOOTH", "BLE" -> preferencesManager.isBluetoothEnabled
            "VOICE" -> preferencesManager.isVoiceSosEnabled
            "MANUAL" -> true
            else -> true
        }

        if (!isSourceEnabled) {
            Log.d("AegisBackgroundService", "SOS Trigger ignored: Trigger source '$source' is disabled in settings.")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastSosTriggerTime < 5000) {
            Log.d("AegisBackgroundService", "SOS trigger ignored due to active cooldown (source: $source)")
            return
        }
        lastSosTriggerTime = now

        Log.d("AegisBackgroundService", "SOS Triggered from source: $source")

        val vm = SafetyViewModel.activeViewModel
        if (vm != null) {
            vm.triggerSos(source)
        } else {
            fetchFreshLocationAndExecuteSos(source)
        }
    }

    private fun fetchFreshLocationAndExecuteSos(source: String) {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    val lat = loc?.latitude?.toFloat() ?: preferencesManager.lastLatitude
                    val lng = loc?.longitude?.toFloat() ?: preferencesManager.lastLongitude
                    preferencesManager.lastLatitude = lat
                    preferencesManager.lastLongitude = lng

                    executeSosDispatchPipeline(source, lat.toDouble(), lng.toDouble())
                }.addOnFailureListener {
                    executeSosDispatchPipeline(source, preferencesManager.lastLatitude.toDouble(), preferencesManager.lastLongitude.toDouble())
                }
            } catch (e: Exception) {
                executeSosDispatchPipeline(source, preferencesManager.lastLatitude.toDouble(), preferencesManager.lastLongitude.toDouble())
            }
        } else {
            executeSosDispatchPipeline(source, preferencesManager.lastLatitude.toDouble(), preferencesManager.lastLongitude.toDouble())
        }
    }

    private fun executeSosDispatchPipeline(source: String, lat: Double, lng: Double) {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10000)
            }
        } catch (e: Exception) {
            Log.e("AegisBackgroundService", "WakeLock acquisition error: ${e.message}")
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val contactRepository = SafetyRepository(
                    contactDao = db.contactDao(),
                    sosLogDao = db.sosLogDao(),
                    preferencesManager = preferencesManager,
                    trackedLocationDao = db.trackedLocationDao(),
                    cachedPlaceDao = db.cachedPlaceDao(),
                    guardianChatDao = db.guardianChatDao(),
                    guardianPairingRequestDao = db.guardianPairingRequestDao(),
                    emergencyIncidentDao = db.emergencyIncidentDao()
                )
                val networkMon = com.example.data.NetworkConnectivityMonitor(applicationContext)
                val isOnlineNow = networkMon.isCurrentlyOnline()

                // Phase 2: Create or reuse/update EmergencyIncidentEntity for background SOS trigger
                val normalizedTrigger = SafetyRepository.normalizeIncidentTriggerSource(source)
                if (normalizedTrigger != null) {
                    val nowMs = System.currentTimeMillis()
                    val hasCoords = lat != 0.0 || lng != 0.0
                    val locTimestamp = preferencesManager.lastLocationTimestamp.takeIf { it > 0L } ?: nowMs
                    val locStatus = when {
                        hasCoords && (nowMs - locTimestamp <= 60_000L) ->
                            com.example.data.EmergencyIncidentEntity.LOCATION_STATUS_LIVE
                        hasCoords && preferencesManager.hasAcquiredRealGpsFix ->
                            SafetyRepository.classifyLocationStatus(
                                hasLiveFix = false,
                                hasCachedFix = true,
                                locationTimestamp = locTimestamp,
                                nowMs = nowMs
                            )
                        else -> com.example.data.EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN
                    }
                    val medEnabled = preferencesManager.isMedicalSharingExplicitlyConfigured && preferencesManager.shareMedicalInfo
                    val medSummary = if (medEnabled) {
                        val parts = mutableListOf<String>()
                        if (preferencesManager.bloodGroup.isNotBlank()) parts.add("Blood: ${preferencesManager.bloodGroup.trim()}")
                        if (preferencesManager.allergies.isNotBlank()) parts.add("Allergies: ${preferencesManager.allergies.trim()}")
                        if (preferencesManager.medicalConditions.isNotBlank()) parts.add("Conditions: ${preferencesManager.medicalConditions.trim()}")
                        if (preferencesManager.medicalNotes.isNotBlank()) parts.add("Notes: ${preferencesManager.medicalNotes.trim()}")
                        parts.joinToString(", ").takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                    val battPercent = try {
                        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                        if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100) else 95
                    } catch (_: Exception) {
                        95
                    }

                    val incidentResult = contactRepository.createEmergencyIncident(
                        triggerSource = normalizedTrigger,
                        latitude = if (locStatus != com.example.data.EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN) lat else null,
                        longitude = if (locStatus != com.example.data.EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN) lng else null,
                        locationTimestamp = if (locStatus != com.example.data.EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN) locTimestamp else null,
                        locationStatus = locStatus,
                        batteryPercent = battPercent,
                        networkStatus = if (isOnlineNow) "ONLINE" else "OFFLINE",
                        medicalSharingEnabled = medEnabled,
                        medicalSummary = medSummary,
                        audioRecordingStatus = com.example.data.EmergencyIncidentEntity.AUDIO_STATUS_NOT_STARTED,
                        startedAt = nowMs
                    )
                    val createdIncident = incidentResult.getOrNull()
                    if (createdIncident != null && isOnlineNow) {
                        contactRepository.syncEmergencyIncidentToSupabase(createdIncident, createdIncident.userId)
                    }
                }

                var voiceClipFile: java.io.File? = null
                var voiceClipUrl: String? = null

                if (source.equals("VOICE", ignoreCase = true)) {
                    val preRecorded = java.io.File(applicationContext.filesDir, "emergency_voice_clip.mp4")
                    voiceClipFile = if (preRecorded.exists() && preRecorded.length() > 0) {
                        contactRepository.updateActiveIncidentAudioStatus(
                            audioRecordingStatus = com.example.data.EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED,
                            audioDurationMs = 2500L
                        )
                        preRecorded
                    } else {
                        val hasMicPerm = ContextCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasMicPerm) {
                            contactRepository.updateActiveIncidentAudioStatus(
                                audioRecordingStatus = com.example.data.EmergencyIncidentEntity.AUDIO_STATUS_NOT_AVAILABLE,
                                audioDurationMs = null
                            )
                            null
                        } else {
                            contactRepository.updateActiveIncidentAudioStatus(
                                audioRecordingStatus = com.example.data.EmergencyIncidentEntity.AUDIO_STATUS_RECORDING,
                                audioDurationMs = null
                            )
                            val recorded = com.example.data.DistressClipManager.startDistressRecording(applicationContext)
                            if (recorded == null) {
                                contactRepository.updateActiveIncidentAudioStatus(
                                    audioRecordingStatus = com.example.data.EmergencyIncidentEntity.AUDIO_STATUS_FAILED,
                                    audioDurationMs = null
                                )
                                null
                            } else {
                                kotlinx.coroutines.delay(2500)
                                val stoppedFile = com.example.data.DistressClipManager.stopDistressRecording()
                                if (stoppedFile != null && stoppedFile.exists()) {
                                    contactRepository.updateActiveIncidentAudioStatus(
                                        audioRecordingStatus = com.example.data.EmergencyIncidentEntity.AUDIO_STATUS_COMPLETED,
                                        audioDurationMs = 2500L
                                    )
                                } else {
                                    contactRepository.updateActiveIncidentAudioStatus(
                                        audioRecordingStatus = com.example.data.EmergencyIncidentEntity.AUDIO_STATUS_FAILED,
                                        audioDurationMs = null
                                    )
                                }
                                stoppedFile
                            }
                        }
                    }

                    if (isOnlineNow && voiceClipFile != null && voiceClipFile.exists()) {
                        val userId = preferencesManager.supabaseUserId.ifBlank { "anonymous_aegis_user" }
                        val uploadResult = com.example.data.DistressClipManager.uploadDistressClip(voiceClipFile, userId)
                        if (uploadResult.isSuccess) {
                            voiceClipUrl = uploadResult.getOrNull()
                        }
                    }
                    if (isOnlineNow) {
                        contactRepository.getActiveEmergencyIncident()?.let { activeInc ->
                            contactRepository.syncEmergencyIncidentToSupabase(activeInc, activeInc.userId)
                        }
                    }
                }

                val allContacts = contactRepository.allContacts.first()

                withContext(Dispatchers.Main) {
                    com.example.domain.SosCommunicationDispatcher.dispatchEmergencyCommunication(
                        context = applicationContext,
                        userName = preferencesManager.userName,
                        triggerSource = source,
                        latitude = lat,
                        longitude = lng,
                        contacts = allContacts,
                        countryCode = preferencesManager.countryCode,
                        isLastKnownLocation = (lat == 0.0 && lng == 0.0 && preferencesManager.lastLatitude != 0f),
                        voiceClipUrl = voiceClipUrl,
                        onLog = { Log.d("AegisBackgroundService", it) }
                    )
                }

                saveSosLogToDatabase(lat.toFloat(), lng.toFloat(), source, voiceClipFile?.absolutePath ?: "", voiceClipUrl ?: "")
                withContext(Dispatchers.Main) {
                    showFullScreenNotification(source)
                }
            } catch (e: Exception) {
                Log.e("AegisBackgroundService", "Error in executeSosDispatchPipeline", e)
            }
        }
    }

    private fun saveSosLogToDatabase(lat: Float, lng: Float, source: String = "SHAKE", voiceClipPath: String = "", voiceClipUrl: String = "") {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val contactRepository = SafetyRepository(db.contactDao(), db.sosLogDao(), preferencesManager)
                val allContacts = contactRepository.allContacts.first()
                val notifiedContacts = allContacts.joinToString(", ") { "${it.name} (${it.priority})" }
                
                val payloadString = "SOS ALERT [$source]! User: ${preferencesManager.userName}, Lat: $lat, Lng: $lng, Time: ${System.currentTimeMillis()}"
                val (pqcPubKey, pqcPrvKey) = com.example.data.SecurityUtils.generatePqcKeyPair()
                val encryptedResult = com.example.data.SecurityUtils.encryptPqcKyber(payloadString, pqcPubKey)
                val dilithiumSig = com.example.data.SecurityUtils.signPqcDilithium(payloadString, pqcPrvKey)
                val logPayloadInfo = encryptedResult.encryptedData + "\n[PQC DILITHIUM SIGNATURE]:\n" + dilithiumSig

                val locStatus = if (lat != 0f || lng != 0f) "ACTIVE_GPS" else "UNAVAILABLE"
                val status = if (source.equals("VOICE", ignoreCase = true) && voiceClipPath.isNotBlank() && voiceClipUrl.isBlank()) "Pending_Sync" else "Sent"
                contactRepository.insertLog(
                    com.example.data.SosLogEntity(
                        durationSeconds = 1,
                        latitude = lat.toDouble(),
                        longitude = lng.toDouble(),
                        locationStatus = locStatus,
                        status = status,
                        triggerSource = source,
                        encryptedPayload = logPayloadInfo,
                        eventType = "SENT",
                        contacts = notifiedContacts.ifBlank { "No contacts configured" },
                        senderInfo = "AIZA Background Engine ($source)",
                        voiceClipPath = voiceClipPath,
                        voiceClipUrl = voiceClipUrl
                    )
                )
                Log.d("AegisBackgroundService", "SOS log saved to database from background service for source: $source.")
            } catch (e: Exception) {
                Log.e("AegisBackgroundService", "Error saving SOS log to database", e)
            }
        }
    }

    fun sendEmergencySms() {
        Log.d("AegisBackgroundService", "sendEmergencySms called: delegating to unified pipeline")
        executeSosDispatchPipeline("SOS", preferencesManager.lastLatitude.toDouble(), preferencesManager.lastLongitude.toDouble())
    }

    fun initiateEmergencyCall() {
        Log.d("AegisBackgroundService", "initiateEmergencyCall called: handled within unified pipeline")
    }

    private fun showFullScreenNotification(source: String = "SHAKE") {
        val channelId = "aegis_emergency"
        val channelName = "AIZA Emergency Alerts"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(chan)
        }

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("trigger_sos_from_bg", true)
            putExtra("sos_trigger_source", source)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            fullScreenIntent,
            flags
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AIZA EMERGENCY SHAKE TRIGGERED")
            .setContentText("AIZA Shield detected a shake. Initializing SOS...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(3, notification)
    }

    private fun removeGravity(values: FloatArray): FloatArray {

    val alpha = 0.8f

    gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0]
    gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1]
    gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2]

    return floatArrayOf(
        values[0] - gravity[0],
        values[1] - gravity[1],
        values[2] - gravity[2]
    )
}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
