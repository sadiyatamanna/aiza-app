package com.example.ui.incident

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.data.EmergencyIncidentEntity
import com.example.data.EmergencyIncidentRecipientEntity
import com.example.domain.AizaEmergencyNotificationManager
import com.example.domain.SafetyRepository
import com.example.ui.theme.AlertPulseRed
import com.example.ui.theme.CurrentBorder
import com.example.ui.theme.CurrentTextMuted
import com.example.ui.theme.CurrentTextPrimary
import com.example.ui.theme.EmergencyCrimsonDark
import com.example.ui.theme.MagentaPrimary
import com.example.ui.theme.MagentaSoftBg
import com.example.ui.theme.MinimalWhiteBackground
import com.example.ui.theme.MinimalWhiteSurface
import com.example.ui.theme.StatusEmerald
import com.example.viewmodel.SafetyViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedIncidentMedicalInfo(
    val bloodGroup: String? = null,
    val allergies: String? = null,
    val conditions: String? = null,
    val notes: String? = null,
    val rawSummary: String
)

object EmergencyIncidentFormatter {

    fun formatTriggerDisplayLabel(triggerSource: String?): String {
        return when (triggerSource?.trim()?.uppercase(Locale.US)) {
            EmergencyIncidentEntity.TRIGGER_MANUAL -> "Manual SOS"
            EmergencyIncidentEntity.TRIGGER_SHAKE -> "Shake SOS"
            EmergencyIncidentEntity.TRIGGER_VOICE -> "Voice SOS"
            EmergencyIncidentEntity.TRIGGER_BLUETOOTH -> "Bluetooth SOS"
            else -> triggerSource?.trim()?.ifBlank { "Emergency SOS" } ?: "Emergency SOS"
        }
    }

    fun formatIncidentDateTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return "Unknown time"
        val now = System.currentTimeMillis()
        val calNow = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val calEvent = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
        val isSameDay = calNow.get(java.util.Calendar.YEAR) == calEvent.get(java.util.Calendar.YEAR) &&
            calNow.get(java.util.Calendar.DAY_OF_YEAR) == calEvent.get(java.util.Calendar.DAY_OF_YEAR)

        return if (isSameDay) {
            val timePart = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
            "Today, $timePart"
        } else {
            SimpleDateFormat("MMM dd, yyyy, h:mm a", Locale.getDefault()).format(Date(timestampMs))
        }
    }

    fun parseMedicalSummary(rawSummary: String?): ParsedIncidentMedicalInfo? {
        val clean = rawSummary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var bloodGroup: String? = null
        var allergies: String? = null
        var conditions: String? = null
        var notes: String? = null

        val segments = clean.split(Regex("[,;\\n]"))
        for (segment in segments) {
            val trimmed = segment.trim()
            val lower = trimmed.lowercase(Locale.US)
            when {
                lower.startsWith("blood group:") ->
                    bloodGroup = trimmed.substringAfter(":").trim().takeIf { it.isNotEmpty() }
                lower.startsWith("blood:") ->
                    bloodGroup = trimmed.substringAfter(":").trim().takeIf { it.isNotEmpty() }
                lower.startsWith("allergies:") || lower.startsWith("allergy:") ->
                    allergies = trimmed.substringAfter(":").trim().takeIf { it.isNotEmpty() }
                lower.startsWith("conditions:") || lower.startsWith("condition:") ->
                    conditions = trimmed.substringAfter(":").trim().takeIf { it.isNotEmpty() }
                lower.startsWith("notes:") || lower.startsWith("note:") ->
                    notes = trimmed.substringAfter(":").trim().takeIf { it.isNotEmpty() }
            }
        }

        return ParsedIncidentMedicalInfo(
            bloodGroup = bloodGroup,
            allergies = allergies,
            conditions = conditions,
            notes = notes,
            rawSummary = clean
        )
    }
}

@Composable
fun EmergencyIncidentDetailsScreen(
    incidentId: String,
    incident: EmergencyIncidentEntity?,
    recipient: EmergencyIncidentRecipientEntity? = null,
    senderName: String? = null,
    senderPhone: String? = null,
    isLoading: Boolean = false,
    innerPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit,
    onOpenInMap: (EmergencyIncidentEntity) -> Unit = {},
    onCallSender: (EmergencyIncidentEntity) -> Unit = {}
) {
    BackHandler { onBack() }

    val isValidId = remember(incidentId) {
        SafetyRepository.isRealAuthenticatedUserUuid(incidentId.trim())
    }
    val matchedIncident = remember(incident, incidentId, isValidId) {
        if (isValidId && incident != null && incident.id.trim().equals(incidentId.trim(), ignoreCase = true)) {
            incident
        } else {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalWhiteBackground)
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("emergency_incident_details_screen")
    ) {
        // Top Bar with Back Action & Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("emergency_incident_details_placeholder"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("emergency_incident_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = CurrentTextPrimary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🚨 AIZA EMERGENCY",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = AlertPulseRed,
                    modifier = Modifier.testTag("emergency_details_header")
                )
                Text(
                    text = AizaEmergencyNotificationManager.EMERGENCY_NOTIFICATION_TITLE,
                    style = MaterialTheme.typography.labelSmall,
                    color = CurrentTextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (!isValidId || (matchedIncident == null && !isLoading)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("emergency_incident_not_found_state"),
                colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
                border = BorderStroke(1.dp, CurrentBorder),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Incident Unavailable",
                        tint = AlertPulseRed,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "Emergency Incident Unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CurrentTextPrimary
                    )
                    Text(
                        text = "The requested emergency incident could not be found or the incident ID is invalid.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CurrentTextMuted,
                        textAlign = TextAlign.Center
                    )
                    if (isValidId) {
                        Text(
                            text = "Incident ID: ${incidentId.trim()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = CurrentTextMuted,
                            modifier = Modifier.testTag("emergency_incident_id_text")
                        )
                    }
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Return")
                    }
                }
            }
            return@Column
        }

        if (matchedIncident == null && isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = MagentaPrimary)
                if (isValidId) {
                    Text(
                        text = "Incident ID: ${incidentId.trim()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = CurrentTextMuted,
                        modifier = Modifier.testTag("emergency_incident_id_text")
                    )
                }
            }
            return@Column
        }

        val currentIncident = matchedIncident ?: return@Column
        val normalizedStatus = currentIncident.status.trim().uppercase(Locale.US)
        val isActive = normalizedStatus == EmergencyIncidentEntity.STATUS_ACTIVE
        val isResolved = normalizedStatus == EmergencyIncidentEntity.STATUS_RESOLVED
        val statusColor = when {
            isActive -> AlertPulseRed
            isResolved -> StatusEmerald
            else -> CurrentTextMuted
        }
        val resolvedSenderDisplay = AizaEmergencyNotificationManager.sanitizeSenderName(senderName)
            ?: "Connected AIZA User"
        val triggerCanonical = currentIncident.triggerSource.trim().uppercase(Locale.US)
        val triggerFriendly = EmergencyIncidentFormatter.formatTriggerDisplayLabel(triggerCanonical)
        val formattedStartedAt = EmergencyIncidentFormatter.formatIncidentDateTime(currentIncident.startedAt)
        val hasValidLocation = AizaEmergencyNotificationManager.isIncidentLocationAvailable(currentIncident)

        // 1. Primary Incident Summary Card (Sender, Status, Trigger, Date/Time)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("emergency_incident_summary_card"),
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) {
                    AlertPulseRed.copy(alpha = 0.08f)
                } else {
                    MinimalWhiteSurface
                }
            ),
            border = BorderStroke(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) AlertPulseRed else CurrentBorder
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🚨 Emergency from $resolvedSenderDisplay",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = CurrentTextPrimary,
                            modifier = Modifier.testTag("emergency_incident_sender_name")
                        )
                        Text(
                            text = "Sender: $resolvedSenderDisplay",
                            style = MaterialTheme.typography.bodySmall,
                            color = CurrentTextMuted
                        )
                    }

                    Surface(
                        color = statusColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, statusColor)
                    ) {
                        Text(
                            text = normalizedStatus,
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("emergency_incident_status_badge")
                        )
                    }
                }

                HorizontalDivider(color = CurrentBorder.copy(alpha = 0.6f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Trigger Source",
                            fontSize = 11.sp,
                            color = CurrentTextMuted
                        )
                        Text(
                            text = "$triggerFriendly ($triggerCanonical)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CurrentTextPrimary,
                            modifier = Modifier.testTag("emergency_incident_trigger_text")
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Date / Time",
                            fontSize = 11.sp,
                            color = CurrentTextMuted
                        )
                        Text(
                            text = formattedStartedAt,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CurrentTextPrimary,
                            modifier = Modifier.testTag("emergency_incident_datetime_text")
                        )
                    }
                }

                Text(
                    text = "Status: $normalizedStatus",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    modifier = Modifier.testTag("emergency_incident_status_text")
                )

                Text(
                    text = "Incident ID: ${currentIncident.id}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = CurrentTextMuted,
                    modifier = Modifier.testTag("emergency_incident_id_text")
                )

                recipient?.let { rec ->
                    Text(
                        text = "Guardian Delivery: ${rec.deliveryStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CurrentTextMuted,
                        modifier = Modifier.testTag("emergency_incident_recipient_status_text")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. LOCATION Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("emergency_incident_location_section"),
            colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
            border = BorderStroke(1.dp, CurrentBorder),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasValidLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                        contentDescription = "Emergency Location",
                        tint = if (hasValidLocation) StatusEmerald else AlertPulseRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LOCATION",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = CurrentTextPrimary
                    )
                }

                if (hasValidLocation) {
                    val lat = currentIncident.latitude!!
                    val lng = currentIncident.longitude!!
                    val locStatus = currentIncident.locationStatus
                        ?.takeIf { it.isNotBlank() }
                        ?: EmergencyIncidentEntity.LOCATION_STATUS_LIVE

                    Text(
                        text = AizaEmergencyNotificationManager.LOCATION_AVAILABLE_TEXT,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = StatusEmerald,
                        modifier = Modifier.testTag("emergency_incident_location_status_text")
                    )

                    // AIZA Map Location Preview Box
                    Surface(
                        color = MagentaSoftBg.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MagentaPrimary.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("emergency_incident_map_preview")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(StatusEmerald)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "AIZA Emergency Radar Pin • $locStatus",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CurrentTextPrimary
                                    )
                                }
                            }
                            Text(
                                text = String.format(Locale.US, "%.5f, %.5f", lat, lng),
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = MagentaPrimary,
                                modifier = Modifier.testTag("emergency_incident_coordinates_text")
                            )
                        }
                    }

                    Button(
                        onClick = { onOpenInMap(currentIncident) },
                        colors = ButtonDefaults.buttonColors(containerColor = MagentaPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("emergency_open_in_map_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = "Open in AIZA Map",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open in AIZA Map",
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = AizaEmergencyNotificationManager.LOCATION_UNAVAILABLE_TEXT,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AlertPulseRed,
                        modifier = Modifier.testTag("emergency_incident_location_status_text")
                    )
                    Text(
                        text = "No GPS coordinates were captured for this emergency incident.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CurrentTextMuted,
                        modifier = Modifier.testTag("emergency_incident_location_unavailable_text")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Telemetry Section (Battery, Network, Location Status, Audio)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("emergency_incident_telemetry_section"),
            colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
            border = BorderStroke(1.dp, CurrentBorder),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "INCIDENT TELEMETRY",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = CurrentTextPrimary
                )

                // Battery Row
                val batteryText = currentIncident.batteryPercent?.let { "$it%" } ?: "Not available"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryStd,
                            contentDescription = "Battery",
                            tint = MagentaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🔋 Battery",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CurrentTextPrimary
                        )
                    }
                    Text(
                        text = batteryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CurrentTextPrimary,
                        modifier = Modifier.testTag("emergency_incident_battery_text")
                    )
                }

                HorizontalDivider(color = CurrentBorder.copy(alpha = 0.5f))

                // Network Row
                val networkText = currentIncident.networkStatus?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NetworkCell,
                            contentDescription = "Network",
                            tint = MagentaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "📶 Network",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CurrentTextPrimary
                        )
                    }
                    Text(
                        text = networkText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CurrentTextPrimary,
                        modifier = Modifier.testTag("emergency_incident_network_text")
                    )
                }

                HorizontalDivider(color = CurrentBorder.copy(alpha = 0.5f))

                // Location Status Row
                val locationFreshness = currentIncident.locationStatus
                    ?.takeIf { it.isNotBlank() }
                    ?: EmergencyIncidentEntity.LOCATION_STATUS_UNKNOWN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location Status",
                            tint = MagentaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "📍 Location status",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CurrentTextPrimary
                        )
                    }
                    Text(
                        text = locationFreshness,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CurrentTextPrimary,
                        modifier = Modifier.testTag("emergency_incident_location_freshness_text")
                    )
                }

                HorizontalDivider(color = CurrentBorder.copy(alpha = 0.5f))

                // Audio Status Row
                val audioStatus = currentIncident.audioRecordingStatus
                    .trim()
                    .ifBlank { EmergencyIncidentEntity.AUDIO_STATUS_NOT_AVAILABLE }
                val hasUploadedAudioFile = !currentIncident.audioStoragePath.isNullOrBlank()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Audio Status",
                            tint = MagentaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🎤 Audio",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CurrentTextPrimary
                        )
                    }
                    Text(
                        text = audioStatus,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CurrentTextPrimary,
                        modifier = Modifier.testTag("emergency_incident_audio_status_text")
                    )
                }

                if (!hasUploadedAudioFile) {
                    Text(
                        text = "No audio file uploaded — audio playback is not available.",
                        fontSize = 11.sp,
                        color = CurrentTextMuted,
                        modifier = Modifier.testTag("emergency_incident_audio_not_playable_text")
                    )
                }
            }
        }

        // 4. ACTIONS Section (Call Sender)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("emergency_incident_actions_section"),
            colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
            border = BorderStroke(1.dp, CurrentBorder),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "GUARDIAN RESPONSE ACTIONS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = CurrentTextPrimary
                )

                val canCall = !senderPhone.isNullOrBlank()
                Button(
                    onClick = { onCallSender(currentIncident) },
                    enabled = canCall,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("emergency_call_sender_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Call Sender",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Call Sender",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun EmergencyIncidentDetailsRoute(
    incidentId: String,
    viewModel: SafetyViewModel,
    innerPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit,
    onNavigateToMap: () -> Unit = {},
    onNavigateToContacts: () -> Unit = {}
) {
    val context = LocalContext.current
    val selectedIncident by viewModel.selectedEmergencyIncident.collectAsState()
    val selectedRecipient by viewModel.selectedEmergencyIncidentRecipient.collectAsState()
    val isNotFound by viewModel.selectedEmergencyIncidentNotFound.collectAsState()
    val activeIncidentId by viewModel.selectedEmergencyIncidentId.collectAsState()
    val rawLoading by viewModel.isEmergencyIncidentDetailsLoading.collectAsState()
    val isLoading = rawLoading && !isNotFound
    val senderNames by viewModel.incidentSenderNames.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    LaunchedEffect(incidentId) {
        val alreadyActive = activeIncidentId?.equals(incidentId, ignoreCase = true) == true
        if (SafetyRepository.isRealAuthenticatedUserUuid(incidentId) &&
            !alreadyActive &&
            (selectedIncident == null || !selectedIncident!!.id.equals(incidentId, ignoreCase = true))
        ) {
            viewModel.openEmergencyIncidentDetails(incidentId)
        }
    }

    val resolvedSenderName = remember(selectedIncident, senderNames, contacts) {
        selectedIncident?.let { viewModel.resolveSenderNameForIncident(it) }
    }
    val resolvedSenderPhone = remember(selectedIncident, contacts) {
        selectedIncident?.let { viewModel.resolvePhoneForIncidentSender(it) }
    }

    EmergencyIncidentDetailsScreen(
        incidentId = incidentId,
        incident = selectedIncident,
        recipient = selectedRecipient,
        senderName = resolvedSenderName,
        senderPhone = resolvedSenderPhone,
        isLoading = isLoading,
        innerPadding = innerPadding,
        onBack = onBack,
        onOpenInMap = { inc ->
            if (viewModel.openEmergencyIncidentInMap(inc)) {
                onNavigateToMap()
            }
        },
        onCallSender = { inc ->
            viewModel.dialIncidentSenderIfAvailable(context, inc)
        }
    )
}
