/**
 * Aegis SQLite Database Migrations
 * Schema Version: 8 (Matching Room Database v8 + Step 1 Guardian Circle metadata)
 */

export interface Migration {
  version: number;
  up: (executeSql: (sql: string, params?: any[]) => Promise<any> | any) => Promise<void> | void;
}

export const TARGET_DATABASE_VERSION = 8;

export const DB_MIGRATIONS: Migration[] = [
  {
    version: 1,
    up: async (execute) => {
      // 1. Contacts Table
      await execute(`
        CREATE TABLE IF NOT EXISTS contacts (
          id TEXT PRIMARY KEY NOT NULL,
          user_id TEXT NOT NULL,
          name TEXT NOT NULL,
          phone TEXT NOT NULL,
          relationship TEXT NOT NULL,
          priority INTEGER NOT NULL,
          verified INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL
        );
      `);
      await execute(`CREATE INDEX IF NOT EXISTS idx_contacts_priority ON contacts(priority);`);
      await execute(`CREATE INDEX IF NOT EXISTS idx_contacts_user_id ON contacts(user_id);`);

      // 2. SOS Logs Table
      await execute(`
        CREATE TABLE IF NOT EXISTS sos_logs (
          id TEXT PRIMARY KEY NOT NULL,
          user_id TEXT NOT NULL,
          trigger_type TEXT NOT NULL,
          latitude REAL NOT NULL,
          longitude REAL NOT NULL,
          location_available INTEGER NOT NULL DEFAULT 1,
          location_is_last_known INTEGER NOT NULL DEFAULT 0,
          timestamp INTEGER NOT NULL,
          status TEXT NOT NULL,
          encrypted_payload TEXT,
          signature TEXT,
          voice_clip_local_path TEXT,
          voice_clip_url TEXT,
          synced_to_cloud INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL
        );
      `);
      await execute(`CREATE INDEX IF NOT EXISTS idx_sos_logs_timestamp ON sos_logs(timestamp);`);
      await execute(`CREATE INDEX IF NOT EXISTS idx_sos_logs_synced ON sos_logs(synced_to_cloud);`);

      // 3. Tracked Locations Table
      await execute(`
        CREATE TABLE IF NOT EXISTS tracked_locations (
          id TEXT PRIMARY KEY NOT NULL,
          user_id TEXT NOT NULL,
          latitude REAL NOT NULL,
          longitude REAL NOT NULL,
          accuracy REAL NOT NULL,
          timestamp INTEGER NOT NULL,
          sos_related INTEGER NOT NULL DEFAULT 0,
          synced_to_cloud INTEGER NOT NULL DEFAULT 0
        );
      `);
      await execute(`CREATE INDEX IF NOT EXISTS idx_tracked_locations_timestamp ON tracked_locations(timestamp);`);
      await execute(`CREATE INDEX IF NOT EXISTS idx_tracked_locations_synced ON tracked_locations(synced_to_cloud);`);

      // 4. Cached Places Table
      await execute(`
        CREATE TABLE IF NOT EXISTS cached_places (
          id TEXT PRIMARY KEY NOT NULL,
          place_id TEXT NOT NULL,
          name TEXT NOT NULL,
          category TEXT NOT NULL,
          latitude REAL NOT NULL,
          longitude REAL NOT NULL,
          address TEXT NOT NULL,
          distance REAL,
          source TEXT NOT NULL,
          timestamp INTEGER NOT NULL
        );
      `);
      await execute(`CREATE INDEX IF NOT EXISTS idx_cached_places_category ON cached_places(category);`);

      // 5. Safe Zones Table
      await execute(`
        CREATE TABLE IF NOT EXISTS safe_zones (
          id TEXT PRIMARY KEY NOT NULL,
          user_id TEXT NOT NULL,
          name TEXT NOT NULL,
          latitude REAL NOT NULL,
          longitude REAL NOT NULL,
          radius REAL NOT NULL,
          created_at INTEGER NOT NULL
        );
      `);
      await execute(`CREATE INDEX IF NOT EXISTS idx_safe_zones_user_id ON safe_zones(user_id);`);
    },
  },
  {
    version: 8,
    up: async (execute) => {
      // Ensure schema is explicitly aligned with Room v8 columns and indices
      await execute(`CREATE INDEX IF NOT EXISTS idx_sos_status ON sos_logs(status);`);

      // Step 1: Guardian Circle Status & Privacy Table (extends contacts non-destructively)
      await execute(`
        CREATE TABLE IF NOT EXISTS guardian_circle_prefs (
          contact_id TEXT PRIMARY KEY NOT NULL,
          is_active INTEGER NOT NULL DEFAULT 1,
          avatar_url TEXT,
          location_sharing_allowed INTEGER NOT NULL DEFAULT 0,
          last_known_lat REAL,
          last_known_lng REAL,
          last_location_update INTEGER,
          battery_level INTEGER,
          is_online INTEGER NOT NULL DEFAULT 0,
          connection_status TEXT NOT NULL DEFAULT 'OFFLINE',
          updated_at INTEGER NOT NULL
        );
      `);
    },
  },
];
