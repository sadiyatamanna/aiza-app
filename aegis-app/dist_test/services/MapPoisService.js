"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MapPoisService = exports.MAPTILER_API_KEY = void 0;
exports.calculateDistanceMeters = calculateDistanceMeters;
exports.normalizeCategory = normalizeCategory;
const CachedPlaceDao_1 = require("../database/CachedPlaceDao");
exports.MAPTILER_API_KEY = (typeof process !== 'undefined' && process.env?.MAPTILER_API_KEY) || '';
/**
 * Calculates Haversine distance in meters between two real geographic coordinates.
 * Returns null if either coordinate pair is invalid.
 */
function calculateDistanceMeters(lat1, lon1, lat2, lon2) {
    if (typeof lat1 !== 'number' ||
        typeof lon1 !== 'number' ||
        typeof lat2 !== 'number' ||
        typeof lon2 !== 'number' ||
        isNaN(lat1) ||
        isNaN(lon1) ||
        isNaN(lat2) ||
        isNaN(lon2)) {
        return 0;
    }
    const R = 6371e3; // Earth radius in meters
    const φ1 = (lat1 * Math.PI) / 180;
    const φ2 = (lat2 * Math.PI) / 180;
    const Δφ = ((lat2 - lat1) * Math.PI) / 180;
    const Δλ = ((lon2 - lon1) * Math.PI) / 180;
    const a = Math.sin(Δφ / 2) * Math.sin(Δφ / 2) +
        Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) * Math.sin(Δλ / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return Math.round(R * c);
}
function normalizeCategory(cat) {
    if (!cat || cat === 'ALL')
        return undefined;
    const upper = cat.toUpperCase();
    if (upper === 'POLICE')
        return 'POLICE';
    if (upper === 'HOSPITAL')
        return 'HOSPITAL';
    if (upper === 'PHARMACY')
        return 'PHARMACY';
    if (upper === 'SHELTER' || upper === 'SAFE_HAVEN')
        return 'SHELTER';
    return undefined;
}
exports.MapPoisService = {
    /**
     * Primary service method for Phase 6 emergency POIs.
     * Online: MapTiler / OpenStreetMap Overpass -> SQLite Cache (CachedPlaceDao) -> Results
     * Offline: SQLite Cache (CachedPlaceDao) -> Results
     *
     * Strictly enforces:
     * - No fabricated/random coordinates
     * - Real distance calculations
     * - Persistent caching in SQLite
     */
    async fetchNearbyEmergencyPlaces(latitude, longitude, category, radiusMeters = 8000) {
        // Validate coordinates
        if (typeof latitude !== 'number' ||
            typeof longitude !== 'number' ||
            isNaN(latitude) ||
            isNaN(longitude)) {
            return [];
        }
        const normCat = normalizeCategory(category);
        try {
            // 1. Attempt Online Fetch
            let places = [];
            // Try MapTiler POIs if API key is available
            if (exports.MAPTILER_API_KEY) {
                places = await this.fetchFromMapTiler(latitude, longitude, normCat, radiusMeters);
            }
            // If no places from MapTiler, fallback to OpenStreetMap Overpass
            if (places.length === 0) {
                places = await this.fetchFromOsmOverpass(latitude, longitude, normCat, radiusMeters);
            }
            // If online fetch returned results, cache them to SQLite
            if (places.length > 0) {
                await CachedPlaceDao_1.CachedPlaceDao.insertBatch(places);
                return places;
            }
        }
        catch (err) {
            // Network failure or timeout -> fallback to offline SQLite cache
        }
        // 2. Offline Fallback: Retrieve cached places from SQLite
        return await this.getCachedPlaces(normCat, latitude, longitude);
    },
    /**
     * Retrieve cached places directly from SQLite, calculating distance if user coords provided.
     */
    async getCachedPlaces(category, userLat, userLon) {
        let list = [];
        if (category) {
            list = await CachedPlaceDao_1.CachedPlaceDao.getByCategory(category);
        }
        else {
            list = await CachedPlaceDao_1.CachedPlaceDao.getAll();
        }
        if (typeof userLat === 'number' && typeof userLon === 'number') {
            return list.map((item) => ({
                ...item,
                distance: calculateDistanceMeters(userLat, userLon, item.latitude, item.longitude),
            })).sort((a, b) => (a.distance || 0) - (b.distance || 0));
        }
        return list;
    },
    /**
     * MapTiler Places / Geocoding POI Source
     */
    async fetchFromMapTiler(lat, lon, category, _radiusMeters = 8000) {
        if (!exports.MAPTILER_API_KEY)
            return [];
        const searchQuery = category ? category.toLowerCase() : 'emergency';
        const url = `https://api.maptiler.com/geocoding/${encodeURIComponent(searchQuery)}.json?key=${exports.MAPTILER_API_KEY}&proximity=${lon},${lat}&limit=25`;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 7000);
        try {
            const response = await fetch(url, { signal: controller.signal });
            clearTimeout(timeout);
            if (!response.ok)
                return [];
            const data = await response.json();
            if (!data?.features || !Array.isArray(data.features))
                return [];
            const now = Date.now();
            const results = [];
            for (const feat of data.features) {
                const coords = feat.geometry?.coordinates;
                if (!coords || coords.length < 2)
                    continue;
                const placeLon = coords[0];
                const placeLat = coords[1];
                const distance = calculateDistanceMeters(lat, lon, placeLat, placeLon);
                let detectedCat = category || 'POLICE';
                const textLower = (feat.text || '').toLowerCase();
                if (textLower.includes('hospital') || textLower.includes('clinic')) {
                    detectedCat = 'HOSPITAL';
                }
                else if (textLower.includes('pharmacy') || textLower.includes('chemist')) {
                    detectedCat = 'PHARMACY';
                }
                else if (textLower.includes('police')) {
                    detectedCat = 'POLICE';
                }
                else if (textLower.includes('shelter')) {
                    detectedCat = 'SHELTER';
                }
                results.push({
                    id: `maptiler_${feat.id || Math.random().toString(36).substring(2, 9)}`,
                    place_id: feat.id || `${placeLat}_${placeLon}`,
                    name: feat.text || `${detectedCat} Facility`,
                    category: detectedCat,
                    latitude: placeLat,
                    longitude: placeLon,
                    address: feat.place_name || feat.properties?.address || 'Near current location',
                    distance,
                    source: 'MAPTILER',
                    timestamp: now,
                });
            }
            return results.sort((a, b) => (a.distance || 0) - (b.distance || 0));
        }
        catch (e) {
            clearTimeout(timeout);
            return [];
        }
    },
    /**
     * OpenStreetMap Overpass Fallback POI Source
     */
    async fetchFromOsmOverpass(lat, lon, category, radiusMeters = 8000) {
        let amenityFilter = '';
        if (category === 'POLICE') {
            amenityFilter = '["amenity"="police"]';
        }
        else if (category === 'HOSPITAL') {
            amenityFilter = '["amenity"~"hospital|clinic"]';
        }
        else if (category === 'PHARMACY') {
            amenityFilter = '["amenity"="pharmacy"]';
        }
        else if (category === 'SHELTER') {
            amenityFilter = '["amenity"="shelter"]';
        }
        else {
            amenityFilter = '["amenity"~"police|hospital|pharmacy|shelter"]';
        }
        const query = `
      [out:json][timeout:10];
      (
        node${amenityFilter}(around:${radiusMeters},${lat},${lon});
        way${amenityFilter}(around:${radiusMeters},${lat},${lon});
      );
      out center 35;
    `;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 9000);
        try {
            const response = await fetch('https://overpass-api.de/api/interpreter', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: `data=${encodeURIComponent(query)}`,
                signal: controller.signal,
            });
            clearTimeout(timeout);
            if (!response.ok)
                return [];
            const data = await response.json();
            if (!data?.elements || !Array.isArray(data.elements))
                return [];
            const now = Date.now();
            const results = [];
            for (const el of data.elements) {
                const placeLat = el.lat || el.center?.lat;
                const placeLon = el.lon || el.center?.lon;
                if (typeof placeLat !== 'number' || typeof placeLon !== 'number')
                    continue;
                const distance = calculateDistanceMeters(lat, lon, placeLat, placeLon);
                const tags = el.tags || {};
                const amenity = (tags.amenity || '').toLowerCase();
                let detectedCat = 'POLICE';
                if (amenity.includes('hospital') || amenity.includes('clinic')) {
                    detectedCat = 'HOSPITAL';
                }
                else if (amenity.includes('pharmacy')) {
                    detectedCat = 'PHARMACY';
                }
                else if (amenity.includes('shelter')) {
                    detectedCat = 'SHELTER';
                }
                else if (amenity.includes('police')) {
                    detectedCat = 'POLICE';
                }
                const name = tags.name || `${detectedCat} Facility`;
                const street = tags['addr:street'] || '';
                const city = tags['addr:city'] || '';
                const address = [street, city].filter(Boolean).join(', ') || 'Local Safe Zone / POI';
                results.push({
                    id: `osm_${el.id}`,
                    place_id: `osm_${el.id}`,
                    name,
                    category: detectedCat,
                    latitude: placeLat,
                    longitude: placeLon,
                    address,
                    distance,
                    source: 'OSM_OVERPASS',
                    timestamp: now,
                });
            }
            return results.sort((a, b) => (a.distance || 0) - (b.distance || 0));
        }
        catch (e) {
            clearTimeout(timeout);
            return [];
        }
    },
};
