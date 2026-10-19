import { ContactDao, ContactDbRecord } from '../database/ContactDao';
import { EmergencyContactService, SupabaseEmergencyContactRecord } from './EmergencyContactService';
import { UserLocationService } from './UserLocationService';
import { LocationService } from './LocationService';
import { useSafetyStore } from '../store/safetyStore';
import { EmergencyContact, GuardianConnectionStatus } from '../types';

export interface GuardianCircleInput {
  userId: string;
  name: string;
  phone: string;
  relationship: string;
  priority?: number;
  avatarUrl?: string | null;
  isActive?: boolean;
  locationSharingAllowed?: boolean;
}

export interface GuardianEditInput {
  id: string;
  name: string;
  phone: string;
  relationship: string;
  priority?: number;
  avatarUrl?: string | null;
  isActive?: boolean;
  locationSharingAllowed?: boolean;
}

/**
 * STEP 1 — GUARDIAN CIRCLE + GUARDIAN STATUS SERVICE
 * Extends the existing AIZA Guardians / Emergency Contacts functionality into a
 * Guardian Circle while preserving existing SOS contact lookup, SQLite offline-first
 * persistence, Supabase RLS sync, and explicit user-controlled location sharing.
 */
const UUID_V4_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export const GuardianCircleService = {
  /**
   * Generates a standard RFC 4122 UUID v4 string to serve as the single canonical
   * guardian ID across SQLite (contacts & guardian_circle_prefs) and Supabase (emergency_contacts).
   */
  generateCanonicalUuidV4(): string {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  },

  /**
   * Checks whether an ID conforms to RFC 4122 UUID v4 format.
   */
  isValidUuid(id: string): boolean {
    return UUID_V4_REGEX.test(id);
  },

  /**
   * Loads all guardians from local SQLite (and syncs status with Supabase when online),
   * enriched with Guardian Circle status, telemetry, and user-controlled permissions.
   */
  async getGuardianCircle(userId?: string): Promise<EmergencyContact[]> {
    const store = useSafetyStore.getState();
    const dbContacts = userId
      ? await ContactDao.getAllForUser(userId)
      : await ContactDao.getAll();

    // If online, attempt to refresh guardian telemetry for contacts that have a linked user account
    if (store.networkAvailable) {
      for (const c of dbContacts) {
        try {
          const locRes = await UserLocationService.getInstance().getLatestLocation(c.id);
          if (locRes.success && locRes.data && locRes.data.is_sharing_enabled) {
            await ContactDao.updateGuardianTelemetry(c.id, {
              latitude: locRes.data.latitude,
              longitude: locRes.data.longitude,
              lastLocationUpdate: locRes.data.timestamp,
              batteryLevel: locRes.data.battery_level,
              isOnline: true,
              connectionStatus: 'ONLINE',
            });
          }
        } catch (_) {
          // Keep locally cached guardian telemetry offline-first
        }
      }
    }

    const refreshed = userId
      ? await ContactDao.getAllForUser(userId)
      : await ContactDao.getAll();
    const mapped = refreshed.map((c) => this.mapDbRecordToEmergencyContact(c));
    store.setContacts(mapped);
    return mapped;
  },

  /**
   * Maps a SQLite ContactDbRecord to the enriched EmergencyContact model.
   */
  mapDbRecordToEmergencyContact(c: ContactDbRecord): EmergencyContact {
    const isOnline = c.is_online === 1;
    const hasRecentUpdate =
      typeof c.last_location_update === 'number' &&
      Date.now() - c.last_location_update < 15 * 60 * 1000;
    const effectiveOnline = isOnline || hasRecentUpdate;
    const connectionStatus: GuardianConnectionStatus =
      c.connection_status && c.connection_status !== 'OFFLINE'
        ? c.connection_status
        : effectiveOnline
        ? 'ONLINE'
        : c.last_location_update
        ? 'STALE'
        : 'OFFLINE';

    return {
      id: c.id,
      userId: c.user_id,
      name: c.name,
      phoneNumber: c.phone,
      relationship: c.relationship,
      priorityOrder: c.priority,
      isVerified: c.verified === 1,
      avatarUrl: c.avatar_url ?? undefined,
      createdAt: c.created_at,
      isActive: (c.is_active ?? 1) === 1,
      isOnline: effectiveOnline,
      lastKnownLatitude: c.last_known_lat ?? null,
      lastKnownLongitude: c.last_known_lng ?? null,
      lastLocationUpdate: c.last_location_update ?? null,
      batteryLevel: c.battery_level ?? null,
      connectionStatus,
      // Explicitly opt-in per guardian — never enabled permanently by default
      locationSharingAllowed: (c.location_sharing_allowed ?? 0) === 1,
    };
  },

  /**
   * Creates a new Guardian in local SQLite (and syncs to Supabase if online)
   * using a single canonical RFC 4122 UUID v4 string.
   * Location sharing defaults to false (0) so permanent location sharing is never enabled automatically.
   */
  async addGuardian(input: GuardianCircleInput): Promise<ContactDbRecord> {
    const store = useSafetyStore.getState();
    const existing = await ContactDao.getAll();
    const nextPriority = input.priority ?? existing.length + 1;
    const now = Date.now();
    const contactId = this.generateCanonicalUuidV4();

    const record: ContactDbRecord = {
      id: contactId,
      user_id: input.userId,
      name: input.name.trim(),
      phone: input.phone.trim(),
      relationship: input.relationship.trim() || 'Emergency Contact',
      priority: nextPriority,
      verified: 1,
      created_at: now,
      updated_at: now,
      is_active: input.isActive !== undefined ? (input.isActive ? 1 : 0) : 1,
      avatar_url: input.avatarUrl ?? null,
      // Explicitly false (0) by default unless user explicitly opts in
      location_sharing_allowed: input.locationSharingAllowed ? 1 : 0,
      last_known_lat: null,
      last_known_lng: null,
      last_location_update: null,
      battery_level: null,
      is_online: 0,
      connection_status: 'OFFLINE',
    };

    await ContactDao.insert(record);

    if (store.currentUser?.id && store.networkAvailable) {
      try {
        const supaRes = await EmergencyContactService.getInstance().addContact({
          id: contactId,
          userId: store.currentUser.id,
          name: record.name,
          phone: record.phone,
          relationship: record.relationship,
          priority: record.priority,
        });

        if (supaRes.success && supaRes.data?.id && supaRes.data.id !== contactId) {
          console.warn(
            `[GuardianCircleService] Canonical UUID mismatch detected: local=${contactId}, supabase=${supaRes.data.id}. Keeping single local canonical record without creating duplicate.`
          );
        }
      } catch (_) {
        // Offline-first SQLite record is preserved with its canonical UUID
      }
    }

    await this.getGuardianCircle();
    return (await ContactDao.getById(contactId)) ?? record;
  },

  /**
   * Merges Supabase emergency_contacts rows into local SQLite without creating duplicate
   * guardian rows and without resetting existing guardian_circle_prefs (is_active,
   * location_sharing_allowed, priority, telemetry).
   */
  async mergeSupabaseContacts(
    remoteContacts: SupabaseEmergencyContactRecord[]
  ): Promise<void> {
    for (const sc of remoteContacts) {
      const existingLocal = await ContactDao.getById(sc.id);
      if (existingLocal) {
        const existingPrefs = await ContactDao.getGuardianPrefs(sc.id);
        await ContactDao.update({
          id: sc.id,
          user_id: sc.user_id || existingLocal.user_id,
          name: sc.name || existingLocal.name,
          phone: sc.phone || existingLocal.phone,
          relationship: sc.relationship || existingLocal.relationship,
          priority:
            existingLocal.priority > 0
              ? existingLocal.priority
              : parseInt(sc.priority, 10) || 1,
          verified: 1,
          created_at: existingLocal.created_at,
          updated_at: sc.updated_at ? new Date(sc.updated_at).getTime() : Date.now(),
          is_active: existingPrefs?.is_active ?? existingLocal.is_active ?? 1,
          avatar_url: existingPrefs?.avatar_url ?? existingLocal.avatar_url ?? null,
          location_sharing_allowed:
            existingPrefs?.location_sharing_allowed ??
            existingLocal.location_sharing_allowed ??
            0,
          last_known_lat: existingPrefs?.last_known_lat ?? existingLocal.last_known_lat ?? null,
          last_known_lng: existingPrefs?.last_known_lng ?? existingLocal.last_known_lng ?? null,
          last_location_update:
            existingPrefs?.last_location_update ?? existingLocal.last_location_update ?? null,
          battery_level: existingPrefs?.battery_level ?? existingLocal.battery_level ?? null,
          is_online: existingPrefs?.is_online ?? existingLocal.is_online ?? 0,
          connection_status:
            existingPrefs?.connection_status ?? existingLocal.connection_status ?? 'OFFLINE',
        });
      } else {
        await ContactDao.insert({
          id: sc.id,
          user_id: sc.user_id,
          name: sc.name,
          phone: sc.phone,
          relationship: sc.relationship,
          priority: parseInt(sc.priority, 10) || 1,
          verified: 1,
          created_at: sc.created_at ? new Date(sc.created_at).getTime() : Date.now(),
          updated_at: sc.updated_at ? new Date(sc.updated_at).getTime() : Date.now(),
        });
      }
    }
  },

  /**
   * Synchronizes local canonical-UUID guardians with Supabase emergency_contacts:
   * 1. Uploads any offline-created local UUID guardians not yet present in Supabase using their exact canonical UUID.
   * 2. Merges remote Supabase rows into SQLite without duplicating guardians or resetting guardian_circle_prefs.
   */
  async syncWithSupabase(userId: string): Promise<void> {
    const store = useSafetyStore.getState();
    if (!userId || !store.networkAvailable) return;

    const remoteRes = await EmergencyContactService.getInstance().getContacts(userId);
    const remoteList = remoteRes.success && remoteRes.data ? remoteRes.data : [];
    const remoteIds = new Set(remoteList.map((r) => r.id));

    const localContacts = await ContactDao.getAllForUser(userId);
    for (const local of localContacts) {
      if (this.isValidUuid(local.id) && !remoteIds.has(local.id)) {
        try {
          const addRes = await EmergencyContactService.getInstance().addContact({
            id: local.id,
            userId,
            name: local.name,
            phone: local.phone,
            relationship: local.relationship,
            priority: local.priority,
          });
          if (addRes.success && addRes.data) {
            remoteIds.add(addRes.data.id);
          }
        } catch (_) {
          // Keep offline record intact
        }
      }
    }

    if (remoteList.length > 0) {
      await this.mergeSupabaseContacts(remoteList);
    }
  },

  /**
   * Edits an existing Guardian's details, priority, active state, and location-sharing permission.
   */
  async editGuardian(input: GuardianEditInput): Promise<ContactDbRecord | null> {
    const store = useSafetyStore.getState();
    const existing = await ContactDao.getById(input.id);
    if (!existing) return null;

    const targetPriority = input.priority ?? existing.priority;
    if (targetPriority !== existing.priority) {
      await this.setGuardianPriority(input.id, targetPriority);
    }

    const updated: ContactDbRecord = {
      ...existing,
      name: input.name.trim(),
      phone: input.phone.trim(),
      relationship: input.relationship.trim() || existing.relationship,
      priority: targetPriority,
      updated_at: Date.now(),
      is_active:
        input.isActive !== undefined ? (input.isActive ? 1 : 0) : existing.is_active ?? 1,
      avatar_url:
        input.avatarUrl !== undefined ? input.avatarUrl : existing.avatar_url ?? null,
      location_sharing_allowed:
        input.locationSharingAllowed !== undefined
          ? input.locationSharingAllowed
            ? 1
            : 0
          : existing.location_sharing_allowed ?? 0,
    };

    await ContactDao.update(updated);

    if (store.currentUser?.id && store.networkAvailable) {
      try {
        await EmergencyContactService.getInstance().updateContact(
          input.id,
          store.currentUser.id,
          {
            name: updated.name,
            phone: updated.phone,
            relationship: updated.relationship,
            priority: updated.priority,
          }
        );
      } catch (_) {
        // Offline-first SQLite update preserved
      }
    }

    await this.getGuardianCircle();
    return await ContactDao.getById(input.id);
  },

  /**
   * Removes a guardian from SQLite (and Supabase when online) and normalizes remaining priorities.
   */
  async removeGuardian(contactId: string): Promise<void> {
    const store = useSafetyStore.getState();
    await ContactDao.delete(contactId);

    // Normalize remaining priorities (1..N) so Rank 1 Primary Call is always deterministic
    const remaining = await ContactDao.getAll();
    for (let i = 0; i < remaining.length; i++) {
      const expectedPriority = i + 1;
      if (remaining[i].priority !== expectedPriority) {
        await ContactDao.updatePriority(remaining[i].id, expectedPriority);
      }
    }

    if (store.currentUser?.id && store.networkAvailable) {
      try {
        await EmergencyContactService.getInstance().deleteContact(contactId, store.currentUser.id);
      } catch (_) {}
    }

    await this.getGuardianCircle();
  },

  /**
   * Toggles whether a guardian is active or inactive in the Guardian Circle.
   */
  async setGuardianActiveState(contactId: string, isActive: boolean): Promise<void> {
    await ContactDao.updateGuardianPreferences(contactId, { isActive });
    await this.getGuardianCircle();
  },

  /**
   * Explicitly updates whether the user allows sharing location with a specific guardian.
   * Location sharing is strictly opt-in / user-controlled, never permanent surveillance.
   */
  async setGuardianLocationSharing(contactId: string, allowed: boolean): Promise<void> {
    await ContactDao.updateGuardianPreferences(contactId, {
      locationSharingAllowed: allowed,
    });
    await this.getGuardianCircle();
  },

  /**
   * Returns only active guardians for whom the user has explicitly enabled location sharing.
   */
  async getGuardiansWithLocationPermission(): Promise<EmergencyContact[]> {
    const store = useSafetyStore.getState();
    if (!store.locationSharingEnabled) {
      return [];
    }
    const all = await this.getGuardianCircle();
    return all.filter((g) => g.isActive !== false && g.locationSharingAllowed === true);
  },

  /**
   * Updates telemetry (online status, battery percentage, last known coordinates, connection status)
   * for a guardian when received from Supabase user_locations or wearable/peer sync.
   */
  async updateGuardianTelemetry(
    contactId: string,
    telemetry: {
      latitude?: number | null;
      longitude?: number | null;
      lastKnownLat?: number | null;
      lastKnownLng?: number | null;
      lastLocationUpdate?: number | null;
      batteryLevel?: number | null;
      isOnline?: boolean;
      connectionStatus?: GuardianConnectionStatus;
    }
  ): Promise<void> {
    await ContactDao.updateGuardianTelemetry(contactId, telemetry);
    await this.getGuardianCircle();
  },

  /**
   * Explicitly shares a single on-demand location snapshot with permitted active guardians.
   * Does not run continuous background surveillance.
   */
  async shareOneTimeLocationSnapshot(): Promise<{
    sharedCount: number;
    latitude: number | null;
    longitude: number | null;
  }> {
    const store = useSafetyStore.getState();
    if (!store.locationSharingEnabled) {
      return { sharedCount: 0, latitude: null, longitude: null };
    }

    const permitted = await this.getGuardiansWithLocationPermission();
    if (permitted.length === 0) {
      return { sharedCount: 0, latitude: null, longitude: null };
    }

    const pos = await LocationService.getCurrentPosition();
    const lat = pos?.latitude ?? store.currentLocation?.latitude ?? null;
    const lng = pos?.longitude ?? store.currentLocation?.longitude ?? null;

    if (lat === null || lng === null) {
      return { sharedCount: 0, latitude: null, longitude: null };
    }

    if (store.currentUser?.id && store.networkAvailable) {
      try {
        await UserLocationService.getInstance().insertLocation({
          userId: store.currentUser.id,
          latitude: lat,
          longitude: lng,
          accuracy: pos?.accuracy ?? 10,
          isSharingEnabled: true,
          batteryLevel: store.batteryLevel,
          timestamp: Date.now(),
        });
      } catch (_) {}
    }

    return {
      sharedCount: permitted.length,
      latitude: lat,
      longitude: lng,
    };
  },

  /**
   * Promotes or reorders guardian priority rank while preserving existing ContactDao logic.
   */
  async setGuardianPriority(contactId: string, newPriority: number): Promise<void> {
    const store = useSafetyStore.getState();
    const all = await ContactDao.getAll();
    const target = all.find((c) => c.id === contactId);
    if (!target) return;

    const existingAtRank = all.find((c) => c.priority === newPriority && c.id !== contactId);
    if (existingAtRank) {
      await ContactDao.updatePriority(existingAtRank.id, target.priority);
    }
    await ContactDao.updatePriority(contactId, newPriority);

    if (store.currentUser?.id && store.networkAvailable) {
      try {
        if (existingAtRank) {
          await EmergencyContactService.getInstance().updateContactPriority(
            existingAtRank.id,
            store.currentUser.id,
            target.priority
          );
        }
        await EmergencyContactService.getInstance().updateContactPriority(
          contactId,
          store.currentUser.id,
          newPriority
        );
      } catch (_) {}
    }

    await this.getGuardianCircle();
  },
};
