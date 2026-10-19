"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AppDatabase = exports.AppDatabaseManager = void 0;
const DatabaseMigrations_1 = require("./DatabaseMigrations");
class AppDatabaseManager {
    constructor() {
        this.db = null;
        this.isInitialized = false;
        this.dbName = 'aegis_safety.db';
    }
    static getInstance() {
        if (!AppDatabaseManager.instance) {
            AppDatabaseManager.instance = new AppDatabaseManager();
        }
        return AppDatabaseManager.instance;
    }
    async initialize() {
        if (this.isInitialized && this.db) {
            return;
        }
        try {
            // Lazy load quick-sqlite at runtime on device
            const { open } = require('react-native-quick-sqlite');
            this.db = open({ name: this.dbName });
            await this.runMigrations();
            this.isInitialized = true;
        }
        catch (error) {
            console.error('[AppDatabase] Initialization failed:', error);
            throw error;
        }
    }
    /**
     * For unit tests or custom database connection injection
     */
    setConnection(connection) {
        this.db = connection;
        this.isInitialized = true;
    }
    getConnection() {
        if (!this.db) {
            throw new Error('[AppDatabase] Database not initialized. Call initialize() first.');
        }
        return this.db;
    }
    async runMigrations() {
        if (!this.db)
            return;
        // Create schema migrations tracking table
        await this.executeRaw(`
      CREATE TABLE IF NOT EXISTS schema_version (
        version INTEGER PRIMARY KEY NOT NULL,
        applied_at INTEGER NOT NULL
      );
    `);
        const currentVersionRes = await this.executeRaw(`
      SELECT MAX(version) as current_version FROM schema_version;
    `);
        let currentVersion = 0;
        if (currentVersionRes.rows && currentVersionRes.rows.length > 0) {
            currentVersion = currentVersionRes.rows[0].current_version || 0;
        }
        const pendingMigrations = DatabaseMigrations_1.DB_MIGRATIONS.filter((m) => m.version > currentVersion).sort((a, b) => a.version - b.version);
        for (const migration of pendingMigrations) {
            await migration.up(async (sql, params) => {
                await this.executeRaw(sql, params);
            });
            await this.executeRaw(`INSERT INTO schema_version (version, applied_at) VALUES (?, ?);`, [migration.version, Date.now()]);
        }
        // Set schema target version in SQLite pragma
        await this.executeRaw(`PRAGMA user_version = ${DatabaseMigrations_1.TARGET_DATABASE_VERSION};`);
    }
    async executeRaw(sql, params = []) {
        if (!this.db) {
            throw new Error('[AppDatabase] Database connection is null');
        }
        const result = this.db.execute(sql, params);
        const rows = [];
        if (result.rows && result.rows.length > 0) {
            for (let i = 0; i < result.rows.length; i++) {
                rows.push(result.rows.item(i));
            }
        }
        return {
            rows,
            insertId: result.insertId,
            rowsAffected: result.rowsAffected ?? 0,
        };
    }
    async getSchemaVersion() {
        const res = await this.executeRaw(`SELECT MAX(version) as ver FROM schema_version;`);
        if (res.rows && res.rows.length > 0) {
            return res.rows[0].ver ?? 0;
        }
        return 0;
    }
    async close() {
        if (this.db) {
            if (typeof this.db.close === 'function') {
                this.db.close();
            }
            this.db = null;
            this.isInitialized = false;
        }
    }
}
exports.AppDatabaseManager = AppDatabaseManager;
AppDatabaseManager.instance = null;
exports.AppDatabase = AppDatabaseManager.getInstance();
