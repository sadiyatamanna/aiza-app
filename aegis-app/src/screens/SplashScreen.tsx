import React, { useEffect, useRef } from 'react';
import { View, Text, StyleSheet, Animated, Image } from 'react-native';
import { Colors, Typography, Spacing } from '../theme';
import { useSafetyStore } from '../store/safetyStore';

export const SplashScreen: React.FC<{ navigation: any }> = ({ navigation }) => {
  const logoFadeAnim = useRef(new Animated.Value(0)).current;
  const logoScaleAnim = useRef(new Animated.Value(0.92)).current;
  const textFadeAnim = useRef(new Animated.Value(0)).current;
  const creditFadeAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    // Subtle professional animations: Logo fade & scale up, then title fade in
    Animated.sequence([
      Animated.parallel([
        Animated.timing(logoFadeAnim, {
          toValue: 1,
          duration: 650,
          useNativeDriver: true,
        }),
        Animated.timing(logoScaleAnim, {
          toValue: 1,
          duration: 650,
          useNativeDriver: true,
        }),
      ]),
      Animated.parallel([
        Animated.timing(textFadeAnim, {
          toValue: 1,
          duration: 450,
          useNativeDriver: true,
        }),
        Animated.timing(creditFadeAnim, {
          toValue: 1,
          duration: 500,
          useNativeDriver: true,
        }),
      ]),
    ]).start();

    // Preserve existing routing behavior without excessive delay (~1.5s total)
    // Routes to MainTabs if session/authentication exists, otherwise to Onboarding -> Auth -> Main
    const timer = setTimeout(() => {
      const { isAuthenticated, session } = useSafetyStore.getState();
      if (isAuthenticated || session) {
        navigation.replace('MainTabs');
      } else {
        navigation.replace('Onboarding');
      }
    }, 1600);

    return () => clearTimeout(timer);
  }, [navigation, logoFadeAnim, logoScaleAnim, textFadeAnim, creditFadeAnim]);

  return (
    <View style={styles.container}>
      {/* Centered Main Visual Area: Logo + AIZA */}
      <View style={styles.centerSection}>
        <Animated.View
          style={[
            styles.logoContainer,
            {
              opacity: logoFadeAnim,
              transform: [{ scale: logoScaleAnim }],
            },
          ]}
        >
          <Image
            source={require('../../assets/logo.png')}
            style={styles.logoImage}
            resizeMode="contain"
          />
        </Animated.View>

        <Animated.View style={{ opacity: textFadeAnim, alignItems: 'center' }}>
          <Text style={styles.brandTitle}>AIZA</Text>
        </Animated.View>
      </View>

      {/* Subtle Developer Credit centered near bottom */}
      <Animated.View style={[styles.bottomSection, { opacity: creditFadeAnim }]}>
        <Text style={styles.developerCredit}>Developed by Tam</Text>
      </Animated.View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: Colors.background,
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: Spacing.xxl,
    paddingHorizontal: Spacing.lg,
  },
  centerSection: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  logoContainer: {
    width: 120,
    height: 120,
    borderRadius: 28,
    backgroundColor: Colors.surface,
    borderWidth: 1.5,
    borderColor: 'rgba(255, 46, 99, 0.35)',
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: Spacing.lg,
    shadowColor: Colors.primary,
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.3,
    shadowRadius: 16,
    elevation: 8,
  },
  logoImage: {
    width: 90,
    height: 90,
  },
  brandTitle: {
    color: Colors.textPrimary,
    fontSize: 42,
    fontWeight: '800',
    letterSpacing: 8,
    textAlign: 'center',
  },
  bottomSection: {
    paddingBottom: Spacing.lg,
    alignItems: 'center',
  },
  developerCredit: {
    color: Colors.textTertiary,
    fontSize: 13,
    fontWeight: '500',
    letterSpacing: 1.2,
    textAlign: 'center',
  },
});
