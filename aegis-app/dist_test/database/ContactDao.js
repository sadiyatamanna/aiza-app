"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ContactDao = void 0;
const AppDatabase_1 = require("./AppDatabase");
exports.ContactDao = {
    async insert(contact) {
        await AppDatabase_1.AppDatabase.executeRaw(`INSERT OR REPLACE INTO contacts (
        id, user_id, name, phone, relationship, priority, verified, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);`, [
            contact.id,
            contact.user_id,
            contact.name,
            contact.phone,
            contact.relationship,
            contact.priority,
            contact.verified,
            contact.created_at,
            contact.updated_at,
        ]);
        const existing = await this.getGuardianPrefs(contact.id);
        if (existing ||
            contact.is_active !== undefined ||
            contact.avatar_url !== undefined ||
            contact.location_sharing_allowed !== undefined ||
            contact.last_known_lat !== undefined ||
            contact.last_known_lng !== undefined ||
            contact.last_location_update !== undefined ||
            contact.battery_level !== undefined ||
            contact.is_online !== undefined ||
            contact.connection_status !== undefined) {
            await this.upsertGuardianPrefs({
                contact_id: contact.id,
                is_active: contact.is_active ?? existing?.is_active ?? 1,
                avatar_url: contact.avatar_url !== undefined ? contact.avatar_url : existing?.avatar_url ?? null,
                // Never enable permanent location sharing automatically (defaults to 0 unless explicitly set)
                location_sharing_allowed: contact.location_sharing_allowed ?? existing?.location_sharing_allowed ?? 0,
                last_known_lat: contact.last_known_lat !== undefined ? contact.last_known_lat : existing?.last_known_lat ?? null,
                last_known_lng: contact.last_known_lng !== undefined ? contact.last_known_lng : existing?.last_known_lng ?? null,
                last_location_update: contact.last_location_update !== undefined
                    ? contact.last_location_update
                    : existing?.last_location_update ?? null,
                battery_level: contact.battery_level !== undefined ? contact.battery_level : existing?.battery_level ?? null,
                is_online: contact.is_online ?? existing?.is_online ?? 0,
                connection_status: contact.connection_status ?? existing?.connection_status ?? 'OFFLINE',
                updated_at: Date.now(),
            });
        }
    },
    async update(contact) {
        await AppDatabase_1.AppDatabase.executeRaw(`UPDATE contacts SET
        name = ?, phone = ?, relationship = ?, priority = ?, verified = ?, updated_at = ?
      WHERE id = ?;`, [
            contact.name,
            contact.phone,
            contact.relationship,
            contact.priority,
            contact.verified,
            contact.updated_at,
            contact.id,
        ]);
        if (contact.is_active !== undefined ||
            contact.avatar_url !== undefined ||
            contact.location_sharing_allowed !== undefined ||
            contact.last_known_lat !== undefined ||
            contact.last_known_lng !== undefined ||
            contact.last_location_update !== undefined ||
            contact.battery_level !== undefined ||
            contact.is_online !== undefined ||
            contact.connection_status !== undefined) {
            const existing = await this.getGuardianPrefs(contact.id);
            await this.upsertGuardianPrefs({
                contact_id: contact.id,
                is_active: contact.is_active ?? existing?.is_active ?? 1,
                avatar_url: contact.avatar_url !== undefined ? contact.avatar_url : existing?.avatar_url ?? null,
                location_sharing_allowed: contact.location_sharing_allowed ?? existing?.location_sharing_allowed ?? 0,
                last_known_lat: contact.last_known_lat !== undefined ? contact.last_known_lat : existing?.last_known_lat ?? null,
                last_known_lng: contact.last_known_lng !== undefined ? contact.last_known_lng : existing?.last_known_lng ?? null,
                last_location_update: contact.last_location_update !== undefined
                    ? contact.last_location_update
                    : existing?.last_location_update ?? null,
                battery_level: contact.battery_level !== undefined ? contact.battery_level : existing?.battery_level ?? null,
                is_online: contact.is_online ?? existing?.is_online ?? 0,
                connection_status: contact.connection_status ?? existing?.connection_status ?? 'OFFLINE',
                updated_at: Date.now(),
            });
        }
    },
    async delete(id) {
        await AppDatabase_1.AppDatabase.executeRaw(`DELETE FROM contacts WHERE id = ?;`, [id]);
        try {
            await AppDatabase_1.AppDatabase.executeRaw(`DELETE FROM guardian_circle_prefs WHERE contact_id = ?;`, [id]);
        }
        catch (_) { }
    },
    async getById(id) {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM contacts WHERE id = ? LIMIT 1;`, [id]);
        if (res.rows && res.rows.length > 0) {
            const base = res.rows[0];
            return await this.enrichWithPrefs(base);
        }
        return null;
    },
    async getAll() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM contacts ORDER BY priority ASC;`);
        const rows = (res.rows || []);
        const enriched = [];
        for (const r of rows) {
            enriched.push(await this.enrichWithPrefs(r));
        }
        return enriched;
    },
    async getAllForUser(userId) {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM contacts WHERE user_id = ? ORDER BY priority ASC;`, [userId]);
        const rows = (res.rows || []);
        const enriched = [];
        for (const r of rows) {
            enriched.push(await this.enrichWithPrefs(r));
        }
        return enriched;
    },
    async updatePriority(id, newPriority) {
        await AppDatabase_1.AppDatabase.executeRaw(`UPDATE contacts SET priority = ?, updated_at = ? WHERE id = ?;`, [newPriority, Date.now(), id]);
    },
    async count() {
        const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT COUNT(*) as cnt FROM contacts;`);
        return res.rows && res.rows.length > 0 ? res.rows[0].cnt : 0;
    },
    async getGuardianPrefs(contactId) {
        try {
            const res = await AppDatabase_1.AppDatabase.executeRaw(`SELECT * FROM guardian_circle_prefs WHERE contact_id = ? LIMIT 1;`, [contactId]);
            if (res.rows && res.rows.length > 0) {
                return res.rows[0];
            }
        }
        catch (_) { }
        return null;
    },
    async upsertGuardianPrefs(prefs) {
        await AppDatabase_1.AppDatabase.executeRaw(`INSERT OR REPLACE INTO guardian_circle_prefs (
        contact_id, is_active, avatar_url, location_sharing_allowed,
        last_known_lat, last_known_lng, last_location_update,
        battery_level, is_online, connection_status, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);`, [
            prefs.contact_id,
            prefs.is_active,
            prefs.avatar_url,
            prefs.location_sharing_allowed,
            prefs.last_known_lat,
            prefs.last_known_lng,
            prefs.last_location_update,
            prefs.battery_level,
            prefs.is_online,
            prefs.connection_status,
            prefs.updated_at,
        ]);
    },
    async updateGuardianPreferences(contactId, updates) {
        const existing = await this.getGuardianPrefs(contactId);
        const merged = {
            contact_id: contactId,
            is_active: updates.isActive !== undefined
                ? updates.isActive
                    ? 1
                    : 0
                : existing?.is_active ?? 1,
            avatar_url: updates.avatarUrl !== undefined ? updates.avatarUrl : existing?.avatar_url ?? null,
            location_sharing_allowed: updates.locationSharingAllowed !== undefined
                ? updates.locationSharingAllowed
                    ? 1
                    : 0
                : existing?.location_sharing_allowed ?? 0,
            last_known_lat: existing?.last_known_lat ?? null,
            last_known_lng: existing?.last_known_lng ?? null,
            last_location_update: existing?.last_location_update ?? null,
            battery_level: existing?.battery_level ?? null,
            is_online: existing?.is_online ?? 0,
            connection_status: existing?.connection_status ?? 'OFFLINE',
            updated_at: Date.now(),
        };
        await this.upsertGuardianPrefs(merged);
    },
    async updateGuardianTelemetry(contactId, telemetry) {
        const existing = await this.getGuardianPrefs(contactId);
        const lat = telemetry.latitude !== undefined
            ? telemetry.latitude
            : telemetry.lastKnownLat !== undefined
                ? telemetry.lastKnownLat
                : existing?.last_known_lat ?? null;
        const lng = telemetry.longitude !== undefined
            ? telemetry.longitude
            : telemetry.lastKnownLng !== undefined
                ? telemetry.lastKnownLng
                : existing?.last_known_lng ?? null;
        const isOnlineVal = telemetry.isOnline !== undefined
            ? telemetry.isOnline
                ? 1
                : 0
            : existing?.is_online ?? 0;
        const connStatus = telemetry.connectionStatus ??
            (isOnlineVal === 1 ? 'ONLINE' : existing?.connection_status ?? 'OFFLINE');
        const merged = {
            contact_id: contactId,
            is_active: existing?.is_active ?? 1,
            avatar_url: existing?.avatar_url ?? null,
            location_sharing_allowed: existing?.location_sharing_allowed ?? 0,
            last_known_lat: lat,
            last_known_lng: lng,
            last_location_update: telemetry.lastLocationUpdate !== undefined
                ? telemetry.lastLocationUpdate
                : existing?.last_location_update ?? Date.now(),
            battery_level: telemetry.batteryLevel !== undefined
                ? telemetry.batteryLevel
                : existing?.battery_level ?? null,
            is_online: isOnlineVal,
            connection_status: connStatus,
            updated_at: Date.now(),
        };
        await this.upsertGuardianPrefs(merged);
    },
    async enrichWithPrefs(contact) {
        const prefs = await this.getGuardianPrefs(contact.id);
        if (!prefs) {
            return {
                ...contact,
                is_active: contact.is_active ?? 1,
                avatar_url: contact.avatar_url ?? null,
                // Never enable permanent location sharing automatically
                location_sharing_allowed: contact.location_sharing_allowed ?? 0,
                last_known_lat: contact.last_known_lat ?? null,
                last_known_lng: contact.last_known_lng ?? null,
                last_location_update: contact.last_location_update ?? null,
                battery_level: contact.battery_level ?? null,
                is_online: contact.is_online ?? 0,
                connection_status: contact.connection_status ?? 'OFFLINE',
            };
        }
        return {
            ...contact,
            is_active: prefs.is_active,
            avatar_url: prefs.avatar_url,
            location_sharing_allowed: prefs.location_sharing_allowed,
            last_known_lat: prefs.last_known_lat,
            last_known_lng: prefs.last_known_lng,
            last_location_update: prefs.last_location_update,
            battery_level: prefs.battery_level,
            is_online: prefs.is_online,
            connection_status: prefs.connection_status,
        };
    },
};
