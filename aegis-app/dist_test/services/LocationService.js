"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LocationService = void 0;
const nativeBridge_1 = require("./nativeBridge");
const safetyStore_1 = require("../store/safetyStore");
const TrackedLocationDao_1 = require("../database/TrackedLocationDao");
exports.LocationService = {
    async requestLocationPermissions() {
        if (nativeBridge_1.Platform.OS === 'android') {
            try {
                const fineGranted = await nativeBridge_1.PermissionsAndroid.request(nativeBridge_1.PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION, {
                    title: 'Location Permission',
                    message: 'AIZA requires real GPS coordinates for emergency SOS and nearby safe place radar.',
                    buttonPositive: 'Grant',
                });
                return fineGranted === nativeBridge_1.PermissionsAndroid.RESULTS.GRANTED;
            }
            catch (err) {
                return false;
            }
        }
        else if (nativeBridge_1.Platform.OS === 'ios') {
            try {
                if (nativeBridge_1.NativeModules.AegisLocationModule?.requestPermissions) {
                    await nativeBridge_1.NativeModules.AegisLocationModule.requestPermissions();
                    return true;
                }
            }
            catch (err) {
                return false;
            }
        }
        return true;
    },
    async getCurrentPosition() {
        // 1. Try iOS CoreLocation native module if running on iOS
        if (nativeBridge_1.Platform.OS === 'ios' && nativeBridge_1.NativeModules.AegisLocationModule?.getCurrentPosition) {
            try {
                const iosPos = await nativeBridge_1.NativeModules.AegisLocationModule.getCurrentPosition();
                if (iosPos && typeof iosPos.latitude === 'number' && typeof iosPos.longitude === 'number') {
                    const loc = {
                        latitude: iosPos.latitude,
                        longitude: iosPos.longitude,
                        accuracy: iosPos.accuracy || 5,
                        altitude: iosPos.altitude,
                        speed: iosPos.speed,
                        timestamp: iosPos.timestamp || Date.now(),
                    };
                    safetyStore_1.useSafetyStore.getState().setCurrentLocation(loc);
                    return loc;
                }
            }
            catch (e) {
                // Fall through
            }
        }
        // 2. Try real GPS via navigator.geolocation if available at runtime
        const navGeo = typeof navigator !== 'undefined' ? navigator.geolocation : null;
        if (navGeo) {
            try {
                const pos = await new Promise((resolve) => {
                    navGeo.getCurrentPosition((position) => {
                        resolve({
                            latitude: position.coords.latitude,
                            longitude: position.coords.longitude,
                            accuracy: position.coords.accuracy || 10,
                            altitude: position.coords.altitude || undefined,
                            speed: position.coords.speed || undefined,
                            timestamp: position.timestamp || Date.now(),
                        });
                    }, () => resolve(null), { enableHighAccuracy: true, timeout: 8000, maximumAge: 15000 });
                });
                if (pos) {
                    safetyStore_1.useSafetyStore.getState().setCurrentLocation(pos);
                    return pos;
                }
            }
            catch (e) {
                // Fall through to store or last known location
            }
        }
        // 2. Check store active location
        const storeLoc = safetyStore_1.useSafetyStore.getState().currentLocation;
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
            const latestTracked = await TrackedLocationDao_1.TrackedLocationDao.getLatest();
            if (latestTracked && typeof latestTracked.latitude === 'number') {
                return {
                    latitude: latestTracked.latitude,
                    longitude: latestTracked.longitude,
                    accuracy: latestTracked.accuracy,
                    timestamp: latestTracked.timestamp,
                };
            }
        }
        catch (e) {
            // Database unavailable or empty
        }
        // GPS unavailable - strictly return null (no fake or fabricated coordinates)
        return null;
    },
    async getLastKnownPosition() {
        const storeLast = safetyStore_1.useSafetyStore.getState().lastKnownLocation;
        if (storeLast && typeof storeLast.latitude === 'number') {
            return {
                latitude: storeLast.latitude,
                longitude: storeLast.longitude,
                accuracy: 20,
                timestamp: Date.now(),
            };
        }
        try {
            const latestTracked = await TrackedLocationDao_1.TrackedLocationDao.getLatest();
            if (latestTracked) {
                return {
                    latitude: latestTracked.latitude,
                    longitude: latestTracked.longitude,
                    accuracy: latestTracked.accuracy,
                    timestamp: latestTracked.timestamp,
                };
            }
        }
        catch (e) {
            // Ignore
        }
        return null;
    },
    startContinuousTracking(onUpdate) {
        let watchId = null;
        const navGeo = typeof navigator !== 'undefined' ? navigator.geolocation : null;
        if (navGeo) {
            try {
                watchId = navGeo.watchPosition((pos) => {
                    const loc = {
                        latitude: pos.coords.latitude,
                        longitude: pos.coords.longitude,
                        accuracy: pos.coords.accuracy || 10,
                        altitude: pos.coords.altitude || undefined,
                        speed: pos.coords.speed || undefined,
                        timestamp: pos.timestamp || Date.now(),
                    };
                    safetyStore_1.useSafetyStore.getState().setCurrentLocation(loc);
                    onUpdate(loc);
                }, () => { }, { enableHighAccuracy: true, distanceFilter: 10 });
            }
            catch (e) {
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
