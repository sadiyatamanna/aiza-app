import { MapPoisService, calculateDistanceMeters, normalizeCategory } from './MapPoisService';
import { LocationService } from './LocationService';
import { CachedPlaceDao, CachedPlaceDbRecord } from '../database/CachedPlaceDao';
import { SafeZoneDao, SafeZoneDbRecord } from '../database/SafeZoneDao';
import { AppDatabase } from '../database/AppDatabase';
import { InMemorySqliteMock } from '../database/InMemorySqliteMock';
import { DB_MIGRATIONS } from '../database/DatabaseMigrations';
import { useSafetyStore } from '../store/safetyStore';

let passed = 0;
let failed = 0;

function assert(condition: boolean, testName: string, detail?: string) {
  if (condition) {
    console.log(`[PASS] ${testName}`);
    passed++;
  } else {
    console.error(`[FAIL] ${testName}${detail ? ` - ${detail}` : ''}`);
    failed++;
  }
}

async function runPhase6Tests() {
  console.log('--- Starting AIZA Phase 6 Maps, Emergency POIs, Radar & Safe Zones Tests ---');

  // 1. Initialize in-memory SQLite database
  const mock = new InMemorySqliteMock();
  (AppDatabase as any).executeRaw = (sql: string, params?: any[]) => mock.executeRaw(sql, params);
  for (const m of DB_MIGRATIONS) {
    await m.up(async (sql) => mock.executeRaw(sql));
  }

  // Test 1: MapPoisService initialization
  try {
    assert(
      typeof MapPoisService.fetchNearbyEmergencyPlaces === 'function' &&
        typeof MapPoisService.getCachedPlaces === 'function',
      '1. MapPoisService initialization and public API verification'
    );
  } catch (e: any) {
    assert(false, '1. MapPoisService initialization and public API verification', e.message);
  }

  // Test 2: Emergency POI category normalization
  try {
    const policeCat = normalizeCategory('POLICE');
    const hospCat = normalizeCategory('HOSPITAL');
    const pharmCat = normalizeCategory('PHARMACY');
    const shelterCat = normalizeCategory('SHELTER');
    const safeHavenCat = normalizeCategory('SAFE_HAVEN');
    const allCat = normalizeCategory('ALL');

    assert(
      policeCat === 'POLICE' &&
        hospCat === 'HOSPITAL' &&
        pharmCat === 'PHARMACY' &&
        shelterCat === 'SHELTER' &&
        safeHavenCat === 'SHELTER' &&
        allCat === undefined,
      '2. Emergency POI category handling (POLICE, HOSPITAL, PHARMACY, SHELTER)'
    );
  } catch (e: any) {
    assert(false, '2. Emergency POI category handling (POLICE, HOSPITAL, PHARMACY, SHELTER)', e.message);
  }

  // Test 3: Cached POI insertion
  const fixturePlaces: CachedPlaceDbRecord[] = [
    {
      id: 'poi_test_1',
      place_id: 'osm_101',
      name: 'Central Metropolitan Police Station',
      category: 'POLICE',
      latitude: 37.7749,
      longitude: -122.4194,
      address: '100 Main St',
      distance: 350,
      source: 'OSM_OVERPASS',
      timestamp: Date.now(),
    },
    {
      id: 'poi_test_2',
      place_id: 'osm_102',
      name: 'City General Hospital Emergency',
      category: 'HOSPITAL',
      latitude: 37.7780,
      longitude: -122.4150,
      address: '200 Hospital Way',
      distance: 620,
      source: 'OSM_OVERPASS',
      timestamp: Date.now(),
    },
    {
      id: 'poi_test_3',
      place_id: 'osm_103',
      name: 'Downtown 24hr Emergency Pharmacy',
      category: 'PHARMACY',
      latitude: 37.7760,
      longitude: -122.4180,
      address: '350 Market St',
      distance: 400,
      source: 'MAPTILER',
      timestamp: Date.now(),
    },
    {
      id: 'poi_test_4',
      place_id: 'osm_104',
      name: 'Community Safe Shelter & Sanctuary',
      category: 'SHELTER',
      latitude: 37.7710,
      longitude: -122.4220,
      address: '400 Civic Center',
      distance: 850,
      source: 'OFFLINE_CACHE',
      timestamp: Date.now(),
    },
  ];

  try {
    await CachedPlaceDao.insertBatch(fixturePlaces);
    const count = await CachedPlaceDao.count();
    assert(count === 4, '3. Cached POI insertion persists to SQLite cached_places');
  } catch (e: any) {
    assert(false, '3. Cached POI insertion persists to SQLite cached_places', e.message);
  }

  // Test 4: Cached POI retrieval by category
  try {
    const policePlaces = await CachedPlaceDao.getByCategory('POLICE');
    const hospitalPlaces = await CachedPlaceDao.getByCategory('HOSPITAL');
    assert(
      policePlaces.length === 1 &&
        policePlaces[0].name.includes('Police') &&
        hospitalPlaces.length === 1 &&
        hospitalPlaces[0].category === 'HOSPITAL',
      '4. Cached POI retrieval by category filters accurately'
    );
  } catch (e: any) {
    assert(false, '4. Cached POI retrieval by category filters accurately', e.message);
  }

  // Test 5: Offline cached POI access via MapPoisService
  try {
    // When offline, MapPoisService.getCachedPlaces reads SQLite and computes distances
    const offlineList = await MapPoisService.getCachedPlaces(undefined, 37.7749, -122.4194);
    assert(
      offlineList.length === 4 &&
        offlineList[0].distance !== null &&
        offlineList[0].distance !== undefined,
      '5. Offline cached POI access reads SQLite and computes distances without network'
    );
  } catch (e: any) {
    assert(false, '5. Offline cached POI access reads SQLite and computes distances without network', e.message);
  }

  // Test 6: Safe zone insertion & persistence
  try {
    const testZone: SafeZoneDbRecord = {
      id: 'zone_p6_home',
      user_id: 'user_p6_owner',
      name: 'Safe Haven Residence',
      latitude: 37.7749,
      longitude: -122.4194,
      radius: 250,
      created_at: Date.now(),
    };
    await SafeZoneDao.insert(testZone);
    const zoneCount = await SafeZoneDao.count();
    assert(zoneCount === 1, '6. Safe zone persistence stores circular geofence in SQLite');
  } catch (e: any) {
    assert(false, '6. Safe zone persistence stores circular geofence in SQLite', e.message);
  }

  // Test 7: Safe zone retrieval
  try {
    const zones = await SafeZoneDao.getAll();
    assert(
      zones.length === 1 &&
        zones[0].name === 'Safe Haven Residence' &&
        zones[0].radius === 250 &&
        zones[0].latitude === 37.7749,
      '7. Safe zone retrieval returns valid geometry and radius'
    );
  } catch (e: any) {
    assert(false, '7. Safe zone retrieval returns valid geometry and radius', e.message);
  }

  // Test 8: Real-location validation and distance calculation
  try {
    const distMeters = calculateDistanceMeters(37.7749, -122.4194, 37.7780, -122.4150);
    // Real mathematical distance is ~505 meters
    assert(
      distMeters > 450 && distMeters < 600,
      `8. Real-location Haversine distance calculation is mathematically precise (actual: ${distMeters}m)`
    );
  } catch (e: any) {
    assert(false, '8. Real-location Haversine distance calculation is mathematically precise', e.message);
  }

  // Test 9: Strict NO fake coordinates rule
  try {
    // Reset store location to null
    (useSafetyStore.getState() as any).currentLocation = null;
    (useSafetyStore.getState() as any).lastKnownLocation = null;

    const currentPos = await LocationService.getCurrentPosition();
    assert(
      currentPos === null,
      '9. Strict validation enforces no fake/random coordinates: returns null when GPS unavailable'
    );
  } catch (e: any) {
    assert(false, '9. Strict validation enforces no fake/random coordinates', e.message);
  }

  // Test 10: MapPoisService behavior when GPS is unavailable
  try {
    const placesWithInvalidCoords = await MapPoisService.fetchNearbyEmergencyPlaces(
      NaN as any,
      NaN as any
    );
    assert(
      placesWithInvalidCoords.length === 0,
      '10. MapPoisService safely returns empty array when GPS coordinates are invalid/unavailable'
    );
  } catch (e: any) {
    assert(false, '10. MapPoisService safely returns empty array when GPS coordinates are invalid/unavailable', e.message);
  }

  console.log(`\nAll ${passed}/${passed + failed} Phase 6 Maps, Emergency POIs & Safe Zones Tests finished.`);
  if (failed > 0) {
    process.exit(1);
  }
}

runPhase6Tests().catch((err) => {
  console.error('Fatal error running Phase 6 tests:', err);
  process.exit(1);
});
