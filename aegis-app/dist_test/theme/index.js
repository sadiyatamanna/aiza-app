"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BorderRadius = exports.Typography = exports.Spacing = exports.Colors = void 0;
exports.Colors = {
    // Dark Obsidian Canvas
    background: '#0B0B0E',
    backgroundSecondary: '#141419',
    surface: '#1A1A22',
    surfaceElevated: '#242430',
    surfaceBorder: '#2E2E3E',
    // Core Aegis Brand Accents (Magenta & Crimson)
    primary: '#E91E63',
    primaryDark: '#AD1457',
    primaryGlow: 'rgba(233, 30, 99, 0.28)',
    accent: '#FF4081',
    accentSubtle: '#F48FB1',
    // Emergency SOS Indicator (Critical High-Contrast Red)
    sosRed: '#D32F2F',
    sosRedGlow: 'rgba(211, 47, 47, 0.4)',
    sosPulse: '#FF5252',
    // Status & Telemetry
    success: '#00E676',
    successSubtle: 'rgba(0, 230, 118, 0.15)',
    warning: '#FFD600',
    warningSubtle: 'rgba(255, 214, 0, 0.15)',
    error: '#FF1744',
    info: '#2979FF',
    // Typography
    textPrimary: '#FFFFFF',
    textSecondary: '#A0A0B2',
    textTertiary: '#69697C',
    textDisabled: '#454556',
    textInverse: '#0B0B0E',
    // Hardware & Network Indicators
    bluetoothActive: '#29B6F6',
    gpsActive: '#00E676',
    pqcSecure: '#AB47BC',
};
exports.Spacing = {
    xs: 4,
    sm: 8,
    md: 16,
    lg: 24,
    xl: 32,
    xxl: 48,
};
exports.Typography = {
    h1: {
        fontSize: 28,
        fontWeight: '700',
        letterSpacing: 0.2,
        color: exports.Colors.textPrimary,
    },
    h2: {
        fontSize: 22,
        fontWeight: '700',
        letterSpacing: 0.15,
        color: exports.Colors.textPrimary,
    },
    h3: {
        fontSize: 18,
        fontWeight: '600',
        letterSpacing: 0.1,
        color: exports.Colors.textPrimary,
    },
    bodyLarge: {
        fontSize: 16,
        fontWeight: '400',
        letterSpacing: 0.15,
        color: exports.Colors.textPrimary,
    },
    bodyMedium: {
        fontSize: 14,
        fontWeight: '400',
        letterSpacing: 0.1,
        color: exports.Colors.textSecondary,
    },
    bodySmall: {
        fontSize: 12,
        fontWeight: '400',
        letterSpacing: 0.4,
        color: exports.Colors.textTertiary,
    },
    button: {
        fontSize: 15,
        fontWeight: '600',
        letterSpacing: 0.5,
        color: exports.Colors.textPrimary,
    },
    caption: {
        fontSize: 11,
        fontWeight: '500',
        letterSpacing: 0.5,
        color: exports.Colors.textTertiary,
    },
    sosLabel: {
        fontSize: 24,
        fontWeight: '800',
        letterSpacing: 1.5,
        color: exports.Colors.textPrimary,
    },
};
exports.BorderRadius = {
    sm: 8,
    md: 12,
    lg: 18,
    full: 9999,
};
