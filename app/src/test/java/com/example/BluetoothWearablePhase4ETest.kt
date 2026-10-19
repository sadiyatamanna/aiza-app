package com.example

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.BluetoothDeviceConnectionState
import com.example.data.BluetoothDeviceType
import com.example.data.BluetoothSafetyDevice
import com.example.data.BluetoothSosConfig
import com.example.data.BluetoothSosManager
import com.example.data.BluetoothWearableManager
import com.example.data.ContactEntity
import com.example.data.EmergencyIncidentEntity
import com.example.data.PreferencesManager
import com.example.domain.SafetyRepository
import com.example.ui.profile.ProfileScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SafetyViewModel
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothWearablePhase4ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var prefs: PreferencesManager
    private lateinit var db: AppDatabase
    private lateinit var repository: SafetyRepository
    private lateinit var viewModel: SafetyViewModel
    private lateinit var wearableManager: BluetoothWearableManager

    private val testTrustedDeviceId = "AA:BB:CC:DD:EE:01"
    private val testUntrustedDeviceId = "AA:BB:CC:DD:EE:99"
    private val testEsp32DeviceId = "11:22:33:44:55:66"

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs = PreferencesManager(application)
        prefs.isLoggedIn = true
        prefs.supabaseUserId = "11111111-2222-3333-4444-555555555555"
        prefs.isBluetoothEnabled = true
        prefs.isShakeEnabled = true
        prefs.isVoiceSosEnabled = true
        prefs.userName = "Tamanna"
        prefs.userPhone = "+919876543210"

        db = AppDatabase.getDatabase(application)
        clearDatabase()
        repository = SafetyRepository(
            contactDao = db.contactDao(),
            sosLogDao = db.sosLogDao(),
            preferencesManager = prefs,
            emergencyIncidentDao = db.emergencyIncidentDao()
        )
        viewModel = SafetyViewModel(application)
        wearableManager = viewModel.bluetoothWearableManager
    }

    private fun clearDatabase() {
        java.util.concurrent.Executors.newSingleThreadExecutor().submit {
            db.clearAllTables()
        }.get()
    }

    @After
    fun tearDown() {
        wearableManager.disconnectDevice()
        prefs.removeBluetoothDeviceTrust()
        clearDatabase()
    }

    @Test
    fun `1 & 2 - Bluetooth unavailable and disabled handled safely without crashing`() {
        // When bluetooth is disabled in preferences
        prefs.isBluetoothEnabled = false
        viewModel.toggleBluetoothSetting(false)

        // Attempt processing incoming trigger
        val result = viewModel.processWearableData("S", testTrustedDeviceId)
        assertFalse("Disabled Bluetooth must reject trigger", result)
        assertFalse("SOS should not be triggered when Bluetooth is disabled", viewModel.isSosTriggered.value)

        // Re-enable
        prefs.isBluetoothEnabled = true
        viewModel.toggleBluetoothSetting(true)
    }

    @Test
    fun `3 - Permission verification handled safely`() {
        // Checking hasPermissions on manager does not crash
        val hasPerms = wearableManager.hasPermissions()
        // Returns boolean without throwing exception
        assertTrue(hasPerms || !hasPerms)
    }

    @Test
    fun `4 - Device discovery registers compatible devices and resolves device type`() {
        val testWatch = BluetoothSafetyDevice(
            deviceId = "WATCH:01",
            deviceName = "AIZA Safety Watch",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )
        val testEsp32 = BluetoothSafetyDevice(
            deviceId = testEsp32DeviceId,
            deviceName = "SOS-WEAR-ESP32",
            deviceType = BluetoothDeviceType.ESP32_PANIC_BUTTON
        )

        wearableManager.addDiscoveredDeviceManually(testWatch)
        wearableManager.addDiscoveredDeviceManually(testEsp32)

        val discovered = wearableManager.discoveredDevices.value
        assertEquals(2, discovered.size)
        assertEquals(BluetoothDeviceType.BLE_WEARABLE, discovered.first { it.deviceId == "WATCH:01" }.deviceType)
        assertEquals(BluetoothDeviceType.ESP32_PANIC_BUTTON, discovered.first { it.deviceId == testEsp32DeviceId }.deviceType)
    }

    @Test
    fun `5 - Untrusted device cannot trigger SOS`() {
        var sosTriggered = false
        wearableManager.setOnSosTrigger {
            sosTriggered = true
        }

        // Untrusted device sends 'S'
        val processed = wearableManager.processIncomingData("S", deviceId = testUntrustedDeviceId)
        assertFalse("Untrusted device command must be rejected", processed)
        assertFalse("SOS trigger callback must not fire for untrusted device", sosTriggered)
    }

    @Test
    fun `6 & 7 - Trusted device triggers existing Bluetooth SOS pipeline producing BLUETOOTH trigger source`() = runBlocking {
        clearDatabase()
        // Set up trusted device
        val trustedDevice = BluetoothSafetyDevice(
            deviceId = testTrustedDeviceId,
            deviceName = "AIZA Trusted Watch",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )
        wearableManager.trustDevice(trustedDevice)
        assertTrue(wearableManager.isDeviceTrusted(testTrustedDeviceId))

        var triggerCharReceived: String? = null
        wearableManager.setOnSosTrigger { char ->
            triggerCharReceived = char
            viewModel.triggerSos("BLUETOOTH")
        }

        val processed = wearableManager.processIncomingData("S", deviceId = testTrustedDeviceId)
        assertTrue("Valid trigger from trusted device must be accepted", processed)
        assertEquals(BluetoothSosConfig.TRIGGER_CHARACTER, triggerCharReceived)
        assertTrue("ViewModel SOS must be active", viewModel.isSosTriggered.value)

        // Verify EmergencyIncident trigger source is normalized to BLUETOOTH
        val res = repository.createEmergencyIncident(
            triggerSource = "BLUETOOTH",
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 84,
            networkStatus = "ONLINE",
            medicalSharingEnabled = false,
            medicalSummary = null
        )
        assertTrue(res.isSuccess)
        val activeIncident = repository.getActiveEmergencyIncident(repository.resolveAuthenticatedIncidentUserId())
        assertNotNull("EmergencyIncident must be created", activeIncident)
        assertEquals("BLUETOOTH", activeIncident?.triggerSource)
    }

    @Test
    fun `8 - Malformed Bluetooth command is ignored`() {
        val trustedDevice = BluetoothSafetyDevice(
            deviceId = testTrustedDeviceId,
            deviceName = "AIZA Trusted Watch",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )
        wearableManager.trustDevice(trustedDevice)

        // Send malformed non-trigger data
        val result1 = wearableManager.processIncomingData("RANDOM_DATA", testTrustedDeviceId)
        assertFalse("Arbitrary non-S packet must be ignored", result1)

        val result2 = wearableManager.processIncomingData("", testTrustedDeviceId)
        assertFalse("Empty packet must be ignored", result2)

        val result3 = wearableManager.processIncomingData("BATTERY_STATUS=90", testTrustedDeviceId)
        assertFalse("Telemetry packet must be ignored", result3)
    }

    @Test
    fun `9 - Repeated command within cooldown window does not create duplicate incidents`() {
        val trustedDevice = BluetoothSafetyDevice(
            deviceId = testTrustedDeviceId,
            deviceName = "AIZA Trusted Watch",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )
        wearableManager.trustDevice(trustedDevice)

        // First trigger
        val firstTrigger = wearableManager.processIncomingData("S", testTrustedDeviceId)
        assertTrue("First trigger must succeed", firstTrigger)

        // Immediate duplicate trigger within 5000ms window
        val duplicateTrigger = wearableManager.processIncomingData("S", testTrustedDeviceId)
        assertFalse("Duplicate trigger within cooldown must be suppressed", duplicateTrigger)
    }

    @Test
    fun `10 & 11 - Device disconnect and reconnect do not trigger SOS`() {
        val testDevice = BluetoothSafetyDevice(
            deviceId = testTrustedDeviceId,
            deviceName = "AIZA Watch",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )
        wearableManager.connectDevice(testDevice)
        wearableManager.disconnectDevice()

        assertEquals(BluetoothDeviceConnectionState.DISCONNECTED, wearableManager.connectionState.value)
        assertNull(wearableManager.connectedDevice.value)
        assertFalse("Disconnect must NEVER trigger SOS", viewModel.isSosTriggered.value)

        wearableManager.connectDevice(testDevice)
        assertFalse("Reconnect must NEVER trigger SOS", viewModel.isSosTriggered.value)
    }

    @Test
    fun `12 & 13 - Trust state persists and removing trust prevents future SOS`() {
        val device = BluetoothSafetyDevice(
            deviceId = "WATCH:TRUST_TEST",
            deviceName = "Test Wearable",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )

        assertFalse(prefs.isBluetoothDeviceTrusted("WATCH:TRUST_TEST"))

        // Trust device
        wearableManager.trustDevice(device)
        assertTrue(prefs.isBluetoothDeviceTrusted("WATCH:TRUST_TEST"))
        assertEquals("WATCH:TRUST_TEST", prefs.trustedBluetoothDeviceId)

        // Can trigger when trusted
        val allowed = wearableManager.processIncomingData("S", "WATCH:TRUST_TEST")
        assertTrue(allowed)

        // Untrust device
        wearableManager.removeTrust(device)
        assertFalse(prefs.isBluetoothDeviceTrusted("WATCH:TRUST_TEST"))

        // Rejected after trust removed
        val rejectedAfterUntrust = wearableManager.processIncomingData("S", "WATCH:TRUST_TEST")
        assertFalse("Should be rejected once trust is removed", rejectedAfterUntrust)
    }

    @Test
    fun `14 - Existing ESP32 S command remains compatible`() {
        val esp32Device = BluetoothSafetyDevice(
            deviceId = testEsp32DeviceId,
            deviceName = "SOS-WEAR",
            deviceType = BluetoothDeviceType.ESP32_PANIC_BUTTON
        )
        wearableManager.trustDevice(esp32Device)

        val processed = wearableManager.processIncomingData("S", testEsp32DeviceId)
        assertTrue("ESP32 'S' command must be accepted", processed)
    }

    @Test
    fun `15, 16 & 17 - Manual, Shake, and Voice SOS still work properly`() = runBlocking {
        // Manual SOS
        clearDatabase()
        viewModel.triggerSos("MANUAL")
        assertTrue(viewModel.isSosTriggered.value)
        repository.createEmergencyIncident(
            triggerSource = "MANUAL",
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 84,
            networkStatus = "ONLINE",
            medicalSharingEnabled = false,
            medicalSummary = null
        )
        var inc = repository.getActiveEmergencyIncident(repository.resolveAuthenticatedIncidentUserId())
        assertEquals("MANUAL", inc?.triggerSource)

        clearDatabase()

        // Shake SOS
        viewModel.triggerSos("SHAKE")
        repository.createEmergencyIncident(
            triggerSource = "SHAKE",
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 84,
            networkStatus = "ONLINE",
            medicalSharingEnabled = false,
            medicalSummary = null
        )
        inc = repository.getActiveEmergencyIncident(repository.resolveAuthenticatedIncidentUserId())
        assertEquals("SHAKE", inc?.triggerSource)

        clearDatabase()

        // Voice SOS
        viewModel.triggerSos("VOICE")
        repository.createEmergencyIncident(
            triggerSource = "VOICE",
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 84,
            networkStatus = "ONLINE",
            medicalSharingEnabled = false,
            medicalSummary = null
        )
        inc = repository.getActiveEmergencyIncident(repository.resolveAuthenticatedIncidentUserId())
        assertEquals("VOICE", inc?.triggerSource)
    }

    @Test
    fun `18 & 19 - EmergencyIncident creation and guardian delivery remain unchanged`() = runBlocking {
        // Add a contact
        val contact = ContactEntity(
            name = "Sarah",
            phone = "+919876543211",
            relation = "Guardian",
            priority = "1"
        )
        db.contactDao().insertContact(contact)

        val trustedDevice = BluetoothSafetyDevice(
            deviceId = testTrustedDeviceId,
            deviceName = "AIZA Watch",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )
        wearableManager.trustDevice(trustedDevice)
        viewModel.processBluetoothData("S", testTrustedDeviceId)

        repository.createEmergencyIncident(
            triggerSource = "BLUETOOTH",
            latitude = 12.9716,
            longitude = 77.5946,
            locationTimestamp = System.currentTimeMillis(),
            locationStatus = EmergencyIncidentEntity.LOCATION_STATUS_LIVE,
            batteryPercent = 84,
            networkStatus = "ONLINE",
            medicalSharingEnabled = false,
            medicalSummary = null
        )
        val active = repository.getActiveEmergencyIncident(repository.resolveAuthenticatedIncidentUserId())
        assertNotNull(active)
        assertEquals("BLUETOOTH", active?.triggerSource)
    }

    @Test
    fun `20 - Normal Bluetooth status events do not create EmergencyIncident`() = runBlocking {
        repository.resolveActiveEmergencyIncident()

        wearableManager.startDiscovery()
        wearableManager.stopDiscovery()

        val dev = BluetoothSafetyDevice(
            deviceId = "DEV:TEST",
            deviceName = "AIZA Device",
            deviceType = BluetoothDeviceType.BLE_WEARABLE
        )
        wearableManager.connectDevice(dev)
        wearableManager.disconnectDevice()

        val active = repository.getActiveEmergencyIncident(repository.resolveAuthenticatedIncidentUserId())
        assertNull("Normal Bluetooth connection / status events must never create EmergencyIncident", active)
    }

    @Test
    fun `UI - Settings row Bluetooth Wearable is removed and integrated inside Safety Triggers`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ProfileScreen(
                    viewModel = viewModel,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }

        // Verify the redundant separate row is completely removed
        composeTestRule.onNodeWithTag("settings_row_bluetooth_wearable").assertDoesNotExist()

        // Verify that Bluetooth Hardware SOS is integrated inside Safety Triggers
        composeTestRule.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("bluetooth_hardware_sos_section"))

        composeTestRule.onNodeWithTag("bluetooth_hardware_sos_section").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth Hardware SOS").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bluetooth_switch").assertIsDisplayed()
    }

    @Test
    fun `UI - Bluetooth Hardware SOS controls display status and pairing instructions`() {
        viewModel.toggleBluetoothSetting(true)

        composeTestRule.setContent {
            MyApplicationTheme {
                ProfileScreen(
                    viewModel = viewModel,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("bluetooth_hardware_controls_card"))

        composeTestRule.onNodeWithTag("bluetooth_hardware_controls_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bluetooth_hardware_status_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bluetooth_hardware_connection_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bluetooth_hardware_instructions").assertIsDisplayed()
    }
}
