"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.offlineStorage = exports.OfflineStorageService = void 0;
const AppDatabase_1 = require("../database/AppDatabase");
const ContactDao_1 = require("../database/ContactDao");
const SosLogDao_1 = require("../database/SosLogDao");
const TrackedLocationDao_1 = require("../database/TrackedLocationDao");
const CachedPlaceDao_1 = require("../database/CachedPlaceDao");
const SafeZoneDao_1 = require("../database/SafeZoneDao");
const safetyStore_1 = require("../store/safetyStore");
class OfflineStorageService {
    constructor() {
        this.isInitialized = false;
    }
    static getInstance() {
        if (!OfflineStorageService.instance) {
            OfflineStorageService.instance = new OfflineStorageService();
        }
        return OfflineStorageService.instance;
    }
    async initialize() {
        if (this.isInitialized)
            return;
        await AppDatabase_1.AppDatabase.initialize();
        this.isInitialized = true;
        await this.reloadStoreFromDatabase();
    }
    async reloadStoreFromDatabase() {
        const store = safetyStore_1.useSafetyStore.getState();
        // 1. Load contacts ordered by priority
        const dbContacts = await ContactDao_1.ContactDao.getAll();
        const contacts = dbContacts.map((c) => ({
            id: c.id,
            userId: c.user_id,
            name: c.name,
            phoneNumber: c.phone,
            relationship: c.relationship,
            priorityOrder: c.priority,
            isVerified: c.verified === 1,
            createdAt: c.created_at,
        }));
        store.setContacts(contacts);
        // 2. Load SOS logs
        const dbLogs = await SosLogDao_1.SosLogDao.getAll();
        const sosLogs = dbLogs.map((l) => ({
            id: l.id,
            userId: l.user_id,
            triggerSource: l.trigger_type,
            latitude: l.latitude,
            longitude: l.longitude,
            isLastKnownLocation: l.location_is_last_known === 1,
            accuracy: 0,
            timestamp: l.timestamp,
            syncStatus: l.status,
            voiceClipPath: l.voice_clip_local_path,
            voiceClipUrl: l.voice_clip_url,
            encryptedPayload: l.encrypted_payload,
            signature: l.signature,
        }));
        store.setSosLogs(sosLogs);
        // 3. Load tracked locations
        const dbLocs = await TrackedLocationDao_1.TrackedLocationDao.getRecent(100);
        const trackedLocations = dbLocs.map((l) => ({
            id: l.id,
            userId: l.user_id,
            latitude: l.latitude,
            longitude: l.longitude,
            accuracy: l.accuracy,
            isSharingEnabled: true,
            timestamp: l.timestamp,
            isSynced: l.synced_to_cloud === 1,
        }));
        store.setTrackedLocations(trackedLocations);
        // 4. Load cached places
        const dbPlaces = await CachedPlaceDao_1.CachedPlaceDao.getAll();
        const cachedPlaces = dbPlaces.map((p) => ({
            id: p.id,
            name: p.name,
            category: p.category,
            latitude: p.latitude,
            longitude: p.longitude,
            address: p.address,
            distanceMeters: p.distance ?? undefined,
            cachedAt: p.timestamp,
        }));
        store.setCachedPlaces(cachedPlaces);
        // 5. Load safe zones
        const dbZones = await SafeZoneDao_1.SafeZoneDao.getAll();
        const safeZones = dbZones.map((z) => ({
            id: z.id,
            userId: z.user_id,
            name: z.name,
            latitude: z.latitude,
            longitude: z.longitude,
            radiusMeters: z.radius,
            isActive: true,
            createdAt: z.created_at,
        }));
        store.setSafeZones(safeZones);
    }
    // --- Contact Methods ---
    async saveContact(contact) {
        const record = {
            id: contact.id,
            user_id: contact.userId,
            name: contact.name,
            phone: contact.phoneNumber,
            relationship: contact.relationship,
            priority: contact.priorityOrder,
            verified: contact.isVerified ? 1 : 0,
            created_at: contact.createdAt || Date.now(),
            updated_at: Date.now(),
        };
        await ContactDao_1.ContactDao.insert(record);
        await this.reloadStoreFromDatabase();
    }
    async deleteContact(id) {
        await ContactDao_1.ContactDao.delete(id);
        await this.reloadStoreFromDatabase();
    }
    async updateContactPriority(id, newPriority) {
        await ContactDao_1.ContactDao.updatePriority(id, newPriority);
        await this.reloadStoreFromDatabase();
    }
    // --- SOS Log Methods ---
    async logSosAlert(alert) {
        const record = {
            id: alert.id,
            user_id: alert.userId,
            trigger_type: alert.triggerSource,
            latitude: alert.latitude,
            longitude: alert.longitude,
            location_available: alert.locationAvailable ? 1 : 0,
            location_is_last_known: alert.locationIsLastKnown ? 1 : 0,
            timestamp: Date.now(),
            status: 'Pending_Sync', // Default offline-first status until cloud confirms
            encrypted_payload: alert.encryptedPayload ?? null,
            signature: alert.signature ?? null,
            voice_clip_local_path: alert.voiceClipLocalPath ?? null,
            voice_clip_url: null,
            synced_to_cloud: 0,
            created_at: Date.now(),
        };
        await SosLogDao_1.SosLogDao.insert(record);
        await this.reloadStoreFromDatabase();
    }
    // --- Tracked Location Methods ---
    async recordLocation(loc) {
        const record = {
            id: loc.id,
            user_id: loc.userId,
            latitude: loc.latitude,
            longitude: loc.longitude,
            accuracy: loc.accuracy,
            timestamp: Date.now(),
            sos_related: loc.sosRelated ? 1 : 0,
            synced_to_cloud: 0,
        };
        await TrackedLocationDao_1.TrackedLocationDao.insert(record);
        await this.reloadStoreFromDatabase();
    }
    // --- Cached Places Methods ---
    async cachePlaces(places) {
        const now = Date.now();
        const records = places.map((p) => ({
            id: p.id,
            place_id: p.placeId,
            name: p.name,
            category: p.category,
            latitude: p.latitude,
            longitude: p.longitude,
            address: p.address,
            distance: p.distance ?? null,
            source: p.source,
            timestamp: now,
        }));
        await CachedPlaceDao_1.CachedPlaceDao.insertBatch(records);
        await this.reloadStoreFromDatabase();
    }
    // --- Safe Zones Methods ---
    async saveSafeZone(zone) {
        const record = {
            id: zone.id,
            user_id: zone.userId,
            name: zone.name,
            latitude: zone.latitude,
            longitude: zone.longitude,
            radius: zone.radius,
            created_at: Date.now(),
        };
        await SafeZoneDao_1.SafeZoneDao.insert(record);
        await this.reloadStoreFromDatabase();
    }
    async deleteSafeZone(id) {
        await SafeZoneDao_1.SafeZoneDao.delete(id);
        await this.reloadStoreFromDatabase();
    }
}
exports.OfflineStorageService = OfflineStorageService;
OfflineStorageService.instance = null;
exports.offlineStorage = OfflineStorageService.getInstance();
