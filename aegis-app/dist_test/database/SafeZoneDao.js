"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SafeZoneDao = void 0;
const AppDatabase_1 = require("./AppDatabase");
exports.SafeZoneDao = {
    async insert(zone) {
        await AppDatabase_1.AppDatabase.executeRaw(`INSERT OR REPLACE INTO safe_zones (
        id, user_id, name, latitude, longitude, radius, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?);`, [
            zone.id,
            zone.user_id,
            zone.name,
            zone.latitude,
            zone.longitude,
            zone.radius,
            zone.created_at,
        ]);
    },
    async delete(id) {
        await AppDatabase_1.AppDatabase.executeRaw(`DELETE FROM safe_zones WHERE id = ?;`, [id]);
    },
    async getAll() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM safe_zones ORDER BY created_at DESC;`);
        return (res.rows || []);
    },
    async getAllForUser(userId) {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM safe_zones WHERE user_id = ? ORDER BY created_at DESC;`, [userId]);
        return (res.rows || []);
    },
    async count() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM safe_zones;`);
        return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
    },
};
