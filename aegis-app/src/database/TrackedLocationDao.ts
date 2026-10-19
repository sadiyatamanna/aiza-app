import { AppDatabase } from './AppDatabase';

export interface TrackedLocationDbRecord {
  id: string;
  user_id: string;
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
  sos_related: number; // 0 = false, 1 = true
  synced_to_cloud: number; // 0 = false, 1 = true
}

export const TrackedLocationDao = {
  async insert(record: TrackedLocationDbRecord): Promise<void> {
    await AppDatabase.executeRaw(
      `INSERT OR REPLACE INTO tracked_locations (
        id, user_id, latitude, longitude, accuracy, timestamp, sos_related, synced_to_cloud
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);`,
      [
        record.id,
        record.user_id,
        record.latitude,
        record.longitude,
        record.accuracy,
        record.timestamp,
        record.sos_related,
        record.synced_to_cloud,
      ]
    );
  },

  async getLatest(): Promise<TrackedLocationDbRecord | null> {
    const res = await AppDatabase.executeRaw(
      `SELECT * FROM tracked_locations ORDER BY timestamp DESC LIMIT 1;`
    );
    if (res.rows && res.rows.length > 0) {
      return res.rows[0] as TrackedLocationDbRecord;
    }
    return null;
  },

  async getRecent(limit: number = 100): Promise<TrackedLocationDbRecord[]> {
    const res = await AppDatabase.executeRaw(
      `SELECT * FROM tracked_locations ORDER BY timestamp DESC LIMIT ?;`,
      [limit]
    );
    return (res.rows || []) as TrackedLocationDbRecord[];
  },

  async getUnsynced(): Promise<TrackedLocationDbRecord[]> {
    const res = await AppDatabase.executeRaw(
      `SELECT * FROM tracked_locations WHERE synced_to_cloud = 0 ORDER BY timestamp ASC;`
    );
    return (res.rows || []) as TrackedLocationDbRecord[];
  },

  async markAsSynced(ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    const placeholders = ids.map(() => '?').join(',');
    await AppDatabase.executeRaw(
      `UPDATE tracked_locations SET synced_to_cloud = 1 WHERE id IN (${placeholders});`,
      ids
    );
  },

  async count(): Promise<number> {
    const res = await AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM tracked_locations;`);
    return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
  },
};
