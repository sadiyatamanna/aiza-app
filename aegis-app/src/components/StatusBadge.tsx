import React from 'react';
import { View, Text, StyleSheet, ViewStyle } from 'react-native';
import { Colors, BorderRadius } from '../theme';

export type BadgeVariant =
  | 'success'
  | 'warning'
  | 'error'
  | 'info'
  | 'primary'
  | 'neutral'
  | 'pqc';

interface StatusBadgeProps {
  label: string;
  variant?: BadgeVariant;
  size?: 'small' | 'medium';
  style?: ViewStyle;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({
  label,
  variant = 'neutral',
  size = 'small',
  style,
}) => {
  const getColors = () => {
    switch (variant) {
      case 'success':
        return { bg: Colors.successSubtle, text: Colors.success, border: 'rgba(0, 230, 118, 0.3)' };
      case 'warning':
        return { bg: Colors.warningSubtle, text: Colors.warning, border: 'rgba(255, 214, 0, 0.3)' };
      case 'error':
        return { bg: 'rgba(255, 23, 68, 0.15)', text: Colors.error, border: 'rgba(255, 23, 68, 0.35)' };
      case 'info':
        return { bg: 'rgba(41, 121, 255, 0.15)', text: Colors.info, border: 'rgba(41, 121, 255, 0.3)' };
      case 'primary':
        return { bg: Colors.primaryGlow, text: Colors.accent, border: 'rgba(233, 30, 99, 0.35)' };
      case 'pqc':
        return { bg: 'rgba(171, 71, 188, 0.15)', text: Colors.pqcSecure, border: 'rgba(171, 71, 188, 0.35)' };
      case 'neutral':
      default:
        return { bg: Colors.surfaceElevated, text: Colors.textSecondary, border: Colors.surfaceBorder };
    }
  };

  const palette = getColors();
  const isSmall = size === 'small';

  return (
    <View
      style={[
        styles.badge,
        {
          backgroundColor: palette.bg,
          borderColor: palette.border,
          paddingVertical: isSmall ? 2 : 4,
          paddingHorizontal: isSmall ? 8 : 12,
        },
        style,
      ]}
      accessibilityRole="text"
      accessibilityLabel={`Status: ${label}`}
    >
      <Text
        style={[
          styles.text,
          {
            color: palette.text,
            fontSize: isSmall ? 10 : 12,
          },
        ]}
      >
        {label}
      </Text>
    </View>
  );
};

const styles = StyleSheet.create({
  badge: {
    borderRadius: BorderRadius.full,
    borderWidth: 1,
    alignSelf: 'flex-start',
    justifyContent: 'center',
    alignItems: 'center',
  },
  text: {
    fontWeight: '700',
    letterSpacing: 0.6,
    textTransform: 'uppercase',
  },
});
