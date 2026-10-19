import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  SafeAreaView,
  StatusBar,
} from 'react-native';
import { Colors, Typography, Spacing, BorderRadius } from '../theme';
import { authService } from '../services/AuthService';
import { profileService } from '../services/ProfileService';
import { useSafetyStore } from '../store/safetyStore';

type AuthMode = 'LOGIN' | 'REGISTER' | 'OTP';

export const AuthScreen: React.FC<{ navigation: any }> = ({ navigation }) => {
  const [mode, setMode] = useState<AuthMode>('LOGIN');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [otpToken, setOtpToken] = useState('');

  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  const { setAuthSession, setUserProfile } = useSafetyStore();

  const handleLogin = async () => {
    if (!email.trim() || !password) {
      setErrorMessage('Please enter both email and password.');
      return;
    }

    setIsLoading(true);
    setErrorMessage(null);
    setStatusMessage(null);

    const res = await authService.signIn(email.trim(), password);
    setIsLoading(false);

    if (res.success && res.user && res.session) {
      setAuthSession(res.user, res.session);
      const prof = await profileService.getProfile(res.user.id);
      if (prof.success && prof.data) {
        setUserProfile({
          id: prof.data.id,
          name: prof.data.name,
          email: prof.data.email,
          phone: prof.data.phone,
          createdAt: prof.data.created_at ? new Date(prof.data.created_at).getTime() : Date.now(),
          updatedAt: prof.data.updated_at ? new Date(prof.data.updated_at).getTime() : Date.now(),
        });
      }
      navigation.replace('MainTabs');
    } else {
      setErrorMessage(res.error || 'Authentication failed. Please check credentials.');
    }
  };

  const handleRegister = async () => {
    if (!email.trim() || !password) {
      setErrorMessage('Please provide an email and password.');
      return;
    }
    if (password.length < 6) {
      setErrorMessage('Password must contain at least 6 characters.');
      return;
    }

    setIsLoading(true);
    setErrorMessage(null);
    setStatusMessage(null);

    const res = await authService.signUp(email.trim(), password, {
      name: name.trim(),
      phone: phone.trim(),
    });
    setIsLoading(false);

    if (res.success) {
      if (res.session && res.user) {
        setAuthSession(res.user, res.session);
        await profileService.createProfile({
          id: res.user.id,
          name: name.trim() || 'AIZA User',
          email: email.trim(),
          phone: phone.trim(),
        });
        navigation.replace('MainTabs');
      } else {
        setMode('OTP');
        setStatusMessage(
          `Confirmation code sent to ${email.trim()}. Enter it below to complete activation.`
        );
      }
    } else {
      setErrorMessage(res.error || 'Registration failed.');
    }
  };

  const handleVerifyOtp = async () => {
    if (!otpToken.trim()) {
      setErrorMessage('Please enter the verification code.');
      return;
    }

    setIsLoading(true);
    setErrorMessage(null);
    setStatusMessage(null);

    const res = await authService.verifyEmailOtp(email.trim(), otpToken.trim(), 'signup');
    setIsLoading(false);

    if (res.success && res.user && res.session) {
      setAuthSession(res.user, res.session);
      await profileService.createProfile({
        id: res.user.id,
        name: name.trim() || 'AIZA User',
        email: email.trim(),
        phone: phone.trim(),
      });
      navigation.replace('MainTabs');
    } else {
      setErrorMessage(res.error || 'Invalid or expired OTP code.');
    }
  };

  const handleResendOtp = async () => {
    if (!email.trim()) {
      setErrorMessage('Please provide your email address.');
      return;
    }
    setIsLoading(true);
    setErrorMessage(null);
    const res = await authService.resendVerification(email.trim(), 'signup');
    setIsLoading(false);

    if (res.success) {
      setStatusMessage('Verification code resent successfully to ' + email.trim());
    } else {
      setErrorMessage(res.error || 'Failed to resend verification code.');
    }
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor={Colors.background} />
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.container}
      >
        <ScrollView
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          {/* Header Branding */}
          <View style={styles.header}>
            <View style={styles.logoPill}>
              <Text style={styles.logoPillText}>AIZA</Text>
            </View>
            <Text style={Typography.h1}>
              {mode === 'LOGIN' && 'Welcome Back'}
              {mode === 'REGISTER' && 'Create Account'}
              {mode === 'OTP' && 'Verify Email'}
            </Text>
            <Text style={[Typography.bodyMedium, styles.subtitle]}>
              {mode === 'LOGIN' &&
                'Sign in to access synchronized emergency guardians and live cloud telemetry.'}
              {mode === 'REGISTER' &&
                'Set up your encrypted AIZA guardian profile with zero third-party telemetry.'}
              {mode === 'OTP' && `Enter the 6-digit confirmation code dispatched to ${email}.`}
            </Text>
          </View>

          {/* Feedback Banners */}
          {errorMessage ? (
            <View style={styles.errorBanner}>
              <Text style={styles.errorBannerText}>⚠️ {errorMessage}</Text>
            </View>
          ) : null}

          {statusMessage ? (
            <View style={styles.successBanner}>
              <Text style={styles.successBannerText}>✓ {statusMessage}</Text>
            </View>
          ) : null}

          {/* Form Fields */}
          <View style={styles.formContainer}>
            {mode === 'REGISTER' && (
              <>
                <Text style={styles.inputLabel}>FULL NAME</Text>
                <TextInput
                  style={styles.input}
                  placeholder="Jane Doe"
                  placeholderTextColor={Colors.textDisabled}
                  value={name}
                  onChangeText={setName}
                  autoCapitalize="words"
                />

                <Text style={styles.inputLabel}>PHONE NUMBER (FOR GUARDIANS)</Text>
                <TextInput
                  style={styles.input}
                  placeholder="+15551234567"
                  placeholderTextColor={Colors.textDisabled}
                  value={phone}
                  onChangeText={setPhone}
                  keyboardType="phone-pad"
                />
              </>
            )}

            {mode !== 'OTP' && (
              <>
                <Text style={styles.inputLabel}>EMAIL ADDRESS</Text>
                <TextInput
                  style={styles.input}
                  placeholder="jane@example.com"
                  placeholderTextColor={Colors.textDisabled}
                  value={email}
                  onChangeText={setEmail}
                  keyboardType="email-address"
                  autoCapitalize="none"
                />

                <Text style={styles.inputLabel}>PASSWORD</Text>
                <TextInput
                  style={styles.input}
                  placeholder="••••••••"
                  placeholderTextColor={Colors.textDisabled}
                  value={password}
                  onChangeText={setPassword}
                  secureTextEntry
                />
              </>
            )}

            {mode === 'OTP' && (
              <>
                <Text style={styles.inputLabel}>VERIFICATION CODE (OTP)</Text>
                <TextInput
                  style={[styles.input, styles.otpInput]}
                  placeholder="123456"
                  placeholderTextColor={Colors.textDisabled}
                  value={otpToken}
                  onChangeText={setOtpToken}
                  keyboardType="number-pad"
                  maxLength={8}
                />

                <TouchableOpacity
                  style={styles.resendButton}
                  onPress={handleResendOtp}
                  disabled={isLoading}
                >
                  <Text style={styles.resendButtonText}>Resend Verification Code</Text>
                </TouchableOpacity>
              </>
            )}

            {/* Submit Primary Action */}
            <TouchableOpacity
              style={[styles.primaryButton, isLoading && styles.buttonDisabled]}
              onPress={
                mode === 'LOGIN'
                  ? handleLogin
                  : mode === 'REGISTER'
                  ? handleRegister
                  : handleVerifyOtp
              }
              disabled={isLoading}
              activeOpacity={0.85}
              accessibilityRole="button"
            >
              {isLoading ? (
                <ActivityIndicator color={Colors.textPrimary} />
              ) : (
                <Text style={Typography.button}>
                  {mode === 'LOGIN' && 'Sign In'}
                  {mode === 'REGISTER' && 'Create Account'}
                  {mode === 'OTP' && 'Verify & Enter'}
                </Text>
              )}
            </TouchableOpacity>

            {/* Mode Switch Toggle */}
            <View style={styles.toggleRow}>
              {mode === 'LOGIN' ? (
                <TouchableOpacity
                  onPress={() => {
                    setMode('REGISTER');
                    setErrorMessage(null);
                    setStatusMessage(null);
                  }}
                  style={styles.toggleTouch}
                >
                  <Text style={styles.toggleText}>
                    Don't have an account? <Text style={styles.toggleTextAccent}>Register</Text>
                  </Text>
                </TouchableOpacity>
              ) : (
                <TouchableOpacity
                  onPress={() => {
                    setMode('LOGIN');
                    setErrorMessage(null);
                    setStatusMessage(null);
                  }}
                  style={styles.toggleTouch}
                >
                  <Text style={styles.toggleText}>
                    Already registered? <Text style={styles.toggleTextAccent}>Sign In</Text>
                  </Text>
                </TouchableOpacity>
              )}
            </View>

            {/* Divider */}
            <View style={styles.dividerRow}>
              <View style={styles.dividerLine} />
              <Text style={styles.dividerText}>OR</Text>
              <View style={styles.dividerLine} />
            </View>

            {/* Offline Local Access Bypass (Preserves Phase 1 & 2 full local safety operability) */}
            <TouchableOpacity
              style={styles.offlineBypassButton}
              onPress={() => navigation.replace('MainTabs')}
              activeOpacity={0.8}
              accessibilityRole="button"
              accessibilityLabel="Continue in Local Offline Mode"
            >
              <Text style={styles.offlineBypassText}>Continue in Local Offline Mode</Text>
              <Text style={styles.offlineBypassSub}>Full SOS, Bluetooth & local database active</Text>
            </TouchableOpacity>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
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
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: 'center',
    paddingHorizontal: Spacing.xl,
    paddingVertical: Spacing.xl,
  },
  header: {
    alignItems: 'center',
    marginBottom: Spacing.lg,
  },
  logoPill: {
    backgroundColor: Colors.primaryGlow,
    paddingHorizontal: Spacing.md + 4,
    paddingVertical: 4,
    borderRadius: BorderRadius.full,
    borderWidth: 1,
    borderColor: Colors.primary,
    marginBottom: Spacing.sm,
  },
  logoPillText: {
    color: Colors.accent,
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 2,
  },
  subtitle: {
    marginTop: Spacing.xs,
    lineHeight: 20,
    textAlign: 'center',
    color: Colors.textSecondary,
    maxWidth: 320,
  },
  errorBanner: {
    backgroundColor: 'rgba(255, 23, 68, 0.15)',
    borderLeftWidth: 4,
    borderLeftColor: Colors.error,
    padding: Spacing.md,
    borderRadius: BorderRadius.sm,
    marginBottom: Spacing.md,
  },
  errorBannerText: {
    color: Colors.error,
    fontSize: 13,
    fontWeight: '500',
  },
  successBanner: {
    backgroundColor: 'rgba(0, 230, 118, 0.15)',
    borderLeftWidth: 4,
    borderLeftColor: Colors.success,
    padding: Spacing.md,
    borderRadius: BorderRadius.sm,
    marginBottom: Spacing.md,
  },
  successBannerText: {
    color: Colors.success,
    fontSize: 13,
    fontWeight: '500',
  },
  formContainer: {
    width: '100%',
  },
  inputLabel: {
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1,
    color: Colors.textTertiary,
    marginTop: Spacing.md,
    marginBottom: 4,
  },
  input: {
    backgroundColor: Colors.surface,
    borderColor: Colors.surfaceBorder,
    borderWidth: 1,
    borderRadius: BorderRadius.md,
    paddingHorizontal: Spacing.md,
    paddingVertical: Spacing.sm + 4,
    color: Colors.textPrimary,
    fontSize: 15,
    minHeight: 48,
  },
  otpInput: {
    letterSpacing: 6,
    textAlign: 'center',
    fontSize: 22,
    fontWeight: '700',
  },
  primaryButton: {
    backgroundColor: Colors.primary,
    paddingVertical: Spacing.md,
    borderRadius: BorderRadius.md,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: Spacing.xl,
    minHeight: 50,
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  resendButton: {
    alignSelf: 'center',
    marginTop: Spacing.md,
    padding: Spacing.xs,
  },
  resendButtonText: {
    color: Colors.accent,
    fontSize: 13,
    fontWeight: '600',
  },
  toggleRow: {
    alignItems: 'center',
    marginTop: Spacing.lg,
  },
  toggleTouch: {
    padding: Spacing.xs,
    minHeight: 40,
    justifyContent: 'center',
  },
  toggleText: {
    color: Colors.textSecondary,
    fontSize: 14,
  },
  toggleTextAccent: {
    color: Colors.accent,
    fontWeight: '700',
  },
  dividerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginVertical: Spacing.lg,
  },
  dividerLine: {
    flex: 1,
    height: 1,
    backgroundColor: Colors.surfaceBorder,
  },
  dividerText: {
    color: Colors.textTertiary,
    fontSize: 11,
    fontWeight: '700',
    paddingHorizontal: Spacing.md,
  },
  offlineBypassButton: {
    backgroundColor: Colors.surfaceElevated,
    borderWidth: 1,
    borderColor: Colors.surfaceBorder,
    borderRadius: BorderRadius.md,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: Spacing.md,
    minHeight: 52,
  },
  offlineBypassText: {
    color: Colors.textPrimary,
    fontSize: 13,
    fontWeight: '700',
  },
  offlineBypassSub: {
    color: Colors.textTertiary,
    fontSize: 11,
    marginTop: 2,
  },
});
