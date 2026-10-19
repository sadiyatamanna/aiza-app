package com.example.ui.evidence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.data.evidence.EvidenceCaptureManager
import com.example.data.evidence.EvidenceType
import com.example.ui.theme.*
import com.example.viewmodel.SafetyViewModel
import java.io.File
import java.io.FileOutputStream

@Composable
fun CaptureEvidenceDialog(
    viewModel: SafetyViewModel,
    onDismiss: () -> Unit,
    preselectedSosEventId: String? = null
) {
    val context = LocalContext.current

    val isRecording by EvidenceCaptureManager.isRecording.collectAsState()
    val recordingDuration by EvidenceCaptureManager.recordingDurationSeconds.collectAsState()
    val recordingType by EvidenceCaptureManager.currentRecordingType.collectAsState()

    var activeSosId by remember {
        mutableStateOf(preselectedSosEventId ?: viewModel.activeIncidentId.value)
    }

    var pendingPhotoFile by remember { mutableStateOf<File?>(null) }
    var pendingVideoFile by remember { mutableStateOf<File?>(null) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Launcher for taking high-res photo via FileProvider
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingPhotoFile
        if (success && file != null && file.exists() && file.length() > 0) {
            viewModel.protectAndStoreEvidence(
                rawFile = file,
                evidenceType = EvidenceType.PHOTO,
                mimeType = "image/jpeg",
                sosEventId = activeSosId
            )
            onDismiss()
        } else {
            file?.delete()
            pendingPhotoFile = null
        }
    }

    // Fallback thumbnail capture if TakePicture URI is not available
    val takePreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            val file = EvidenceCaptureManager.createTempCaptureFile(context, EvidenceType.PHOTO)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            viewModel.protectAndStoreEvidence(
                rawFile = file,
                evidenceType = EvidenceType.PHOTO,
                mimeType = "image/jpeg",
                sosEventId = activeSosId
            )
            onDismiss()
        }
    }

    // Launcher for video capture via FileProvider
    val captureVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        val file = pendingVideoFile
        if (success && file != null && file.exists() && file.length() > 0) {
            viewModel.protectAndStoreEvidence(
                rawFile = file,
                evidenceType = EvidenceType.VIDEO,
                mimeType = "video/mp4",
                sosEventId = activeSosId
            )
            onDismiss()
        } else {
            file?.delete()
            pendingVideoFile = null
        }
    }

    // Permission launchers
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val file = EvidenceCaptureManager.createTempCaptureFile(context, EvidenceType.PHOTO)
                pendingPhotoFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                takePictureLauncher.launch(uri)
            } catch (_: Exception) {
                takePreviewLauncher.launch(null)
            }
        } else {
            errorMessage = "Camera permission is required to capture photo evidence."
        }
    }

    val videoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val camGranted = permissions[Manifest.permission.CAMERA] == true
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (camGranted && micGranted) {
            try {
                val file = EvidenceCaptureManager.createTempCaptureFile(context, EvidenceType.VIDEO)
                pendingVideoFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                captureVideoLauncher.launch(uri)
            } catch (e: Exception) {
                errorMessage = "Failed to open video recorder: ${e.message}"
            }
        } else {
            errorMessage = "Camera and microphone permissions are required for video evidence."
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val result = EvidenceCaptureManager.startAudioRecording(context)
            if (result.isFailure) {
                errorMessage = result.exceptionOrNull()?.message ?: "Audio recording failed"
            }
        } else {
            errorMessage = "Microphone permission is required to record audio evidence."
        }
    }

    Dialog(onDismissRequest = {
        if (isRecording) {
            EvidenceCaptureManager.cancelRecording()
        }
        onDismiss()
    }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
            border = BorderStroke(1.dp, CurrentBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("capture_evidence_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MagentaPrimary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MagentaPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Capture Evidence",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CurrentTextPrimary,
                                modifier = Modifier.testTag("capture_dialog_title")
                            )
                            Text(
                                text = "PQC ML-KEM & ML-DSA Protected",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isRecording) EvidenceCaptureManager.cancelRecording()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("capture_dialog_close_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = CurrentTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Error message if any
                errorMessage?.let { err ->
                    Surface(
                        color = EmergencyCrimson.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("capture_dialog_error_banner")
                    ) {
                        Text(
                            text = err,
                            color = EmergencyCrimson,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // SOS Event Link Status Chip
                Surface(
                    color = if (!activeSosId.isNullOrBlank()) StatusEmerald.copy(alpha = 0.1f) else CurrentBorder.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (!activeSosId.isNullOrBlank()) StatusEmerald.copy(alpha = 0.4f) else CurrentBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("capture_dialog_sos_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = if (!activeSosId.isNullOrBlank()) StatusEmerald else CurrentTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!activeSosId.isNullOrBlank()) {
                                "Linked to SOS: ${activeSosId!!.take(8)}..."
                            } else {
                                "Standalone Emergency Evidence"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!activeSosId.isNullOrBlank()) StatusEmerald else CurrentTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // RECORDING IN PROGRESS VIEW
                if (isRecording) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )

                    Surface(
                        color = EmergencyCrimson.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, EmergencyCrimson),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("active_recording_indicator_panel")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(EmergencyCrimson.copy(alpha = pulseAlpha))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "AUDIO RECORDING ACTIVE",
                                    color = EmergencyCrimson,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Timer display
                            val minutes = recordingDuration / 60
                            val seconds = recordingDuration % 60
                            val timeStr = String.format("%02d:%02d", minutes, seconds)

                            Text(
                                text = timeStr,
                                color = CurrentTextPrimary,
                                fontSize = 32.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("recording_timer_text")
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        EvidenceCaptureManager.cancelRecording()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("cancel_recording_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Discard", color = CurrentTextMuted)
                                }

                                Button(
                                    onClick = {
                                        val recordedFile = EvidenceCaptureManager.stopRecording()
                                        if (recordedFile != null && recordedFile.exists()) {
                                            viewModel.protectAndStoreEvidence(
                                                rawFile = recordedFile,
                                                evidenceType = EvidenceType.AUDIO,
                                                mimeType = "audio/mp4",
                                                sosEventId = activeSosId
                                            )
                                            onDismiss()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .testTag("stop_recording_button")
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Stop & Secure", fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                } else {
                    // THREE CAPTURE OPTIONS
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1. Photo Capture Option
                        CaptureOptionCard(
                            title = "Photo Capture",
                            description = "Instant camera snapshot encrypted with AES-256-GCM",
                            icon = Icons.Default.CameraAlt,
                            accentColor = MagentaPrimary,
                            testTag = "capture_option_photo",
                            onClick = {
                                errorMessage = null
                                if (!EvidenceCaptureManager.checkStorageAvailable(context)) {
                                    errorMessage = "Insufficient storage space to record evidence"
                                    return@CaptureOptionCard
                                }
                                if (EvidenceCaptureManager.hasCameraPermission(context)) {
                                    try {
                                        val file = EvidenceCaptureManager.createTempCaptureFile(context, EvidenceType.PHOTO)
                                        pendingPhotoFile = file
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        takePictureLauncher.launch(uri)
                                    } catch (_: Exception) {
                                        takePreviewLauncher.launch(null)
                                    }
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )

                        // 2. Audio Recording Option
                        CaptureOptionCard(
                            title = "Audio Recording",
                            description = "Record ambient sounds and emergency speech with live timer",
                            icon = Icons.Default.Mic,
                            accentColor = EmergencyCrimson,
                            testTag = "capture_option_audio",
                            onClick = {
                                errorMessage = null
                                if (!EvidenceCaptureManager.checkStorageAvailable(context)) {
                                    errorMessage = "Insufficient storage space to record evidence"
                                    return@CaptureOptionCard
                                }
                                if (EvidenceCaptureManager.hasAudioPermission(context)) {
                                    val result = EvidenceCaptureManager.startAudioRecording(context)
                                    if (result.isFailure) {
                                        errorMessage = result.exceptionOrNull()?.message
                                    }
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        )

                        // 3. Video Recording Option
                        CaptureOptionCard(
                            title = "Video Recording",
                            description = "Record video clip with synced audio and quantum-resistant signature",
                            icon = Icons.Default.Videocam,
                            accentColor = StatusEmerald,
                            testTag = "capture_option_video",
                            onClick = {
                                errorMessage = null
                                if (!EvidenceCaptureManager.checkStorageAvailable(context)) {
                                    errorMessage = "Insufficient storage space to record evidence"
                                    return@CaptureOptionCard
                                }
                                val hasCam = EvidenceCaptureManager.hasCameraPermission(context)
                                val hasMic = EvidenceCaptureManager.hasAudioPermission(context)
                                if (hasCam && hasMic) {
                                    try {
                                        val file = EvidenceCaptureManager.createTempCaptureFile(context, EvidenceType.VIDEO)
                                        pendingVideoFile = file
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        captureVideoLauncher.launch(uri)
                                    } catch (e: Exception) {
                                        errorMessage = "Video capture unavailable: ${e.message}"
                                    }
                                } else {
                                    videoPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CaptureOptionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MinimalWhiteSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = CurrentTextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CurrentTextMuted,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
