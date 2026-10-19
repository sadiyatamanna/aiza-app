import { AppDatabase } from '../database/AppDatabase';
import { ContactDao, ContactDbRecord } from '../database/ContactDao';
import { SosLogDao, SosLogDbRecord, DbSosTriggerSource } from '../database/SosLogDao';
import { TrackedLocationDao, TrackedLocationDbRecord } from '../database/TrackedLocationDao';
import { CachedPlaceDao, CachedPlaceDbRecord, DbPlaceCategory } from '../database/CachedPlaceDao';
import { SafeZoneDao, SafeZoneDbRecord } from '../database/SafeZoneDao';
import { useSafetyStore } from '../store/safetyStore';
import {
  EmergencyContact,
  SosAlert,
  TrackedLocation,
  CachedPlace,
  SafeZone,
} from '../types';

export class OfflineStorageService {
  private static instance: OfflineStorageService | null = null;
  private isInitialized = false;

  private constructor() {}

  public static getInstance(): OfflineStorageService {
    if (!OfflineStorageService.instance) {
      OfflineStorageService.instance = new OfflineStorageService();
    }
    return OfflineStorageService.instance;
  }

  public async initialize(): Promise<void> {
    if (this.isInitialized) return;
    await AppDatabase.initialize();
    this.isInitialized = true;
    await this.reloadStoreFromDatabase();
  }

  public async reloadStoreFromDatabase(): Promise<void> {
    const store = useSafetyStore.getState();

    // 1. Load contacts ordered by priority
    const dbContacts = await ContactDao.getAll();
    const contacts: EmergencyContact[] = dbContacts.map((c) => ({
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
    const dbLogs = await SosLogDao.getAll();
    const sosLogs: SosAlert[] = dbLogs.map((l) => ({
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
    const dbLocs = await TrackedLocationDao.getRecent(100);
    const trackedLocations: TrackedLocation[] = dbLocs.map((l) => ({
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
    const dbPlaces = await CachedPlaceDao.getAll();
    const cachedPlaces: CachedPlace[] = dbPlaces.map((p) => ({
      id: p.id,
      name: p.name,
      category: p.category as any,
      latitude: p.latitude,
      longitude: p.longitude,
      address: p.address,
      distanceMeters: p.distance ?? undefined,
      cachedAt: p.timestamp,
    }));
    store.setCachedPlaces(cachedPlaces);

    // 5. Load safe zones
    const dbZones = await SafeZoneDao.getAll();
    const safeZones: SafeZone[] = dbZones.map((z) => ({
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
  public async saveContact(contact: EmergencyContact): Promise<void> {
    const record: ContactDbRecord = {
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
    await ContactDao.insert(record);
    await this.reloadStoreFromDatabase();
  }

  public async deleteContact(id: string): Promise<void> {
    await ContactDao.delete(id);
    await this.reloadStoreFromDatabase();
  }

  public async updateContactPriority(id: string, newPriority: number): Promise<void> {
    await ContactDao.updatePriority(id, newPriority);
    await this.reloadStoreFromDatabase();
  }

  // --- SOS Log Methods ---
  public async logSosAlert(alert: {
    id: string;
    userId: string;
    triggerSource: DbSosTriggerSource;
    latitude: number;
    longitude: number;
    locationAvailable: boolean;
    locationIsLastKnown: boolean;
    voiceClipLocalPath?: string | null;
    encryptedPayload?: string | null;
    signature?: string | null;
  }): Promise<void> {
    const record: SosLogDbRecord = {
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
    await SosLogDao.insert(record);
    await this.reloadStoreFromDatabase();
  }

  // --- Tracked Location Methods ---
  public async recordLocation(loc: {
    id: string;
    userId: string;
    latitude: number;
    longitude: number;
    accuracy: number;
    sosRelated: boolean;
  }): Promise<void> {
    const record: TrackedLocationDbRecord = {
      id: loc.id,
      user_id: loc.userId,
      latitude: loc.latitude,
      longitude: loc.longitude,
      accuracy: loc.accuracy,
      timestamp: Date.now(),
      sos_related: loc.sosRelated ? 1 : 0,
      synced_to_cloud: 0,
    };
    await TrackedLocationDao.insert(record);
    await this.reloadStoreFromDatabase();
  }

  // --- Cached Places Methods ---
  public async cachePlaces(
    places: {
      id: string;
      placeId: string;
      name: string;
      category: DbPlaceCategory;
      latitude: number;
      longitude: number;
      address: string;
      distance?: number | null;
      source: string;
    }[]
  ): Promise<void> {
    const now = Date.now();
    const records: CachedPlaceDbRecord[] = places.map((p) => ({
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
    await CachedPlaceDao.insertBatch(records);
    await this.reloadStoreFromDatabase();
  }

  // --- Safe Zones Methods ---
  public async saveSafeZone(zone: {
    id: string;
    userId: string;
    name: string;
    latitude: number;
    longitude: number;
    radius: number;
  }): Promise<void> {
    const record: SafeZoneDbRecord = {
      id: zone.id,
      user_id: zone.userId,
      name: zone.name,
      latitude: zone.latitude,
      longitude: zone.longitude,
      radius: zone.radius,
      created_at: Date.now(),
    };
    await SafeZoneDao.insert(record);
    await this.reloadStoreFromDatabase();
  }

  public async deleteSafeZone(id: string): Promise<void> {
    await SafeZoneDao.delete(id);
    await this.reloadStoreFromDatabase();
  }
}

export const offlineStorage = OfflineStorageService.getInstance();
