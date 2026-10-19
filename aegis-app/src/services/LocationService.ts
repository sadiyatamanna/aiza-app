import { NativeModules, PermissionsAndroid, Platform } from './nativeBridge';
import { useSafetyStore } from '../store/safetyStore';
import { TrackedLocationDao } from '../database/TrackedLocationDao';

export interface LocationCoordinates {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude?: number;
  speed?: number;
  timestamp: number;
}

export const LocationService = {
  async requestLocationPermissions(): Promise<boolean> {
    if (Platform.OS === 'android') {
      try {
        const fineGranted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
          {
            title: 'Location Permission',
            message: 'AIZA requires real GPS coordinates for emergency SOS and nearby safe place radar.',
            buttonPositive: 'Grant',
          }
        );
        return fineGranted === PermissionsAndroid.RESULTS.GRANTED;
      } catch (err) {
        return false;
      }
    } else if (Platform.OS === 'ios') {
      try {
        if (NativeModules.AegisLocationModule?.requestPermissions) {
          await NativeModules.AegisLocationModule.requestPermissions();
          return true;
        }
      } catch (err) {
        return false;
      }
    }
    return true;
  },

  async getCurrentPosition(): Promise<LocationCoordinates | null> {
    // 1. Try iOS CoreLocation native module if running on iOS
    if (Platform.OS === 'ios' && NativeModules.AegisLocationModule?.getCurrentPosition) {
      try {
        const iosPos = await NativeModules.AegisLocationModule.getCurrentPosition();
        if (iosPos && typeof iosPos.latitude === 'number' && typeof iosPos.longitude === 'number') {
          const loc: LocationCoordinates = {
            latitude: iosPos.latitude,
            longitude: iosPos.longitude,
            accuracy: iosPos.accuracy || 5,
            altitude: iosPos.altitude,
            speed: iosPos.speed,
            timestamp: iosPos.timestamp || Date.now(),
          };
          useSafetyStore.getState().setCurrentLocation(loc);
          return loc;
        }
      } catch (e) {
        // Fall through
      }
    }

    // 2. Try real GPS via navigator.geolocation if available at runtime
    const navGeo = typeof navigator !== 'undefined' ? (navigator as any).geolocation : null;
    if (navGeo) {
      try {
        const pos = await new Promise<LocationCoordinates | null>((resolve) => {
          navGeo.getCurrentPosition(
            (position: any) => {
              resolve({
                latitude: position.coords.latitude,
                longitude: position.coords.longitude,
                accuracy: position.coords.accuracy || 10,
                altitude: position.coords.altitude || undefined,
                speed: position.coords.speed || undefined,
                timestamp: position.timestamp || Date.now(),
              });
            },
            () => resolve(null),
            { enableHighAccuracy: true, timeout: 8000, maximumAge: 15000 }
          );
        });

        if (pos) {
          useSafetyStore.getState().setCurrentLocation(pos);
          return pos;
        }
      } catch (e) {
        // Fall through to store or last known location
      }
    }

    // 2. Check store active location
    const storeLoc = useSafetyStore.getState().currentLocation;
    if (storeLoc && typeof storeLoc.latitude === 'number' && typeof storeLoc.longitude === 'number') {
      return {
        latitude: storeLoc.latitude,
        longitude: storeLoc.longitude,
        accuracy: storeLoc.accuracy || 10,
        timestamp: Date.now(),
      };
    }

    // 3. Check SQLite local database last known location
    try {
      const latestTracked = await TrackedLocationDao.getLatest();
      if (latestTracked && typeof latestTracked.latitude === 'number') {
        return {
          latitude: latestTracked.latitude,
          longitude: latestTracked.longitude,
          accuracy: latestTracked.accuracy,
          timestamp: latestTracked.timestamp,
        };
      }
    } catch (e) {
      // Database unavailable or empty
    }

    // GPS unavailable - strictly return null (no fake or fabricated coordinates)
    return null;
  },

  async getLastKnownPosition(): Promise<LocationCoordinates | null> {
    const storeLast = useSafetyStore.getState().lastKnownLocation;
    if (storeLast && typeof storeLast.latitude === 'number') {
      return {
        latitude: storeLast.latitude,
        longitude: storeLast.longitude,
        accuracy: 20,
        timestamp: Date.now(),
      };
    }

    try {
      const latestTracked = await TrackedLocationDao.getLatest();
      if (latestTracked) {
        return {
          latitude: latestTracked.latitude,
          longitude: latestTracked.longitude,
          accuracy: latestTracked.accuracy,
          timestamp: latestTracked.timestamp,
        };
      }
    } catch (e) {
      // Ignore
    }

    return null;
  },

  startContinuousTracking(onUpdate: (location: LocationCoordinates) => void): () => void {
    let watchId: number | null = null;
    const navGeo = typeof navigator !== 'undefined' ? (navigator as any).geolocation : null;
    if (navGeo) {
      try {
        watchId = navGeo.watchPosition(
          (pos: any) => {
            const loc: LocationCoordinates = {
              latitude: pos.coords.latitude,
              longitude: pos.coords.longitude,
              accuracy: pos.coords.accuracy || 10,
              altitude: pos.coords.altitude || undefined,
              speed: pos.coords.speed || undefined,
              timestamp: pos.timestamp || Date.now(),
            };
            useSafetyStore.getState().setCurrentLocation(loc);
            onUpdate(loc);
          },
          () => {},
          { enableHighAccuracy: true, distanceFilter: 10 }
        );
      } catch (e) {
        // Fallback
      }
    }

    return () => {
      if (watchId !== null && navGeo) {
        navGeo.clearWatch(watchId);
      }
    };
  },
};
