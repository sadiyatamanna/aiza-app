import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
  TextInput,
  Alert,
  Modal,
} from 'react-native';
import { ScreenContainer } from '../components/ScreenContainer';
import { StatusBadge } from '../components/StatusBadge';
import { Colors, Typography, Spacing, BorderRadius } from '../theme';
import { useSafetyStore } from '../store/safetyStore';
import { authService } from '../services/AuthService';
import { profileService } from '../services/ProfileService';

export const ProfileScreen: React.FC<{ navigation?: any }> = ({ navigation }) => {
  const {
    currentUser,
    userProfile,
    isAuthenticated,
    hardwareSosConnected,
    setAuthSession,
    setUserProfile,
  } = useSafetyStore();

  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [isEditModalVisible, setIsEditModalVisible] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  // Profile Details State
  const [name, setName] = useState(userProfile?.name || '');
  const [phone, setPhone] = useState(userProfile?.phone || '');

  const openEditModal = () => {
    setName(userProfile?.name || '');
    setPhone(userProfile?.phone || '');
    setIsEditModalVisible(true);
  };

  const handleSaveProfile = async () => {
    setIsSaving(true);
    try {
      const updated = {
        id: userProfile?.id || currentUser?.id || 'local_user',
        name: name.trim() || 'AIZA User',
        email: userProfile?.email || currentUser?.email || 'local@aiza.safety',
        phone: phone.trim(),
        createdAt: userProfile?.createdAt || Date.now(),
        updatedAt: Date.now(),
      };

      setUserProfile(updated);

      if (currentUser?.id) {
        await profileService.updateProfile(currentUser.id, {
          name: updated.name,
          phone: updated.phone,
        });
      }

      setIsEditModalVisible(false);
      Alert.alert('Saved', 'Profile details updated.');
    } catch (_) {
      Alert.alert('Update Failed', 'Could not save profile updates.');
    } finally {
      setIsSaving(false);
    }
  };

  const handleLogout = () => {
    Alert.alert('Sign Out', 'Are you sure you want to sign out from AIZA?', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Sign Out',
        style: 'destructive',
        onPress: async () => {
          setIsLoggingOut(true);
          try {
            await authService.signOut();
            setAuthSession(null, null);
            setUserProfile(null);
          } finally {
            setIsLoggingOut(false);
            if (navigation?.replace) {
              navigation.replace('Auth');
            }
          }
        },
      },
    ]);
  };

  const displayName = userProfile?.name || currentUser?.email?.split('@')[0] || 'AIZA Guardian User';
  const displayEmail = currentUser?.email || userProfile?.email || 'Offline Local Mode';
  const displayPhone = userProfile?.phone || 'Not configured';

  return (
    <ScreenContainer
      title="Profile"
      subtitle="Identity, Telemetry Status & Account Settings"
    >
      {/* 1. Identity & Account Card */}
      <View style={styles.card}>
        <View style={styles.identityRow}>
          <View style={styles.avatarCircle}>
            <Text style={styles.avatarText}>{displayName.charAt(0).toUpperCase()}</Text>
          </View>
          <View style={styles.identityInfo}>
            <Text style={Typography.h2} numberOfLines={1}>
              {displayName}
            </Text>
            <Text style={styles.identityEmail} numberOfLines={1}>
              {displayEmail}
            </Text>
            <View style={{ marginTop: 6 }}>
              <StatusBadge
                label={isAuthenticated ? 'AUTHENTICATED (SUPABASE)' : 'LOCAL / OFFLINE MODE'}
                variant={isAuthenticated ? 'success' : 'warning'}
              />
            </View>
          </View>
        </View>

        {/* Contact Info Strip */}
        <View style={styles.contactDetailsStrip}>
          <View style={styles.detailItem}>
            <Text style={styles.detailLabel}>PHONE NUMBER (DISPATCH)</Text>
            <Text style={styles.detailValue}>{displayPhone}</Text>
          </View>
          <TouchableOpacity
            style={styles.editButton}
            onPress={openEditModal}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Edit Profile Details"
          >
            <Text style={styles.editButtonText}>Edit Details</Text>
          </TouchableOpacity>
        </View>

        {!isAuthenticated && (
          <TouchableOpacity
            style={styles.connectAccountButton}
            onPress={() => navigation?.navigate('Auth')}
            activeOpacity={0.8}
            accessibilityRole="button"
            accessibilityLabel="Sign in to Cloud Account"
          >
            <Text style={styles.connectAccountText}>Sign In to Cloud Account ↗</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* 2. Hardware & Protection Telemetry Card */}
      <View style={styles.card}>
        <Text style={Typography.h3}>Protection Telemetry</Text>

        <View style={styles.telemetryRow}>
          <Text style={styles.telemetryLabel}>ESP32 WEARABLE TRIGGER</Text>
          <StatusBadge
            label={hardwareSosConnected ? 'CONNECTED' : 'STANDBY'}
            variant={hardwareSosConnected ? 'info' : 'neutral'}
          />
        </View>

        <View style={styles.telemetryRow}>
          <Text style={styles.telemetryLabel}>POST-QUANTUM CRYPTO</Text>
          <StatusBadge label="ML-KEM-1024 / ML-DSA-87" variant="pqc" />
        </View>

        <View style={styles.telemetryRow}>
          <Text style={styles.telemetryLabel}>DATABASE STORAGE</Text>
          <StatusBadge label="OFFLINE SQLITE ACTIVE" variant="success" />
        </View>
      </View>

      {/* Developer Credit */}
      <View style={styles.creditContainer}>
        <Text style={styles.creditText}>
          AIZA • Developed by Tam
        </Text>
      </View>

      {/* 3. Sign Out Button */}
      {isAuthenticated && (
        <TouchableOpacity
          style={styles.logoutButton}
          onPress={handleLogout}
          disabled={isLoggingOut}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Sign Out from AIZA"
        >
          {isLoggingOut ? (
            <ActivityIndicator color={Colors.error} size="small" />
          ) : (
            <Text style={styles.logoutButtonText}>Sign Out from AIZA</Text>
          )}
        </TouchableOpacity>
      )}

      {/* Modal for Editing Profile Details */}
      <Modal
        visible={isEditModalVisible}
        transparent={true}
        animationType="slide"
        onRequestClose={() => setIsEditModalVisible(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={Typography.h2}>Edit Profile Details</Text>
            <Text style={[Typography.bodyMedium, styles.modalSubtitle]}>
              Used in emergency SMS dispatches to identified guardians.
            </Text>

            <Text style={styles.modalInputLabel}>FULL NAME</Text>
            <TextInput
              style={styles.modalInput}
              value={name}
              onChangeText={setName}
              placeholder="e.g., Jane Doe"
              placeholderTextColor={Colors.textDisabled}
            />

            <Text style={styles.modalInputLabel}>PHONE NUMBER (WITH COUNTRY CODE)</Text>
            <TextInput
              style={styles.modalInput}
              value={phone}
              onChangeText={setPhone}
              placeholder="+15551234567"
              placeholderTextColor={Colors.textDisabled}
              keyboardType="phone-pad"
            />

            <View style={styles.modalButtonsRow}>
              <TouchableOpacity
                style={styles.modalCancelButton}
                onPress={() => setIsEditModalVisible(false)}
                disabled={isSaving}
              >
                <Text style={styles.modalCancelButtonText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.modalSaveButton}
                onPress={handleSaveProfile}
                disabled={isSaving}
              >
                {isSaving ? (
                  <ActivityIndicator color={Colors.textPrimary} size="small" />
                ) : (
                  <Text style={styles.modalSaveButtonText}>Save Details</Text>
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
  card: {
    backgroundColor: Colors.surface,
    padding: Spacing.md,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    marginBottom: Spacing.md,
  },
  identityRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Spacing.md,
  },
  avatarCircle: {
    width: 58,
    height: 58,
    borderRadius: 29,
    backgroundColor: Colors.primaryGlow,
    borderWidth: 2,
    borderColor: Colors.primary,
    justifyContent: 'center',
    alignItems: 'center',
  },
  avatarText: {
    fontSize: 24,
    fontWeight: '800',
    color: Colors.accent,
  },
  identityInfo: {
    flex: 1,
  },
  identityEmail: {
    fontSize: 13,
    color: Colors.textSecondary,
    marginTop: 2,
  },
  contactDetailsStrip: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: Colors.surfaceElevated,
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm + 2,
    borderRadius: BorderRadius.sm,
    marginTop: Spacing.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  detailItem: {
    flex: 1,
  },
  detailLabel: {
    fontSize: 9,
    fontWeight: '700',
    color: Colors.textTertiary,
    letterSpacing: 0.6,
  },
  detailValue: {
    fontSize: 14,
    fontWeight: '600',
    color: Colors.textPrimary,
    marginTop: 2,
  },
  editButton: {
    backgroundColor: Colors.surface,
    paddingHorizontal: Spacing.md,
    paddingVertical: 6,
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  editButtonText: {
    color: Colors.primary,
    fontWeight: '700',
    fontSize: 12,
  },
  connectAccountButton: {
    marginTop: Spacing.md,
    paddingVertical: Spacing.xs + 2,
    alignItems: 'center',
    borderTopWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  connectAccountText: {
    color: Colors.primary,
    fontSize: 13,
    fontWeight: '600',
  },
  telemetryRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: Spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(46, 46, 62, 0.3)',
  },
  telemetryLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: Colors.textSecondary,
    letterSpacing: 0.5,
  },
  creditContainer: {
    alignItems: 'center',
    marginVertical: Spacing.sm,
  },
  creditText: {
    color: Colors.textTertiary,
    fontSize: 11,
    fontWeight: '700',
  },
  logoutButton: {
    marginTop: Spacing.md,
    backgroundColor: 'rgba(255, 23, 68, 0.12)',
    borderWidth: 1,
    borderColor: Colors.error,
    paddingVertical: Spacing.md,
    borderRadius: BorderRadius.md,
    alignItems: 'center',
    marginBottom: Spacing.xxl,
  },
  logoutButtonText: {
    color: Colors.error,
    fontSize: 14,
    fontWeight: '700',
    letterSpacing: 0.5,
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
    marginBottom: Spacing.md,
    color: Colors.textSecondary,
  },
  modalInputLabel: {
    fontSize: 10,
    fontWeight: '700',
    color: Colors.textTertiary,
    letterSpacing: 0.8,
    marginBottom: 4,
    marginTop: Spacing.xs,
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
    marginBottom: Spacing.sm,
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
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalSaveButtonText: {
    color: Colors.textPrimary,
    fontWeight: '700',
    fontSize: 14,
  },
});
