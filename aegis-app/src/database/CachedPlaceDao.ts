import { AppDatabase } from './AppDatabase';

export type DbPlaceCategory = 'POLICE' | 'HOSPITAL' | 'PHARMACY' | 'SHELTER';

export interface CachedPlaceDbRecord {
  id: string;
  place_id: string;
  name: string;
  category: DbPlaceCategory;
  latitude: number;
  longitude: number;
  address: string;
  distance: number | null;
  source: string; // 'GOOGLE_PLACES' | 'OSM_OVERPASS' | 'OFFLINE_CACHE'
  timestamp: number;
}

export const CachedPlaceDao = {
  async insert(place: CachedPlaceDbRecord): Promise<void> {
    await AppDatabase.executeRaw(
      `INSERT OR REPLACE INTO cached_places (
        id, place_id, name, category, latitude, longitude, address, distance, source, timestamp
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);`,
      [
        place.id,
        place.place_id,
        place.name,
        place.category,
        place.latitude,
        place.longitude,
        place.address,
        place.distance,
        place.source,
        place.timestamp,
      ]
    );
  },

  async insertBatch(places: CachedPlaceDbRecord[]): Promise<void> {
    for (const place of places) {
      await this.insert(place);
    }
  },

  async getAll(): Promise<CachedPlaceDbRecord[]> {
    const res = await AppDatabase.executeRaw(`SELECT * FROM cached_places ORDER BY distance ASC;`);
    return (res.rows || []) as CachedPlaceDbRecord[];
  },

  async getByCategory(category: DbPlaceCategory): Promise<CachedPlaceDbRecord[]> {
    const res = await AppDatabase.executeRaw(
      `SELECT * FROM cached_places WHERE category = ? ORDER BY distance ASC;`,
      [category]
    );
    return (res.rows || []) as CachedPlaceDbRecord[];
  },

  async clearOldCache(olderThanTimestamp: number): Promise<void> {
    await AppDatabase.executeRaw(
      `DELETE FROM cached_places WHERE timestamp < ?;`,
      [olderThanTimestamp]
    );
  },

  async count(): Promise<number> {
    const res = await AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM cached_places;`);
    return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
  },
};
