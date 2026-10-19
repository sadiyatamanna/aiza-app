package com.example.ui.profile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.PhoneUtils
import com.example.data.SosLogEntity
import com.example.ui.theme.AlertPulseRed
import com.example.ui.theme.CurrentBorder
import com.example.ui.theme.CurrentTextMuted
import com.example.ui.theme.CurrentTextPrimary
import com.example.ui.theme.MinimalWhiteBackground
import com.example.ui.theme.MinimalWhiteSurface
import com.example.ui.theme.MagentaPrimary
import com.example.ui.theme.MagentaSoftBg
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import com.example.ui.theme.AvailableAccents
import com.example.ui.theme.AppAccentPalette
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.EmergencyCrimsonDark
import com.example.viewmodel.SafetyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileScreen(
    viewModel: SafetyViewModel,
    innerPadding: PaddingValues
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val name by viewModel.userName.collectAsState()
    val phone by viewModel.userPhone.collectAsState()
    val email by viewModel.userEmail.collectAsState()
    val cleanPhone = remember(phone) { if (phone.contains("@")) "" else phone.trim() }
    val publicKey by viewModel.publicKey.collectAsState()

    val isLocationEnabled by viewModel.isLocationEnabled.collectAsState()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
    val isVoiceSosEnabled by viewModel.isVoiceSosEnabled.collectAsState()
    val isWifiEnabled by viewModel.isWifiEnabled.collectAsState()
    val isShakeEnabled by viewModel.isShakeEnabled.collectAsState()

    val logs by viewModel.logs.collectAsState()
    val profileImageUri by viewModel.profileImageUri.collectAsState()

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showAvatarSourceDialog by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsState()
    val deleteAccountError by viewModel.deleteAccountError.collectAsState()
    var showAboutAizaScreen by remember { mutableStateOf(false) }
    var showEvidenceVaultScreen by remember { mutableStateOf(false) }

    // Location Permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleLocationSetting(true)
            Toast.makeText(context, "Location permission granted", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.toggleLocationSetting(false)
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Phone Call Permission launcher
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Phone call permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Phone call permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // SMS Permission launcher
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "SMS permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "SMS permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Microphone Permission launcher for Voice SOS
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleVoiceSosSetting(true)
            Toast.makeText(context, "Microphone permission granted for Voice SOS", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.toggleVoiceSosSetting(false)
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Bluetooth Permission launcher
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleBluetoothSetting(true)
            Toast.makeText(context, "Bluetooth permission granted", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.toggleBluetoothSetting(false)
            Toast.makeText(context, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Media picker for profile picture
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

    // Avatar Selection Dialog
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
                    text = "Select a photo from your gallery or clear your profile photo:",
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
                            galleryLauncher.launch("image/*")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Choose Photo")
                    }
                }
            },
            containerColor = MinimalWhiteSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Edit Profile Dialog
    if (showEditProfileDialog) {
        var nameInput by remember(name) { mutableStateOf(name) }
        var phoneInput by remember(cleanPhone) { mutableStateOf(cleanPhone) }
        var nameError by remember { mutableStateOf(false) }
        var phoneError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = {
                Text(
                    text = "Edit Profile Details",
                    color = CurrentTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (email.isNotBlank()) {
                        Text(
                            text = "Account Email: $email",
                            color = CurrentTextMuted,
                            fontSize = 12.sp
                        )
                    }

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            nameError = false
                        },
                        label = { Text("Full Name") },
                        isError = nameError,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MagentaPrimary,
                            unfocusedBorderColor = CurrentBorder,
                            focusedLabelColor = MagentaPrimary,
                            unfocusedLabelColor = CurrentTextMuted,
                            focusedTextColor = CurrentTextPrimary,
                            unfocusedTextColor = CurrentTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_name_input"),
                        singleLine = true
                    )
                    if (nameError) {
                        Text("Name cannot be empty.", color = Color.Red, fontSize = 11.sp)
                    }

                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = {
                            if (!it.contains("@")) {
                                phoneInput = it
                                phoneError = false
                            }
                        },
                        label = { Text("Phone Number (+91)") },
                        placeholder = { Text("+91 9876543210") },
                        isError = phoneError,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MagentaPrimary,
                            unfocusedBorderColor = CurrentBorder,
                            focusedLabelColor = MagentaPrimary,
                            unfocusedLabelColor = CurrentTextMuted,
                            focusedTextColor = CurrentTextPrimary,
                            unfocusedTextColor = CurrentTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_phone_input"),
                        singleLine = true
                    )
                    if (phoneError) {
                        Text(
                            text = "Please enter a valid phone number (7-15 digits).",
                            color = Color.Red,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val isNameValid = nameInput.isNotBlank()
                        val isPhoneValid = !phoneInput.contains("@") && PhoneUtils.isValid(phoneInput)

                        if (!isNameValid) nameError = true
                        if (!isPhoneValid) phoneError = true

                        if (isNameValid && isPhoneValid) {
                            viewModel.updateProfileDetails(
                                nameInput.trim(),
                                PhoneUtils.normalize(phoneInput.trim())
                            )
                            showEditProfileDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary)
                ) {
                    Text("SAVE CHANGES", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("CANCEL", color = CurrentTextMuted)
                }
            },
            containerColor = MinimalWhiteSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }



    // Logout Confirmation Dialog
    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = {
                Text(
                    text = "Sign Out of AIZA",
                    color = CurrentTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to log out? Active emergency features will require you to sign in again.",
                    color = CurrentTextMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirmDialog = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimsonDark)
                ) {
                    Text("Log Out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text("Cancel", color = CurrentTextMuted)
                }
            },
            containerColor = MinimalWhiteSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Permanent Delete Account Confirmation Dialog
    if (showDeleteAccountDialog) {
        var purgeOfflineEvidence by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isDeletingAccount) {
                    showDeleteAccountDialog = false
                    viewModel.clearDeleteAccountError()
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = AlertPulseRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Permanently Delete Account",
                        color = AlertPulseRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "This action is PERMANENT and CANNOT be undone. Your user identity, all quantum-safe encrypted cloud evidence, emergency incident history, and guardian connections will be permanently erased from the server.",
                        color = CurrentTextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CurrentBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "☁️ Cloud Deletion vs. 📱 Local Evidence",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = CurrentTextPrimary
                            )
                            Text(
                                text = "• Cloud account: Wiped completely from Supabase servers.\n• Local offline evidence: Preserved securely on device by default so you do not lose local legal records.",
                                fontSize = 11.sp,
                                color = CurrentTextMuted,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isDeletingAccount) { purgeOfflineEvidence = !purgeOfflineEvidence }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = purgeOfflineEvidence,
                            onCheckedChange = { purgeOfflineEvidence = it },
                            enabled = !isDeletingAccount
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Also purge locally stored offline evidence from this device",
                            fontSize = 12.sp,
                            color = if (purgeOfflineEvidence) AlertPulseRed else CurrentTextPrimary,
                            fontWeight = if (purgeOfflineEvidence) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    if (deleteAccountError != null) {
                        Surface(
                            color = AlertPulseRed.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, AlertPulseRed.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = deleteAccountError ?: "",
                                color = AlertPulseRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    if (isDeletingAccount) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = AlertPulseRed
                            )
                            Text(
                                "Purging cloud records & deleting account...",
                                fontSize = 12.sp,
                                color = CurrentTextMuted
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAccount(
                            purgeLocalOfflineEvidence = purgeOfflineEvidence,
                            onSuccess = { showDeleteAccountDialog = false },
                            onError = { /* Error message displayed via deleteAccountError state */ }
                        )
                    },
                    enabled = !isDeletingAccount,
                    colors = ButtonDefaults.buttonColors(containerColor = AlertPulseRed),
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text("Delete Forever", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteAccountDialog = false
                        viewModel.clearDeleteAccountError()
                    },
                    enabled = !isDeletingAccount,
                    modifier = Modifier.testTag("cancel_delete_account_button")
                ) {
                    Text("Cancel", color = CurrentTextMuted)
                }
            },
            containerColor = MinimalWhiteSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showEvidenceVaultScreen) {
        com.example.ui.evidence.EvidenceVaultScreen(
            viewModel = viewModel,
            innerPadding = innerPadding,
            onBack = { showEvidenceVaultScreen = false }
        )
    } else if (showAboutAizaScreen) {
        AboutAizaScreen(
            innerPadding = innerPadding,
            onBack = { showAboutAizaScreen = false }
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MinimalWhiteBackground)
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .testTag("profile_list"),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item { Spacer(modifier = Modifier.height(10.dp)) }

        // 1. User Profile Header Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .clickable { showAvatarSourceDialog = true }
                            .testTag("profile_avatar_image"),
                        contentAlignment = Alignment.Center
                    ) {
                        val avatarBitmap = remember(profileImageUri) {
                            if (profileImageUri.isNotEmpty()) {
                                try {
                                    val file = java.io.File(profileImageUri)
                                    if (file.exists()) {
                                        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                    } else null
                                } catch (e: Exception) {
                                    null
                                }
                            } else null
                        }

                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = "Avatar",
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Avatar",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = name.ifBlank { "AIZA User" },
                        color = CurrentTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (email.isNotBlank()) {
                        Text(
                            text = email,
                            color = CurrentTextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        text = if (cleanPhone.isNotBlank()) PhoneUtils.normalize(cleanPhone) else "No phone set",
                        color = CurrentTextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Avatar Action Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (profileImageUri.isNotEmpty()) {
                            Button(
                                onClick = { viewModel.updateProfileImage("") },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("btn_remove_avatar")
                            ) {
                                Text("Remove", fontSize = 12.sp)
                            }
                        }
                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("btn_gallery_avatar")
                        ) {
                            Text("Choose Photo", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = CurrentBorder)
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showEditProfileDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("edit_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Profile",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "EDIT PROFILE DETAILS",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }



        // 2. Cryptographic Public Key Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "E2EE Public Key",
                                tint = EmeraldDark,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Device E2EE Public Key",
                                color = CurrentTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(publicKey))
                                Toast.makeText(context, "Public key copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Public Key",
                                tint = EmeraldDark,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = publicKey.take(120) + "\n...",
                        color = CurrentTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    )
                }
            }
        }

        // 3. Theme Mode & Brand Accent Palette Card
        item {
            val appTheme by viewModel.appTheme.collectAsState()
            val accentColor by viewModel.accentColor.collectAsState()

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "App Appearance & Theme",
                        color = CurrentTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Choose light/dark mode and select your preferred brand accent color.",
                        color = CurrentTextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                    )

                    // Theme Mode Selector
                    Text(
                        text = "APPEARANCE MODE",
                        color = CurrentTextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf("light" to "☀️ Light", "dark" to "🌙 Dark", "system" to "⚙️ System").forEach { (themeKey, label) ->
                            val isSelected = appTheme == themeKey
                            val buttonBg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            val textColor = if (isSelected) Color.White else CurrentTextPrimary

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(buttonBg)
                                    .clickable { viewModel.setAppTheme(themeKey) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = textColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = CurrentBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Brand Accent Color Selector
                    Text(
                        text = "BRAND ACCENT COLOR",
                        color = CurrentTextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AvailableAccents) { palette ->
                            val isSelected = accentColor.equals(palette.key, ignoreCase = true)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.setAccentColor(palette.key) }
                                    .padding(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(palette.previewColor)
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = palette.name.split(" ").first(),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else CurrentTextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Unified Safety Triggers & System Permissions Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Safety Triggers & System Permissions",
                        color = CurrentTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Manage emergency triggers and system permissions in one place.",
                        color = CurrentTextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 1. Live GPS Location Permission & Guard
                    val hasLocationPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    UnifiedSafetyRow(
                        icon = Icons.Default.LocationOn,
                        title = "GPS Location Access",
                        description = "Required for real-time GPS tracking during emergency alerts",
                        isEnabled = isLocationEnabled,
                        isGranted = hasLocationPermission,
                        onToggle = { enable ->
                            if (enable) {
                                if (hasLocationPermission) {
                                    viewModel.toggleLocationSetting(true)
                                } else {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            } else {
                                viewModel.toggleLocationSetting(false)
                            }
                        },
                        testTag = "safety_location_row"
                    )

                    HorizontalDivider(color = CurrentBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // 2. Emergency Call Permission
                    val hasCallPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CALL_PHONE
                    ) == PackageManager.PERMISSION_GRANTED

                    UnifiedSafetyRow(
                        icon = Icons.Default.Phone,
                        title = "Emergency Phone Calling",
                        description = "Places immediate priority phone calls to guardian contacts",
                        isEnabled = hasCallPermission,
                        isGranted = hasCallPermission,
                        onToggle = { enable ->
                            if (enable) {
                                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                            } else {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            }
                        },
                        testTag = "safety_call_row"
                    )

                    HorizontalDivider(color = CurrentBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // 3. SMS Emergency Messaging Permission
                    val hasSmsPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.SEND_SMS
                    ) == PackageManager.PERMISSION_GRANTED

                    UnifiedSafetyRow(
                        icon = Icons.Default.Sms,
                        title = "SMS Emergency Alerts",
                        description = "Dispatches automated encrypted emergency SMS notifications",
                        isEnabled = hasSmsPermission,
                        isGranted = hasSmsPermission,
                        onToggle = { enable ->
                            if (enable) {
                                smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            } else {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            }
                        },
                        testTag = "safety_sms_row"
                    )

                    HorizontalDivider(color = CurrentBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // 4. Shake-to-SOS Detection
                    UnifiedSafetyRow(
                        icon = Icons.Default.Vibration,
                        title = "Shake-to-SOS Trigger",
                        description = "Triggers SOS countdown on accelerometer shake",
                        isEnabled = isShakeEnabled,
                        isGranted = true,
                        onToggle = { viewModel.toggleShakeSetting(it) },
                        testTag = "shake_sos_switch"
                    )

                    HorizontalDivider(color = CurrentBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // 5. Voice SOS Detection & Microphone
                    val hasMicPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    UnifiedSafetyRow(
                        icon = Icons.Default.Mic,
                        title = "Voice SOS Detection & Mic",
                        description = "Triggers SOS when emergency phrase ('help me') is spoken",
                        isEnabled = isVoiceSosEnabled,
                        isGranted = hasMicPermission,
                        onToggle = { enable ->
                            if (enable) {
                                if (hasMicPermission) {
                                    viewModel.toggleVoiceSosSetting(true)
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            } else {
                                viewModel.toggleVoiceSosSetting(false)
                            }
                        },
                        testTag = "voice_sos_switch"
                    )

                    HorizontalDivider(color = CurrentBorder, modifier = Modifier.padding(vertical = 12.dp))

                    // 6. Bluetooth Hardware SOS
                    val btPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Manifest.permission.BLUETOOTH_CONNECT
                    } else {
                        Manifest.permission.BLUETOOTH
                    }
                    val hasBtPermission = ContextCompat.checkSelfPermission(context, btPerm) == PackageManager.PERMISSION_GRANTED
                    val wearableConnState by viewModel.wearableConnectionState.collectAsState()
                    val connectedWearable by viewModel.connectedWearableDevice.collectAsState()
                    val isScanningWearables by viewModel.isScanningWearables.collectAsState()
                    val isBtConnected = (wearableConnState == com.example.data.BluetoothDeviceConnectionState.CONNECTED)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bluetooth_hardware_sos_section")
                    ) {
                        UnifiedSafetyRow(
                            icon = Icons.Default.Bluetooth,
                            title = "Bluetooth Hardware SOS",
                            description = "Triggers SOS from external Bluetooth panic button device",
                            isEnabled = isBluetoothEnabled,
                            isGranted = hasBtPermission,
                            onToggle = { enable ->
                                if (enable) {
                                    if (hasBtPermission) {
                                        viewModel.toggleBluetoothSetting(true)
                                    } else {
                                        bluetoothPermissionLauncher.launch(btPerm)
                                    }
                                } else {
                                    viewModel.toggleBluetoothSetting(false)
                                }
                            },
                            testTag = "bluetooth_switch"
                        )

                        if (isBluetoothEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, CurrentBorder),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("bluetooth_hardware_controls_card")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Status
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Status",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CurrentTextMuted
                                        )
                                        Text(
                                            text = if (isBtConnected) "Connected" else "Disconnected",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isBtConnected) StatusEmerald else AlertPulseRed,
                                            modifier = Modifier.testTag("bluetooth_hardware_status_text")
                                        )
                                    }

                                    // Device
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Device",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CurrentTextMuted
                                        )
                                        Text(
                                            text = connectedWearable?.deviceName ?: "ESP32 Panic Button / Compatible Device",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = CurrentTextPrimary,
                                            modifier = Modifier.testTag("bluetooth_hardware_device_text")
                                        )
                                    }

                                    // Signal & Battery if connected
                                    if (isBtConnected) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Signal / Battery",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = CurrentTextMuted
                                            )
                                            Text(
                                                text = "BLE Active • 95%",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                color = StatusEmerald,
                                                modifier = Modifier.testTag("bluetooth_hardware_signal_battery_text")
                                            )
                                        }
                                    }

                                    // Connection action
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isBtConnected) "Disconnect Device" else "Pairing & Link",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CurrentTextMuted
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (!isBtConnected && connectedWearable != null) {
                                                Button(
                                                    onClick = {
                                                        if (hasBtPermission) {
                                                            viewModel.startWearableScan()
                                                        } else {
                                                            bluetoothPermissionLauncher.launch(btPerm)
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.testTag("bluetooth_reconnect_button")
                                                ) {
                                                    Text(
                                                        text = "Reconnect",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Button(
                                                onClick = {
                                                    if (isBtConnected) {
                                                        viewModel.disconnectWearableDevice()
                                                    } else {
                                                        if (hasBtPermission) {
                                                            viewModel.startWearableScan()
                                                        } else {
                                                            bluetoothPermissionLauncher.launch(btPerm)
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isBtConnected) AlertPulseRed else MagentaPrimary
                                                ),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("bluetooth_hardware_connection_button")
                                            ) {
                                                Text(
                                                    text = when {
                                                        isBtConnected -> "Disconnect"
                                                        isScanningWearables -> "Scanning..."
                                                        else -> "Connect"
                                                    },
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    // Pairing instructions
                                    HorizontalDivider(color = CurrentBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                                    Text(
                                        text = "Pairing Instructions: Turn on your Bluetooth panic button or wearable. Tap Connect to scan and link. Pressing the physical button will trigger an instant emergency alert.",
                                        fontSize = 10.sp,
                                        color = CurrentTextMuted,
                                        lineHeight = 14.sp,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .testTag("bluetooth_hardware_instructions")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Safety & System Settings Links
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_navigation_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Safety & App Details",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = CurrentTextPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )



                    // Row: Secure Evidence Vault (PQC)
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showEvidenceVaultScreen = true }
                            .testTag("settings_row_evidence_vault")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(StatusEmerald.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Secure Evidence Vault",
                                    tint = StatusEmerald,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Secure Evidence Vault",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = CurrentTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "PQC ML-KEM & ML-DSA protected evidence",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CurrentTextMuted
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = CurrentTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = CurrentBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))

                    // Row 2: About
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAboutAizaScreen = true }
                            .testTag("settings_row_about")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "About",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "About",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = CurrentTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Learn about AIZA, its technology and safety features",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CurrentTextMuted
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Navigate to About AIZA",
                                tint = CurrentTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 6. Account Actions: Logout & Permanent Delete Account
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                border = BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_actions_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Account Session & Security",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = CurrentTextPrimary
                    )

                    // Logout Button (ends current session, keeps account intact)
                    Button(
                        onClick = { showLogoutConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("logout_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ExitToApp,
                                contentDescription = "Logout",
                                tint = CurrentTextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "LOG OUT",
                                color = CurrentTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Delete Account Button (permanently wipes user from Supabase and purges cloud evidence)
                    OutlinedButton(
                        onClick = { showDeleteAccountDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertPulseRed),
                        border = BorderStroke(1.dp, AlertPulseRed.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("delete_account_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "Delete Account",
                                tint = AlertPulseRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "DELETE ACCOUNT PERMANENTLY",
                                color = AlertPulseRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 7. About App Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAboutAizaScreen = true }
                    .testTag("about_app_section")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "About App",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("about_app_header")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "AIZA",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("about_app_name")
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Your Safety, Our Priority",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("about_app_tagline")
                    )

                    HorizontalDivider(
                        color = CurrentBorder.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(160.dp)
                    )

                    Text(
                        text = "Developed by Tam",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.4.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("about_app_developer")
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
    }
}

@Composable
fun HistoryLogCard(log: SosLogEntity) {
    val date = Date(log.timestamp)
    val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm:ss", Locale.getDefault())

    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, CurrentBorder),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatter.format(date),
                    color = CurrentTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(EmeraldDark.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("RESOLVED", color = EmeraldDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tracking Coordinates: ${String.format("%.5f", log.latitude)}, ${String.format("%.5f", log.longitude)}",
                color = CurrentTextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Active duration: ${log.durationSeconds}s",
                color = CurrentTextMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun UnifiedSafetyRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    isEnabled: Boolean,
    isGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    testTag: String
) {
    val activeAndGranted = isEnabled && isGranted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (activeAndGranted) EmeraldDark else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = CurrentTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (activeAndGranted) EmeraldDark.copy(alpha = 0.18f)
                                else Color.Gray.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (activeAndGranted) "GRANTED" else "OFF",
                            color = if (activeAndGranted) EmeraldDark else CurrentTextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(description, color = CurrentTextMuted, fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = activeAndGranted,
            onCheckedChange = { onToggle(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = EmeraldDark,
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = CurrentTextMuted.copy(alpha = 0.3f)
            )
        )
    }
}
