import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Linking,
} from 'react-native';
import { ScreenContainer } from '../components/ScreenContainer';
import { StatusBadge } from '../components/StatusBadge';
import { EmptyState } from '../components/EmptyState';
import { LoadingState } from '../components/LoadingState';
import { Colors, Spacing, BorderRadius } from '../theme';
import { SosLogDao, SosLogDbRecord } from '../database/SosLogDao';

export const HistoryScreen: React.FC = () => {
  const [logs, setLogs] = useState<SosLogDbRecord[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const loadHistory = useCallback(async () => {
    setIsLoading(true);
    try {
      const records = await SosLogDao.getAll();
      setLogs(records);
    } catch (e) {
      console.warn('Failed to load SOS history from local database:', e);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const formatDate = (timestamp: number) => {
    try {
      const d = new Date(timestamp);
      return d.toLocaleDateString(undefined, {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch (_) {
      return 'Recent Alert';
    }
  };

  const getTriggerVariant = (trigger: string) => {
    switch (trigger) {
      case 'BLE':
        return 'info';
      case 'SHAKE':
        return 'warning';
      case 'VOICE':
        return 'primary';
      case 'MANUAL':
      default:
        return 'error';
    }
  };

  const openLocationInMap = (lat: number, lng: number) => {
    const url = `https://www.google.com/maps/search/?api=1&query=${lat},${lng}`;
    Linking.openURL(url).catch(() => {});
  };

  return (
    <ScreenContainer
      title="History"
      subtitle="Local Emergency Log • Post-Quantum Cryptographic Audit Trail"
      headerRight={
        <TouchableOpacity
          onPress={loadHistory}
          style={styles.refreshButton}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel="Refresh SOS History"
        >
          <Text style={styles.refreshButtonText}>↻ Refresh</Text>
        </TouchableOpacity>
      }
    >
      {/* Summary Stats Header Bar */}
      <View style={styles.summaryGrid}>
        <View style={styles.summaryCard}>
          <Text style={styles.summaryNumber}>{logs.length}</Text>
          <Text style={styles.summaryLabel}>TOTAL EVENTS</Text>
        </View>
        <View style={styles.summaryCard}>
          <Text
            style={[
              styles.summaryNumber,
              { color: Colors.success },
            ]}
          >
            {logs.length}
          </Text>
          <Text style={styles.summaryLabel}>LOCAL AUDIT</Text>
        </View>
        <View style={styles.summaryCard}>
          <Text style={[styles.summaryNumber, { color: Colors.pqcSecure }]}>
            {logs.filter((l) => !!l.signature).length}
          </Text>
          <Text style={styles.summaryLabel}>PQC SIGNED</Text>
        </View>
      </View>

      {/* Loading State */}
      {isLoading ? (
        <LoadingState message="Loading emergency history..." />
      ) : logs.length === 0 ? (
        /* Empty State */
        <EmptyState
          iconSymbol="📋"
          title="No Emergency Alerts Recorded"
          description="Your local emergency log is clear. Whenever SOS is initiated via the app, ESP32 wearable, voice, or motion, an immutable cryptographic record is saved here."
        />
      ) : (
        /* Emergency Records List - Pure Event History */
        <View style={styles.logList}>
          {logs.map((record) => {
            const hasLocation =
              record.location_available === 1 &&
              (record.latitude !== 0 || record.longitude !== 0);

            return (
              <View key={record.id} style={styles.logCard}>
                {/* Header: Trigger Badge & Timestamp */}
                <View style={styles.logHeaderRow}>
                  <View style={styles.triggerGroup}>
                    <StatusBadge
                      label={`TRIGGER: ${record.trigger_type}`}
                      variant={getTriggerVariant(record.trigger_type)}
                    />
                    <Text style={styles.timestampText}>{formatDate(record.timestamp)}</Text>
                  </View>
                  <Text style={styles.eventId} numberOfLines={1}>
                    ID: {record.id.substring(0, 16)}
                  </Text>
                </View>

                {/* Body Details: Location */}
                <View style={styles.locationSection}>
                  <Text style={styles.fieldLabel}>DISPATCH LOCATION</Text>
                  {hasLocation ? (
                    <TouchableOpacity
                      onPress={() => openLocationInMap(record.latitude, record.longitude)}
                      activeOpacity={0.8}
                      style={styles.locationRow}
                    >
                      <Text style={styles.locationCoordinates}>
                        {record.latitude.toFixed(5)}, {record.longitude.toFixed(5)}
                      </Text>
                      <Text style={styles.mapLink}>Open Map ↗</Text>
                    </TouchableOpacity>
                  ) : (
                    <Text style={styles.locationUnavailable}>
                      Location unavailable at time of trigger
                    </Text>
                  )}
                  {record.location_is_last_known === 1 && (
                    <Text style={styles.lastKnownBadge}>Note: Recorded as last known GPS point</Text>
                  )}
                </View>

                {/* Footer Badges: PQC, Audio */}
                <View style={styles.logFooterRow}>
                  <Text style={styles.auditId} numberOfLines={1}>
                    AIZA Secure Audit Record
                  </Text>
                  <View style={styles.badgesGroup}>
                    {record.signature && (
                      <StatusBadge label="PQC SIGNED" variant="pqc" size="small" />
                    )}
                    {(record.voice_clip_local_path || record.voice_clip_url) && (
                      <StatusBadge label="AUDIO CLIP" variant="info" size="small" />
                    )}
                  </View>
                </View>
              </View>
            );
          })}
        </View>
      )}
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  refreshButton: {
    backgroundColor: Colors.surfaceElevated,
    paddingHorizontal: Spacing.sm + 4,
    paddingVertical: 6,
    borderRadius: BorderRadius.sm,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  refreshButtonText: {
    color: Colors.textSecondary,
    fontSize: 12,
    fontWeight: '600',
  },
  summaryGrid: {
    flexDirection: 'row',
    gap: Spacing.sm,
    marginBottom: Spacing.lg,
  },
  summaryCard: {
    flex: 1,
    backgroundColor: Colors.surface,
    paddingVertical: Spacing.md,
    paddingHorizontal: Spacing.xs,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    alignItems: 'center',
  },
  summaryNumber: {
    fontSize: 20,
    fontWeight: '800',
    color: Colors.textPrimary,
  },
  summaryLabel: {
    fontSize: 9,
    fontWeight: '700',
    color: Colors.textTertiary,
    letterSpacing: 0.5,
    marginTop: 4,
    textAlign: 'center',
  },
  logList: {
    gap: Spacing.md,
    paddingBottom: Spacing.xl,
  },
  logCard: {
    backgroundColor: Colors.surface,
    padding: Spacing.md,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  logHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: Spacing.sm,
  },
  triggerGroup: {
    gap: 4,
  },
  timestampText: {
    fontSize: 11,
    color: Colors.textSecondary,
    fontWeight: '500',
    marginTop: 2,
  },
  eventId: {
    fontSize: 10,
    color: Colors.textTertiary,
    fontWeight: '600',
  },
  locationSection: {
    paddingVertical: Spacing.sm,
    borderTopWidth: 1,
    borderBottomWidth: 1,
    borderColor: 'rgba(46, 46, 62, 0.4)',
    marginVertical: Spacing.xs,
  },
  fieldLabel: {
    fontSize: 9,
    fontWeight: '700',
    color: Colors.textTertiary,
    letterSpacing: 0.8,
    marginBottom: 4,
  },
  locationRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  locationCoordinates: {
    fontSize: 13,
    color: Colors.textPrimary,
    fontWeight: '600',
  },
  mapLink: {
    fontSize: 12,
    color: Colors.primary,
    fontWeight: '600',
  },
  locationUnavailable: {
    fontSize: 12,
    color: Colors.textTertiary,
    fontStyle: 'italic',
  },
  lastKnownBadge: {
    fontSize: 10,
    color: Colors.warning,
    marginTop: 2,
  },
  logFooterRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingTop: Spacing.xs,
  },
  auditId: {
    fontSize: 10,
    color: Colors.textTertiary,
    maxWidth: 180,
  },
  badgesGroup: {
    flexDirection: 'row',
    gap: 4,
  },
});
