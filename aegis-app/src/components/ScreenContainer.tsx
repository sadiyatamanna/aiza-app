import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  SafeAreaView,
  StatusBar,
  ViewStyle,
} from 'react-native';
import { Colors, Spacing, Typography } from '../theme';

interface ScreenContainerProps {
  title: string;
  subtitle?: string;
  headerRight?: React.ReactNode;
  children?: React.ReactNode;
  scrollable?: boolean;
  contentContainerStyle?: ViewStyle;
}

export const ScreenContainer: React.FC<ScreenContainerProps> = ({
  title,
  subtitle,
  headerRight,
  children,
  scrollable = true,
  contentContainerStyle,
}) => {
  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor={Colors.background} />
      <View style={styles.container}>
        {/* Header Bar */}
        <View style={styles.header} accessibilityRole="header">
          <View style={styles.titleColumn}>
            <Text style={Typography.h1} numberOfLines={1}>
              {title}
            </Text>
            {subtitle ? (
              <Text style={[Typography.bodySmall, styles.subtitle]} numberOfLines={2}>
                {subtitle}
              </Text>
            ) : null}
          </View>
          {headerRight ? <View style={styles.headerRightContainer}>{headerRight}</View> : null}
        </View>

        {/* Content Body */}
        {scrollable ? (
          <ScrollView
            style={styles.scrollWrapper}
            contentContainerStyle={[styles.scrollContent, contentContainerStyle]}
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
          >
            {children}
          </ScrollView>
        ) : (
          <View style={[styles.fixedContent, contentContainerStyle]}>{children}</View>
        )}
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
    paddingHorizontal: Spacing.md,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingTop: Spacing.md,
    paddingBottom: Spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(46, 46, 62, 0.4)',
    marginBottom: Spacing.sm,
  },
  titleColumn: {
    flex: 1,
    marginRight: Spacing.sm,
  },
  subtitle: {
    marginTop: 2,
    color: Colors.textSecondary,
  },
  headerRightContainer: {
    alignItems: 'flex-end',
    justifyContent: 'center',
  },
  scrollWrapper: {
    flex: 1,
  },
  scrollContent: {
    paddingBottom: Spacing.xxl,
  },
  fixedContent: {
    flex: 1,
    paddingBottom: Spacing.md,
  },
});
