package com.example.ui.home

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.ContactEntity
import com.example.ui.theme.*
import com.example.viewmodel.SafetyViewModel
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: SafetyViewModel,
    innerPadding: PaddingValues,
    onNavigateToMap: () -> Unit = {}
) {
    val userName by viewModel.userName.collectAsState()
    val isSosCountdownActive by viewModel.isSosCountdownActive.collectAsState()
    val sosCountdownSeconds by viewModel.sosCountdownSeconds.collectAsState()
    val isSosTriggered by viewModel.isSosTriggered.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val profileImageUri by viewModel.profileImageUri.collectAsState()
    val contacts by viewModel.contacts.collectAsState()


    val context = LocalContext.current
    var showAvatarSourceDialog by remember { mutableStateOf(false) }
    var showEmergencyShortcutsScreen by remember { mutableStateOf(false) }
    var showSirenScreen by remember { mutableStateOf(false) }
    var selectedSafetyTip by remember { mutableStateOf<SafetyTipItem?>(null) }
    var showCaptureEvidenceDialog by remember { mutableStateOf(false) }

    // Check system service states
    var isGpsHardwareActive by remember {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        mutableStateOf(lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)
    }
    var isBtHardwareActive by remember {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mutableStateOf(bm?.adapter?.isEnabled == true)
    }

    val isBleHardwareConnected by viewModel.isBleHardwareConnected.collectAsState()
    val isBluetoothAppEnabled by viewModel.isBluetoothEnabled.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshHardwareAndServiceState()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val file = java.io.File(context.filesDir, "profile_avatar.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                viewModel.updateProfileImage(file.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            try {
                val file = java.io.File(context.filesDir, "profile_avatar.png")
                file.outputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
                viewModel.updateProfileImage(file.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.callGuardiansOnly()
        } else {
            viewModel.callGuardiansOnly()
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.sendEmergencySmsOnly()
        } else {
            viewModel.sendEmergencySmsOnly()
        }
    }

    if (showAvatarSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarSourceDialog = false },
            title = {
                Text(
                    text = "Update Profile Picture",
                    color = CurrentTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Choose your preferred photo source:",
                    color = CurrentTextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (profileImageUri.isNotEmpty()) {
                        Button(
                            onClick = {
                                showAvatarSourceDialog = false
                                viewModel.updateProfileImage("")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Remove")
                        }
                    }
                    Button(
                        onClick = {
                            showAvatarSourceDialog = false
                            cameraLauncher.launch(null)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary)
                    ) {
                        Text("Camera")
                    }
                    Button(
                        onClick = {
                            showAvatarSourceDialog = false
                            galleryLauncher.launch("image/*")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary)
                    ) {
                        Text("Gallery")
                    }
                }
            },
            containerColor = MinimalWhiteSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Safety Tip Detail Dialog
    selectedSafetyTip?.let { tip ->
        AlertDialog(
            onDismissRequest = { selectedSafetyTip = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MagentaSoftBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(tip.icon, contentDescription = null, tint = MagentaPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = tip.title, color = CurrentTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = tip.summary, color = CurrentTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    tip.points.forEach { pt ->
                        Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            Text("• ", color = MagentaPrimary, fontWeight = FontWeight.Bold)
                            Text(pt, color = CurrentTextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { selectedSafetyTip = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary)
                ) {
                    Text("Got It")
                }
            },
            containerColor = MinimalWhiteSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showCaptureEvidenceDialog) {
        com.example.ui.evidence.CaptureEvidenceDialog(
            viewModel = viewModel,
            onDismiss = { showCaptureEvidenceDialog = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalWhiteBackground)
            .padding(innerPadding)
    ) {
        if (showEmergencyShortcutsScreen) {
            EmergencyShortcutsOverlay(
                onClose = { showEmergencyShortcutsScreen = false }
            )
        } else if (showSirenScreen) {
            SirenOverlay(
                onClose = { showSirenScreen = false }
            )
        } else if (isSosCountdownActive) {
            SosCountdownOverlay(
                secondsLeft = sosCountdownSeconds,
                onCancel = { viewModel.cancelSosCountdown() }
            )
        } else if (isSosTriggered) {
            ActiveSosTrackingOverlay(
                viewModel = viewModel,
                onDeactivate = { viewModel.deactivateSos() }
            )
        } else {
            // Main Dashboard View
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item { Spacer(modifier = Modifier.height(6.dp)) }

                // Greeting & Status Bar Header
                item {
                    HomeGreetingSection(
                        userName = userName,
                        profileImageUri = profileImageUri,
                        onAvatarClick = { showAvatarSourceDialog = true }
                    )
                }

                // Visual Storytelling Promotional Banners Carousel
                item {
                    VisualStorytellingCarousel()
                }

                // Hardware & System Activation Quick Bar (Turn on Location & Bluetooth on device)
                item {
                    SystemHardwareActivationBar(
                        isGpsOn = isGpsHardwareActive,
                        isBtOn = isBtHardwareActive,
                        isBleConnected = isBleHardwareConnected,
                        isBtAppEnabled = isBluetoothAppEnabled,
                        onToggleGps = {
                            try {
                                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        onToggleBt = {
                            try {
                                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                }

                // Core Prominent Circular "HELP" / SOS Panic Button Hub
                item {
                    CircularHelpButtonHub(
                        onTriggerSos = { viewModel.startSosCountdown() }
                    )
                }

                // Quick Emergency Alerting Action Tools
                item {
                    Text(
                        text = "Instant Emergency Actions",
                        color = CurrentTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.3.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            QuickActionCard(
                                title = "Map",
                                subtitle = "Havens & Safe Walk",
                                icon = Icons.Default.Map,
                                color = StatusEmerald,
                                onClick = onNavigateToMap
                            )
                        }
                        item {
                            QuickActionCard(
                                title = "Call Guardian",
                                subtitle = "Priority speed-dial",
                                icon = Icons.Default.Call,
                                color = MagentaPrimary,
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                                        viewModel.callGuardiansOnly()
                                    } else {
                                        callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                                    }
                                }
                            )
                        }
                        item {
                            QuickActionCard(
                                title = "Send Alert SMS",
                                subtitle = "PQC-encrypted SMS",
                                icon = Icons.Default.Send,
                                color = MagentaPrimaryDark,
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                                        viewModel.sendEmergencySmsOnly()
                                    } else {
                                        smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                                    }
                                }
                            )
                        }
                        item {
                            QuickActionCard(
                                title = "Capture Evidence",
                                subtitle = "Photo, audio & video",
                                icon = Icons.Default.CameraAlt,
                                color = StatusEmerald,
                                onClick = { showCaptureEvidenceDialog = true }
                            )
                        }
                        item {
                            QuickActionCard(
                                title = "105dB Siren",
                                subtitle = "Alarm & strobe",
                                icon = Icons.Default.VolumeUp,
                                color = EmergencyCrimson,
                                onClick = { showSirenScreen = true }
                            )
                        }
                        item {
                            QuickActionCard(
                                title = "Emergency Hotlines",
                                subtitle = "Police, 112, Women's",
                                icon = Icons.Default.Security,
                                color = Color(0xFFD97706),
                                onClick = { showEmergencyShortcutsScreen = true }
                            )
                        }
                    }
                }

                // Emergency Contacts Summary Section
                item {
                    EmergencyContactsSummarySection(
                        contacts = contacts
                    )
                }

                // Curated Safety Tips & Guidance Cards
                item {
                    SafetyTipsSection(
                        onSelectTip = { tip -> selectedSafetyTip = tip }
                    )
                }

                // System Feed & Security Logs
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AIZA Activity & Audit Feed",
                            color = CurrentTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (notifications.isNotEmpty()) {
                            Text(
                                text = "Clear",
                                color = MagentaPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { viewModel.clearNotifications() }
                                    .padding(4.dp)
                            )
                        }
                    }
                }

                if (notifications.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                            border = BorderStroke(1.dp, CurrentBorder),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "AIZA protection shield active. Quantum-safe encrypted and guarding offline.",
                                color = CurrentTextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(notifications.take(4)) { alert ->
                        FeedNotificationItem(message = alert)
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun HomeGreetingSection(
    userName: String,
    profileImageUri: String,
    onAvatarClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(StatusEmerald)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AIZA SHIELD ONLINE",
                        color = StatusEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Hello, ${userName.ifBlank { "Guardian" }}",
                    color = CurrentTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MagentaSoftBg)
                    .clickable { onAvatarClick() }
                    .testTag("home_avatar_image"),
                contentAlignment = Alignment.Center
            ) {
                val avatarBitmap = remember(profileImageUri) {
                    if (profileImageUri.isNotEmpty()) {
                        try {
                            val file = java.io.File(profileImageUri)
                            if (file.exists()) {
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }

                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = "Avatar",
                        modifier = Modifier.size(46.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Shield Active",
                        tint = MagentaPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun VisualStorytellingCarousel() {
    val banners = listOf(
        StoryBannerItem(
            drawableRes = R.drawable.img_banner_safety_hero_1788272617455,
            tag = "LIVE RADAR",
            headline = "Everyday Route Protection",
            subtitle = "Encrypted GPS telemetry & live guardian tracking"
        ),
        StoryBannerItem(
            drawableRes = R.drawable.img_banner_story_contacts_1788272634033,
            tag = "TRUSTED CIRCLE",
            headline = "Emergency Guardians",
            subtitle = "Direct emergency contact priority alerting"
        ),
        StoryBannerItem(
            drawableRes = R.drawable.img_banner_voice_safety_1788272648664,
            tag = "VOICE SOS",
            headline = "Hands-Free Voice Detection",
            subtitle = "Automatic emergency keyword detection & instant trigger"
        )
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(banners) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, CurrentBorder),
                modifier = Modifier
                    .width(290.dp)
                    .height(180.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = item.drawableRes),
                        contentDescription = item.headline,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Gradient overlay for crisp typography
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xCC000000)),
                                    startY = 60f
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MagentaPrimary,
                            contentColor = Color.White
                        ) {
                            Text(
                                text = item.tag,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Column {
                            Text(
                                text = item.headline,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = item.subtitle,
                                color = Color(0xFFE0E0E0),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

data class StoryBannerItem(
    val drawableRes: Int,
    val tag: String,
    val headline: String,
    val subtitle: String
)

@Composable
fun SystemHardwareActivationBar(
    isGpsOn: Boolean,
    isBtOn: Boolean,
    isBleConnected: Boolean,
    isBtAppEnabled: Boolean,
    onToggleGps: () -> Unit,
    onToggleBt: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MagentaSoftBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MagentaPrimaryLight.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (isBleConnected) Icons.Default.BluetoothConnected else Icons.Default.GpsFixed,
                    contentDescription = null,
                    tint = if (isBleConnected) StatusEmerald else MagentaPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Hardware Protections",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CurrentTextPrimary
                    )
                    Text(
                        text = when {
                            !isGpsOn -> "Turn on GPS on phone"
                            !isBtOn -> "Turn on Bluetooth on phone"
                            !isBtAppEnabled -> "Bluetooth trigger paused in settings"
                            isBleConnected -> "🟢 ESP32 Panic Button Connected"
                            else -> "GPS active • Scanning for ESP32 button"
                        },
                        fontSize = 10.sp,
                        color = if (isBleConnected) StatusEmerald else CurrentTextMuted
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onToggleGps,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGpsOn) StatusEmerald else MagentaPrimary,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = if (isGpsOn) Icons.Default.Check else Icons.Default.GpsFixed,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isGpsOn) "GPS ON" else "GPS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onToggleBt,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBtOn) StatusEmerald else MagentaPrimary,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = if (isBtOn) Icons.Default.Check else Icons.Default.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isBtOn) "BT ON" else "BT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CircularHelpButtonHub(onTriggerSos: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_trans")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "EMERGENCY ASSISTANCE",
                color = MagentaPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Press to alert trusted contacts immediately",
                color = CurrentTextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Large Circular "HELP" Button with Animated Magenta Ripples
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(200.dp)
            ) {
                // Expanding Pulse Ring
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(MagentaPrimary.copy(alpha = pulseAlpha))
                )

                // Outer decorative ring
                Box(
                    modifier = Modifier
                        .size(175.dp)
                        .clip(CircleShape)
                        .background(MagentaSoftBg)
                        .border(2.dp, MagentaPrimaryLight.copy(alpha = 0.5f), CircleShape)
                )

                // Center Interactive Circular Button
                Box(
                    modifier = Modifier
                        .size(145.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(MagentaPrimary, MagentaPrimaryDark)
                            )
                        )
                        .clickable { onTriggerSos() }
                        .testTag("sos_trigger_hub_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "HELP Emergency",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "HELP",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "HOLD / TAP",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reassurance text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = StatusEmerald,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "5-second cancellation window • Encrypted dispatch",
                    color = CurrentTextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun EmergencyContactsSummarySection(
    contacts: List<ContactEntity>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MagentaSoftBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContactPhone,
                            contentDescription = null,
                            tint = MagentaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Emergency Guardians (${contacts.size})",
                            color = CurrentTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Prioritized alert receivers",
                            color = CurrentTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF0FDF4),
                    border = BorderStroke(1.dp, StatusEmerald.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "Encrypted",
                        color = StatusEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Contact Avatars Preview
            if (contacts.isEmpty()) {
                Text(
                    text = "No emergency contacts configured. Add guardians in Contacts tab.",
                    color = CurrentTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(contacts) { contact ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF9FAFB),
                            border = BorderStroke(1.dp, CurrentBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MagentaPrimary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.name.take(1).uppercase(),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = contact.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CurrentTextPrimary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = contact.relation.ifBlank { "Priority ${contact.priority}" },
                                        fontSize = 10.sp,
                                        color = CurrentTextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SafetyTipsSection(onSelectTip: (SafetyTipItem) -> Unit) {
    val tips = listOf(
        SafetyTipItem(
            title = "Late Night Commute",
            category = "Transit",
            icon = Icons.Default.DirectionsWalk,
            summary = "Essential safety protocols for walking or transit after dark.",
            points = listOf(
                "Keep one ear free without headphones to maintain ambient awareness.",
                "Share your live encrypted AIZA route with a primary contact.",
                "Stay in well-lit areas near open commercial storefronts.",
                "Keep your phone in hand with finger ready near the power button."
            )
        ),
        SafetyTipItem(
            title = "Discreet Triggering",
            category = "SOS",
            icon = Icons.Default.Vibration,
            summary = "Activate distress signals silently when screen is inaccessible.",
            points = listOf(
                "Shake your phone firmly 3 times to begin the 5-second silent countdown.",
                "Speak 'Help me' clearly if voice recognition is enabled.",
                "Press the ESP32 SOS-WEAR physical wearable button.",
                "AIZA bypasses silent mode to dial priority guardians in sequence."
            )
        ),
        SafetyTipItem(
            title = "Safe Zones & Geofencing",
            category = "Navigation",
            icon = Icons.Default.GpsFixed,
            summary = "Creating automated check-in zones for daily destinations.",
            points = listOf(
                "Define trusted safe zones like Home, Office, and University campus.",
                "AIZA silently pings guardians upon safe arrival.",
                "Deviation alerts dispatch automatically if unexpected route changes occur."
            )
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Safety Tips & Reassurance Hub",
                color = CurrentTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap to view",
                color = MagentaPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(tips) { tip ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, CurrentBorder),
                    modifier = Modifier
                        .width(220.dp)
                        .clickable { onSelectTip(tip) }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MagentaSoftBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tip.icon,
                                    contentDescription = null,
                                    tint = MagentaPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFF3F4F6)
                            ) {
                                Text(
                                    text = tip.category,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CurrentTextMuted,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = tip.title,
                            color = CurrentTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tip.summary,
                            color = CurrentTextMuted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

data class SafetyTipItem(
    val title: String,
    val category: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val summary: String,
    val points: List<String>
)

@Composable
fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .width(160.dp)
            .clickable { onClick() }
            .testTag("quick_action_${title.lowercase().replace(" ", "_")}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    color = CurrentTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = CurrentTextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun FeedNotificationItem(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MagentaSoftBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = MagentaPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                color = CurrentTextPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun SosCountdownOverlay(secondsLeft: Int, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "INITIATING EMERGENCY SOS",
                color = EmergencyCrimson,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Dispatching encrypted alerts to all guardians",
                color = CurrentTextMuted,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(36.dp))

            // Countdown Circle
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(MagentaSoftBg)
                    .border(4.dp, MagentaPrimary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$secondsLeft",
                    color = MagentaPrimary,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827), contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel Emergency Alert", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun ActiveSosTrackingOverlay(
    viewModel: SafetyViewModel,
    onDeactivate: () -> Unit
) {
    val latitude by viewModel.latitude.collectAsState()
    val longitude by viewModel.longitude.collectAsState()
    val sosSentLog by viewModel.sosSentLog.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalWhiteBackground)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = EmergencyCrimson.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, EmergencyCrimson.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = EmergencyCrimson, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("ACTIVE EMERGENCY STATE", color = EmergencyCrimson, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            Text("Guardians receive live updates every 5 seconds", color = CurrentTextPrimary, fontSize = 11.sp)
                        }
                    }
                }

                Surface(
                    color = MinimalWhiteSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, CurrentBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Live GPS Coordinates", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CurrentTextPrimary)
                        Text("Latitude: $latitude", fontSize = 12.sp, color = CurrentTextMuted)
                        Text("Longitude: $longitude", fontSize = 12.sp, color = CurrentTextMuted)
                    }
                }

                Surface(
                    color = MinimalWhiteSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, CurrentBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Transmission Dispatch Log", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CurrentTextPrimary)
                        if (sosSentLog.isEmpty()) {
                            Text("Broadcasting packets to primary guardian channels...", fontSize = 11.sp, color = CurrentTextMuted)
                        } else {
                            sosSentLog.takeLast(4).forEach { line ->
                                Text("• $line", fontSize = 11.sp, color = MagentaPrimary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onDeactivate,
                colors = ButtonDefaults.buttonColors(containerColor = StatusEmerald, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Deactivate Emergency Mode", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun EmergencyShortcutsOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val hotlines = listOf(
        Triple("National Emergency", "112", Icons.Default.Security),
        Triple("Police Dispatch", "100", Icons.Default.Shield),
        Triple("Women's Helpline", "1091", Icons.Default.Call),
        Triple("Ambulance Medical", "108", Icons.Default.MedicalServices),
        Triple("Fire & Rescue", "101", Icons.Default.LocalFireDepartment)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalWhiteBackground)
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Emergency Hotlines", color = CurrentTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = CurrentTextPrimary)
                    }
                }

                hotlines.forEach { (name, number, icon) ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, CurrentBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MagentaSoftBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, tint = MagentaPrimary, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(name, color = CurrentTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(number, color = MagentaPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                            Icon(Icons.Default.Call, contentDescription = "Dial", tint = StatusEmerald)
                        }
                    }
                }
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Return to Dashboard", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SirenOverlay(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.example.data.SirenPlayer.start(context)
        onDispose {
            com.example.data.SirenPlayer.stop()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "siren_trans")
    val strobeColor by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(250, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "strobe"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (strobeColor > 0.5f) MagentaPrimary else Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                tint = if (strobeColor > 0.5f) Color.White else MagentaPrimary,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "HIGH-DECIBEL SIREN ACTIVE",
                color = if (strobeColor > 0.5f) Color.White else CurrentTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Flashing visual strobe and acoustic deterrent",
                color = if (strobeColor > 0.5f) Color.White.copy(alpha = 0.9f) else CurrentTextMuted,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(40.dp))
            Button(
                onClick = {
                    com.example.data.SirenPlayer.stop()
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (strobeColor > 0.5f) Color.White else Color(0xFF111827),
                    contentColor = if (strobeColor > 0.5f) MagentaPrimary else Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .testTag("stop_siren_button")
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("Stop Siren Alarm", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
