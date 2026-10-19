import { create } from 'zustand';
import { User, Session } from '@supabase/supabase-js';
import {
  CachedPlace,
  EmergencyContact,
  SafeZone,
  SosAlert,
  SosTriggerSource,
  TrackedLocation,
  UserProfile,
} from '../types';

export interface SafetyState {
  // Authentication & Session
  currentUser: User | null;
  session: Session | null;
  userProfile: UserProfile | null;
  isAuthenticated: boolean;
  isOfflineMode: boolean;

  // Real-time GPS & Telemetry
  currentLocation: { latitude: number; longitude: number; accuracy: number } | null;
  lastKnownLocation: { latitude: number; longitude: number } | null;
  locationSharingEnabled: boolean;
  batteryLevel: number;

  // Emergency SOS State
  sosActive: boolean;
  sosCountdownRemaining: number;
  activeSosAlert: SosAlert | null;
  recentSosTrigger: SosTriggerSource | null;

  // Hardware & Connectivity States
  bluetoothEnabled: boolean;
  hardwareSosConnected: boolean; // ESP32 SOS-WEAR SPP connection
  voiceSosEnabled: boolean;
  shakeSosEnabled: boolean;
  networkAvailable: boolean;

  // Contacts & Offline Safety Assets (Phase 2)
  contacts: EmergencyContact[];
  sosLogs: SosAlert[];
  trackedLocations: TrackedLocation[];
  cachedPlaces: CachedPlace[];
  safeZones: SafeZone[];

  // Actions
  setAuthSession: (user: User | null, session: Session | null) => void;
  setUserProfile: (profile: UserProfile | null) => void;
  setCurrentLocation: (loc: { latitude: number; longitude: number; accuracy: number }) => void;
  setLocationSharingEnabled: (enabled: boolean) => void;
  setBluetoothEnabled: (enabled: boolean) => void;
  setHardwareSosConnected: (connected: boolean) => void;
  setVoiceSosEnabled: (enabled: boolean) => void;
  setShakeSosEnabled: (enabled: boolean) => void;
  setNetworkAvailable: (available: boolean) => void;
  setContacts: (contacts: EmergencyContact[]) => void;
  setSosLogs: (logs: SosAlert[]) => void;
  setTrackedLocations: (locations: TrackedLocation[]) => void;
  setCachedPlaces: (places: CachedPlace[]) => void;
  setSafeZones: (zones: SafeZone[]) => void;
  triggerSos: (source: SosTriggerSource) => void;
  cancelSos: () => void;
}

export const useSafetyStore = create<SafetyState>((set) => ({
  currentUser: null,
  session: null,
  userProfile: null,
  isAuthenticated: false,
  isOfflineMode: false,

  currentLocation: null,
  lastKnownLocation: null,
  locationSharingEnabled: true,
  batteryLevel: 100,

  sosActive: false,
  sosCountdownRemaining: 0,
  activeSosAlert: null,
  recentSosTrigger: null,

  bluetoothEnabled: false,
  hardwareSosConnected: false,
  voiceSosEnabled: true,
  shakeSosEnabled: true,
  networkAvailable: true,

  contacts: [],
  sosLogs: [],
  trackedLocations: [],
  cachedPlaces: [],
  safeZones: [],

  setAuthSession: (user, session) =>
    set({
      currentUser: user,
      session: session,
      isAuthenticated: !!session && !!user,
    }),
  setUserProfile: (profile) =>
    set((state) => ({
      userProfile: profile,
      isAuthenticated: !!profile || !!state.currentUser,
    })),
  setCurrentLocation: (loc) =>
    set({
      currentLocation: loc,
      lastKnownLocation: { latitude: loc.latitude, longitude: loc.longitude },
    }),
  setLocationSharingEnabled: (enabled) => set({ locationSharingEnabled: enabled }),
  setBluetoothEnabled: (enabled) => set({ bluetoothEnabled: enabled }),
  setHardwareSosConnected: (connected) => set({ hardwareSosConnected: connected }),
  setVoiceSosEnabled: (enabled) => set({ voiceSosEnabled: enabled }),
  setShakeSosEnabled: (enabled) => set({ shakeSosEnabled: enabled }),
  setNetworkAvailable: (available) => set({ networkAvailable: available }),
  setContacts: (contacts) => set({ contacts }),
  setSosLogs: (sosLogs) => set({ sosLogs }),
  setTrackedLocations: (trackedLocations) => set({ trackedLocations }),
  setCachedPlaces: (cachedPlaces) => set({ cachedPlaces }),
  setSafeZones: (safeZones) => set({ safeZones }),
  triggerSos: (source) =>
    set({
      sosActive: true,
      recentSosTrigger: source,
      sosCountdownRemaining: 0,
    }),
  cancelSos: () =>
    set({
      sosActive: false,
      sosCountdownRemaining: 0,
    }),
}));
