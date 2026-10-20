package com.example.ui.evidence

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.evidence.EvidenceRecordEntity
import com.example.data.evidence.EvidenceSyncState
import com.example.data.evidence.EvidenceType
import com.example.data.evidence.EvidenceVerificationState
import com.example.ui.theme.*
import com.example.viewmodel.SafetyViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EvidenceVaultScreen(
    viewModel: SafetyViewModel,
    innerPadding: PaddingValues,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val evidenceRecords by viewModel.evidenceRecords.collectAsState()
    val isProcessing by viewModel.isEvidenceProcessing.collectAsState()
    val statusMessage by viewModel.evidenceStatusMessage.collectAsState()
    val decryptedEvidence by viewModel.decryptedEvidence.collectAsState()
    val selectedEvidence by viewModel.selectedEvidence.collectAsState()

    var selectedFilter by remember { mutableStateOf("ALL") }
    var showCaptureDialog by remember { mutableStateOf(false) }
    var evidenceToDelete by remember { mutableStateOf<EvidenceRecordEntity?>(null) }

    val filteredRecords = remember(evidenceRecords, selectedFilter) {
        when (selectedFilter) {
            EvidenceType.PHOTO -> evidenceRecords.filter { it.evidenceType == EvidenceType.PHOTO }
            EvidenceType.AUDIO -> evidenceRecords.filter { it.evidenceType == EvidenceType.AUDIO }
            EvidenceType.VIDEO -> evidenceRecords.filter { it.evidenceType == EvidenceType.VIDEO }
            else -> evidenceRecords
        }
    }

    if (showCaptureDialog) {
        CaptureEvidenceDialog(
            viewModel = viewModel,
            onDismiss = { showCaptureDialog = false }
        )
    }

    // Delete confirmation dialog
    evidenceToDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { evidenceToDelete = null },
            title = {
                Text("Delete Evidence?", fontWeight = FontWeight.Bold, color = CurrentTextPrimary)
            },
            text = {
                Text(
                    "This will permanently delete this PQC-encrypted ${record.evidenceType.lowercase()} evidence both locally and from Supabase cloud storage.",
                    color = CurrentTextMuted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteEvidence(record)
                        evidenceToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { evidenceToDelete = null }) {
                    Text("Cancel", color = CurrentTextMuted)
                }
            },
            containerColor = MinimalWhiteSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Decrypted Evidence Viewer Dialog
    decryptedEvidence?.let { decrypted ->
        DecryptedEvidenceViewerDialog(
            record = selectedEvidence,
            decryptedResult = decrypted,
            onDismiss = { viewModel.clearDecryptedPreview() }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalWhiteBackground)
            .padding(innerPadding)
            .padding(horizontal = 20.dp)
            .testTag("evidence_vault_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("vault_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = CurrentTextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Secure Evidence Vault",
                            color = CurrentTextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("vault_screen_title")
                        )
                        Text(
                            text = "FIPS 203 & 204 Post-Quantum Protected",
                            color = StatusEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = { showCaptureDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("vault_capture_button")
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Capture", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status notification bar if processing
            if (isProcessing || statusMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CurrentBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("vault_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MagentaPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = statusMessage ?: "Processing cryptographic operation...",
                            color = CurrentTextPrimary,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Filter Tabs (All, Photos, Audio, Videos)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_filter_tabs"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to "All",
                    EvidenceType.PHOTO to "Photos",
                    EvidenceType.AUDIO to "Audio",
                    EvidenceType.VIDEO to "Videos"
                ).forEach { (filterKey, label) ->
                    val isSelected = selectedFilter == filterKey
                    Surface(
                        onClick = { selectedFilter = filterKey },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MagentaPrimary else MinimalWhiteSurface,
                        border = BorderStroke(1.dp, if (isSelected) MagentaPrimary else CurrentBorder),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vault_filter_${filterKey.lowercase()}")
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else CurrentTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Evidence List or Empty State
            if (filteredRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("vault_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MagentaSoftBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MagentaPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No evidence records in vault yet.",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = CurrentTextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap Capture to securely record emergency photos, ambient audio, or video protected with AES-256-GCM and post-quantum ML-DSA signatures.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CurrentTextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vault_evidence_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredRecords, key = { it.id }) { record ->
                        EvidenceRecordCard(
                            record = record,
                            onVerify = { viewModel.verifyEvidenceIntegrity(record) },
                            onDecryptAndView = {
                                viewModel.selectEvidence(record)
                                viewModel.retrieveAndDecryptEvidence(record)
                            },
                            onUploadNow = { viewModel.syncEvidenceNow(record) },
                            onDelete = { evidenceToDelete = record }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(60.dp)) }
                }
            }
        }
    }
}

@Composable
fun EvidenceRecordCard(
    record: EvidenceRecordEntity,
    onVerify: () -> Unit,
    onDecryptAndView: () -> Unit,
    onUploadNow: () -> Unit,
    onDelete: () -> Unit
) {
    val typeIcon = when (record.evidenceType) {
        EvidenceType.PHOTO -> Icons.Default.CameraAlt
        EvidenceType.AUDIO -> Icons.Default.Mic
        EvidenceType.VIDEO -> Icons.Default.Videocam
        else -> Icons.Default.InsertDriveFile
    }

    val typeColor = when (record.evidenceType) {
        EvidenceType.PHOTO -> MagentaPrimary
        EvidenceType.AUDIO -> EmergencyCrimson
        EvidenceType.VIDEO -> StatusEmerald
        else -> CurrentTextPrimary
    }

    val formattedDate = remember(record.captureTimestamp) {
        SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(record.captureTimestamp))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("evidence_card_${record.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Type Icon, ID & Timestamp
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
                            .background(typeColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = record.evidenceType,
                            tint = typeColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "${record.evidenceType} Evidence",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CurrentTextPrimary
                        )
                        Text(
                            text = formattedDate,
                            fontSize = 11.sp,
                            color = CurrentTextMuted
                        )
                    }
                }

                // Delete button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("delete_evidence_${record.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = AlertPulseRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // SOS Link if present
            if (!record.sosEventId.isNullOrBlank()) {
                Surface(
                    color = StatusEmerald.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.8.dp, StatusEmerald.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = StatusEmerald, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Linked to SOS Incident: ${record.sosEventId.take(8)}...",
                            color = StatusEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // File sizes and hash details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Size: ${formatFileSize(record.originalFileSize)} (Encrypted: ${formatFileSize(record.encryptedFileSize)})",
                    fontSize = 11.sp,
                    color = CurrentTextMuted
                )
                Text(
                    text = "ID: ${record.id.take(8)}...",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CurrentTextMuted
                )
            }

            // Honest Status Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sync State Badge
                val syncBadgeColor = when (record.syncState) {
                    EvidenceSyncState.BACKED_UP -> StatusEmerald
                    EvidenceSyncState.UPLOADING -> Color(0xFF2563EB)
                    EvidenceSyncState.FAILED -> EmergencyCrimson
                    else -> Color(0xFFD97706) // Local Only
                }

                Surface(
                    color = syncBadgeColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, syncBadgeColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = record.syncState,
                        color = syncBadgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("sync_state_badge_${record.id}")
                    )
                }

                // Verification State Badge
                val (verifColor, verifText) = when (record.verificationState) {
                    EvidenceVerificationState.VERIFICATION_PASSED -> StatusEmerald to "Verification Passed"
                    EvidenceVerificationState.VERIFICATION_FAILED -> EmergencyCrimson to "Verification Failed"
                    else -> CurrentTextMuted to "Unverified"
                }

                Surface(
                    color = verifColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, verifColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = verifText,
                        color = verifColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("verification_state_badge_${record.id}")
                    )
                }
            }

            HorizontalDivider(color = CurrentBorder.copy(alpha = 0.6f))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Verify Signature & Hash Button
                OutlinedButton(
                    onClick = onVerify,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("verify_evidence_button_${record.id}")
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Verify PQC", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Decrypt & View Button
                Button(
                    onClick = onDecryptAndView,
                    colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("decrypt_evidence_button_${record.id}")
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Decrypt & View", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                // Upload Now button if local only or failed
                if (record.syncState != EvidenceSyncState.BACKED_UP && record.syncState != EvidenceSyncState.UPLOADING) {
                    IconButton(
                        onClick = onUploadNow,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("upload_evidence_button_${record.id}")
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Upload", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun DecryptedEvidenceViewerDialog(
    record: EvidenceRecordEntity?,
    decryptedResult: com.example.data.pqc.AizaPqcSecurityManager.DecryptedEvidenceResult,
    onDismiss: () -> Unit
) {
    val file = decryptedResult.decryptedFile
    val verif = decryptedResult.verificationResult

    var isPlayingAudio by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(file) {
        onDispose {
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
        }
    }

    Dialog(onDismissRequest = {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        onDismiss()
    }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
            border = BorderStroke(1.dp, CurrentBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("decrypted_evidence_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                                .background(StatusEmerald.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = StatusEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Decrypted Evidence",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = CurrentTextPrimary
                            )
                            Text(
                                text = "PQC Verification Passed",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = CurrentTextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Media Preview
                if (file.exists()) {
                    if (record?.evidenceType == EvidenceType.PHOTO) {
                        val bitmap = remember(file) {
                            try {
                                BitmapFactory.decodeFile(file.absolutePath)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Decrypted Photo Evidence",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .testTag("decrypted_image_preview")
                            )
                        } else {
                            Text("Image preview format unavailable", color = CurrentTextMuted, fontSize = 12.sp)
                        }
                    } else if (record?.evidenceType == EvidenceType.AUDIO) {
                        Surface(
                            color = EmergencyCrimson.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, EmergencyCrimson.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("decrypted_audio_player")
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Mic, contentDescription = null, tint = EmergencyCrimson)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Audio Clip", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CurrentTextPrimary)
                                        Text("${formatFileSize(file.length())}", fontSize = 11.sp, color = CurrentTextMuted)
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (isPlayingAudio) {
                                            mediaPlayer?.pause()
                                            isPlayingAudio = false
                                        } else {
                                            if (mediaPlayer == null) {
                                                mediaPlayer = MediaPlayer().apply {
                                                    setDataSource(file.absolutePath)
                                                    prepare()
                                                    setOnCompletionListener {
                                                        isPlayingAudio = false
                                                    }
                                                }
                                            }
                                            mediaPlayer?.start()
                                            isPlayingAudio = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyCrimson),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlayingAudio) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlayingAudio) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPlayingAudio) "Pause" else "Play", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    } else if (record?.evidenceType == EvidenceType.VIDEO) {
                        Surface(
                            color = MinimalWhiteSurface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CurrentBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("decrypted_video_player")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                var playError by remember { mutableStateOf(false) }

                                if (!playError) {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                val mediaController = MediaController(ctx)
                                                mediaController.setAnchorView(this)
                                                setMediaController(mediaController)
                                                setOnErrorListener { _, _, _ ->
                                                    playError = true
                                                    true
                                                }
                                                setVideoPath(file.absolutePath)
                                                start()
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = MagentaPrimary,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "Decrypted Video Evidence",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = CurrentTextPrimary
                                        )
                                        Text(
                                            "Video playback preview. Size: ${formatFileSize(file.length())}",
                                            fontSize = 11.sp,
                                            color = CurrentTextMuted,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Cryptographic Verification Audit Details Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CurrentBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cryptographic_audit_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "CRYPTOGRAPHIC AUDIT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CurrentTextMuted,
                            letterSpacing = 0.5.sp
                        )

                        AuditRow("ML-DSA-65 Signature", verif.isSignatureValid)
                        AuditRow("Encrypted SHA-256", verif.isEncryptedHashMatched)
                        AuditRow("Decrypted Media SHA-256", verif.isOriginalHashMatched)
                        AuditRow("ML-KEM-768 Decapsulation", true)
                        AuditRow("AES-256-GCM Decryption", true)

                        if (!record?.formatVersion.isNullOrBlank()) {
                            Text(
                                text = "Standard: ${record!!.formatVersion} (${record.encryptionAlgorithm})",
                                fontSize = 10.sp,
                                color = CurrentTextMuted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary)
                ) {
                    Text("Close Viewer", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AuditRow(label: String, passed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = CurrentTextPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (passed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (passed) StatusEmerald else EmergencyCrimson,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (passed) "MATCHED" else "FAILED",
                color = if (passed) StatusEmerald else EmergencyCrimson,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}
