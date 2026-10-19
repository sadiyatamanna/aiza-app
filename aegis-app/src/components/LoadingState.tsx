import React from 'react';
import { View, Text, StyleSheet, ActivityIndicator, ViewStyle } from 'react-native';
import { Colors, Typography, Spacing } from '../theme';

interface LoadingStateProps {
  message?: string;
  style?: ViewStyle;
}

export const LoadingState: React.FC<LoadingStateProps> = ({
  message = 'Loading safety telemetry...',
  style,
}) => {
  return (
    <View style={[styles.container, style]} accessibilityRole="progressbar">
      <ActivityIndicator size="large" color={Colors.primary} />
      <Text style={[Typography.bodyMedium, styles.text]}>{message}</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingVertical: Spacing.xxl,
    alignItems: 'center',
    justifyContent: 'center',
    gap: Spacing.md,
  },
  text: {
    color: Colors.textSecondary,
    fontSize: 13,
  },
});
