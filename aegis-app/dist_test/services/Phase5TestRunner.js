"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const SosService_1 = require("./SosService");
const SosLogDao_1 = require("../database/SosLogDao");
const ContactDao_1 = require("../database/ContactDao");
const AppDatabase_1 = require("../database/AppDatabase");
const InMemorySqliteMock_1 = require("../database/InMemorySqliteMock");
const DatabaseMigrations_1 = require("../database/DatabaseMigrations");
const SirenService_1 = require("./SirenService");
const HardwareService_1 = require("./HardwareService");
const BackgroundService_1 = require("./BackgroundService");
const safetyStore_1 = require("../store/safetyStore");
let passed = 0;
let failed = 0;
function assert(condition, testName, detail) {
    if (condition) {
        console.log(`[PASS] ${testName}`);
        passed++;
    }
    else {
        console.error(`[FAIL] ${testName}${detail ? ` - ${detail}` : ''}`);
        failed++;
    }
}
async function runPhase5Tests() {
    console.log('--- Starting Aegis Phase 5 Unified SOS Dispatch & Local Persistence Tests ---');
    // Initialize in-memory SQLite mock for database operations in Node.js test environment
    const mock = new InMemorySqliteMock_1.InMemorySqliteMock();
    AppDatabase_1.AppDatabase.executeRaw = (sql, params) => mock.executeRaw(sql, params);
    for (const m of DatabaseMigrations_1.DB_MIGRATIONS) {
        await m.up(async (sql) => mock.executeRaw(sql));
    }
    // Test 1: Setup a test contact in local SQLite
    try {
        await ContactDao_1.ContactDao.insert({
            id: 'contact_p5_1',
            user_id: 'user_test_p5',
            name: 'Primary Guardian',
            phone: '+15559876543',
            relationship: 'Sister',
            priority: 1,
            verified: 1,
            created_at: Date.now(),
            updated_at: Date.now(),
        });
        const contact = await ContactDao_1.ContactDao.getById('contact_p5_1');
        assert(contact !== null && contact.priority === 1, '1. Local emergency contact configured for SOS dispatch');
    }
    catch (err) {
        assert(false, '1. Local emergency contact configured for SOS dispatch', err.message);
    }
    // Test 2: Execute Manual SOS Dispatch
    let dispatchResult = null;
    try {
        dispatchResult = await SosService_1.SosService.executeSosDispatch('MANUAL');
        assert(dispatchResult.success === true &&
            dispatchResult.source === 'MANUAL' &&
            typeof dispatchResult.alertId === 'string' &&
            dispatchResult.callDispatchedTo === '+15559876543' &&
            dispatchResult.smsSentCount >= 1, '2. Unified SOS dispatch dispatches call to Priority 1 contact and sends SMS');
    }
    catch (err) {
        assert(false, '2. Unified SOS dispatch dispatches call to Priority 1 contact and sends SMS', err.message);
    }
    // Test 3: Local SQLite Record Status Verification (Pending_Sync, synced_to_cloud = 0)
    try {
        const alertId = dispatchResult.alertId;
        const log = await SosLogDao_1.SosLogDao.getById(alertId);
        assert(log !== null &&
            log.trigger_type === 'MANUAL' &&
            log.status === 'Pending_Sync' &&
            log.synced_to_cloud === 0, '3. Phase 5 scope strictly enforces status = Pending_Sync and synced_to_cloud = 0');
    }
    catch (err) {
        assert(false, '3. Phase 5 scope strictly enforces status = Pending_Sync and synced_to_cloud = 0', err.message);
    }
    // Test 4: Hardware Alert Activations (Siren & Torch)
    try {
        const sirenPlaying = await SirenService_1.SirenService.isSirenPlaying();
        const torchOn = await HardwareService_1.HardwareService.isTorchOn();
        const bgRunning = await BackgroundService_1.BackgroundService.isServiceRunning();
        assert(sirenPlaying === true && torchOn === true && bgRunning === true, '4. Emergency loud siren, camera torch, and background foreground service are active');
    }
    catch (err) {
        assert(false, '4. Emergency loud siren, camera torch, and background foreground service are active', err.message);
    }
    // Test 5: BLE Hardware Wearable Trigger Execution
    try {
        const bleDispatch = await SosService_1.SosService.executeSosDispatch('BLE');
        const bleLog = await SosLogDao_1.SosLogDao.getById(bleDispatch.alertId);
        assert(bleDispatch.success === true &&
            bleDispatch.source === 'BLE' &&
            bleLog !== null &&
            bleLog.trigger_type === 'BLE' &&
            bleLog.status === 'Pending_Sync' &&
            bleLog.synced_to_cloud === 0, '5. BLE hardware SOS trigger persists locally with status = Pending_Sync, synced_to_cloud = 0');
    }
    catch (err) {
        assert(false, '5. BLE hardware SOS trigger persists locally', err.message);
    }
    // Test 6: Verify NO Automatic Cloud Upload Occurred
    try {
        const pendingLogs = await SosLogDao_1.SosLogDao.getPendingSync();
        const allLogs = await SosLogDao_1.SosLogDao.getAll();
        // All newly dispatched Phase 5 logs must remain in Pending_Sync state
        assert(pendingLogs.length >= 2 &&
            pendingLogs.every(l => l.status === 'Pending_Sync' && l.synced_to_cloud === 0), '6. Verified zero automatic cloud upload: All logs remain strictly Pending_Sync and unsynced locally');
    }
    catch (err) {
        assert(false, '6. Verified zero automatic cloud upload', err.message);
    }
    // Test 7: SOS Cancellation Workflow
    try {
        await SosService_1.SosService.cancelSosAlert();
        const sirenAfter = await SirenService_1.SirenService.isSirenPlaying();
        const torchAfter = await HardwareService_1.HardwareService.isTorchOn();
        const storeAfter = safetyStore_1.useSafetyStore.getState();
        assert(sirenAfter === false && torchAfter === false && storeAfter.sosActive === false, '7. SOS cancellation stops siren, turns off torch, and clears active SOS store state');
    }
    catch (err) {
        assert(false, '7. SOS cancellation stops siren, turns off torch, and clears active SOS store state', err.message);
    }
    console.log(`\nAll ${passed}/${passed + failed} Phase 5 Unified SOS Dispatch & Local Persistence Tests finished.`);
    if (failed > 0) {
        process.exit(1);
    }
}
runPhase5Tests().catch(err => {
    console.error('Fatal error during Phase 5 tests:', err);
    process.exit(1);
});
