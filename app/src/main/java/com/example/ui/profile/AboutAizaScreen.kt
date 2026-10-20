package com.example.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.ui.theme.CurrentBorder
import com.example.ui.theme.CurrentTextMuted
import com.example.ui.theme.CurrentTextPrimary
import com.example.ui.theme.MagentaPrimary
import com.example.ui.theme.MagentaSoftBg
import com.example.ui.theme.MinimalWhiteBackground
import com.example.ui.theme.MinimalWhiteSurface
import com.example.ui.theme.StatusEmerald

@Composable
fun AboutAizaScreen(
    innerPadding: PaddingValues = PaddingValues(0.dp),
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalWhiteBackground)
            .padding(innerPadding)
            .testTag("about_aiza_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("about_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = CurrentTextPrimary
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "About AIZA",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = CurrentTextPrimary,
                    modifier = Modifier.testTag("about_title_topbar")
                )
            }

            HorizontalDivider(color = CurrentBorder.copy(alpha = 0.5f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .testTag("about_scrollable_content"),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                // 1. Header with existing AIZA logo, Name, Tagline, and Developer
                item {
                    AboutHeaderSection()
                }

                // 2. About AIZA App Description
                item {
                    AboutDescriptionCard()
                }

                // 3. Key Safety Features
                item {
                    AboutSafetyFeaturesSection()
                }

                // 4. Security & Privacy
                item {
                    AboutSecurityPrivacyCard()
                }

                // 5. Post-Quantum Cryptography
                item {
                    AboutPostQuantumSecurityCard()
                }

                // 6. Technology Stack
                item {
                    AboutTechnologySection()
                }

                // 7. Emergency Communication Architecture
                item {
                    AboutEmergencyCommunicationCard()
                }

                // 8. Important Note / Legal & Safety Disclaimer
                item {
                    AboutImportantNoteCard()
                }

                // 9. App Information Key-Value Summary
                item {
                    AboutAppInformationCard()
                }

                item { Spacer(modifier = Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun AboutHeaderSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("about_header_section"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Rounded-square logo container reusing existing working AIZA logo
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, CurrentBorder),
            modifier = Modifier
                .size(100.dp)
                .testTag("about_logo_container")
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MagentaSoftBg.copy(alpha = 0.4f))
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.aegis_launcher_foreground_1784101775779),
                    contentDescription = "AIZA Logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "AIZA",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp
            ),
            color = CurrentTextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("about_app_name_text")
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Your Safety, Our Priority",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = CurrentTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("about_app_tagline_text")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, CurrentBorder.copy(alpha = 0.4f))
        ) {
            Text(
                text = "Developed by Tam",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("about_app_developer_text")
            )
        }
    }
}

@Composable
private fun AboutDescriptionCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_description_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "About AIZA",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_description_title")
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "AIZA is a women's safety and emergency communication application designed to help users quickly respond to dangerous situations and stay connected with trusted contacts.\n\nThe app combines emergency SOS activation, guardian communication, location sharing, emergency incident tracking, and security-focused communication features in one platform. While currently implemented natively on Android, expanded support for additional platforms including iOS is planned.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_description_text")
            )
        }
    }
}

@Composable
private fun AboutSafetyFeaturesSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_safety_features_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Safety Features",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = CurrentTextPrimary,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .testTag("about_safety_features_title")
        )

        val features = listOf(
            SafetyFeatureItem(
                title = "Manual SOS",
                description = "Quickly activate an emergency response from the app.",
                icon = Icons.Default.Warning
            ),
            SafetyFeatureItem(
                title = "Shake SOS",
                description = "Automatically trigger an emergency when the configured shake pattern is detected.",
                icon = Icons.Default.Vibration
            ),
            SafetyFeatureItem(
                title = "Voice SOS",
                description = "Use the configured voice trigger to activate an emergency response.",
                icon = Icons.Default.Mic
            ),
            SafetyFeatureItem(
                title = "Bluetooth SOS",
                description = "Compatible Bluetooth safety devices can be connected to AIZA and used as an additional emergency activation method. The AIZA phone remains responsible for processing the emergency and communicating with connected guardians.",
                icon = Icons.Default.Bluetooth
            ),
            SafetyFeatureItem(
                title = "Guardian Communication",
                description = "Stay connected with trusted AIZA users through guardian pairing and one-to-one chat.",
                icon = Icons.Default.ContactPhone
            ),
            SafetyFeatureItem(
                title = "Emergency Location",
                description = "Share and view emergency location information when available.",
                icon = Icons.Default.LocationOn
            ),
            SafetyFeatureItem(
                title = "Normal Location Sharing",
                description = "Share your current location with a connected guardian through Chat or Maps without triggering an emergency.",
                icon = Icons.Default.Navigation
            ),
            SafetyFeatureItem(
                title = "Emergency History",
                description = "View emergencies sent by you and emergencies received from connected users.",
                icon = Icons.Default.History
            ),
            SafetyFeatureItem(
                title = "Emergency Notifications",
                description = "Receive dedicated AIZA emergency alerts when a connected user sends an emergency.",
                icon = Icons.Default.NotificationsActive
            )
        )

        features.forEach { feature ->
            SafetyFeatureRowCard(item = feature)
        }
    }
}

private data class SafetyFeatureItem(
    val title: String,
    val description: String,
    val icon: ImageVector
)

@Composable
private fun SafetyFeatureRowCard(item: SafetyFeatureItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("safety_feature_item_${item.title.replace(" ", "_").lowercase()}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MagentaSoftBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = MagentaPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "• ",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = CurrentTextPrimary
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = CurrentTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 18.sp
                    ),
                    color = CurrentTextMuted,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun AboutSecurityPrivacyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_security_privacy_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Security",
                    tint = StatusEmerald,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Security & Privacy",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = CurrentTextPrimary,
                    modifier = Modifier.testTag("about_security_privacy_title")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "AIZA is designed with authenticated user relationships and controlled data access.\n\nEmergency and guardian relationships use authenticated user identities and database-level access policies.\n\nSensitive information such as medical information is shared only when the sender has explicitly enabled medical sharing for an emergency incident.\n\nLocation sharing and emergency information are kept separate from normal chat and location-sharing actions.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_security_privacy_text")
            )
        }
    }
}

@Composable
private fun AboutPostQuantumSecurityCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_post_quantum_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Post-Quantum Security",
                    tint = MagentaPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Post-Quantum Security",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = CurrentTextPrimary,
                    modifier = Modifier.testTag("about_post_quantum_title")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "AIZA includes post-quantum cryptography support as part of its security architecture.\n\nThe security architecture uses modern post-quantum cryptographic algorithms through the Bouncy Castle cryptographic library, including ML-KEM and ML-DSA, together with AES-256-GCM for authenticated symmetric encryption.\n\nThese technologies are designed to provide protection against security threats from both current and future computing environments.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_post_quantum_text")
            )
        }
    }
}

@Composable
private fun AboutTechnologySection() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_technology_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Technology",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_technology_title")
            )

            Spacer(modifier = Modifier.height(12.dp))

            val technologies = listOf(
                "Kotlin",
                "Jetpack Compose",
                "Material 3",
                "MVVM Architecture",
                "Kotlin Coroutines / StateFlow",
                "Room Database",
                "Supabase",
                "Bouncy Castle",
                "ML-KEM",
                "ML-DSA",
                "AES-256-GCM",
                "Location Services",
                "Bluetooth communication"
            )

            technologies.forEach { tech ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "•",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MagentaPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tech,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CurrentTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutEmergencyCommunicationCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_emergency_communication_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Emergency Communication",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_emergency_communication_title")
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "AIZA supports multiple emergency activation paths so that an emergency can be initiated through the app, supported background triggers, or a connected Bluetooth panic device.\n\nEmergency incidents can contain information such as trigger type, time, location availability, battery status, network status, audio status, and optionally shared medical information.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_emergency_communication_text")
            )
        }
    }
}

@Composable
private fun AboutImportantNoteCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_important_note_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Important Note",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Important",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = CurrentTextPrimary,
                    modifier = Modifier.testTag("about_important_note_title")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "AIZA is a personal safety communication and assistance application. It does not replace emergency services, law enforcement, medical professionals, or other official emergency resources.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_important_note_text")
            )
        }
    }
}

@Composable
private fun AboutAppInformationCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MinimalWhiteSurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, CurrentBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("about_app_information_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "App Information",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = CurrentTextPrimary,
                modifier = Modifier.testTag("about_app_information_title")
            )

            Spacer(modifier = Modifier.height(12.dp))

            val infoRows = listOf(
                "App Name" to "AIZA",
                "Tagline" to "Your Safety, Our Priority",
                "Developer" to "Tam",
                "Platform" to "Android (Cross-Platform Mobile Planned)",
                "Architecture" to "MVVM + Jetpack Compose",
                "Version" to BuildConfig.VERSION_NAME
            )

            infoRows.forEachIndexed { index, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CurrentTextMuted
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = CurrentTextPrimary
                    )
                }

                if (index < infoRows.lastIndex) {
                    HorizontalDivider(
                        color = CurrentBorder.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
