import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
  Alert,
  Animated,
  Easing,
} from 'react-native';
import { ScreenContainer } from '../components/ScreenContainer';
import { StatusBadge } from '../components/StatusBadge';
import { Colors, Typography, Spacing, BorderRadius } from '../theme';
import { useSafetyStore } from '../store/safetyStore';
import { SosService } from '../services/SosService';
import { SirenService } from '../services/SirenService';
import { HardwareService } from '../services/HardwareService';
import { TelephonyService } from '../services/TelephonyService';
import { LocationService } from '../services/LocationService';
import { ContactDao, ContactDbRecord } from '../database/ContactDao';

export const HomeScreen: React.FC<{ navigation?: any }> = ({ navigation }) => {
  const {
    sosActive,
    currentLocation,
    lastKnownLocation,
    batteryLevel,
    networkAvailable,
    hardwareSosConnected,
  } = useSafetyStore();

  const [isDispatching, setIsDispatching] = useState(false);
  const [sirenOn, setSirenOn] = useState(false);
  const [torchOn, setTorchOn] = useState(false);
  const [primaryContact, setPrimaryContact] = useState<ContactDbRecord | null>(null);
  const [gpsRefreshing, setGpsRefreshing] = useState(false);

  // Pulsing animation for active SOS state
  const pulseAnim = React.useRef(new Animated.Value(1)).current;

  useEffect(() => {
    if (sosActive) {
      const pulseLoop = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, {
            toValue: 1.08,
            duration: 600,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(pulseAnim, {
            toValue: 1,
            duration: 600,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ])
      );
      pulseLoop.start();
      return () => pulseLoop.stop();
    } else {
      pulseAnim.setValue(1);
    }
  }, [sosActive, pulseAnim]);

  // Load Primary Guardian from local SQLite
  const loadPrimaryContact = useCallback(async () => {
    try {
      const contacts = await ContactDao.getAll();
      if (contacts && contacts.length > 0) {
        setPrimaryContact(contacts[0]);
      } else {
        setPrimaryContact(null);
      }
    } catch (e) {
      // Local DAO access fallback
    }
  }, []);

  useEffect(() => {
    loadPrimaryContact();
  }, [loadPrimaryContact]);

  // Refresh GPS manually
  const handleRefreshGps = async () => {
    setGpsRefreshing(true);
    try {
      await LocationService.requestLocationPermissions();
      const pos = await LocationService.getCurrentPosition();
      if (pos) {
        useSafetyStore.getState().setCurrentLocation(pos);
      }
    } finally {
      setGpsRefreshing(false);
    }
  };

  // Trigger or cancel SOS alert
  const handleSosPress = async () => {
    if (sosActive) {
      // Cancel active SOS
      setIsDispatching(true);
      try {
        await SosService.cancelSosAlert();
        setSirenOn(false);
        setTorchOn(false);
      } finally {
        setIsDispatching(false);
      }
    } else {
      // Trigger new SOS
      setIsDispatching(true);
      try {
        const result = await SosService.executeSosDispatch('MANUAL');
        if (result.sirenActive) setSirenOn(true);
        if (result.torchActive) setTorchOn(true);
      } catch (err: any) {
        Alert.alert('SOS Warning', 'Emergency alert initiated with partial hardware response.');
      } finally {
        setIsDispatching(false);
      }
    }
  };

  // Toggle siren test
  const handleToggleSiren = async () => {
    if (sirenOn) {
      await SirenService.stopEmergencySiren();
      setSirenOn(false);
    } else {
      const res = await SirenService.playEmergencySiren();
      if (res.success) setSirenOn(true);
    }
  };

  // Toggle camera torch test
  const handleToggleTorch = async () => {
    const nextState = !torchOn;
    const res = await HardwareService.toggleTorch(nextState);
    setTorchOn(res.enabled);
  };

  // Call Primary Guardian
  const handleCallPrimary = async () => {
    if (!primaryContact?.phone) {
      Alert.alert(
        'No Guardian Configured',
        'Add a primary emergency contact in the Guardians tab to enable direct calling.',
        [
          { text: 'Cancel', style: 'cancel' },
          { text: 'Add Contact', onPress: () => navigation?.navigate('Guardians') },
        ]
      );
      return;
    }

    try {
      await TelephonyService.initiateEmergencyCall(primaryContact.phone);
    } catch (e) {
      Alert.alert('Call Failed', 'Unable to initiate cellular call.');
    }
  };

  const activeLat = currentLocation?.latitude ?? lastKnownLocation?.latitude;
  const activeLng = currentLocation?.longitude ?? lastKnownLocation?.longitude;

  return (
    <ScreenContainer
      title="AIZA"
      subtitle="Autonomous Intelligent Zero-latency Armor"
      headerRight={
        <StatusBadge
          label={networkAvailable ? 'ONLINE' : 'OFFLINE'}
          variant={networkAvailable ? 'success' : 'warning'}
        />
      }
    >
      {/* 1. Emergency System Status Banner */}
      <View
        style={[
          styles.systemStatusCard,
          sosActive ? styles.statusCardEmergency : styles.statusCardArmed,
        ]}
      >
        <View style={styles.statusHeaderRow}>
          <View style={styles.statusPill}>
            <View
              style={[
                styles.statusDot,
                { backgroundColor: sosActive ? Colors.sosPulse : Colors.success },
              ]}
            />
            <Text
              style={[
                styles.statusPillText,
                { color: sosActive ? Colors.sosPulse : Colors.success },
              ]}
            >
              {sosActive ? 'EMERGENCY DISPATCH ACTIVE' : 'SYSTEM ARMED & ACTIVE'}
            </Text>
          </View>
          <StatusBadge
            label={hardwareSosConnected ? 'BLE WEARABLE' : 'BLE STANDBY'}
            variant={hardwareSosConnected ? 'info' : 'neutral'}
          />
        </View>

        <Text style={styles.statusDescription}>
          {sosActive
            ? 'Emergency siren, strobe, and contact dispatches are active. Tap CANCEL SOS below to stand down.'
            : 'Continuous multi-layered protection active. Instant SOS dispatch available.'}
        </Text>
      </View>

      {/* 2. Primary SOS Button Hierarchy */}
      <View style={styles.sosContainer}>
        <Animated.View
          style={[
            styles.sosGlowRing,
            sosActive && styles.sosGlowRingActive,
            { transform: [{ scale: pulseAnim }] },
          ]}
        >
          <TouchableOpacity
            style={[styles.sosButton, sosActive && styles.sosButtonActive]}
            onPress={handleSosPress}
            disabled={isDispatching}
            activeOpacity={0.85}
            accessibilityRole="button"
            accessibilityLabel={sosActive ? 'Cancel Emergency SOS' : 'Trigger Emergency SOS'}
            accessibilityHint="Dispatches immediate phone calls, SMS with GPS, siren, and strobe"
          >
            {isDispatching ? (
              <ActivityIndicator size="large" color={Colors.textPrimary} />
            ) : (
              <>
                <Text style={Typography.sosLabel}>{sosActive ? 'CANCEL SOS' : 'SOS'}</Text>
                <Text style={styles.sosSubtext}>
                  {sosActive ? 'TAP TO STAND DOWN' : 'PRESS FOR EMERGENCY'}
                </Text>
              </>
            )}
          </TouchableOpacity>
        </Animated.View>
      </View>

      {/* 3. Live Telemetry & Hardware Cards */}
      <View style={styles.sectionHeaderRow}>
        <Text style={styles.sectionTitle}>DEVICE TELEMETRY</Text>
        <TouchableOpacity onPress={handleRefreshGps} disabled={gpsRefreshing}>
          <Text style={styles.refreshLink}>
            {gpsRefreshing ? 'Acquiring...' : 'Refresh GPS'}
          </Text>
        </TouchableOpacity>
      </View>

      <View style={styles.telemetryGrid}>
        {/* GPS Card */}
        <View style={styles.telemetryCard}>
          <Text style={styles.telemetryLabel}>REAL GPS POSITION</Text>
          {activeLat !== undefined && activeLng !== undefined ? (
            <>
              <Text style={styles.telemetryValue} numberOfLines={1}>
                {activeLat.toFixed(4)}, {activeLng.toFixed(4)}
              </Text>
              <Text style={styles.telemetrySub}>
                {currentLocation
                  ? `Accuracy: ±${Math.round(currentLocation.accuracy)}m`
                  : 'Last known location'}
              </Text>
            </>
          ) : (
            <>
              <Text style={[styles.telemetryValue, { color: Colors.warning }]}>
                GPS Unavailable
              </Text>
              <Text style={styles.telemetrySub}>Tap Refresh GPS above</Text>
            </>
          )}
        </View>

        {/* Battery & System Card */}
        <View style={styles.telemetryCard}>
          <Text style={styles.telemetryLabel}>BATTERY LEVEL</Text>
          <Text
            style={[
              styles.telemetryValue,
              { color: batteryLevel > 20 ? Colors.success : Colors.error },
            ]}
          >
            {batteryLevel}%
          </Text>
          <Text style={styles.telemetrySub}>
            {batteryLevel > 20 ? 'Optimal for dispatch' : 'Low battery warning'}
          </Text>
        </View>
      </View>

      {/* 4. Quick Safety Controls Grid */}
      <View style={styles.sectionHeaderRow}>
        <Text style={styles.sectionTitle}>QUICK SAFETY CONTROLS</Text>
      </View>

      <View style={styles.quickActionsGrid}>
        {/* Siren Toggle */}
        <TouchableOpacity
          style={[styles.quickActionButton, sirenOn && styles.quickActionActive]}
          onPress={handleToggleSiren}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Toggle Emergency Siren"
        >
          <Text style={styles.quickActionIcon}>{sirenOn ? '🔊' : '🔈'}</Text>
          <Text style={styles.quickActionLabel}>
            {sirenOn ? 'Mute Siren' : 'Test Siren'}
          </Text>
        </TouchableOpacity>

        {/* Flashlight Strobe */}
        <TouchableOpacity
          style={[styles.quickActionButton, torchOn && styles.quickActionActive]}
          onPress={handleToggleTorch}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Toggle Camera Torch Strobe"
        >
          <Text style={styles.quickActionIcon}>{torchOn ? '🔦' : '💡'}</Text>
          <Text style={styles.quickActionLabel}>
            {torchOn ? 'Torch On' : 'Strobe Light'}
          </Text>
        </TouchableOpacity>

        {/* Call Primary Guardian */}
        <TouchableOpacity
          style={styles.quickActionButton}
          onPress={handleCallPrimary}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Call Primary Guardian"
        >
          <Text style={styles.quickActionIcon}>📞</Text>
          <Text style={styles.quickActionLabel} numberOfLines={1}>
            {primaryContact ? `Call ${primaryContact.name.split(' ')[0]}` : 'Set Guardian'}
          </Text>
        </TouchableOpacity>

        {/* Radar / Map */}
        <TouchableOpacity
          style={styles.quickActionButton}
          onPress={() => navigation?.navigate('Map')}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Open Safety Radar Map"
        >
          <Text style={styles.quickActionIcon}>📍</Text>
          <Text style={styles.quickActionLabel}>Safety Radar</Text>
        </TouchableOpacity>
      </View>

      {/* 5. Guardian Configuration Warning Banner (if no contacts) */}
      {!primaryContact && (
        <TouchableOpacity
          style={styles.guardianWarningCard}
          onPress={() => navigation?.navigate('Guardians')}
          activeOpacity={0.8}
        >
          <Text style={styles.guardianWarningIcon}>⚠️</Text>
          <View style={styles.guardianWarningTextContainer}>
            <Text style={styles.guardianWarningTitle}>No Emergency Contacts Configured</Text>
            <Text style={styles.guardianWarningDesc}>
              Tap here to add trusted guardians for automated calls and SMS dispatch.
            </Text>
          </View>
        </TouchableOpacity>
      )}
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  systemStatusCard: {
    backgroundColor: Colors.surface,
    padding: Spacing.md,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    marginBottom: Spacing.lg,
  },
  statusCardArmed: {
    borderColor: 'rgba(0, 230, 118, 0.3)',
  },
  statusCardEmergency: {
    backgroundColor: 'rgba(211, 47, 47, 0.15)',
    borderColor: Colors.sosRed,
  },
  statusHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: Spacing.xs,
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  statusPillText: {
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  statusDescription: {
    fontSize: 13,
    color: Colors.textSecondary,
    lineHeight: 18,
    marginTop: 2,
  },
  sosContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    marginVertical: Spacing.lg,
  },
  sosGlowRing: {
    width: 228,
    height: 228,
    borderRadius: 114,
    backgroundColor: 'rgba(233, 30, 99, 0.08)',
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'rgba(233, 30, 99, 0.2)',
  },
  sosGlowRingActive: {
    backgroundColor: 'rgba(211, 47, 47, 0.25)',
    borderColor: Colors.sosPulse,
  },
  sosButton: {
    width: 196,
    height: 196,
    borderRadius: 98,
    backgroundColor: Colors.sosRed,
    justifyContent: 'center',
    alignItems: 'center',
    shadowColor: Colors.sosRed,
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.6,
    shadowRadius: 20,
    elevation: 14,
    borderWidth: 3,
    borderColor: '#FF8A80',
  },
  sosButtonActive: {
    backgroundColor: '#B71C1C',
    borderColor: '#FFFFFF',
    borderWidth: 5,
  },
  sosSubtext: {
    color: '#FFCDD2',
    fontSize: 11,
    fontWeight: '700',
    marginTop: 6,
    letterSpacing: 1,
    textAlign: 'center',
  },
  sectionHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: Spacing.md,
    marginBottom: Spacing.xs,
  },
  sectionTitle: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
    color: Colors.textTertiary,
  },
  refreshLink: {
    fontSize: 12,
    color: Colors.primary,
    fontWeight: '600',
  },
  telemetryGrid: {
    flexDirection: 'row',
    gap: Spacing.sm,
    marginBottom: Spacing.md,
  },
  telemetryCard: {
    flex: 1,
    backgroundColor: Colors.surface,
    padding: Spacing.md,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  telemetryLabel: {
    fontSize: 10,
    fontWeight: '700',
    color: Colors.textTertiary,
    letterSpacing: 0.8,
    marginBottom: 4,
  },
  telemetryValue: {
    fontSize: 15,
    fontWeight: '700',
    color: Colors.textPrimary,
  },
  telemetrySub: {
    fontSize: 11,
    color: Colors.textSecondary,
    marginTop: 2,
  },
  quickActionsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: Spacing.sm,
    marginBottom: Spacing.lg,
  },
  quickActionButton: {
    width: '48%',
    backgroundColor: Colors.surface,
    paddingVertical: Spacing.md,
    paddingHorizontal: Spacing.sm,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 74,
  },
  quickActionActive: {
    backgroundColor: 'rgba(233, 30, 99, 0.15)',
    borderColor: Colors.primary,
  },
  quickActionIcon: {
    fontSize: 22,
    marginBottom: 4,
  },
  quickActionLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: Colors.textPrimary,
  },
  guardianWarningCard: {
    flexDirection: 'row',
    backgroundColor: 'rgba(255, 171, 0, 0.1)',
    borderWidth: 1,
    borderColor: 'rgba(255, 171, 0, 0.35)',
    borderRadius: BorderRadius.md,
    padding: Spacing.md,
    alignItems: 'center',
    gap: Spacing.sm,
    marginTop: Spacing.xs,
  },
  guardianWarningIcon: {
    fontSize: 24,
  },
  guardianWarningTextContainer: {
    flex: 1,
  },
  guardianWarningTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: '#FFD54F',
  },
  guardianWarningDesc: {
    fontSize: 11,
    color: Colors.textSecondary,
    marginTop: 2,
    lineHeight: 16,
  },
});
