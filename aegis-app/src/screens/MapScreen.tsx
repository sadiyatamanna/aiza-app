import React, { useEffect, useState, useRef, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
  Linking,
  Platform,
  ScrollView,
  Modal,
  TextInput,
  Alert,
} from 'react-native';
import MapView, { Marker, Circle, UrlTile, Region } from 'react-native-maps';
import { Colors, Typography, Spacing, BorderRadius } from '../theme';
import { StatusBadge } from '../components/StatusBadge';
import { LocationService, LocationCoordinates } from '../services/LocationService';
import { MapPoisService, MAPTILER_API_KEY } from '../services/MapPoisService';
import { CachedPlaceDbRecord, DbPlaceCategory } from '../database/CachedPlaceDao';
import { AppDatabase } from '../database/AppDatabase';
import { UserLocationService, SupabaseUserLocationRecord } from '../services/UserLocationService';
import { TelephonyService } from '../services/TelephonyService';
import { useSafetyStore } from '../store/safetyStore';

type FilterCategory = 'ALL' | 'POLICE' | 'HOSPITAL' | 'PHARMACY' | 'SHELTER';

interface SafeZoneRecord {
  id: string;
  user_id: string;
  name: string;
  latitude: number;
  longitude: number;
  radius: number;
  created_at: number;
}

const CATEGORY_CHIPS: { id: FilterCategory; label: string; icon: string }[] = [
  { id: 'ALL', label: 'All Places', icon: '🌐' },
  { id: 'POLICE', label: 'Police', icon: '🚨' },
  { id: 'HOSPITAL', label: 'Hospitals', icon: '🏥' },
  { id: 'PHARMACY', label: 'Pharmacies', icon: '💊' },
  { id: 'SHELTER', label: 'Shelters', icon: '🏠' },
];

export const MapScreen: React.FC = () => {
  const mapRef = useRef<MapView | null>(null);

  // User location state - STRICTLY real device coordinates, NO fake coordinates
  const [userLocation, setUserLocation] = useState<LocationCoordinates | null>(null);
  const [isGpsLoading, setIsGpsLoading] = useState(false);
  const [gpsError, setGpsError] = useState<string | null>(null);

  // Filter & POI state
  const [activeFilter, setActiveFilter] = useState<FilterCategory>('ALL');
  const [pois, setPois] = useState<CachedPlaceDbRecord[]>([]);
  const [isPoiLoading, setIsPoiLoading] = useState(false);
  const [selectedPoi, setSelectedPoi] = useState<CachedPlaceDbRecord | null>(null);

  // Safe zones state
  const [safeZones, setSafeZones] = useState<SafeZoneRecord[]>([]);
  const [isSafeZoneModalVisible, setIsSafeZoneModalVisible] = useState(false);
  const [newZoneName, setNewZoneName] = useState('');
  const [newZoneRadius, setNewZoneRadius] = useState('200');

  // Guardian locations state (real data only)
  const [guardianLocations, setGuardianLocations] = useState<
    { name: string; location: SupabaseUserLocationRecord }[]
  >([]);

  const { contacts, currentUser, userProfile, networkAvailable } = useSafetyStore();

  // Load safe zones from SQLite
  const loadSafeZones = useCallback(async () => {
    try {
      const res = await AppDatabase.executeRaw(`SELECT * FROM safe_zones ORDER BY created_at DESC;`);
      setSafeZones((res.rows || []) as SafeZoneRecord[]);
    } catch (e) {
      // SafeZone SQLite read fallback
    }
  }, []);

  // Fetch real user location
  const refreshLocation = useCallback(async () => {
    setIsGpsLoading(true);
    setGpsError(null);
    try {
      await LocationService.requestLocationPermissions();
      const pos = await LocationService.getCurrentPosition();
      if (pos) {
        setUserLocation(pos);
        setGpsError(null);
        // Center map on user location
        if (mapRef.current) {
          mapRef.current.animateToRegion(
            {
              latitude: pos.latitude,
              longitude: pos.longitude,
              latitudeDelta: 0.025,
              longitudeDelta: 0.025,
            },
            600
          );
        }
      } else {
        const last = await LocationService.getLastKnownPosition();
        if (last) {
          setUserLocation(last);
        } else {
          setGpsError('GPS fix unavailable. Please verify device location settings.');
        }
      }
    } catch (err: any) {
      setGpsError('Unable to acquire real GPS position.');
    } finally {
      setIsGpsLoading(false);
    }
  }, []);

  // Fetch nearby POIs using real GPS location
  const loadPois = useCallback(
    async (lat: number, lon: number, filter: FilterCategory) => {
      setIsPoiLoading(true);
      try {
        const results = await MapPoisService.fetchNearbyEmergencyPlaces(
          lat,
          lon,
          filter === 'ALL' ? undefined : filter
        );
        setPois(results);
      } catch (e) {
        // Fallback to offline cache
        const cached = await MapPoisService.getCachedPlaces(
          filter === 'ALL' ? undefined : (filter as DbPlaceCategory),
          lat,
          lon
        );
        setPois(cached);
      } finally {
        setIsPoiLoading(false);
      }
    },
    []
  );

  // Fetch guardian real locations via UserLocationService if contacts have user_ids
  const loadGuardianLocations = useCallback(async () => {
    if (!contacts || contacts.length === 0) {
      setGuardianLocations([]);
      return;
    }

    const found: { name: string; location: SupabaseUserLocationRecord }[] = [];
    for (const contact of contacts) {
      const contactUserId = (contact as any).user_id || (contact as any).userId;
      if (contactUserId) {
        try {
          const res = await UserLocationService.getInstance().getLatestLocation(contactUserId);
          if (res.success && res.data && res.data.latitude && res.data.longitude) {
            found.push({ name: contact.name, location: res.data });
          }
        } catch (e) {
          // Ignore individual fetch failure
        }
      }
    }
    setGuardianLocations(found);
  }, [contacts]);

  // Initial load
  useEffect(() => {
    refreshLocation();
    loadSafeZones();
    loadGuardianLocations();
  }, [refreshLocation, loadSafeZones, loadGuardianLocations]);

  // When user location or filter changes, reload POIs
  useEffect(() => {
    if (userLocation) {
      loadPois(userLocation.latitude, userLocation.longitude, activeFilter);
    }
  }, [userLocation, activeFilter, loadPois]);

  // Launch external navigation
  const openExternalNavigation = (poi: CachedPlaceDbRecord) => {
    const lat = poi.latitude;
    const lng = poi.longitude;
    const label = encodeURIComponent(poi.name);
    const url = Platform.select({
      ios: `maps:0,0?q=${lat},${lng}(${label})`,
      android: `geo:0,0?q=${lat},${lng}(${label})`,
      default: `https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`,
    });

    if (url) {
      Linking.openURL(url).catch(() => {
        Linking.openURL(`https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`);
      });
    }
  };

  // Call POI Facility directly if phone exists
  const callFacility = (poi: CachedPlaceDbRecord) => {
    const phoneNumber = (poi as any).phone || (poi as any).phoneNumber;
    if (phoneNumber) {
      TelephonyService.initiateEmergencyCall(phoneNumber).catch(() => {
        Alert.alert('Call Failed', 'Unable to initiate call to facility.');
      });
    }
  };

  // Create new Safe Zone on current real location
  const handleSaveSafeZone = async () => {
    if (!newZoneName.trim()) {
      Alert.alert('Required', 'Please enter a name for the safe zone (e.g., Home, Work, Campus).');
      return;
    }
    if (!userLocation) {
      Alert.alert('GPS Required', 'Real GPS location is needed to pin a safe zone.');
      return;
    }

    const radiusNum = parseInt(newZoneRadius, 10) || 200;
    const zone: SafeZoneRecord = {
      id: `sz_${Date.now()}`,
      user_id: currentUser?.id || userProfile?.id || 'local_user',
      name: newZoneName.trim(),
      latitude: userLocation.latitude,
      longitude: userLocation.longitude,
      radius: radiusNum,
      created_at: Date.now(),
    };

    try {
      await AppDatabase.executeRaw(
        `INSERT OR REPLACE INTO safe_zones (id, user_id, name, latitude, longitude, radius, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?);`,
        [
          zone.id,
          zone.user_id,
          zone.name,
          zone.latitude,
          zone.longitude,
          zone.radius,
          zone.created_at,
        ]
      );
      setSafeZones((prev) => [zone, ...prev]);
      setNewZoneName('');
      setIsSafeZoneModalVisible(false);
    } catch (e) {
      setSafeZones((prev) => [zone, ...prev]);
      setIsSafeZoneModalVisible(false);
    }
  };

  // Category Color Mapper
  const getCategoryColor = (cat: string) => {
    switch (cat) {
      case 'POLICE':
        return '#2979FF';
      case 'HOSPITAL':
        return '#00E676';
      case 'PHARMACY':
        return '#FF9100';
      case 'SHELTER':
        return '#E040FB';
      default:
        return Colors.primary;
    }
  };

  const initialRegion: Region = userLocation
    ? {
        latitude: userLocation.latitude,
        longitude: userLocation.longitude,
        latitudeDelta: 0.03,
        longitudeDelta: 0.03,
      }
    : {
        latitude: 37.7749,
        longitude: -122.4194,
        latitudeDelta: 0.05,
        longitudeDelta: 0.05,
      };

  const isOfflineSource = !networkAvailable || pois.some((p) => p.source === 'OFFLINE_CACHE');

  return (
    <View style={styles.container}>
      {/* Top Header Bar */}
      <View style={styles.header}>
        <View style={styles.headerLeft}>
          <Text style={Typography.h2}>Safety Radar</Text>
          <Text style={[Typography.caption, styles.headerSubtitle]} numberOfLines={1}>
            {userLocation
              ? `GPS: ${userLocation.latitude.toFixed(4)}, ${userLocation.longitude.toFixed(4)} (±${Math.round(userLocation.accuracy)}m)`
              : isGpsLoading
              ? 'Acquiring GPS fix...'
              : 'GPS unavailable'}
          </Text>
        </View>

        <View style={styles.headerRight}>
          <StatusBadge
            label={isOfflineSource ? 'OFFLINE CACHE' : 'LIVE RADAR'}
            variant={isOfflineSource ? 'warning' : 'success'}
            size="small"
          />
        </View>
      </View>

      {/* Category Filter Chips */}
      <View style={styles.filterContainer}>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.filterScroll}
        >
          {CATEGORY_CHIPS.map((chip) => {
            const isActive = activeFilter === chip.id;
            return (
              <TouchableOpacity
                key={chip.id}
                style={[styles.filterChip, isActive && styles.filterChipActive]}
                onPress={() => setActiveFilter(chip.id)}
                activeOpacity={0.8}
                accessibilityRole="button"
                accessibilityLabel={`Filter by ${chip.label}`}
              >
                <Text style={styles.chipIcon}>{chip.icon}</Text>
                <Text style={[styles.filterChipText, isActive && styles.filterChipTextActive]}>
                  {chip.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>
      </View>

      {/* GPS Warning Banner */}
      {gpsError && (
        <View style={styles.warningBanner}>
          <Text style={styles.warningText}>⚠️ {gpsError}</Text>
        </View>
      )}

      {/* Map Canvas */}
      <View style={styles.mapWrapper}>
        <MapView
          ref={mapRef}
          style={styles.map}
          initialRegion={initialRegion}
          showsUserLocation={!!userLocation}
          showsMyLocationButton={false}
          showsCompass={true}
          toolbarEnabled={false}
        >
          {MAPTILER_API_KEY ? (
            <UrlTile
              urlTemplate={`https://api.maptiler.com/maps/dataviz-dark/256/{z}/{x}/{y}.png?key=${MAPTILER_API_KEY}`}
              maximumZ={19}
              flipY={false}
              zIndex={-1}
            />
          ) : null}

          {userLocation && (
            <Marker
              coordinate={{
                latitude: userLocation.latitude,
                longitude: userLocation.longitude,
              }}
              title="You (Live Position)"
              description={`Accuracy: ±${Math.round(userLocation.accuracy)}m`}
              pinColor="#FF2E63"
            />
          )}

          {pois.map((poi) => (
            <Marker
              key={poi.id}
              coordinate={{
                latitude: poi.latitude,
                longitude: poi.longitude,
              }}
              title={poi.name}
              description={`${poi.category} • ${poi.distance ? `${poi.distance}m` : 'Nearby'}`}
              pinColor={getCategoryColor(poi.category)}
              onPress={() => setSelectedPoi(poi)}
            />
          ))}

          {safeZones.map((zone) => (
            <React.Fragment key={zone.id}>
              <Circle
                center={{
                  latitude: zone.latitude,
                  longitude: zone.longitude,
                }}
                radius={zone.radius}
                strokeColor="rgba(0, 230, 118, 0.8)"
                fillColor="rgba(0, 230, 118, 0.15)"
                strokeWidth={2}
              />
              <Marker
                coordinate={{
                  latitude: zone.latitude,
                  longitude: zone.longitude,
                }}
                title={`Safe Zone: ${zone.name}`}
                description={`Radius: ${zone.radius}m`}
                pinColor="#00E676"
              />
            </React.Fragment>
          ))}

          {guardianLocations.map((guardian, index) => (
            <Marker
              key={`guardian_${index}`}
              coordinate={{
                latitude: guardian.location.latitude,
                longitude: guardian.location.longitude,
              }}
              title={`Guardian: ${guardian.name}`}
              description={`Battery: ${guardian.location.battery_level}% • Live`}
              pinColor="#2979FF"
            />
          ))}
        </MapView>

        <View style={styles.floatingControls}>
          <TouchableOpacity
            style={styles.recenterFab}
            onPress={refreshLocation}
            disabled={isGpsLoading}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Recenter GPS Position"
          >
            {isGpsLoading ? (
              <ActivityIndicator size="small" color={Colors.primary} />
            ) : (
              <Text style={styles.recenterFabText}>🎯</Text>
            )}
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.actionFab}
            onPress={() => setIsSafeZoneModalVisible(true)}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Add Safe Zone Geofence"
          >
            <Text style={styles.actionFabText}>+ Safe Zone</Text>
          </TouchableOpacity>
        </View>

        {isPoiLoading && (
          <View style={styles.loadingPoisBadge}>
            <ActivityIndicator size="small" color={Colors.primary} />
            <Text style={styles.loadingPoisText}>Scanning emergency facilities...</Text>
          </View>
        )}

        {!isPoiLoading && pois.length === 0 && (
          <View style={styles.emptyPoisBadge}>
            <Text style={styles.emptyPoisText}>
              No {activeFilter === 'ALL' ? 'facilities' : activeFilter.toLowerCase()} found in 5km radius
            </Text>
          </View>
        )}
      </View>

      {selectedPoi ? (
        <View style={styles.poiDetailCard}>
          <View style={styles.poiDetailHeader}>
            <View style={{ flex: 1 }}>
              <View style={styles.poiCategoryBadgeRow}>
                <StatusBadge
                  label={selectedPoi.category}
                  variant={
                    selectedPoi.category === 'POLICE'
                      ? 'info'
                      : selectedPoi.category === 'HOSPITAL'
                      ? 'success'
                      : 'primary'
                  }
                  size="small"
                />
                {selectedPoi.source === 'OFFLINE_CACHE' && (
                  <StatusBadge label="OFFLINE CACHE" variant="neutral" size="small" />
                )}
              </View>
              <Text style={[Typography.h3, styles.poiNameText]}>{selectedPoi.name}</Text>
              <Text style={[Typography.caption, styles.poiAddress]}>{selectedPoi.address}</Text>
              {selectedPoi.distance !== null && selectedPoi.distance !== undefined && (
                <Text style={styles.poiDistance}>
                  {selectedPoi.distance < 1000
                    ? `${selectedPoi.distance} meters away`
                    : `${(selectedPoi.distance / 1000).toFixed(1)} km away`}
                </Text>
              )}
            </View>
            <TouchableOpacity
              style={styles.closeCardButton}
              onPress={() => setSelectedPoi(null)}
              accessibilityRole="button"
              accessibilityLabel="Close Facility Details"
            >
              <Text style={styles.closeCardButtonText}>✕</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.poiButtonsRow}>
            {!!((selectedPoi as any).phone || (selectedPoi as any).phoneNumber) && (
              <TouchableOpacity
                style={styles.callFacilityButton}
                onPress={() => callFacility(selectedPoi)}
                activeOpacity={0.8}
                accessibilityRole="button"
                accessibilityLabel={`Call ${selectedPoi.name}`}
              >
                <Text style={styles.callFacilityButtonText}>📞 Call</Text>
              </TouchableOpacity>
            )}
            <TouchableOpacity
              style={styles.navigateButton}
              onPress={() => openExternalNavigation(selectedPoi)}
              activeOpacity={0.8}
              accessibilityRole="button"
              accessibilityLabel="Navigate to Facility"
            >
              <Text style={styles.navigateButtonText}>Navigate ↗</Text>
            </TouchableOpacity>
          </View>
        </View>
      ) : (
        <View style={styles.summaryBar}>
          <Text style={styles.summaryText}>
            {pois.length} facilities visible • {safeZones.length} safe zone(s) active • Tap marker for details
          </Text>
        </View>
      )}

      <Modal
        visible={isSafeZoneModalVisible}
        transparent={true}
        animationType="slide"
        onRequestClose={() => setIsSafeZoneModalVisible(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={Typography.h2}>Add Safe Zone</Text>
            <Text style={[Typography.bodyMedium, styles.modalSubtitle]}>
              Pins an active geofence circle on your current GPS coordinates.
            </Text>

            <Text style={styles.modalInputLabel}>ZONE NAME</Text>
            <TextInput
              style={styles.modalInput}
              placeholder="e.g., Home, Campus, Office"
              placeholderTextColor={Colors.textDisabled}
              value={newZoneName}
              onChangeText={setNewZoneName}
            />

            <Text style={styles.modalInputLabel}>RADIUS (METERS)</Text>
            <TextInput
              style={styles.modalInput}
              placeholder="200"
              placeholderTextColor={Colors.textDisabled}
              value={newZoneRadius}
              onChangeText={setNewZoneRadius}
              keyboardType="numeric"
            />

            <View style={styles.modalButtonsRow}>
              <TouchableOpacity
                style={styles.modalCancelButton}
                onPress={() => setIsSafeZoneModalVisible(false)}
              >
                <Text style={styles.modalCancelButtonText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.modalSaveButton}
                onPress={handleSaveSafeZone}
              >
                <Text style={styles.modalSaveButtonText}>Save Zone</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: Colors.background,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: Spacing.md,
    paddingTop: Spacing.md,
    paddingBottom: Spacing.xs,
  },
  headerLeft: {
    flex: 1,
    marginRight: Spacing.sm,
  },
  headerSubtitle: {
    color: Colors.textSecondary,
    marginTop: 2,
  },
  headerRight: {
    alignItems: 'flex-end',
  },
  filterContainer: {
    paddingVertical: Spacing.xs,
  },
  filterScroll: {
    paddingHorizontal: Spacing.md,
    gap: Spacing.xs,
  },
  filterChip: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: Colors.surface,
    paddingHorizontal: Spacing.md,
    paddingVertical: 6,
    borderRadius: BorderRadius.full,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    minHeight: 36,
  },
  filterChipActive: {
    backgroundColor: Colors.primary,
    borderColor: Colors.primary,
  },
  chipIcon: {
    fontSize: 13,
    marginRight: 4,
  },
  filterChipText: {
    color: Colors.textSecondary,
    fontSize: 12,
    fontWeight: '600',
  },
  filterChipTextActive: {
    color: Colors.textPrimary,
    fontWeight: '700',
  },
  warningBanner: {
    backgroundColor: 'rgba(255, 171, 0, 0.15)',
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.xs,
    borderBottomWidth: 1,
    borderColor: 'rgba(255, 171, 0, 0.3)',
  },
  warningText: {
    color: '#FFD54F',
    fontSize: 12,
  },
  mapWrapper: {
    flex: 1,
    position: 'relative',
  },
  map: {
    ...StyleSheet.absoluteFillObject,
  },
  floatingControls: {
    position: 'absolute',
    top: Spacing.md,
    right: Spacing.md,
    gap: Spacing.sm,
    alignItems: 'flex-end',
  },
  recenterFab: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: Colors.surface,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
    elevation: 5,
  },
  recenterFabText: {
    fontSize: 18,
  },
  actionFab: {
    backgroundColor: Colors.surface,
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.primary,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 6,
    elevation: 6,
  },
  actionFabText: {
    color: Colors.primary,
    fontSize: 12,
    fontWeight: '700',
  },
  loadingPoisBadge: {
    position: 'absolute',
    top: Spacing.md,
    left: Spacing.md,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(15, 15, 18, 0.88)',
    paddingHorizontal: Spacing.sm + 2,
    paddingVertical: 6,
    borderRadius: BorderRadius.sm,
    gap: Spacing.xs,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  loadingPoisText: {
    color: Colors.textSecondary,
    fontSize: 11,
  },
  emptyPoisBadge: {
    position: 'absolute',
    bottom: Spacing.md,
    alignSelf: 'center',
    backgroundColor: 'rgba(15, 15, 18, 0.88)',
    paddingHorizontal: Spacing.md,
    paddingVertical: 6,
    borderRadius: BorderRadius.full,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  emptyPoisText: {
    color: Colors.textSecondary,
    fontSize: 11,
  },
  summaryBar: {
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm,
    backgroundColor: Colors.surface,
    borderTopWidth: 1,
    borderColor: Colors.surfaceBorder,
    alignItems: 'center',
  },
  summaryText: {
    color: Colors.textTertiary,
    fontSize: 12,
    fontWeight: '500',
  },
  poiDetailCard: {
    backgroundColor: Colors.surface,
    borderTopLeftRadius: BorderRadius.lg,
    borderTopRightRadius: BorderRadius.lg,
    padding: Spacing.md,
    borderTopWidth: 1,
    borderColor: Colors.surfaceBorder,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 10,
  },
  poiDetailHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: Spacing.sm,
  },
  poiCategoryBadgeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Spacing.xs,
    marginBottom: 4,
  },
  poiNameText: {
    marginTop: 2,
  },
  poiAddress: {
    color: Colors.textSecondary,
    marginTop: 2,
  },
  poiDistance: {
    color: Colors.success,
    fontSize: 12,
    fontWeight: '600',
    marginTop: 4,
  },
  closeCardButton: {
    padding: Spacing.xs,
  },
  closeCardButtonText: {
    color: Colors.textTertiary,
    fontSize: 16,
  },
  poiButtonsRow: {
    flexDirection: 'row',
    gap: Spacing.sm,
    marginTop: Spacing.sm,
  },
  callFacilityButton: {
    backgroundColor: Colors.surfaceElevated,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    paddingVertical: Spacing.sm + 2,
    paddingHorizontal: Spacing.md,
    borderRadius: BorderRadius.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  callFacilityButtonText: {
    color: Colors.textPrimary,
    fontWeight: '700',
    fontSize: 13,
  },
  navigateButton: {
    flex: 1,
    backgroundColor: Colors.primary,
    paddingVertical: Spacing.sm + 2,
    borderRadius: BorderRadius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  navigateButtonText: {
    color: Colors.textPrimary,
    fontWeight: '700',
    fontSize: 13,
    letterSpacing: 0.5,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.7)',
    justifyContent: 'center',
    padding: Spacing.xl,
  },
  modalCard: {
    backgroundColor: Colors.surface,
    borderRadius: BorderRadius.lg,
    padding: Spacing.xl,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  modalSubtitle: {
    color: Colors.textSecondary,
    marginTop: Spacing.xs,
    marginBottom: Spacing.lg,
  },
  modalInputLabel: {
    color: Colors.textSecondary,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1,
    marginBottom: Spacing.xs,
  },
  modalInput: {
    backgroundColor: Colors.surfaceElevated,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    borderRadius: BorderRadius.md,
    color: Colors.textPrimary,
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm,
    marginBottom: Spacing.md,
    fontSize: 14,
  },
  modalButtonsRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: Spacing.md,
    marginTop: Spacing.md,
  },
  modalCancelButton: {
    paddingVertical: Spacing.sm,
    paddingHorizontal: Spacing.md,
  },
  modalCancelButtonText: {
    color: Colors.textSecondary,
    fontWeight: '600',
  },
  modalSaveButton: {
    backgroundColor: Colors.primary,
    paddingVertical: Spacing.sm,
    paddingHorizontal: Spacing.lg,
    borderRadius: BorderRadius.md,
  },
  modalSaveButtonText: {
    color: Colors.textPrimary,
    fontWeight: '700',
  },
});
