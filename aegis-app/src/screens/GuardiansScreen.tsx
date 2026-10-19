import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Modal,
  TextInput,
  Alert,
  ActivityIndicator,
} from 'react-native';
import { ScreenContainer } from '../components/ScreenContainer';
import { StatusBadge } from '../components/StatusBadge';
import { EmptyState } from '../components/EmptyState';
import { LoadingState } from '../components/LoadingState';
import { Colors, Typography, Spacing, BorderRadius } from '../theme';
import { useSafetyStore } from '../store/safetyStore';
import { ContactDao, ContactDbRecord } from '../database/ContactDao';
import { EmergencyContactService } from '../services/EmergencyContactService';
import { TelephonyService } from '../services/TelephonyService';
import { GuardianCircleService } from '../services/GuardianCircleService';

export const GuardiansScreen: React.FC<{ navigation?: any }> = ({ navigation }) => {
  const {
    currentUser,
    userProfile,
    networkAvailable,
    locationSharingEnabled,
    setLocationSharingEnabled,
    setContacts,
  } = useSafetyStore();
  const userId = currentUser?.id || userProfile?.id || 'local_user';

  const [contactsList, setContactsList] = useState<ContactDbRecord[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Add / Edit Guardian Modal State
  const [isModalVisible, setIsModalVisible] = useState(false);
  const [editingContact, setEditingContact] = useState<ContactDbRecord | null>(null);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [relationship, setRelationship] = useState('Family');
  const [priorityInput, setPriorityInput] = useState('1');
  const [isActiveGuardian, setIsActiveGuardian] = useState(true);
  const [allowLocationShare, setAllowLocationShare] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  // Load contacts from SQLite first (offline-first mandate), then sync if online
  const loadContacts = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    try {
      // 1. Local SQLite read + Guardian Circle enrichment
      const localContacts = await ContactDao.getAll();
      setContactsList(localContacts);

      // Update Zustand store via GuardianCircleService mapping
      setContacts(
        localContacts.map((c) => GuardianCircleService.mapDbRecordToEmergencyContact(c))
      );

      // 2. If authenticated and online, sync from Supabase without duplicating rows or resetting prefs
      if (currentUser?.id && networkAvailable) {
        try {
          await GuardianCircleService.syncWithSupabase(currentUser.id);
          await GuardianCircleService.getGuardianCircle();
          const refreshed = await ContactDao.getAll();
          setContactsList(refreshed);
        } catch (_) {
          // Keep local contacts on network failure
        }
      }
    } catch (err: any) {
      setErrorMessage('Unable to load contacts from local database.');
    } finally {
      setIsLoading(false);
    }
  }, [currentUser, networkAvailable, setContacts]);

  useEffect(() => {
    loadContacts();
  }, [loadContacts]);

  const openAddModal = () => {
    setEditingContact(null);
    setName('');
    setPhone('');
    setRelationship('Family');
    setPriorityInput(String(contactsList.length + 1));
    setIsActiveGuardian(true);
    // Explicitly false by default — no permanent location sharing enabled automatically
    setAllowLocationShare(false);
    setIsModalVisible(true);
  };

  const openEditModal = (contact: ContactDbRecord) => {
    setEditingContact(contact);
    setName(contact.name);
    setPhone(contact.phone);
    setRelationship(contact.relationship);
    setPriorityInput(String(contact.priority));
    setIsActiveGuardian((contact.is_active ?? 1) === 1);
    setAllowLocationShare((contact.location_sharing_allowed ?? 0) === 1);
    setIsModalVisible(true);
  };

  // Add or Edit Guardian Handler
  const handleSaveGuardian = async () => {
    if (!name.trim() || !phone.trim()) {
      Alert.alert('Required Fields', 'Please provide both guardian name and phone number.');
      return;
    }

    setIsSaving(true);
    try {
      const parsedPriority = Math.max(1, parseInt(priorityInput, 10) || contactsList.length + 1);

      if (editingContact) {
        await GuardianCircleService.editGuardian({
          id: editingContact.id,
          name: name.trim(),
          phone: phone.trim(),
          relationship: relationship.trim() || 'Emergency Contact',
          priority: parsedPriority,
          isActive: isActiveGuardian,
          locationSharingAllowed: allowLocationShare,
        });
      } else {
        await GuardianCircleService.addGuardian({
          userId,
          name: name.trim(),
          phone: phone.trim(),
          relationship: relationship.trim() || 'Emergency Contact',
          priority: parsedPriority,
          isActive: isActiveGuardian,
          locationSharingAllowed: allowLocationShare,
        });
      }

      setIsModalVisible(false);
      setEditingContact(null);
      await loadContacts();
    } catch (e: any) {
      Alert.alert('Error', 'Failed to save guardian.');
    } finally {
      setIsSaving(false);
    }
  };

  // Delete Guardian Handler
  const handleDeleteContact = (contact: ContactDbRecord) => {
    Alert.alert(
      'Remove Guardian',
      `Are you sure you want to remove ${contact.name} from your Guardian Circle?`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Remove',
          style: 'destructive',
          onPress: async () => {
            await GuardianCircleService.removeGuardian(contact.id);
            await loadContacts();
          },
        },
      ]
    );
  };

  // Promote to Rank 1 (Primary)
  const handleMakePrimary = async (contact: ContactDbRecord) => {
    if (contact.priority === 1) return;

    try {
      await GuardianCircleService.setGuardianPriority(contact.id, 1);
      await loadContacts();
    } catch (e) {
      Alert.alert('Error', 'Unable to adjust guardian priority.');
    }
  };

  // Toggle Per-Guardian Active / Inactive State
  const handleToggleActiveState = async (contact: ContactDbRecord) => {
    const currentlyActive = (contact.is_active ?? 1) === 1;
    await GuardianCircleService.setGuardianActiveState(contact.id, !currentlyActive);
    await loadContacts();
  };

  // Toggle Per-Guardian Location Sharing Permission (Explicitly User-Controlled)
  const handleToggleLocationPermission = async (contact: ContactDbRecord) => {
    const currentlyAllowed = (contact.location_sharing_allowed ?? 0) === 1;
    await GuardianCircleService.setGuardianLocationSharing(contact.id, !currentlyAllowed);
    await loadContacts();
  };

  // Share One-Time Location Snapshot
  const handleShareSnapshot = async () => {
    const res = await GuardianCircleService.shareOneTimeLocationSnapshot();
    if (res.latitude !== null && res.longitude !== null && res.sharedCount > 0) {
      Alert.alert(
        'Location Snapshot Shared',
        `Shared one-time GPS snapshot (${res.latitude.toFixed(4)}, ${res.longitude.toFixed(4)}) with ${res.sharedCount} permitted guardian(s).`
      );
    } else {
      Alert.alert(
        'Location Not Shared',
        !locationSharingEnabled
          ? 'Enable Master Location Sharing first to share a snapshot.'
          : 'Enable GPS sharing for at least one active guardian and ensure GPS is available.'
      );
    }
  };

  // Direct Call Action
  const handleCall = async (phoneToCall: string) => {
    try {
      await TelephonyService.initiateEmergencyCall(phoneToCall);
    } catch (e) {
      Alert.alert('Call Failed', 'Unable to initiate cellular call.');
    }
  };

  // Send Test SMS Action
  const handleSendTestSms = async (contact: ContactDbRecord) => {
    try {
      await TelephonyService.sendDirectSms(
        contact.phone,
        `[AIZA System Check] Hello ${contact.name}, you are registered as an emergency guardian in AIZA. No emergency exists.`
      );
      Alert.alert('SMS Dispatched', `Test verification SMS sent to ${contact.name}.`);
    } catch (e) {
      Alert.alert('SMS Failed', 'Unable to send SMS message.');
    }
  };

  const formatLastSeen = (ts?: number | null) => {
    if (!ts) return 'No recent activity';
    try {
      return `Updated ${new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    } catch {
      return 'Recent';
    }
  };

  const getInitials = (fullName: string) => {
    const parts = fullName.trim().split(/\s+/);
    if (parts.length >= 2) {
      return `${parts[0][0] || ''}${parts[1][0] || ''}`.toUpperCase();
    }
    return (fullName.trim().slice(0, 2) || 'GD').toUpperCase();
  };

  return (
    <ScreenContainer
      title="Guardian Circle"
      subtitle="Priority 1 = Direct Phone Call • Priority 2+ = SMS & Circle Alerts"
      headerRight={
        <TouchableOpacity
          style={styles.headerAddButton}
          onPress={openAddModal}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Add New Emergency Guardian"
        >
          <Text style={styles.headerAddButtonText}>+ Add</Text>
        </TouchableOpacity>
      }
    >
      {/* Explicit User-Controlled Location Privacy Bar (No Permanent Surveillance) */}
      <View style={styles.privacyControlCard}>
        <View style={styles.privacyHeaderRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.privacyCardTitle}>
              🛡️ User-Controlled Location Sharing
            </Text>
            <Text style={styles.privacyCardSub}>
              No permanent surveillance. Location sharing is off by default for new guardians and explicitly controlled per guardian.
            </Text>
          </View>
          <TouchableOpacity
            style={[
              styles.masterShareToggle,
              locationSharingEnabled && styles.masterShareToggleActive,
            ]}
            onPress={() => setLocationSharingEnabled(!locationSharingEnabled)}
            accessibilityRole="button"
            accessibilityLabel="Toggle Master Location Sharing"
          >
            <Text style={styles.masterShareToggleText}>
              {locationSharingEnabled ? 'SHARING ON' : 'PAUSED'}
            </Text>
          </TouchableOpacity>
        </View>

        <View style={styles.snapshotRow}>
          <TouchableOpacity
            style={styles.snapshotBtn}
            onPress={handleShareSnapshot}
            accessibilityRole="button"
            accessibilityLabel="Share One-Time Location Snapshot"
          >
            <Text style={styles.snapshotBtnText}>📍 Share One-Time Location Snapshot</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Offline Status Badge Banner */}
      {!networkAvailable && (
        <View style={styles.offlineBanner}>
          <Text style={styles.offlineBannerText}>
            Offline Mode • Using locally stored SQLite Guardian Circle
          </Text>
        </View>
      )}

      {/* Error Message */}
      {errorMessage && (
        <View style={styles.errorBanner}>
          <Text style={styles.errorBannerText}>{errorMessage}</Text>
        </View>
      )}

      {/* Loading State */}
      {isLoading ? (
        <LoadingState message="Loading trusted Guardian Circle..." />
      ) : contactsList.length === 0 ? (
        /* Empty State */
        <EmptyState
          iconSymbol="👥"
          title="No Guardians in Your Circle"
          description="Add trusted family, partners, or friends. Control individual location permissions, active status, and SOS dispatch priority."
          actionLabel="+ Add Primary Guardian"
          onAction={openAddModal}
        />
      ) : (
        /* Guardian Circle Cards List */
        <View style={styles.contactList}>
          {contactsList.map((contact) => {
            const isPrimary = contact.priority === 1;
            const isActive = (contact.is_active ?? 1) === 1;
            const isOnline = contact.is_online === 1;
            const locAllowed = (contact.location_sharing_allowed ?? 0) === 1;
            const connStatus = contact.connection_status ?? (isOnline ? 'ONLINE' : 'OFFLINE');

            return (
              <View
                key={contact.id}
                style={[
                  styles.contactCard,
                  isPrimary && styles.primaryContactCard,
                  !isActive && styles.inactiveContactCard,
                ]}
              >
                {/* Header Row: Priority and Active/Inactive */}
                <View style={styles.cardHeaderRow}>
                  <StatusBadge
                    label={isPrimary ? 'RANK 1 • PRIMARY CALL' : `RANK ${contact.priority} • SMS`}
                    variant={isPrimary ? 'primary' : 'neutral'}
                  />
                  <StatusBadge
                    label={isActive ? 'ACTIVE' : 'INACTIVE'}
                    variant={isActive ? 'info' : 'neutral'}
                    size="small"
                  />
                </View>

                {/* Avatar + Contact Information */}
                <View style={styles.contactProfileRow}>
                  <View style={[styles.avatarCircle, isPrimary && styles.avatarCirclePrimary]}>
                    <Text style={styles.avatarInitials}>{getInitials(contact.name)}</Text>
                  </View>
                  <View style={styles.contactMainInfo}>
                    <Text style={Typography.h3}>{contact.name}</Text>
                    <Text style={styles.contactPhone}>{contact.phone}</Text>
                    <Text style={styles.contactRelationship}>
                      Relationship:{' '}
                      <Text style={{ color: Colors.textPrimary }}>{contact.relationship}</Text>
                    </Text>
                  </View>
                </View>

                {/* Guardian Status & Telemetry Strip */}
                <View style={styles.telemetryStrip}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.telemetryStripText}>
                      📍{' '}
                      {contact.last_known_lat != null && contact.last_known_lng != null
                        ? `Last known: ${contact.last_known_lat.toFixed(4)}, ${contact.last_known_lng.toFixed(4)}`
                        : 'Last known location: Not shared'}
                    </Text>
                    <Text style={styles.telemetryTimeText}>
                      ⏱ {formatLastSeen(contact.last_location_update || contact.updated_at)} • Connection: {connStatus}
                    </Text>
                  </View>
                  <Text style={styles.telemetryStripBattery}>
                    🔋 {contact.battery_level != null ? `${contact.battery_level}%` : 'N/A'}
                  </Text>
                </View>

                {/* Individual Location Sharing & Active Status Controls */}
                <View style={styles.prefsSection}>
                  <Text style={styles.prefsLabel}>GUARDIAN CIRCLE CONTROLS</Text>
                  <View style={styles.prefsChipsRow}>
                    <TouchableOpacity
                      style={[
                        styles.prefChip,
                        locAllowed && styles.prefChipActive,
                      ]}
                      onPress={() => handleToggleLocationPermission(contact)}
                      accessibilityRole="button"
                      accessibilityLabel={`Toggle location sharing for ${contact.name}`}
                    >
                      <Text style={styles.prefChipText}>
                        {locAllowed ? '✓ Share My GPS Allowed' : '✕ GPS Not Shared'}
                      </Text>
                    </TouchableOpacity>

                    <TouchableOpacity
                      style={[
                        styles.prefChip,
                        isActive && styles.prefChipActive,
                      ]}
                      onPress={() => handleToggleActiveState(contact)}
                      accessibilityRole="button"
                      accessibilityLabel={`Toggle active state for ${contact.name}`}
                    >
                      <Text style={styles.prefChipText}>
                        {isActive ? '✓ Active in Circle' : '⏸ Inactive'}
                      </Text>
                    </TouchableOpacity>
                  </View>
                </View>

                {/* Action Buttons Row: Call, Test SMS, Edit, Priority, Delete */}
                <View style={styles.cardActionsRow}>

                  <TouchableOpacity
                    style={styles.actionIconButton}
                    onPress={() => handleCall(contact.phone)}
                    activeOpacity={0.8}
                    accessibilityRole="button"
                    accessibilityLabel={`Call ${contact.name}`}
                  >
                    <Text style={styles.actionIconText}>📞 Call</Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    style={styles.actionIconButton}
                    onPress={() => handleSendTestSms(contact)}
                    activeOpacity={0.8}
                    accessibilityRole="button"
                    accessibilityLabel={`Send test SMS to ${contact.name}`}
                  >
                    <Text style={styles.actionIconText}>📲 SMS</Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    style={styles.actionIconButton}
                    onPress={() => openEditModal(contact)}
                    activeOpacity={0.8}
                    accessibilityRole="button"
                    accessibilityLabel={`Edit ${contact.name}`}
                  >
                    <Text style={styles.actionIconText}>✏️ Edit</Text>
                  </TouchableOpacity>

                  {!isPrimary && (
                    <TouchableOpacity
                      style={styles.actionIconButton}
                      onPress={() => handleMakePrimary(contact)}
                      activeOpacity={0.8}
                      accessibilityRole="button"
                      accessibilityLabel={`Make ${contact.name} primary guardian`}
                    >
                      <Text style={[styles.actionIconText, { color: Colors.accent }]}>
                        ⭐ Rank 1
                      </Text>
                    </TouchableOpacity>
                  )}

                  <TouchableOpacity
                    style={[styles.actionIconButton, styles.deleteButton]}
                    onPress={() => handleDeleteContact(contact)}
                    activeOpacity={0.8}
                    accessibilityRole="button"
                    accessibilityLabel={`Delete ${contact.name}`}
                  >
                    <Text style={[styles.actionIconText, { color: Colors.error }]}>✕</Text>
                  </TouchableOpacity>
                </View>
              </View>
            );
          })}
        </View>
      )}

      {/* Add / Edit Guardian Modal */}
      <Modal
        visible={isModalVisible}
        transparent={true}
        animationType="slide"
        onRequestClose={() => setIsModalVisible(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={Typography.h2}>
              {editingContact ? 'Edit Guardian' : 'Add Emergency Guardian'}
            </Text>
            <Text style={[Typography.bodyMedium, styles.modalSubtitle]}>
              {editingContact
                ? 'Update guardian details, priority rank, active status, and location-sharing permission.'
                : 'Will receive emergency dispatches. Location sharing remains off unless explicitly enabled.'}
            </Text>

            <Text style={styles.modalLabel}>FULL NAME</Text>
            <TextInput
              style={styles.modalInput}
              placeholder="e.g., Mom, Alex Rivera"
              placeholderTextColor={Colors.textDisabled}
              value={name}
              onChangeText={setName}
            />

            <Text style={styles.modalLabel}>PHONE NUMBER (WITH COUNTRY CODE)</Text>
            <TextInput
              style={styles.modalInput}
              placeholder="+15551234567"
              placeholderTextColor={Colors.textDisabled}
              value={phone}
              onChangeText={setPhone}
              keyboardType="phone-pad"
            />

            <Text style={styles.modalLabel}>RELATIONSHIP</Text>
            <TextInput
              style={styles.modalInput}
              placeholder="Family, Partner, Friend, Doctor"
              placeholderTextColor={Colors.textDisabled}
              value={relationship}
              onChangeText={setRelationship}
            />

            <Text style={styles.modalLabel}>PRIORITY RANK (1 = PRIMARY CALL)</Text>
            <TextInput
              style={styles.modalInput}
              placeholder="1"
              placeholderTextColor={Colors.textDisabled}
              value={priorityInput}
              onChangeText={setPriorityInput}
              keyboardType="numeric"
            />

            <View style={styles.modalTogglesRow}>
              <TouchableOpacity
                style={[styles.prefChip, isActiveGuardian && styles.prefChipActive]}
                onPress={() => setIsActiveGuardian(!isActiveGuardian)}
              >
                <Text style={styles.prefChipText}>
                  {isActiveGuardian ? '✓ Status: Active' : '⏸ Status: Inactive'}
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={[styles.prefChip, allowLocationShare && styles.prefChipActive]}
                onPress={() => setAllowLocationShare(!allowLocationShare)}
              >
                <Text style={styles.prefChipText}>
                  {allowLocationShare ? '✓ Allow Location Share' : '✕ Location Share Off'}
                </Text>
              </TouchableOpacity>
            </View>

            <View style={styles.modalButtonsRow}>
              <TouchableOpacity
                style={styles.modalCancelButton}
                onPress={() => setIsModalVisible(false)}
                disabled={isSaving}
              >
                <Text style={styles.modalCancelButtonText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.modalSaveButton}
                onPress={handleSaveGuardian}
                disabled={isSaving}
              >
                {isSaving ? (
                  <ActivityIndicator color={Colors.textPrimary} size="small" />
                ) : (
                  <Text style={styles.modalSaveButtonText}>
                    {editingContact ? 'Update Guardian' : 'Save Guardian'}
                  </Text>
                )}
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  headerAddButton: {
    backgroundColor: Colors.primary,
    paddingHorizontal: Spacing.md,
    paddingVertical: 6,
    borderRadius: BorderRadius.md,
  },
  headerAddButtonText: {
    color: Colors.textPrimary,
    fontWeight: '700',
    fontSize: 13,
  },
  privacyControlCard: {
    backgroundColor: Colors.surface,
    borderRadius: BorderRadius.md,
    padding: Spacing.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    marginBottom: Spacing.md,
  },
  privacyHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: Spacing.sm,
  },
  privacyCardTitle: {
    color: Colors.textPrimary,
    fontWeight: '700',
    fontSize: 13,
  },
  privacyCardSub: {
    color: Colors.textSecondary,
    fontSize: 11,
    marginTop: 2,
    lineHeight: 15,
  },
  masterShareToggle: {
    backgroundColor: Colors.surfaceElevated,
    paddingHorizontal: Spacing.sm + 2,
    paddingVertical: 6,
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  masterShareToggleActive: {
    backgroundColor: 'rgba(0, 230, 118, 0.16)',
    borderColor: Colors.success,
  },
  masterShareToggleText: {
    color: Colors.textPrimary,
    fontSize: 10,
    fontWeight: '800',
  },
  snapshotRow: {
    flexDirection: 'row',
    gap: Spacing.xs,
    marginTop: Spacing.sm,
  },
  snapshotBtn: {
    flex: 1,
    backgroundColor: Colors.surfaceElevated,
    paddingVertical: 8,
    paddingHorizontal: Spacing.sm,
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    alignItems: 'center',
  },
  snapshotBtnText: {
    color: Colors.textPrimary,
    fontSize: 11,
    fontWeight: '600',
  },
  contactProfileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Spacing.md,
    marginBottom: Spacing.md,
  },
  avatarCircle: {
    width: 46,
    height: 46,
    borderRadius: 23,
    backgroundColor: Colors.surfaceElevated,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarCirclePrimary: {
    backgroundColor: 'rgba(233, 30, 99, 0.18)',
    borderColor: Colors.primary,
  },
  avatarInitials: {
    color: Colors.textPrimary,
    fontSize: 15,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  telemetryStrip: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: Colors.surfaceElevated,
    paddingHorizontal: Spacing.sm,
    paddingVertical: 8,
    borderRadius: BorderRadius.sm,
    marginBottom: Spacing.sm,
  },
  telemetryStripText: {
    color: Colors.textSecondary,
    fontSize: 11,
  },
  telemetryTimeText: {
    color: Colors.textTertiary,
    fontSize: 10,
    marginTop: 2,
  },
  telemetryStripBattery: {
    color: Colors.success,
    fontSize: 11,
    fontWeight: '700',
    marginLeft: Spacing.xs,
  },
  prefsSection: {
    marginBottom: Spacing.sm,
  },
  prefsLabel: {
    fontSize: 9,
    fontWeight: '700',
    color: Colors.textTertiary,
    letterSpacing: 0.8,
    marginBottom: 6,
  },
  prefsChipsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  prefChip: {
    backgroundColor: Colors.surfaceElevated,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  prefChipActive: {
    backgroundColor: 'rgba(0, 230, 118, 0.12)',
    borderColor: Colors.success,
  },
  prefChipText: {
    color: Colors.textPrimary,
    fontSize: 11,
    fontWeight: '600',
  },
  offlineBanner: {
    backgroundColor: 'rgba(255, 171, 0, 0.12)',
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.xs,
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: 'rgba(255, 171, 0, 0.3)',
    marginBottom: Spacing.md,
  },
  offlineBannerText: {
    color: '#FFD54F',
    fontSize: 12,
    fontWeight: '500',
  },
  errorBanner: {
    backgroundColor: 'rgba(255, 23, 68, 0.15)',
    padding: Spacing.md,
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: Colors.error,
    marginBottom: Spacing.md,
  },
  errorBannerText: {
    color: Colors.error,
    fontSize: 13,
  },
  contactList: {
    gap: Spacing.md,
    paddingBottom: Spacing.xl,
  },
  contactCard: {
    backgroundColor: Colors.surface,
    padding: Spacing.md,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  primaryContactCard: {
    borderColor: Colors.primary,
    backgroundColor: 'rgba(233, 30, 99, 0.05)',
  },
  inactiveContactCard: {
    opacity: 0.72,
  },
  cardHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: Spacing.sm,
    gap: 6,
  },
  contactMainInfo: {
    flex: 1,
  },
  contactPhone: {
    fontSize: 15,
    color: Colors.textPrimary,
    fontWeight: '600',
    marginTop: 2,
  },
  contactRelationship: {
    fontSize: 12,
    color: Colors.textSecondary,
    marginTop: 2,
  },
  cardActionsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: Spacing.xs,
    borderTopWidth: 1,
    borderTopColor: Colors.surfaceBorder,
    paddingTop: Spacing.sm,
  },
  actionIconButton: {
    backgroundColor: Colors.surfaceElevated,
    paddingHorizontal: Spacing.sm + 2,
    paddingVertical: 8,
    borderRadius: BorderRadius.sm,
    minHeight: 38,
    justifyContent: 'center',
    alignItems: 'center',
  },
  deleteButton: {
    marginLeft: 'auto',
    backgroundColor: 'rgba(255, 23, 68, 0.12)',
    paddingHorizontal: Spacing.md,
  },
  actionIconText: {
    fontSize: 12,
    fontWeight: '600',
    color: Colors.textSecondary,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.75)',
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
    marginTop: Spacing.xs,
    marginBottom: Spacing.lg,
    color: Colors.textSecondary,
  },
  modalLabel: {
    fontSize: 11,
    fontWeight: '700',
    color: Colors.textTertiary,
    letterSpacing: 1,
    marginBottom: 4,
  },
  modalInput: {
    backgroundColor: Colors.surfaceElevated,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    borderRadius: BorderRadius.md,
    color: Colors.textPrimary,
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm + 2,
    fontSize: 14,
    marginBottom: Spacing.md,
  },
  modalTogglesRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: Spacing.xs,
    marginBottom: Spacing.md,
  },
  modalButtonsRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: Spacing.md,
    marginTop: Spacing.sm,
  },
  modalCancelButton: {
    paddingVertical: Spacing.sm,
    paddingHorizontal: Spacing.md,
    justifyContent: 'center',
  },
  modalCancelButtonText: {
    color: Colors.textSecondary,
    fontWeight: '600',
  },
  modalSaveButton: {
    backgroundColor: Colors.primary,
    paddingVertical: Spacing.sm + 2,
    paddingHorizontal: Spacing.lg,
    borderRadius: BorderRadius.md,
    minWidth: 110,
    alignItems: 'center',
    justifyContent: 'center',
  },
  modalSaveButtonText: {
    color: Colors.textPrimary,
    fontWeight: '700',
    fontSize: 14,
  },
});
