import { AppDatabase } from './AppDatabase';

export interface SafeZoneDbRecord {
  id: string;
  user_id: string;
  name: string;
  latitude: number;
  longitude: number;
  radius: number;
  created_at: number;
}

export const SafeZoneDao = {
  async insert(zone: SafeZoneDbRecord): Promise<void> {
    await AppDatabase.executeRaw(
      `INSERT OR REPLACE INTO safe_zones (
        id, user_id, name, latitude, longitude, radius, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?);`,
      [
        zone.id,
        zone.user_id,
        zone.name,
        zone.latitude,
        zone.longitude,
        zone.radius,
        zone.created_at,
      ]
    );
  },

  async delete(id: string): Promise<void> {
    await AppDatabase.executeRaw(`DELETE FROM safe_zones WHERE id = ?;`, [id]);
  },

  async getAll(): Promise<SafeZoneDbRecord[]> {
    const res = await AppDatabase.executeRaw(`SELECT * FROM safe_zones ORDER BY created_at DESC;`);
    return (res.rows || []) as SafeZoneDbRecord[];
  },

  async getAllForUser(userId: string): Promise<SafeZoneDbRecord[]> {
    const res = await AppDatabase.executeRaw(
      `SELECT * FROM safe_zones WHERE user_id = ? ORDER BY created_at DESC;`,
      [userId]
    );
    return (res.rows || []) as SafeZoneDbRecord[];
  },

  async count(): Promise<number> {
    const res = await AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM safe_zones;`);
    return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
  },
};
