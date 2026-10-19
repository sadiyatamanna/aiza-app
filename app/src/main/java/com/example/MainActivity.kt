package com.example

import android.content.Context
import android.hardware.Sensor
import androidx.compose.ui.unit.dp
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.example.data.EmergencyIncidentEntity
import com.example.domain.AizaEmergencyNotificationManager
import com.example.ui.contacts.ContactsScreen
import com.example.ui.home.HomeScreen
import com.example.ui.maps.MapsScreen
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.profile.ProfileScreen
import com.example.ui.history.HistoryScreen
import com.example.ui.theme.DeepObsidianBackground
import com.example.ui.theme.CurrentSurface
import com.example.ui.theme.CurrentTextMuted
import com.example.ui.theme.CurrentBorder
import com.example.ui.theme.MinimalWhiteBackground
import com.example.ui.theme.MinimalWhiteSurface
import com.example.ui.theme.MagentaPrimary
import com.example.ui.theme.MagentaSoftBg
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.data.SupabaseClientProvider
import io.github.jan.supabase.auth.handleDeeplinks
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SafetyViewModel
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private val viewModel: SafetyViewModel by viewModels()
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            viewModel.updateLocation(location.latitude, location.longitude, location.accuracy)
        }
    }

    // Shake detection calculation states
    private val preferencesManager by lazy { com.example.data.PreferencesManager(applicationContext) }
    private val gravity = FloatArray(3)
    private var lastShakeTime = 0L
    private var lastPulseTime = 0L
    private val shakeTimes = mutableListOf<Long>()
    private var lastDirectionX = 0
    private var directionChanges = 0

    private fun startShakeService() {
        try {
            val serviceIntent = Intent(this, AegisBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleSosIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("trigger_sos_from_bg", false) == true) {
            if (!viewModel.isSosTriggered.value && !viewModel.isSosCountdownActive.value) {
                val bgSource = intent.getStringExtra("sos_trigger_source") ?: "SHAKE"
                Toast.makeText(this, "AIZA: Background Shake SOS Triggered!", Toast.LENGTH_LONG).show()
                viewModel.startSosCountdown(bgSource)
            }
        }
    }

    private fun handleEmergencyIncidentNotificationIntent(intent: Intent?) {
        viewModel.handleEmergencyNotificationIntent(intent)
    }

    private fun handleAuthDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        val host = uri.host?.lowercase(java.util.Locale.ROOT)
        if ((scheme == "aiza" || scheme == "aegis") &&
            (host == "auth-callback" || host == "reset-password")
        ) {
            try {
                SupabaseClientProvider.client.handleDeeplinks(intent)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Supabase handleDeeplinks note: ${e.message}")
            }
            viewModel.handleAuthRecoveryDeeplink(uri)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSosIntent(intent)
        handleEmergencyIncidentNotificationIntent(intent)
        handleAuthDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Setup Accelerometer Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Initialize acceleration variables

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startShakeService()
        handleSosIntent(intent)
        handleEmergencyIncidentNotificationIntent(intent)
        handleAuthDeepLink(intent)

        // Permissions are managed inside the app safety settings rather than prompted on startup

        setContent {
            val appTheme by viewModel.appTheme.collectAsState()
            val accentColor by viewModel.accentColor.collectAsState()
            val useDarkTheme = when (appTheme) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = useDarkTheme, accentKey = accentColor) {
                var showSplash by remember { mutableStateOf(true) }

                if (showSplash) {
                    com.example.ui.splash.SplashScreen(
                        onFinished = {
                            showSplash = false
                        }
                    )
                } else {
                    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
                    val isLoggedIn by viewModel.isLoggedIn.collectAsState()

                    if (!isOnboardingCompleted) {
                        OnboardingScreen(
                            onComplete = {
                                viewModel.completeOnboardingOnly()
                            }
                        )
                    } else if (!isLoggedIn) {
                        com.example.ui.auth.AuthScreen(viewModel = viewModel)
                    } else {
                        MainNavigationScaffold(viewModel = viewModel)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requestLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        // Register sensor listener
        accelerometer?.let {
            sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME)
        }
        if (preferencesManager.isLoggedIn) {
            viewModel.syncReceivedEmergencyIncidents()
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister sensor listener to conserve battery
        sensorManager.unregisterListener(this)
    }

    // SensorEventListener Implementations
    override fun onSensorChanged(event: SensorEvent?) {

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

            lastShakeTime = now

            directionChanges = 0

            shakeTimes.clear()

            triggerSOS()
        }
    }
}

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Unused
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).apply {
                setMinUpdateIntervalMillis(2000L)
                setWaitForAccurateLocation(true)
            }.build()

            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
            } catch (e: SecurityException) {
                e.printStackTrace()
                // Fallback: try last known location
                try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            viewModel.updateLocation(location.latitude, location.longitude)
                        }
                    }
                } catch (ex: SecurityException) {
                    ex.printStackTrace()
                }
            }
        }
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
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.indices.any { (permissions[it] == Manifest.permission.ACCESS_FINE_LOCATION || permissions[it] == Manifest.permission.ACCESS_COARSE_LOCATION) && grantResults[it] == PackageManager.PERMISSION_GRANTED }) {
                requestLocationUpdates()
                startShakeService()
            }
        }
    }
    private fun triggerSOS() {

    if (!viewModel.isSosTriggered.value &&
        !viewModel.isSosCountdownActive.value
    ) {

        Toast.makeText(
            this,
            "AIZA SOS ALERT Triggered",
            Toast.LENGTH_LONG
        ).show()

        viewModel.startSosCountdown("SHAKE")
    }
}
}

@Composable
fun MainNavigationScaffold(viewModel: SafetyViewModel) {
    var currentRoute by remember { mutableStateOf("home") }
    val selectedEmergencyIncidentId by viewModel.selectedEmergencyIncidentId.collectAsState()
    val selectedEmergencyIncident by viewModel.selectedEmergencyIncident.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.testTag("bottom_nav_bar")
            ) {
                // Home Nav Item
                NavigationBarItem(
                    selected = currentRoute == "home" && selectedEmergencyIncidentId == null,
                    onClick = {
                        viewModel.closeEmergencyIncidentDetails()
                        currentRoute = "home"
                    },
                    label = { Text("Home", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = CurrentTextMuted,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = CurrentTextMuted,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.testTag("nav_home")
                )

                // Contacts Nav Item
                NavigationBarItem(
                    selected = currentRoute == "contacts" && selectedEmergencyIncidentId == null,
                    onClick = {
                        viewModel.closeEmergencyIncidentDetails()
                        currentRoute = "contacts"
                    },
                    label = { Text("Guardians", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Outlined.Contacts, contentDescription = "Guardians") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = CurrentTextMuted,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = CurrentTextMuted,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.testTag("nav_contacts")
                )

                // Maps Nav Item
                NavigationBarItem(
                    selected = currentRoute == "map" && selectedEmergencyIncidentId == null,
                    onClick = {
                        viewModel.closeEmergencyIncidentDetails()
                        currentRoute = "map"
                    },
                    label = { Text("Radar Map", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Outlined.Map, contentDescription = "Radar Map") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = CurrentTextMuted,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = CurrentTextMuted,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.testTag("nav_map")
                )

                // History Nav Item
                NavigationBarItem(
                    selected = currentRoute == "history" && selectedEmergencyIncidentId == null,
                    onClick = {
                        viewModel.closeEmergencyIncidentDetails()
                        currentRoute = "history"
                    },
                    label = { Text("History", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Outlined.History, contentDescription = "History") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = CurrentTextMuted,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = CurrentTextMuted,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.testTag("nav_history")
                )

                // Profile Nav Item
                NavigationBarItem(
                    selected = currentRoute == "profile" && selectedEmergencyIncidentId == null,
                    onClick = {
                        viewModel.closeEmergencyIncidentDetails()
                        currentRoute = "profile"
                    },
                    label = { Text("Settings", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Outlined.Person, contentDescription = "Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = CurrentTextMuted,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = CurrentTextMuted,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.testTag("nav_profile")
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        val activeIncidentRouteId = selectedEmergencyIncidentId
        if (!activeIncidentRouteId.isNullOrBlank()) {
            com.example.ui.incident.EmergencyIncidentDetailsRoute(
                incidentId = activeIncidentRouteId,
                viewModel = viewModel,
                innerPadding = innerPadding,
                onBack = { viewModel.closeEmergencyIncidentDetails() },
                onNavigateToMap = { currentRoute = "map" },
                onNavigateToContacts = { currentRoute = "contacts" }
            )
        } else {
            when (currentRoute) {
                "home" -> HomeScreen(
                    viewModel = viewModel,
                    innerPadding = innerPadding,
                    onNavigateToMap = { currentRoute = "map" }
                )
                "contacts" -> ContactsScreen(
                    viewModel = viewModel,
                    innerPadding = innerPadding,
                    onNavigateToMap = { currentRoute = "map" }
                )
                "map" -> MapsScreen(viewModel = viewModel, innerPadding = innerPadding)
                "history" -> HistoryScreen(
                    viewModel = viewModel,
                    innerPadding = innerPadding,
                    onOpenIncidentDetails = { incidentId ->
                        viewModel.openEmergencyIncidentDetails(incidentId)
                    }
                )
                "profile" -> ProfileScreen(viewModel = viewModel, innerPadding = innerPadding)
            }
        }
    }
}

@Composable
fun EmergencyIncidentDetailsPlaceholder(
    incidentId: String,
    incident: EmergencyIncidentEntity?,
    innerPadding: PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(24.dp)
            .testTag("emergency_incident_details_placeholder"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = AizaEmergencyNotificationManager.EMERGENCY_NOTIFICATION_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Emergency Incident",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Incident ID: $incidentId",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("emergency_incident_id_text")
                )
                val statusText = incident?.status ?: EmergencyIncidentEntity.STATUS_ACTIVE
                Text(
                    text = "Status: $statusText",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("emergency_incident_status_text")
                )
                val locationAvailability = if (incident != null &&
                    AizaEmergencyNotificationManager.isIncidentLocationAvailable(incident)
                ) {
                    AizaEmergencyNotificationManager.LOCATION_AVAILABLE_TEXT
                } else {
                    AizaEmergencyNotificationManager.LOCATION_UNAVAILABLE_TEXT
                }
                Text(
                    text = locationAvailability,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("emergency_incident_location_status_text")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_incident_back_button")
                ) {
                    Text("Back")
                }
            }
        }
    }
}
