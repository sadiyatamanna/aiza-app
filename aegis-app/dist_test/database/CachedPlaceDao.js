"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.CachedPlaceDao = void 0;
const AppDatabase_1 = require("./AppDatabase");
exports.CachedPlaceDao = {
    async insert(place) {
        await AppDatabase_1.AppDatabase.executeRaw(`INSERT OR REPLACE INTO cached_places (
        id, place_id, name, category, latitude, longitude, address, distance, source, timestamp
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);`, [
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
        ]);
    },
    async insertBatch(places) {
        for (const place of places) {
            await this.insert(place);
        }
    },
    async getAll() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM cached_places ORDER BY distance ASC;`);
        return (res.rows || []);
    },
    async getByCategory(category) {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM cached_places WHERE category = ? ORDER BY distance ASC;`, [category]);
        return (res.rows || []);
    },
    async clearOldCache(olderThanTimestamp) {
        await AppDatabase_1.AppDatabase.executeRaw(`DELETE FROM cached_places WHERE timestamp < ?;`, [olderThanTimestamp]);
    },
    async count() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM cached_places;`);
        return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
    },
};
