package com.example.ui.maps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.GuardianChatMessageEntity
import com.example.ui.theme.*
import com.example.viewmodel.SafetyViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapsScreen(
    viewModel: SafetyViewModel,
    innerPadding: PaddingValues
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Existing Location and SOS State from ViewModel
    val latitude by viewModel.latitude.collectAsState()
    val longitude by viewModel.longitude.collectAsState()
    val isSosActive by viewModel.isSosTriggered.collectAsState()
    val sosDuration by viewModel.sosDurationSeconds.collectAsState()
    val trackingPath by viewModel.trackingPath.collectAsState()
    val triggerSource = viewModel.currentSosTriggerSource

    // Guardian Walk state
    val isGuardianWalkActive by viewModel.isGuardianWalkActive.collectAsState()
    val guardianWalkDestination by viewModel.guardianWalkDestination.collectAsState()
    val guardianWalkRemainingSeconds by viewModel.guardianWalkRemainingSeconds.collectAsState()
    val guardianWalkElapsedSeconds by viewModel.guardianWalkElapsedSeconds.collectAsState()

    // Location Validity
    val hasValidLocation = (latitude != 0.0 || longitude != 0.0)
    val defaultFallbackPosition = LatLng(28.6139, 77.2090) // Default fallback if GPS is acquiring

    // Check existing location permission
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    // Map UI and Filter state
    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var selectedCategoryFilter by remember { mutableStateOf("ALL") }
    var selectedPoi by remember { mutableStateOf<EmergencyPoi?>(null) }
    var showLayersDialog by remember { mutableStateOf(false) }
    var showSafeZonesDialog by remember { mutableStateOf(false) }
    var showStartWalkDialog by remember { mutableStateOf(false) }
    var showSosDetailsDialog by remember { mutableStateOf(false) }
    var showAddSafeZoneDialog by remember { mutableStateOf(false) }
    var showContactLocationDialog by remember { mutableStateOf(false) }
    var showShareCurrentLocationDialog by remember { mutableStateOf(false) }

    // Continuous Location Sharing state (independent of SOS)
    val isContinuousSharing by viewModel.isContinuousLocationSharingEnabled.collectAsState()
    val lastLocationSyncTimestamp by viewModel.lastLocationSyncTimestamp.collectAsState()

    // Phase 4A: Normal Location Sharing & Guardian Chat Map Focus
    val sharedMapLocationTarget by viewModel.sharedMapLocationTarget.collectAsState()
    val guardianLocationShareStatus by viewModel.guardianLocationShareStatus.collectAsState()

    // Authorized Emergency Contacts & Location Viewing
    val emergencyContacts by viewModel.contacts.collectAsState()
    val selectedContactForLocation by viewModel.selectedContactForLocation.collectAsState()
    val contactSharedLocation by viewModel.contactSharedLocation.collectAsState()
    val isContactLocationLoading by viewModel.isContactLocationLoading.collectAsState()
    val contactLocationError by viewModel.contactLocationError.collectAsState()

    // Offline & Real Nearby Places state from ViewModel
    val nearbyPois: List<EmergencyPoi> by viewModel.nearbyPlaces.collectAsState()
    val isPlacesLoading: Boolean by viewModel.isPlacesLoading.collectAsState()
    val placesFromCache: Boolean by viewModel.placesFromCache.collectAsState()
    val placesLastUpdated: Long by viewModel.placesLastUpdated.collectAsState()
    val isOnline: Boolean by viewModel.isOnline.collectAsState()

    // Safe Zones / Havens list: single source of truth from ViewModel
    val safeZones by viewModel.userSafeZones.collectAsState()

    // Refresh real places when coordinates are available or network state changes
    LaunchedEffect(hasValidLocation, isOnline) {
        if (hasValidLocation) {
            viewModel.refreshNearbyPlaces()
        }
    }

    // Check active safe zone
    val currentSafeZone = remember(latitude, longitude, safeZones) {
        MapPoisRepository.findActiveSafeZone(latitude, longitude, safeZones)
    }

    // Explicitly selected Safe Haven map location (tap to place pin)
    var explicitlySelectedHavenLocation by remember { mutableStateOf<LatLng?>(null) }

    // 2. Google Maps Camera State
    val initialSharedTarget = sharedMapLocationTarget?.takeIf {
        GuardianChatMessageEntity.hasValidLocationCoordinates(it.latitude, it.longitude)
    }
    val initialTarget = when {
        initialSharedTarget != null -> LatLng(initialSharedTarget.latitude, initialSharedTarget.longitude)
        hasValidLocation -> LatLng(latitude, longitude)
        else -> defaultFallbackPosition
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            initialTarget,
            if (initialSharedTarget != null) 16f else if (hasValidLocation) 15.5f else 12f
        )
    }

    // Camera Behavior:
    // When the screen first receives a valid GPS location, center the map.
    // After that, do NOT continuously force the camera to the user's position every time latitude/longitude changes.
    // The user must be able to manually pan and zoom the map freely.
    var hasCenteredOnValidLocation by remember { mutableStateOf(initialSharedTarget != null) }

    LaunchedEffect(hasValidLocation) {
        if (hasValidLocation && !hasCenteredOnValidLocation && sharedMapLocationTarget == null) {
            hasCenteredOnValidLocation = true
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 15.5f),
                1000
            )
        }
    }

    // Phase 4A: Center map when opened from a shared location card in GuardianChat
    LaunchedEffect(sharedMapLocationTarget) {
        val target = sharedMapLocationTarget
        if (target != null && GuardianChatMessageEntity.hasValidLocationCoordinates(target.latitude, target.longitude)) {
            hasCenteredOnValidLocation = true
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), 16f),
                1000
            )
        }
    }

    // Auto-center on selected contact's location when received
    LaunchedEffect(contactSharedLocation) {
        val loc = contactSharedLocation
        if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 16f),
                1000
            )
        }
    }

    // 3. User Location Marker State (one marker, moves smoothly with location updates)
    val userMarkerState = rememberMarkerState(
        position = if (hasValidLocation) LatLng(latitude, longitude) else defaultFallbackPosition
    )
    LaunchedEffect(latitude, longitude) {
        if (hasValidLocation) {
            userMarkerState.position = LatLng(latitude, longitude)
        }
    }

    // Map Properties & UI Settings
    val mapProperties = remember(hasLocationPermission, mapType) {
        MapProperties(
            isMyLocationEnabled = hasLocationPermission,
            mapType = mapType
        )
    }
    val mapUiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false, // Clean custom floating buttons available
            compassEnabled = true,
            myLocationButtonEnabled = false, // Custom FAB handles recentering with active location request
            mapToolbarEnabled = true,
            rotationGesturesEnabled = true,
            scrollGesturesEnabled = true,
            tiltGesturesEnabled = true,
            zoomGesturesEnabled = true
        )
    }

    // Flashing transition for active SOS banner
    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val pulseBorderColor by infiniteTransition.animateColor(
        initialValue = AlertPulseRed,
        targetValue = AlertPulseRed.copy(alpha = 0.3f),
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
    ) {
        // ==========================================
        // 1. REAL INTERACTIVE GOOGLE MAP
        // ==========================================
        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .testTag("google_map_view"),
            cameraPositionState = cameraPositionState,
            properties = mapProperties,
            uiSettings = mapUiSettings,
            onMapClick = { latLng ->
                explicitlySelectedHavenLocation = latLng
            }
        ) {
            // Explicitly Selected Safe Haven Pin Marker
            explicitlySelectedHavenLocation?.let { pin ->
                Marker(
                    state = rememberMarkerState(key = "haven_pin_${pin.latitude}_${pin.longitude}", position = pin),
                    title = "Selected Safe Haven Pin",
                    snippet = "${String.format(Locale.US, "%.5f, %.5f", pin.latitude, pin.longitude)}",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
                )
            }

            // User Current Location Marker (only when valid location is available, not 0,0)
            if (hasValidLocation) {
                Marker(
                    state = userMarkerState,
                    title = if (isSosActive) "SOS Incident Location" else "My Current Location",
                    snippet = "Lat: ${String.format(Locale.US, "%.5f", latitude)}, Lng: ${String.format(Locale.US, "%.5f", longitude)}",
                    icon = BitmapDescriptorFactory.defaultMarker(
                        if (isSosActive) BitmapDescriptorFactory.HUE_RED else BitmapDescriptorFactory.HUE_AZURE
                    )
                )

                // SOS Perimeter Circle (when SOS is active)
                if (isSosActive) {
                    Circle(
                        center = LatLng(latitude, longitude),
                        radius = 150.0,
                        fillColor = AlertPulseRed.copy(alpha = 0.22f),
                        strokeColor = AlertPulseRed,
                        strokeWidth = 3f
                    )
                }
            }

            // Safe Zone Perimeter Circles
            safeZones.filter { it.isEnabled }.forEach { zone ->
                Circle(
                    center = LatLng(zone.latitude, zone.longitude),
                    radius = zone.radiusMeters,
                    fillColor = StatusEmerald.copy(alpha = 0.14f),
                    strokeColor = StatusEmerald,
                    strokeWidth = 2f
                )
            }

            // Live Breadcrumb Tracking Path
            if (trackingPath.size >= 2) {
                Polyline(
                    points = trackingPath.map { LatLng(it.first, it.second) },
                    color = Color(0xFFE91E63),
                    width = 7f
                )
            }

            // Authorized Emergency Contact Shared Location Marker
            contactSharedLocation?.let { contactLoc ->
                if (contactLoc.latitude != 0.0 || contactLoc.longitude != 0.0) {
                    val contactLatLng = LatLng(contactLoc.latitude, contactLoc.longitude)
                    val contactName = selectedContactForLocation?.name ?: "Emergency Contact"
                    val isLive = contactLoc.isSharingEnabled
                    Marker(
                        state = rememberMarkerState(position = contactLatLng),
                        title = "$contactName's Location",
                        snippet = if (isLive) "Live Sharing Active (Accuracy: ${contactLoc.accuracy.toInt()}m)" else "Last Known Location",
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)
                    )
                    Circle(
                        center = contactLatLng,
                        radius = contactLoc.accuracy.toDouble().coerceIn(30.0, 150.0),
                        fillColor = Color(0x339C27B0),
                        strokeColor = Color(0xFF9C27B0),
                        strokeWidth = 2f
                    )
                }
            }

            // Phase 4A: Shared Location Marker from GuardianChat
            sharedMapLocationTarget?.let { sharedTarget ->
                if (GuardianChatMessageEntity.hasValidLocationCoordinates(sharedTarget.latitude, sharedTarget.longitude)) {
                    val sharedLatLng = LatLng(sharedTarget.latitude, sharedTarget.longitude)
                    Marker(
                        state = rememberMarkerState(
                            key = "shared_${sharedTarget.messageId}_${sharedTarget.latitude}_${sharedTarget.longitude}",
                            position = sharedLatLng
                        ),
                        title = "${sharedTarget.senderName}'s Shared Location",
                        snippet = "${String.format(Locale.US, "%.5f, %.5f", sharedTarget.latitude, sharedTarget.longitude)} • ${sharedTarget.locationStatus}",
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA)
                    )
                    Circle(
                        center = sharedLatLng,
                        radius = 45.0,
                        fillColor = MagentaPrimary.copy(alpha = 0.18f),
                        strokeColor = MagentaPrimary,
                        strokeWidth = 3f
                    )
                }
            }

            // Nearby Verified Emergency POIs (Police, Hospitals, Safe Havens - Pharmacies removed)
            val filteredPois = remember(nearbyPois, selectedCategoryFilter) {
                val nonPharmacy = nearbyPois.filter { it.category != PoiCategory.PHARMACY }
                if (selectedCategoryFilter == "ALL") nonPharmacy
                else nonPharmacy.filter { it.category.name == selectedCategoryFilter }
            }

            filteredPois.forEach { poi ->
                val markerHue = when (poi.category) {
                    PoiCategory.POLICE -> BitmapDescriptorFactory.HUE_BLUE
                    PoiCategory.HOSPITAL -> BitmapDescriptorFactory.HUE_RED
                    PoiCategory.SHELTER -> BitmapDescriptorFactory.HUE_VIOLET
                    PoiCategory.PHARMACY -> BitmapDescriptorFactory.HUE_GREEN
                }

                Marker(
                    state = rememberMarkerState(key = poi.id, position = LatLng(poi.latitude, poi.longitude)),
                    title = poi.name,
                    snippet = "${poi.category.shortLabel} • ${poi.formattedDistance}",
                    icon = BitmapDescriptorFactory.defaultMarker(markerHue),
                    onClick = {
                        selectedPoi = poi
                        false
                    }
                )
            }
        }

        // ==========================================
        // 2. TOP HEADER STACK (Status, Telemetry & Filter Chips)
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Live Status & Geofence Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                ),
                border = BorderStroke(1.dp, if (isSosActive) pulseBorderColor else MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isSosActive) showSosDetailsDialog = true
                    }
                    .testTag("map_status_bar")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSosActive) AlertPulseRed.copy(alpha = 0.18f)
                                else if (!isOnline) Color(0xFFEAB308).copy(alpha = 0.18f)
                                else if (currentSafeZone != null) StatusEmerald.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSosActive) Icons.Default.Warning
                            else if (!isOnline) Icons.Default.SatelliteAlt
                            else if (currentSafeZone != null) Icons.Default.Shield
                            else Icons.Default.LocationOn,
                            contentDescription = "Status Icon",
                            tint = if (isSosActive) AlertPulseRed
                            else if (!isOnline) Color(0xFFEAB308)
                            else if (currentSafeZone != null) StatusEmerald
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isSosActive) "EMERGENCY SOS ACTIVE"
                                else if (!isOnline) "OFFLINE — GPS TRACKING ACTIVE"
                                else if (currentSafeZone != null) "INSIDE SAFE ZONE"
                                else "LIVE GPS TRACKING ACTIVE • ONLINE",
                                color = if (isSosActive) AlertPulseRed
                                else if (!isOnline) Color(0xFFEAB308)
                                else if (currentSafeZone != null) StatusEmerald
                                else MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            if (isSosActive) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${sosDuration}s)",
                                    color = AlertPulseRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = if (isSosActive) "Broadcasting real-time coordinates to responders"
                            else if (currentSafeZone != null) "${currentSafeZone.name} • Geofence Active"
                            else if (hasValidLocation) "Coordinates: ${String.format(Locale.US, "%.4f, %.4f", latitude, longitude)}"
                            else "Acquiring GPS location...",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Refresh Places Button
                    IconButton(
                        onClick = { viewModel.refreshNearbyPlaces(force = true) },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("refresh_places_button")
                    ) {
                        if (isPlacesLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Real Nearby Places",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Share Live Location Button
                    IconButton(
                        onClick = {
                            val shareUrl = "https://maps.google.com/?q=$latitude,$longitude"
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "🚨 AIZA Live Location: I am sharing my live coordinates: $shareUrl (Lat: ${String.format(Locale.US, "%.4f", latitude)}, Lng: ${String.format(Locale.US, "%.4f", longitude)})"
                                )
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Live GPS Location"))
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("share_location_top_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share Live Location",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Location Permission Notice if Denied
            if (!hasLocationPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Location permission is required for live GPS tracking. Map is in preview mode.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategoryFilter == "ALL",
                    onClick = { selectedCategoryFilter = "ALL" },
                    label = { Text("All (${nearbyPois.count { it.category != PoiCategory.PHARMACY }})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("filter_all")
                )

                FilterChip(
                    selected = selectedCategoryFilter == "POLICE",
                    onClick = { selectedCategoryFilter = "POLICE" },
                    label = {
                        Text(
                            "Police (${nearbyPois.count { it.category == PoiCategory.POLICE }})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.LocalPolice, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("filter_police")
                )

                FilterChip(
                    selected = selectedCategoryFilter == "HOSPITAL",
                    onClick = { selectedCategoryFilter = "HOSPITAL" },
                    label = {
                        Text(
                            "Hospitals (${nearbyPois.count { it.category == PoiCategory.HOSPITAL }})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.LocalHospital, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("filter_hospitals")
                )

                FilterChip(
                    selected = selectedCategoryFilter == "SHELTER",
                    onClick = { selectedCategoryFilter = "SHELTER" },
                    label = {
                        Text(
                            "Safe Havens (${nearbyPois.count { it.category == PoiCategory.SHELTER }})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.VolunteerActivism, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.testTag("filter_shelters")
                )
            }

            // Offline Cache / Freshness Status Pill
            if (!isOnline) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFEAB308).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEAB308))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (nearbyPois.isNotEmpty()) {
                                "Offline — showing saved nearby places"
                            } else {
                                "Offline — GPS tracking active (No nearby places cached yet)"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (placesFromCache && nearbyPois.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Showing verified nearby places from local cache",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Safe Havens Empty State Banner (when Safe Havens filter is selected and none exist)
            if (selectedCategoryFilter == "SHELTER" && nearbyPois.none { it.category == PoiCategory.SHELTER }) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "No verified safe havens found nearby.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Register your current location or map spot as a verified Safe Haven.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { showAddSafeZoneDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusEmerald),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("btn_add_safe_haven_banner")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Haven", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Continuous Live Location Sharing Toggle Card
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (isContinuousSharing) StatusEmerald.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_location_sharing_bar")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isContinuousSharing) StatusEmerald else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isContinuousSharing) "Live Location Sharing: Active" else "Live Location Sharing: Paused",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isContinuousSharing) "Syncing real-time coordinates to Supabase for guardians" else "Coordinates only transmitted upon SOS activation",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isContinuousSharing,
                        onCheckedChange = { viewModel.toggleContinuousLocationSharing(it) },
                        modifier = Modifier.testTag("live_location_sharing_switch")
                    )
                }
            }

            // Contact Shared Location Active Banner
            if (selectedContactForLocation != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, Color(0xFF9C27B0)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contact_location_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFF9C27B0),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Viewing: ${selectedContactForLocation?.name}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isContactLocationLoading) {
                                Text("Connecting to cloud and fetching coordinates...", fontSize = 10.sp, color = Color(0xFF7B1FA2))
                            } else if (contactLocationError != null) {
                                Text(contactLocationError ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                            } else if (contactSharedLocation != null) {
                                val loc = contactSharedLocation!!
                                val statusText = if (loc.isSharingEnabled) "🟢 Live Sharing" else "⚪ Last Known"
                                Text(
                                    "$statusText • ${String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF7B1FA2)
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.selectContactForLocation(null) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Contact Location", tint = Color(0xFF7B1FA2), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Phase 4A: Share Current Location to Guardian Chat Bar
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MagentaPrimary.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.clearGuardianLocationShareStatus()
                        showShareCurrentLocationDialog = true
                    }
                    .testTag("maps_share_current_location_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Share Current Location",
                            tint = MagentaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Share Current Location",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Send your current coordinates to a Guardian Chat",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send Location to Guardian",
                        tint = MagentaPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Phase 4A: Status / Error Banner for Location Sharing from MapsScreen
            if (!guardianLocationShareStatus.isNullOrBlank()) {
                val isShareError = guardianLocationShareStatus!!.startsWith("Unable", ignoreCase = true) ||
                    guardianLocationShareStatus!!.startsWith("Could not", ignoreCase = true)
                Surface(
                    color = if (isShareError) AlertPulseRed.copy(alpha = 0.12f) else StatusEmerald.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isShareError) AlertPulseRed.copy(alpha = 0.6f) else StatusEmerald.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("maps_location_share_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isShareError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isShareError) AlertPulseRed else StatusEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = guardianLocationShareStatus!!,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearGuardianLocationShareStatus() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss status",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Phase 4A: Shared Location Card when MapsScreen is opened from GuardianChat
            sharedMapLocationTarget?.let { sharedTarget ->
                if (GuardianChatMessageEntity.hasValidLocationCoordinates(sharedTarget.latitude, sharedTarget.longitude)) {
                    val sharedTimeText = remember(sharedTarget.locationTimestamp) {
                        try {
                            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(sharedTarget.locationTimestamp))
                        } catch (_: Exception) {
                            ""
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, MagentaPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("maps_shared_location_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MagentaPrimary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Shared Location Pin",
                                    tint = MagentaPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Shared Location • ${sharedTarget.senderName}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.5f, %.5f",
                                        sharedTarget.latitude,
                                        sharedTarget.longitude
                                    ),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MagentaPrimary,
                                    modifier = Modifier.testTag("maps_shared_location_coordinates")
                                )
                                Text(
                                    text = if (sharedTimeText.isNotBlank()) {
                                        "Updated $sharedTimeText • ${sharedTarget.locationStatus}"
                                    } else {
                                        sharedTarget.locationStatus
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("maps_shared_location_freshness")
                                )
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(
                                                LatLng(sharedTarget.latitude, sharedTarget.longitude),
                                                16f
                                            ),
                                            800
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("maps_shared_location_recenter")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CenterFocusStrong,
                                    contentDescription = "Center on Shared Location",
                                    tint = MagentaPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { viewModel.clearSharedMapLocationTarget() },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("maps_shared_location_close")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Shared Location Card",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 3. FLOATING MAP CONTROLS (Right Side Column)
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Map Layers (Normal / Satellite / Terrain)
            SmallFloatingActionButton(
                onClick = { showLayersDialog = true },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("btn_map_layers")
            ) {
                Icon(Icons.Default.Layers, contentDescription = "Map Layers")
            }

            // Safe Zones Manager
            SmallFloatingActionButton(
                onClick = { showSafeZonesDialog = true },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = StatusEmerald,
                modifier = Modifier.testTag("btn_safe_zones")
            ) {
                Icon(Icons.Default.Shield, contentDescription = "Safe Zones")
            }

            // View Emergency Contact Shared Location
            SmallFloatingActionButton(
                onClick = { showContactLocationDialog = true },
                containerColor = if (selectedContactForLocation != null) Color(0xFF9C27B0) else MaterialTheme.colorScheme.surface,
                contentColor = if (selectedContactForLocation != null) Color.White else Color(0xFF9C27B0),
                modifier = Modifier.testTag("btn_contact_locations")
            ) {
                Icon(Icons.Default.People, contentDescription = "View Contact Location")
            }

            // Guardian Walk Toggle
            SmallFloatingActionButton(
                onClick = {
                    if (isGuardianWalkActive) {
                        viewModel.stopGuardianWalk(arrivedSafely = true)
                    } else {
                        showStartWalkDialog = true
                    }
                },
                containerColor = if (isGuardianWalkActive) StatusEmerald else MaterialTheme.colorScheme.surface,
                contentColor = if (isGuardianWalkActive) Color.White else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("btn_guardian_walk_toggle")
            ) {
                Icon(
                    imageVector = if (isGuardianWalkActive) Icons.Default.DirectionsRun else Icons.Default.DirectionsWalk,
                    contentDescription = "Guardian Safe Walk"
                )
            }

            // Zoom In
            SmallFloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.zoomIn(), 300)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("btn_zoom_in")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In")
            }

            // Zoom Out
            SmallFloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.zoomOut(), 300)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("btn_zoom_out")
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out")
            }

            // Center My Location Button
            FloatingActionButton(
                onClick = {
                    viewModel.requestLiveLocationUpdates()
                    if (hasValidLocation) {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 16f),
                                800
                            )
                        }
                        Toast.makeText(context, "Centered on GPS location", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Acquiring GPS location...", Toast.LENGTH_SHORT).show()
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("btn_recenter_gps")
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Re-center GPS", modifier = Modifier.size(24.dp))
            }
        }

        // ==========================================
        // 4. BOTTOM OVERLAYS: SOS ACTIVE OVERLAY & GUARDIAN WALK
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Dedicated SOS Active Information Panel (overlaid on Google Map)
            AnimatedVisibility(
                visible = isSosActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
                    border = BorderStroke(2.dp, pulseBorderColor),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sos_active_map_panel")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(AlertPulseRed)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SOS ACTIVE",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = AlertPulseRed,
                                    fontSize = 14.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            val mins = sosDuration / 60
                            val secs = sosDuration % 60
                            Surface(
                                color = AlertPulseRed.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Duration: ${String.format(Locale.US, "%02d:%02d", mins, secs)}",
                                    color = AlertPulseRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Trigger: $triggerSource",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (hasValidLocation) "GPS: Accurate" else "GPS: Acquiring...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasValidLocation) StatusEmerald else AlertPulseRed
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { showSosDetailsDialog = true },
                                modifier = Modifier.testTag("btn_view_sos_details")
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = AlertPulseRed)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Incident Details", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AlertPulseRed)
                            }
                        }
                    }
                }
            }

            // Guardian Walk Floating Banner
            AnimatedVisibility(
                visible = isGuardianWalkActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.5.dp, StatusEmerald),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("guardian_walk_active_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(StatusEmerald.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsWalk,
                                        contentDescription = null,
                                        tint = StatusEmerald,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "GUARDIAN WALK ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusEmerald,
                                        letterSpacing = 0.8.sp
                                    )
                                    Text(
                                        guardianWalkDestination.ifBlank { "Safe Commute" },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }

                            // Remaining Countdown Time
                            val minutes = guardianWalkRemainingSeconds / 60
                            val seconds = guardianWalkRemainingSeconds % 60
                            Surface(
                                color = StatusEmerald.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                                    color = StatusEmerald,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Extend by 5 minutes
                            OutlinedButton(
                                onClick = { viewModel.extendGuardianWalk(5) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+5 Min", fontSize = 12.sp)
                            }

                            // Arrived Safely (End Walk)
                            Button(
                                onClick = { viewModel.stopGuardianWalk(arrivedSafely = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = StatusEmerald),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("I Arrived Safely", fontSize = 12.sp)
                            }

                            // Emergency SOS
                            Button(
                                onClick = { viewModel.triggerSos("GUARDIAN_WALK_ALERT") },
                                colors = ButtonDefaults.buttonColors(containerColor = AlertPulseRed),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("SOS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 5. SELECTED EMERGENCY POI MODAL BOTTOM SHEET
        // ==========================================
        if (selectedPoi != null) {
            val poi = selectedPoi!!
            ModalBottomSheet(
                onDismissRequest = { selectedPoi = null },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(poi.category.badgeColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (poi.category) {
                                    PoiCategory.POLICE -> Icons.Default.LocalPolice
                                    PoiCategory.HOSPITAL -> Icons.Default.LocalHospital
                                    PoiCategory.SHELTER -> Icons.Default.VolunteerActivism
                                    PoiCategory.PHARMACY -> Icons.Default.MedicalServices
                                },
                                contentDescription = null,
                                tint = poi.category.badgeColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = poi.category.badgeColor,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = poi.category.shortLabel.uppercase(),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                if (poi.verified) {
                                    Icon(
                                        Icons.Default.Verified,
                                        contentDescription = "Verified",
                                        tint = StatusEmerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Verified 24/7", fontSize = 11.sp, color = StatusEmerald, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = poi.name,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Distance & ETA Bar
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("DISTANCE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                Text(poi.formattedDistance, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("WALKING ETA", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                Text("~${poi.walkingEtaMinutes} min", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("DRIVING ETA", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                Text("~${poi.drivingEtaMinutes} min", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = StatusEmerald)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Address
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(poi.address, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Description
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(poi.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons (Call, Navigate, Share)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Call Emergency / Contact
                        Button(
                            onClick = {
                                val callIntent = Intent(Intent.ACTION_DIAL).apply {
                                    data = Uri.parse("tel:${poi.phone.split("/").first().trim()}")
                                }
                                context.startActivity(callIntent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = poi.category.badgeColor),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                                .testTag("btn_call_poi")
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Call ${poi.category.emergencyNumber}", fontWeight = FontWeight.Bold)
                        }

                        // Navigate via Google Maps App
                        Button(
                            onClick = {
                                val navUri = Uri.parse("google.navigation:q=${poi.latitude},${poi.longitude}")
                                val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                                    setPackage("com.google.android.apps.maps")
                                }
                                try {
                                    context.startActivity(mapIntent)
                                } catch (e: Exception) {
                                    val webNavIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${poi.latitude},${poi.longitude}")
                                    )
                                    context.startActivity(webNavIntent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                                .testTag("btn_navigate_poi")
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Navigate")
                        }

                        // Share
                        OutlinedIconButton(
                            onClick = {
                                val shareText = "📍 Safe Facility: ${poi.name} (${poi.category.title})\nAddress: ${poi.address}\nPhone: ${poi.phone}\nLocation: https://maps.google.com/?q=${poi.latitude},${poi.longitude}"
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Facility Details"))
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // ==========================================
        // 6. MAP TYPE SELECTOR DIALOG
        // ==========================================
        if (showLayersDialog) {
            AlertDialog(
                onDismissRequest = { showLayersDialog = false },
                title = { Text("Select Map Layer Style", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(MapType.NORMAL, "Standard Streets", Icons.Default.Map),
                            Triple(MapType.SATELLITE, "Satellite Imagery", Icons.Default.Satellite),
                            Triple(MapType.TERRAIN, "Terrain Relief", Icons.Default.Terrain),
                            Triple(MapType.HYBRID, "Hybrid Navigation", Icons.Default.Layers)
                        ).forEach { (type, label, icon) ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (mapType == type)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (mapType == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        mapType = type
                                        showLayersDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (mapType == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        label,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLayersDialog = false }) { Text("Close") }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }

        // ==========================================
        // 7. SAFE ZONES MANAGEMENT DIALOG
        // ==========================================
        // 7. SAFE ZONES MANAGEMENT DIALOG
        // ==========================================
        if (showSafeZonesDialog) {
            AlertDialog(
                onDismissRequest = { showSafeZonesDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = StatusEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Active Safe Zones", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Safe Zones trigger automatic check-ins and notify guardians if you leave the safety perimeter unexpectedly.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (safeZones.isEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No safe zones configured yet. Add your home, hostel, or workplace.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(14.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            safeZones.forEach { zone ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (zone.isEnabled) StatusEmerald else MaterialTheme.colorScheme.outlineVariant),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(zone.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text(
                                                "Radius: ${zone.radiusMeters.toInt()}m • ${zone.type.title}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        // Delete Safe Zone button
                                        IconButton(
                                            onClick = { viewModel.removeSafeHaven(zone.id) },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .testTag("delete_safe_zone_${zone.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Delete Safe Haven",
                                                tint = AlertPulseRed,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        Switch(
                                            checked = zone.isEnabled,
                                            onCheckedChange = { checked ->
                                                viewModel.toggleSafeHaven(zone.id, checked)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {
                                showSafeZonesDialog = false
                                showAddSafeZoneDialog = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add New Safe Haven", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSafeZonesDialog = false }) { Text("Done") }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }

        // ==========================================
        // 8. ADD SAFE ZONE / HAVEN DIALOG
        // ==========================================
        if (showAddSafeZoneDialog) {
            var newZoneName by remember { mutableStateOf("") }
            var selectedRadius by remember { mutableFloatStateOf(300f) }
            var selectedType by remember { mutableStateOf(SafeZoneType.CUSTOM) }
            var useGpsPosition by remember { mutableStateOf(hasValidLocation) }

            val isGpsValid = hasValidLocation &&
                !latitude.isNaN() && !latitude.isInfinite() &&
                !longitude.isNaN() && !longitude.isInfinite() &&
                latitude >= -90.0 && latitude <= 90.0 &&
                longitude >= -180.0 && longitude <= 180.0 &&
                !(latitude == 0.0 && longitude == 0.0)

            val targetCoords: LatLng? = if (useGpsPosition) {
                if (isGpsValid) LatLng(latitude, longitude) else null
            } else {
                explicitlySelectedHavenLocation
            }

            AlertDialog(
                onDismissRequest = { showAddSafeZoneDialog = false },
                title = { Text("Register Safe Haven", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Safe havens are persistent verified safe perimeters displayed on the radar map and used for geofenced check-ins.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Anchor Location Preview & Mode Selector
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (useGpsPosition) "📍 Current GPS Mode" else "🎯 Map Pin Mode",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    TextButton(
                                        onClick = { useGpsPosition = !useGpsPosition },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(if (useGpsPosition) "Switch to Map Pin" else "Switch to GPS", fontSize = 11.sp)
                                    }
                                }

                                if (useGpsPosition) {
                                    if (isGpsValid) {
                                        Text(
                                            text = "Coords: ${String.format(Locale.US, "%.5f", latitude)}, ${String.format(Locale.US, "%.5f", longitude)}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            text = "⚠️ GPS fix is currently unavailable. Please wait for a GPS fix or switch to Map Pin mode to select a location on the map.",
                                            fontSize = 11.sp,
                                            color = AlertPulseRed,
                                            lineHeight = 15.sp
                                        )
                                    }
                                } else {
                                    if (explicitlySelectedHavenLocation != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Pin: ${String.format(Locale.US, "%.5f", explicitlySelectedHavenLocation!!.latitude)}, ${String.format(Locale.US, "%.5f", explicitlySelectedHavenLocation!!.longitude)}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            TextButton(
                                                onClick = { explicitlySelectedHavenLocation = null },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                            ) {
                                                Text("Clear Pin", fontSize = 11.sp, color = AlertPulseRed)
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = "No map location selected yet. Tap anywhere on the map, or tap below to explicitly pin the current map center.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    val center = cameraPositionState.position.target
                                                    explicitlySelectedHavenLocation = center
                                                },
                                                modifier = Modifier.fillMaxWidth().testTag("pin_map_center_button")
                                            ) {
                                                Text("🎯 Pin Current Map Center Location", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = newZoneName,
                            onValueChange = { newZoneName = it },
                            label = { Text("Safe Haven Name (e.g. Home, Office, Hostel)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_safe_zone_name")
                        )

                        // Type selector chips
                        Text("Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SafeZoneType.values().forEach { type ->
                                FilterChip(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    label = { Text(type.title, fontSize = 11.sp) }
                                )
                            }
                        }

                        Text("Perimeter Radius: ${selectedRadius.toInt()} meters", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = selectedRadius,
                            onValueChange = { selectedRadius = it },
                            valueRange = 100f..1000f,
                            steps = 8
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newZoneName.isBlank()) {
                                Toast.makeText(context, "Please enter a name for the Safe Haven", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (useGpsPosition && !isGpsValid) {
                                Toast.makeText(
                                    context,
                                    "GPS fix is unavailable. Please wait for GPS or switch to Map Pin mode.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@Button
                            }
                            if (!useGpsPosition && explicitlySelectedHavenLocation == null) {
                                Toast.makeText(
                                    context,
                                    "Please select a location on the map or tap 'Pin Current Map Center Location' before saving.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@Button
                            }
                            val coords = targetCoords
                            if (coords == null) {
                                Toast.makeText(
                                    context,
                                    "No valid location selected. Please wait for a GPS fix or select a location on the map.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@Button
                            }
                            val result = viewModel.addSafeHaven(
                                name = newZoneName.trim(),
                                latitude = coords.latitude,
                                longitude = coords.longitude,
                                radiusMeters = selectedRadius.toDouble(),
                                type = selectedType,
                                isExplicitlySelected = !useGpsPosition
                            )
                            if (result.isSuccess) {
                                Toast.makeText(context, "Safe Haven registered successfully", Toast.LENGTH_SHORT).show()
                                showAddSafeZoneDialog = false
                                newZoneName = ""
                                explicitlySelectedHavenLocation = null
                            } else {
                                Toast.makeText(
                                    context,
                                    result.exceptionOrNull()?.message ?: "Failed to add safe haven",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.testTag("btn_save_safe_zone")
                    ) {
                        Text("Save Haven")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddSafeZoneDialog = false }) { Text("Cancel") }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }

        // ==========================================
        // 9. START GUARDIAN WALK DIALOG
        // ==========================================
        if (showStartWalkDialog) {
            var destinationInput by remember { mutableStateOf("") }
            var selectedMinutes by remember { mutableIntStateOf(15) }

            AlertDialog(
                onDismissRequest = { showStartWalkDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = StatusEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Guardian Safe Walk", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Guardian Walk records your real-time breadcrumb path and checks in automatically. If you don't arrive before the timer, guardians are alerted.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = destinationInput,
                            onValueChange = { destinationInput = it },
                            label = { Text("Destination (e.g. Metro to Home, Night Walk)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Estimated Duration:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(10, 15, 30, 45).forEach { mins ->
                                FilterChip(
                                    selected = selectedMinutes == mins,
                                    onClick = { selectedMinutes = mins },
                                    label = { Text("$mins min", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val dest = destinationInput.ifBlank { "Safe Commute" }
                            viewModel.startGuardianWalk(dest, selectedMinutes)
                            showStartWalkDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusEmerald)
                    ) {
                        Text("Start Walk Monitoring")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStartWalkDialog = false }) { Text("Cancel") }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }

        // ==========================================
        // 11. SOS ACTIVE DETAILS DIALOG
        // ==========================================
        if (showSosDetailsDialog) {
            AlertDialog(
                onDismissRequest = { showSosDetailsDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AlertPulseRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Active SOS Incident Details", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• Status: ACTIVE EMERGENCY", fontWeight = FontWeight.Bold, color = AlertPulseRed)
                        Text("• Trigger Method: $triggerSource", fontWeight = FontWeight.Medium)
                        Text("• Active Duration: ${sosDuration}s", fontWeight = FontWeight.Medium)
                        Text(
                            "• Live Coordinates: ${if (hasValidLocation) "${String.format(Locale.US, "%.5f", latitude)}, ${String.format(Locale.US, "%.5f", longitude)}" else "Acquiring..."}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Text("• Breadcrumb Points: ${trackingPath.size} logged", fontSize = 12.sp)
                        Text("• Security: Post-Quantum Dilithium Signed & Encrypted", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• Cloud Status: Dispatched to Supabase sos_alerts", fontSize = 11.sp, color = StatusEmerald)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSosDetailsDialog = false }) {
                        Text("Close")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }
        // ==========================================
        // 12. EMERGENCY CONTACT LOCATION SELECTOR DIALOG
        // ==========================================
        if (showContactLocationDialog) {
            AlertDialog(
                onDismissRequest = { showContactLocationDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.People, contentDescription = null, tint = Color(0xFF9C27B0))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Contact Location", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Select an emergency guardian to view their shared live or last known location on the map:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (emergencyContacts.isEmpty()) {
                            Text(
                                "No emergency contacts configured yet. Add contacts in the Guardians tab to view their locations.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            val sortedContacts = remember(emergencyContacts) {
                                emergencyContacts.sortedBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(sortedContacts) { contact ->
                                    val isSelected = selectedContactForLocation?.id == contact.id
                                    Surface(
                                        color = if (isSelected) Color(0xFFF3E5F5) else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp),
                                        border = if (isSelected) BorderStroke(1.5.dp, Color(0xFF9C27B0)) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.selectContactForLocation(contact)
                                                showContactLocationDialog = false
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(if (contact.priority == "1") AlertPulseRed else MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = contact.priority,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = contact.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = contact.phone,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color(0xFF9C27B0),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (selectedContactForLocation != null) {
                        TextButton(onClick = {
                            viewModel.selectContactForLocation(null)
                            showContactLocationDialog = false
                        }) {
                            Text("Clear View", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showContactLocationDialog = false }) {
                        Text("Close")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // 13. PHASE 4A: SHARE CURRENT LOCATION TO GUARDIAN DIALOG
        // ==========================================
        if (showShareCurrentLocationDialog) {
            AlertDialog(
                onDismissRequest = { showShareCurrentLocationDialog = false },
                modifier = Modifier.testTag("maps_share_location_dialog"),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = MagentaPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Share Current Location",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Select a guardian contact to send your current GPS coordinates in Guardian Chat:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (emergencyContacts.isEmpty()) {
                            Text(
                                text = "No guardian contacts configured yet. Add a guardian in the Guardians tab first.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(vertical = 12.dp)
                                    .testTag("maps_share_location_no_contacts")
                            )
                        } else {
                            val sortedRecipients = remember(emergencyContacts) {
                                emergencyContacts.sortedWith(
                                    compareByDescending<com.example.data.ContactEntity> { it.isGuardianAccountPaired }
                                        .thenBy { it.priority.toIntOrNull() ?: Int.MAX_VALUE }
                                )
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .testTag("maps_share_recipient_list"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    items = sortedRecipients,
                                    key = { it.contactId }
                                ) { contact ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (contact.isGuardianAccountPaired) StatusEmerald.copy(alpha = 0.6f)
                                            else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showShareCurrentLocationDialog = false
                                                viewModel.shareCurrentLocationToGuardian(contact)
                                            }
                                            .testTag("maps_share_recipient_${contact.contactId}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(MagentaPrimary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = contact.name.take(1).uppercase(Locale.getDefault()),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = contact.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = if (contact.isGuardianAccountPaired) {
                                                        "AIZA Account Connected"
                                                    } else {
                                                        contact.phone
                                                    },
                                                    fontSize = 11.sp,
                                                    color = if (contact.isGuardianAccountPaired) {
                                                        StatusEmerald
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.Send,
                                                contentDescription = "Share Location with ${contact.name}",
                                                tint = MagentaPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showShareCurrentLocationDialog = false }) {
                        Text("Cancel")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
