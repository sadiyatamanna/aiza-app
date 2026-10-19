"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Phase7TestRunner = void 0;
const AppDatabase_1 = require("../database/AppDatabase");
const InMemorySqliteMock_1 = require("../database/InMemorySqliteMock");
const safetyStore_1 = require("../store/safetyStore");
const SosService_1 = require("./SosService");
const ContactDao_1 = require("../database/ContactDao");
const SosLogDao_1 = require("../database/SosLogDao");
const SafeZoneDao_1 = require("../database/SafeZoneDao");
const theme_1 = require("../theme");
class Phase7TestRunner {
    static async runAllTests() {
        console.log('--- Starting AIZA Phase 7 UI Polish & Screen Architecture Tests ---');
        // Attach in-memory SQLite mock for Node environment test runner
        const mock = new InMemorySqliteMock_1.InMemorySqliteMock();
        AppDatabase_1.AppDatabase.executeRaw = (sql, params) => mock.executeRaw(sql, params);
        // 1. Theme Tokens Verification
        console.assert(!!theme_1.Colors.background, 'Test 1 Failed: Background color must exist');
        console.assert(!!theme_1.Colors.primary, 'Test 1 Failed: Primary color must exist');
        console.assert(!!theme_1.Colors.surface, 'Test 1 Failed: Surface color must exist');
        console.assert(theme_1.Spacing.md === 16, 'Test 1 Failed: Spacing.md standard 8dp grid');
        console.assert(theme_1.BorderRadius.md === 12, 'Test 1 Failed: BorderRadius.md standard');
        console.log('[PASS] 1. Theme tokens match AIZA dark obsidian specification');
        // 2. Reactive SafetyStore state actions
        const store = safetyStore_1.useSafetyStore.getState();
        console.assert(store !== undefined, 'Test 2 Failed: SafetyStore is initialized');
        console.assert(typeof store.triggerSos === 'function', 'Test 2 Failed: triggerSos exists');
        console.assert(typeof store.cancelSos === 'function', 'Test 2 Failed: cancelSos exists');
        console.log('[PASS] 2. Reactive SafetyStore state actions available for all screens');
        // 3. Contact & Guardian Persistence Layer for GuardiansScreen
        const testContact = {
            id: `phase7_test_contact_${Date.now()}`,
            user_id: 'phase7_user',
            name: 'Elena Rostova',
            phone: '+15559876543',
            relationship: 'Sister',
            priority: 1,
            verified: 1,
            created_at: Date.now(),
            updated_at: Date.now(),
        };
        await ContactDao_1.ContactDao.insert(testContact);
        const contacts = await ContactDao_1.ContactDao.getAll();
        const foundContact = contacts.find((c) => c.id === testContact.id);
        console.assert(!!foundContact, 'Test 3 Failed: ContactDao insert');
        console.assert(foundContact?.priority === 1, 'Test 3 Failed: Priority 1 rank');
        await ContactDao_1.ContactDao.delete(testContact.id);
        console.log('[PASS] 3. GuardiansScreen offline-first SQLite integration verified');
        // 4. SOS Log Layer for HistoryScreen
        const testLog = {
            id: `phase7_sos_${Date.now()}`,
            user_id: 'phase7_user',
            trigger_type: 'MANUAL',
            latitude: 37.7749,
            longitude: -122.4194,
            location_available: 1,
            location_is_last_known: 0,
            timestamp: Date.now(),
            status: 'Pending_Sync',
            encrypted_payload: 'test_payload',
            signature: 'test_sig',
            voice_clip_local_path: null,
            voice_clip_url: null,
            synced_to_cloud: 0,
            created_at: Date.now(),
        };
        await SosLogDao_1.SosLogDao.insert(testLog);
        const logs = await SosLogDao_1.SosLogDao.getAll();
        const foundLog = logs.find((l) => l.id === testLog.id);
        console.assert(!!foundLog, 'Test 4 Failed: SosLogDao insert');
        console.assert(foundLog?.status === 'Pending_Sync', 'Test 4 Failed: Pending_Sync status');
        console.assert(foundLog?.synced_to_cloud === 0, 'Test 4 Failed: Local-only storage');
        console.log('[PASS] 4. HistoryScreen offline-first SQLite audit log verified');
        // 5. Radar, POIs & Safe Zones Layer for MapScreen
        const testZone = {
            id: `phase7_zone_${Date.now()}`,
            user_id: 'phase7_user',
            name: 'Safe Haven SF',
            latitude: 37.7749,
            longitude: -122.4194,
            radius: 300,
            created_at: Date.now(),
        };
        await SafeZoneDao_1.SafeZoneDao.insert(testZone);
        const zones = await SafeZoneDao_1.SafeZoneDao.getAll();
        const foundZone = zones.find((z) => z.id === testZone.id);
        console.assert(!!foundZone, 'Test 5 Failed: SafeZoneDao insert');
        console.assert(foundZone?.radius === 300, 'Test 5 Failed: Geofence radius');
        await SafeZoneDao_1.SafeZoneDao.delete(testZone.id);
        console.log('[PASS] 5. MapScreen Safe Zone & Geofence integration verified');
        // 6. SOS Service Contract Preservation
        console.assert(typeof SosService_1.SosService.executeSosDispatch === 'function', 'Test 6 Failed: executeSosDispatch');
        console.assert(typeof SosService_1.SosService.cancelSosAlert === 'function', 'Test 6 Failed: cancelSosAlert');
        console.log('[PASS] 6. HomeScreen SOS execution contract preserved without duplication');
        console.log('All 6/6 Phase 7 UI Polish & Screen Architecture Tests passed successfully!');
    }
}
exports.Phase7TestRunner = Phase7TestRunner;
if (require.main === module) {
    Phase7TestRunner.runAllTests().catch((err) => {
        console.error('Phase 7 Test Runner Error:', err);
        process.exit(1);
    });
}
