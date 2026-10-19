"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TrackedLocationDao = void 0;
const AppDatabase_1 = require("./AppDatabase");
exports.TrackedLocationDao = {
    async insert(record) {
        await AppDatabase_1.AppDatabase.executeRaw(`INSERT OR REPLACE INTO tracked_locations (
        id, user_id, latitude, longitude, accuracy, timestamp, sos_related, synced_to_cloud
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);`, [
            record.id,
            record.user_id,
            record.latitude,
            record.longitude,
            record.accuracy,
            record.timestamp,
            record.sos_related,
            record.synced_to_cloud,
        ]);
    },
    async getLatest() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM tracked_locations ORDER BY timestamp DESC LIMIT 1;`);
        if (res.rows && res.rows.length > 0) {
            return res.rows[0];
        }
        return null;
    },
    async getRecent(limit = 100) {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM tracked_locations ORDER BY timestamp DESC LIMIT ?;`, [limit]);
        return (res.rows || []);
    },
    async getUnsynced() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM tracked_locations WHERE synced_to_cloud = 0 ORDER BY timestamp ASC;`);
        return (res.rows || []);
    },
    async markAsSynced(ids) {
        if (ids.length === 0)
            return;
        const placeholders = ids.map(() => '?').join(',');
        await AppDatabase_1.AppDatabase.executeRaw(`UPDATE tracked_locations SET synced_to_cloud = 1 WHERE id IN (${placeholders});`, ids);
    },
    async count() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM tracked_locations;`);
        return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
    },
};
