import React, { useEffect } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { StatusBar } from 'react-native';
import { RootNavigator } from './src/navigation/RootNavigator';
import { offlineStorage } from './src/services/OfflineStorageService';
import { networkService } from './src/services/NetworkService';
import { authService } from './src/services/AuthService';
import { profileService } from './src/services/ProfileService';
import { useSafetyStore } from './src/store/safetyStore';

export default function App() {
  const setNetworkAvailable = useSafetyStore((state) => state.setNetworkAvailable);
  const setAuthSession = useSafetyStore((state) => state.setAuthSession);
  const setUserProfile = useSafetyStore((state) => state.setUserProfile);

  useEffect(() => {
    // 1. Initialize SQLite Database and restore local offline state (Phase 2)
    offlineStorage.initialize().catch((err) => {
      console.warn('[AIZA] Offline database initialization warning:', err);
    });

    // 2. Subscribe to network connectivity changes (NetInfo)
    const unsubscribeNetwork = networkService.subscribe((status) => {
      setNetworkAvailable(status === 'ONLINE');
    });

    // 3. Restore existing Supabase session safely without blocking offline startup
    const restoreSession = async () => {
      try {
        const session = await authService.getCurrentSession();
        if (session?.user) {
          setAuthSession(session.user, session);
          // Attempt to load associated cloud profile if online, otherwise continue with offline store
          if (networkService.isOnline()) {
            const profileRes = await profileService.getProfile(session.user.id);
            if (profileRes.success && profileRes.data) {
              setUserProfile({
                id: profileRes.data.id,
                email: profileRes.data.email,
                name: profileRes.data.name,
                phone: profileRes.data.phone,
                createdAt: profileRes.data.created_at ? new Date(profileRes.data.created_at).getTime() : Date.now(),
                updatedAt: profileRes.data.updated_at ? new Date(profileRes.data.updated_at).getTime() : Date.now(),
              });
            }
          }
        }
      } catch (err) {
        console.warn('[AIZA] Supabase session restore warning (continuing offline):', err);
      }
    };

    restoreSession();

    // 4. Listen for ongoing auth state changes (e.g. login, token refresh, logout)
    const { unsubscribe: unsubscribeAuth } = authService.onAuthStateChange(
      async (_event, session) => {
        if (session?.user) {
          setAuthSession(session.user, session);
        } else {
          setAuthSession(null, null);
        }
      }
    );

    return () => {
      unsubscribeNetwork();
      unsubscribeAuth();
    };
  }, [setNetworkAvailable, setAuthSession, setUserProfile]);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#0B0B0E" />
      <RootNavigator />
    </SafeAreaProvider>
  );
}
