import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity, SafeAreaView, StatusBar } from 'react-native';
import { Colors, Typography, Spacing, BorderRadius } from '../theme';

export const OnboardingScreen: React.FC<{ navigation: any }> = ({ navigation }) => {
  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor={Colors.background} />
      <View style={styles.container}>
        {/* Header Branding */}
        <View style={styles.header}>
          <View style={styles.logoBadge}>
            <Text style={styles.logoBadgeText}>AIZA</Text>
          </View>
          <Text style={[Typography.h1, styles.title]}>Mission-Critical Safety</Text>
          <Text style={[Typography.bodyMedium, styles.subtitle]}>
            Continuous personal protection engineered for zero-latency emergency response.
          </Text>
        </View>

        {/* Value Proposition Feature Cards */}
        <View style={styles.cardsContainer}>
          <View style={styles.featureCard}>
            <View style={styles.featureIconContainer}>
              <Text style={styles.featureIcon}>🚨</Text>
            </View>
            <View style={styles.featureTextContainer}>
              <Text style={styles.featureTitle}>Instant SOS Dispatch</Text>
              <Text style={styles.featureDesc}>
                Direct cellular call to your primary guardian, multi-contact SMS, siren, and torch strobe.
              </Text>
            </View>
          </View>

          <View style={styles.featureCard}>
            <View style={styles.featureIconContainer}>
              <Text style={styles.featureIcon}>📡</Text>
            </View>
            <View style={styles.featureTextContainer}>
              <Text style={styles.featureTitle}>ESP32 Hardware Bridge</Text>
              <Text style={styles.featureDesc}>
                Discrete physical wearable trigger via Bluetooth SPP for hands-free emergency alerts.
              </Text>
            </View>
          </View>

          <View style={styles.featureCard}>
            <View style={styles.featureIconContainer}>
              <Text style={styles.featureIcon}>🛡️</Text>
            </View>
            <View style={styles.featureTextContainer}>
              <Text style={styles.featureTitle}>Offline-First Persistence</Text>
              <Text style={styles.featureDesc}>
                Complete local SQLite database guarantees zero-delay safety operation even without internet.
              </Text>
            </View>
          </View>
        </View>

        {/* Action Button */}
        <View style={styles.footer}>
          <TouchableOpacity
            style={styles.button}
            onPress={() => navigation.replace('Auth')}
            activeOpacity={0.85}
            accessibilityRole="button"
            accessibilityLabel="Get Started with AIZA"
          >
            <Text style={Typography.button}>Get Started</Text>
          </TouchableOpacity>
        </View>
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: Colors.background,
  },
  container: {
    flex: 1,
    backgroundColor: Colors.background,
    justifyContent: 'space-between',
    paddingHorizontal: Spacing.xl,
    paddingVertical: Spacing.xl,
  },
  header: {
    alignItems: 'center',
    marginTop: Spacing.lg,
  },
  logoBadge: {
    backgroundColor: Colors.primaryGlow,
    borderWidth: 1,
    borderColor: Colors.primary,
    paddingHorizontal: Spacing.lg,
    paddingVertical: 4,
    borderRadius: BorderRadius.full,
    marginBottom: Spacing.md,
  },
  logoBadgeText: {
    color: Colors.accent,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 2,
  },
  title: {
    textAlign: 'center',
    marginBottom: Spacing.xs,
  },
  subtitle: {
    textAlign: 'center',
    color: Colors.textSecondary,
    lineHeight: 20,
    maxWidth: 320,
  },
  cardsContainer: {
    gap: Spacing.md,
    marginVertical: Spacing.lg,
  },
  featureCard: {
    flexDirection: 'row',
    backgroundColor: Colors.surface,
    padding: Spacing.md,
    borderRadius: BorderRadius.md,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    alignItems: 'center',
    gap: Spacing.md,
  },
  featureIconContainer: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: Colors.surfaceElevated,
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
  },
  featureIcon: {
    fontSize: 22,
  },
  featureTextContainer: {
    flex: 1,
  },
  featureTitle: {
    fontSize: 14,
    fontWeight: '700',
    color: Colors.textPrimary,
    marginBottom: 2,
  },
  featureDesc: {
    fontSize: 12,
    color: Colors.textSecondary,
    lineHeight: 17,
  },
  footer: {
    marginBottom: Spacing.md,
  },
  button: {
    backgroundColor: Colors.primary,
    paddingVertical: Spacing.md,
    borderRadius: BorderRadius.md,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 50,
  },
});
