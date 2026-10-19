"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SosLogDao = void 0;
const AppDatabase_1 = require("./AppDatabase");
exports.SosLogDao = {
    async insert(log) {
        await AppDatabase_1.AppDatabase.executeRaw(`INSERT OR REPLACE INTO sos_logs (
        id, user_id, trigger_type, latitude, longitude, location_available,
        location_is_last_known, timestamp, status, encrypted_payload, signature,
        voice_clip_local_path, voice_clip_url, synced_to_cloud, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);`, [
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
        ]);
    },
    async getAll() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM sos_logs ORDER BY timestamp DESC;`);
        return (res.rows || []);
    },
    async getById(id) {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM sos_logs WHERE id = ? LIMIT 1;`, [id]);
        if (res.rows && res.rows.length > 0) {
            return res.rows[0];
        }
        return null;
    },
    async getPendingSync() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM sos_logs WHERE synced_to_cloud = 0 OR status = 'Pending_Sync' ORDER BY timestamp ASC;`);
        return (res.rows || []);
    },
    async markAsSynced(id, cloudVoiceClipUrl) {
        if (cloudVoiceClipUrl !== undefined) {
            await AppDatabase_1.AppDatabase.executeRaw(`UPDATE sos_logs SET synced_to_cloud = 1, status = 'Sent', voice_clip_url = ? WHERE id = ?;`, [cloudVoiceClipUrl, id]);
        }
        else {
            await AppDatabase_1.AppDatabase.executeRaw(`UPDATE sos_logs SET synced_to_cloud = 1, status = 'Sent' WHERE id = ?;`, [id]);
        }
    },
    async count() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM sos_logs;`);
        return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
    },
};
