import { AppDatabase } from './AppDatabase';

export type DbSosTriggerSource = 'MANUAL' | 'SHAKE' | 'VOICE' | 'BLE';
export type DbSosSyncStatus = 'Sent' | 'Pending_Sync';

export interface SosLogDbRecord {
  id: string;
  user_id: string;
  trigger_type: DbSosTriggerSource;
  latitude: number;
  longitude: number;
  location_available: number; // 0 = false, 1 = true
  location_is_last_known: number; // 0 = false, 1 = true
  timestamp: number;
  status: DbSosSyncStatus;
  encrypted_payload: string | null;
  signature: string | null;
  voice_clip_local_path: string | null;
  voice_clip_url: string | null;
  synced_to_cloud: number; // 0 = false, 1 = true
  created_at: number;
}

export const SosLogDao = {
  async insert(log: SosLogDbRecord): Promise<void> {
    await AppDatabase.executeRaw(
      `INSERT OR REPLACE INTO sos_logs (
        id, user_id, trigger_type, latitude, longitude, location_available,
        location_is_last_known, timestamp, status, encrypted_payload, signature,
        voice_clip_local_path, voice_clip_url, synced_to_cloud, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);`,
      [
        log.id,
        log.user_id,
        log.trigger_type,
        log.latitude,
        log.longitude,
        log.location_available,
        log.location_is_last_known,
        log.timestamp,
        log.status,
        log.encrypted_payload,
        log.signature,
        log.voice_clip_local_path,
        log.voice_clip_url,
        log.synced_to_cloud,
        log.created_at,
      ]
    );
  },

  async getAll(): Promise<SosLogDbRecord[]> {
    const res = await AppDatabase.executeRaw(
      `SELECT * FROM sos_logs ORDER BY timestamp DESC;`
    );
    return (res.rows || []) as SosLogDbRecord[];
  },

  async getById(id: string): Promise<SosLogDbRecord | null> {
    const res = await AppDatabase.executeRaw(`SELECT * FROM sos_logs WHERE id = ? LIMIT 1;`, [id]);
    if (res.rows && res.rows.length > 0) {
      return res.rows[0] as SosLogDbRecord;
    }
    return null;
  },

  async getPendingSync(): Promise<SosLogDbRecord[]> {
    const res = await AppDatabase.executeRaw(
      `SELECT * FROM sos_logs WHERE synced_to_cloud = 0 OR status = 'Pending_Sync' ORDER BY timestamp ASC;`
    );
    return (res.rows || []) as SosLogDbRecord[];
  },

  async markAsSynced(id: string, cloudVoiceClipUrl?: string | null): Promise<void> {
    if (cloudVoiceClipUrl !== undefined) {
      await AppDatabase.executeRaw(
        `UPDATE sos_logs SET synced_to_cloud = 1, status = 'Sent', voice_clip_url = ? WHERE id = ?;`,
        [cloudVoiceClipUrl, id]
      );
    } else {
      await AppDatabase.executeRaw(
        `UPDATE sos_logs SET synced_to_cloud = 1, status = 'Sent' WHERE id = ?;`,
        [id]
      );
    }
  },

  async count(): Promise<number> {
    const res = await AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM sos_logs;`);
    return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
  },
};
