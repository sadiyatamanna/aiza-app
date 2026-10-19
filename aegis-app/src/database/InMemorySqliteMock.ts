import { ContactDbRecord, GuardianCirclePrefRecord } from './ContactDao';
import { SosLogDbRecord } from './SosLogDao';
import { TrackedLocationDbRecord } from './TrackedLocationDao';
import { CachedPlaceDbRecord } from './CachedPlaceDao';
import { SafeZoneDbRecord } from './SafeZoneDao';

/**
 * In-memory SQLite Mock Engine for local verification of DAOs,
 * SQL parsing, queries, indexing, and migration logic in Node test runners.
 */
export class InMemorySqliteMock {
  private schemaVersion = 0;
  private contactsTable: Map<string, ContactDbRecord> = new Map();
  private guardianPrefsTable: Map<string, GuardianCirclePrefRecord> = new Map();
  private sosLogsTable: Map<string, SosLogDbRecord> = new Map();
  private trackedLocationsTable: Map<string, TrackedLocationDbRecord> = new Map();
  private cachedPlacesTable: Map<string, CachedPlaceDbRecord> = new Map();
  private safeZonesTable: Map<string, SafeZoneDbRecord> = new Map();

  public async executeRaw(
    sql: string,
    params: any[] = []
  ): Promise<{ rows: any[]; rowsAffected: number }> {
    const trimmed = sql.trim().toUpperCase();

    // 1. Schema version PRAGMA / Table
    if (trimmed.startsWith('PRAGMA USER_VERSION =')) {
      const match = sql.match(/PRAGMA user_version = (\d+)/i);
      if (match) {
        this.schemaVersion = parseInt(match[1], 10);
      }
      return { rows: [], rowsAffected: 0 };
    }

    if (trimmed.includes('SELECT MAX(VERSION)')) {
      return {
        rows: [{ ver: this.schemaVersion, current_version: this.schemaVersion }],
        rowsAffected: 0,
      };
    }

    if (trimmed.startsWith('CREATE TABLE') || trimmed.startsWith('CREATE INDEX')) {
      return { rows: [], rowsAffected: 0 };
    }

    if (trimmed.startsWith('INSERT INTO SCHEMA_VERSION')) {
      this.schemaVersion = params[0] || 0;
      return { rows: [], rowsAffected: 1 };
    }

    // 2. Contacts Table
    if (trimmed.includes('INTO CONTACTS')) {
      const record: ContactDbRecord = {
        id: params[0],
        user_id: params[1],
        name: params[2],
        phone: params[3],
        relationship: params[4],
        priority: params[5],
        verified: params[6],
        created_at: params[7],
        updated_at: params[8],
      };
      this.contactsTable.set(record.id, record);
      return { rows: [], rowsAffected: 1 };
    }

    if (trimmed.startsWith('UPDATE CONTACTS SET PRIORITY')) {
      const newPriority = params[0];
      const updatedAt = params[1];
      const id = params[2];
      const item = this.contactsTable.get(id);
      if (item) {
        item.priority = newPriority;
        item.updated_at = updatedAt;
      }
      return { rows: [], rowsAffected: item ? 1 : 0 };
    }

    if (trimmed.startsWith('UPDATE CONTACTS SET')) {
      // Full contact update: name, phone, relationship, priority, verified, updated_at WHERE id = ?
      const [name, phone, relationship, priority, verified, updated_at, id] = params;
      const item = this.contactsTable.get(id);
      if (item) {
        item.name = name;
        item.phone = phone;
        item.relationship = relationship;
        item.priority = priority;
        item.verified = verified;
        item.updated_at = updated_at;
      }
      return { rows: [], rowsAffected: item ? 1 : 0 };
    }

    if (trimmed.startsWith('DELETE FROM CONTACTS')) {
      const id = params[0];
      const exists = this.contactsTable.has(id);
      this.contactsTable.delete(id);
      return { rows: [], rowsAffected: exists ? 1 : 0 };
    }

    if (trimmed.includes('FROM CONTACTS')) {
      if (trimmed.includes('COUNT(*)')) {
        return { rows: [{ cnt: this.contactsTable.size }], rowsAffected: 0 };
      }
      if (trimmed.includes('WHERE ID = ?')) {
        const item = this.contactsTable.get(params[0]);
        return { rows: item ? [{ ...item }] : [], rowsAffected: 0 };
      }
      if (trimmed.includes('WHERE USER_ID = ?')) {
        const userId = params[0];
        const allForUser = Array.from(this.contactsTable.values())
          .filter((c) => c.user_id === userId)
          .sort((a, b) => a.priority - b.priority)
          .map((c) => ({ ...c }));
        return { rows: allForUser, rowsAffected: 0 };
      }
      const all = Array.from(this.contactsTable.values())
        .sort((a, b) => a.priority - b.priority)
        .map((c) => ({ ...c }));
      return { rows: all, rowsAffected: 0 };
    }

    // 2B. Guardian Circle Prefs Table (Step 1)
    if (trimmed.includes('INTO GUARDIAN_CIRCLE_PREFS')) {
      const record: GuardianCirclePrefRecord = {
        contact_id: params[0],
        is_active: params[1],
        avatar_url: params[2],
        location_sharing_allowed: params[3],
        last_known_lat: params[4],
        last_known_lng: params[5],
        last_location_update: params[6],
        battery_level: params[7],
        is_online: params[8],
        connection_status: params[9],
        updated_at: params[10],
      };
      this.guardianPrefsTable.set(record.contact_id, record);
      return { rows: [], rowsAffected: 1 };
    }

    if (trimmed.startsWith('DELETE FROM GUARDIAN_CIRCLE_PREFS')) {
      const id = params[0];
      const exists = this.guardianPrefsTable.has(id);
      this.guardianPrefsTable.delete(id);
      return { rows: [], rowsAffected: exists ? 1 : 0 };
    }

    if (trimmed.includes('FROM GUARDIAN_CIRCLE_PREFS')) {
      if (trimmed.includes('WHERE CONTACT_ID = ?')) {
        const item = this.guardianPrefsTable.get(params[0]);
        return { rows: item ? [{ ...item }] : [], rowsAffected: 0 };
      }
      return {
        rows: Array.from(this.guardianPrefsTable.values()).map((p) => ({ ...p })),
        rowsAffected: 0,
      };
    }

    // 3. SOS Logs Table
    if (trimmed.includes('INTO SOS_LOGS')) {
      const record: SosLogDbRecord = {
        id: params[0],
        user_id: params[1],
        trigger_type: params[2],
        latitude: params[3],
        longitude: params[4],
        location_available: params[5],
        location_is_last_known: params[6],
        timestamp: params[7],
        status: params[8],
        encrypted_payload: params[9],
        signature: params[10],
        voice_clip_local_path: params[11],
        voice_clip_url: params[12],
        synced_to_cloud: params[13],
        created_at: params[14],
      };
      this.sosLogsTable.set(record.id, record);
      return { rows: [], rowsAffected: 1 };
    }

    if (trimmed.includes('FROM SOS_LOGS')) {
      if (trimmed.includes('COUNT(*)')) {
        return { rows: [{ cnt: this.sosLogsTable.size }], rowsAffected: 0 };
      }
      if (trimmed.includes('WHERE ID = ?')) {
        const item = this.sosLogsTable.get(params[0]);
        return { rows: item ? [item] : [], rowsAffected: 0 };
      }
      if (trimmed.includes('SYNCED_TO_CLOUD = 0')) {
        const pending = Array.from(this.sosLogsTable.values()).filter(
          (s) => s.synced_to_cloud === 0 || s.status === 'Pending_Sync'
        );
        return { rows: pending, rowsAffected: 0 };
      }
      const all = Array.from(this.sosLogsTable.values()).sort((a, b) => b.timestamp - a.timestamp);
      return { rows: all, rowsAffected: 0 };
    }

    // 4. Tracked Locations Table
    if (trimmed.includes('INTO TRACKED_LOCATIONS')) {
      const record: TrackedLocationDbRecord = {
        id: params[0],
        user_id: params[1],
        latitude: params[2],
        longitude: params[3],
        accuracy: params[4],
        timestamp: params[5],
        sos_related: params[6],
        synced_to_cloud: params[7],
      };
      this.trackedLocationsTable.set(record.id, record);
      return { rows: [], rowsAffected: 1 };
    }

    if (trimmed.includes('FROM TRACKED_LOCATIONS')) {
      if (trimmed.includes('COUNT(*)')) {
        return { rows: [{ cnt: this.trackedLocationsTable.size }], rowsAffected: 0 };
      }
      const all = Array.from(this.trackedLocationsTable.values()).sort(
        (a, b) => b.timestamp - a.timestamp
      );
      return { rows: all, rowsAffected: 0 };
    }

    // 5. Cached Places Table
    if (trimmed.includes('INTO CACHED_PLACES')) {
      const record: CachedPlaceDbRecord = {
        id: params[0],
        place_id: params[1],
        name: params[2],
        category: params[3],
        latitude: params[4],
        longitude: params[5],
        address: params[6],
        distance: params[7],
        source: params[8],
        timestamp: params[9],
      };
      this.cachedPlacesTable.set(record.id, record);
      return { rows: [], rowsAffected: 1 };
    }

    if (trimmed.includes('FROM CACHED_PLACES')) {
      if (trimmed.includes('COUNT(*)')) {
        return { rows: [{ cnt: this.cachedPlacesTable.size }], rowsAffected: 0 };
      }
      if (trimmed.includes('CATEGORY = ?')) {
        const filtered = Array.from(this.cachedPlacesTable.values()).filter(
          (p) => p.category === params[0]
        );
        return { rows: filtered, rowsAffected: 0 };
      }
      const all = Array.from(this.cachedPlacesTable.values()).sort(
        (a, b) => (a.distance ?? 0) - (b.distance ?? 0)
      );
      return { rows: all, rowsAffected: 0 };
    }

    // 6. Safe Zones Table
    if (trimmed.includes('INTO SAFE_ZONES')) {
      const record: SafeZoneDbRecord = {
        id: params[0],
        user_id: params[1],
        name: params[2],
        latitude: params[3],
        longitude: params[4],
        radius: params[5],
        created_at: params[6],
      };
      this.safeZonesTable.set(record.id, record);
      return { rows: [], rowsAffected: 1 };
    }

    if (trimmed.startsWith('DELETE FROM SAFE_ZONES')) {
      const id = params[0];
      const exists = this.safeZonesTable.has(id);
      this.safeZonesTable.delete(id);
      return { rows: [], rowsAffected: exists ? 1 : 0 };
    }

    if (trimmed.includes('FROM SAFE_ZONES')) {
      if (trimmed.includes('COUNT(*)')) {
        return { rows: [{ cnt: this.safeZonesTable.size }], rowsAffected: 0 };
      }
      const all = Array.from(this.safeZonesTable.values()).sort(
        (a, b) => b.created_at - a.created_at
      );
      return { rows: all, rowsAffected: 0 };
    }

    return { rows: [], rowsAffected: 0 };
  }

  public reset(): void {
    this.schemaVersion = 0;
    this.contactsTable.clear();
    this.guardianPrefsTable.clear();
    this.sosLogsTable.clear();
    this.trackedLocationsTable.clear();
    this.cachedPlacesTable.clear();
    this.safeZonesTable.clear();
  }
}
