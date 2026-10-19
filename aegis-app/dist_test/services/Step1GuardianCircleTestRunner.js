"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.runStep1GuardianCircleTests = runStep1GuardianCircleTests;
const AppDatabase_1 = require("../database/AppDatabase");
const InMemorySqliteMock_1 = require("../database/InMemorySqliteMock");
const DatabaseMigrations_1 = require("../database/DatabaseMigrations");
const ContactDao_1 = require("../database/ContactDao");
const GuardianCircleService_1 = require("./GuardianCircleService");
const TelephonyService_1 = require("./TelephonyService");
const SosService_1 = require("./SosService");
const safetyStore_1 = require("../store/safetyStore");
const SupabaseMock_1 = require("./SupabaseMock");
const SupabaseClientModule = __importStar(require("./SupabaseClient"));
function assert(condition, message) {
    if (!condition) {
        throw new Error(`ASSERTION FAILED: ${message}`);
    }
}
async function runStep1GuardianCircleTests() {
    console.log('==========================================================');
    console.log('🛡️ AIZA — STEP 1: GUARDIAN CIRCLE + GUARDIAN STATUS TESTS');
    console.log('==========================================================');
    const mock = new InMemorySqliteMock_1.InMemorySqliteMock();
    AppDatabase_1.AppDatabase.executeRaw = (sql, params) => mock.executeRaw(sql, params);
    for (const m of DatabaseMigrations_1.DB_MIGRATIONS) {
        await m.up(async (sql, params) => mock.executeRaw(sql, params));
        await mock.executeRaw(`INSERT INTO schema_version (version, applied_at) VALUES (?, ?);`, [
            m.version,
            Date.now(),
        ]);
    }
    await mock.executeRaw(`PRAGMA user_version = ${DatabaseMigrations_1.TARGET_DATABASE_VERSION};`);
    const supabaseMock = new SupabaseMock_1.SupabaseMock();
    SupabaseClientModule.supabase = supabaseMock;
    // 1. Guardian Creation & Default Privacy + Canonical UUID (A & B)
    console.log('\n[1] Testing Guardian Creation, Canonical UUID & Default Privacy...');
    safetyStore_1.useSafetyStore.getState().setAuthSession({ id: 'user_step1', email: 'step1@aiza.org' }, { access_token: 'jwt_step1', user: { id: 'user_step1' } });
    safetyStore_1.useSafetyStore.getState().setNetworkAvailable(true);
    safetyStore_1.useSafetyStore.getState().setLocationSharingEnabled(true);
    safetyStore_1.useSafetyStore.getState().setCurrentLocation({
        latitude: 37.7749,
        longitude: -122.4194,
        accuracy: 5,
    });
    const g1 = await GuardianCircleService_1.GuardianCircleService.addGuardian({
        userId: 'user_step1',
        name: 'Elena Vance',
        phone: '+15550101',
        relationship: 'Sister',
        priority: 1,
    });
    const g2 = await GuardianCircleService_1.GuardianCircleService.addGuardian({
        userId: 'user_step1',
        name: 'Marcus Chen',
        phone: '+15550102',
        relationship: 'Partner',
        priority: 2,
    });
    // (A) New guardian receives a valid RFC 4122 UUID v4
    assert(GuardianCircleService_1.GuardianCircleService.isValidUuid(g1.id) && !g1.id.startsWith('contact_'), 'A. Guardian 1 receives a valid RFC 4122 UUID v4');
    assert(GuardianCircleService_1.GuardianCircleService.isValidUuid(g2.id) && !g2.id.startsWith('contact_'), 'A. Guardian 2 receives a valid RFC 4122 UUID v4');
    // (B) The same UUID is used for local SQLite (contacts + prefs) and Supabase creation payload
    const g1Prefs = await ContactDao_1.ContactDao.getGuardianPrefs(g1.id);
    const g1Supabase = supabaseMock.emergencyContactsTable.get(g1.id);
    assert(!!g1Prefs && g1Prefs.contact_id === g1.id && !!g1Supabase && g1Supabase.id === g1.id, 'B. Same canonical UUID is used across SQLite contacts.id, guardian_circle_prefs.contact_id, and Supabase emergency_contacts.id');
    assert(g1.name === 'Elena Vance' && g1.priority === 1, 'Guardian 1 created with Priority 1');
    assert(g1.is_active === 1, 'Guardian 1 defaults to Active (1)');
    assert(g1.location_sharing_allowed === 0 && g2.location_sharing_allowed === 0, 'Location sharing is OFF (0) by default — never enabled permanently automatically');
    console.log('  ✅ Guardian creation, canonical UUID & default privacy verified');
    // 2. Guardian Editing (Name, Phone, Relationship, Active State, Location Permission)
    console.log('\n[2] Testing Guardian Editing...');
    const editedG2 = await GuardianCircleService_1.GuardianCircleService.editGuardian({
        id: g2.id,
        name: 'Marcus Chen-Vance',
        phone: '+15550199',
        relationship: 'Spouse',
        priority: 2,
        isActive: true,
        locationSharingAllowed: true,
    });
    assert(editedG2 !== null, 'Edited guardian returned');
    assert(editedG2.name === 'Marcus Chen-Vance', 'Guardian name updated');
    assert(editedG2.phone === '+15550199', 'Guardian phone updated');
    assert(editedG2.relationship === 'Spouse', 'Guardian relationship updated');
    assert(editedG2.location_sharing_allowed === 1, 'Guardian location sharing updated via edit');
    // (C) Edit uses the canonical UUID in Supabase
    assert(supabaseMock.emergencyContactsTable.get(g2.id)?.name === 'Marcus Chen-Vance' &&
        supabaseMock.emergencyContactsTable.get(g2.id)?.phone === '+15550199', 'C. Edit uses canonical UUID to update existing Supabase emergency_contacts row');
    console.log('  ✅ Guardian editing verified');
    // 3. Guardian Status & Telemetry (Online/Offline, Last Active Time, Location, Battery, Connection)
    console.log('\n[3] Testing Guardian Status & Telemetry...');
    const nowTs = Date.now();
    await GuardianCircleService_1.GuardianCircleService.updateGuardianTelemetry(g1.id, {
        isOnline: true,
        latitude: 37.7749,
        longitude: -122.4194,
        lastLocationUpdate: nowTs,
        batteryLevel: 92,
        connectionStatus: 'ONLINE',
    });
    const circleAfterTelemetry = await GuardianCircleService_1.GuardianCircleService.getGuardianCircle('user_step1');
    const mappedG1 = circleAfterTelemetry.find((c) => c.id === g1.id);
    assert(mappedG1.isOnline === true, 'Guardian 1 online status is true');
    assert(mappedG1.connectionStatus === 'ONLINE', 'Guardian 1 connection status is ONLINE');
    assert(mappedG1.batteryLevel === 92, 'Guardian 1 battery percentage is 92%');
    assert(mappedG1.lastKnownLatitude === 37.7749 && mappedG1.lastKnownLongitude === -122.4194, 'Guardian 1 last known coordinates stored');
    assert(mappedG1.lastLocationUpdate === nowTs, 'Guardian 1 last active/update time stored');
    console.log('  ✅ Guardian status & telemetry verified');
    // 4. Priority Management & Active/Inactive State
    console.log('\n[4] Testing Priority Reordering & Active/Inactive State...');
    await GuardianCircleService_1.GuardianCircleService.setGuardianPriority(g2.id, 1);
    const reordered = await ContactDao_1.ContactDao.getAll();
    assert(reordered[0].id === g2.id && reordered[0].priority === 1, 'Guardian 2 promoted to Priority 1');
    assert(reordered[1].id === g1.id && reordered[1].priority === 2, 'Guardian 1 swapped to Priority 2');
    // (E) Priority update uses the canonical UUID in Supabase
    assert(supabaseMock.emergencyContactsTable.get(g2.id)?.priority === '1', 'E. Priority update uses canonical UUID in Supabase emergency_contacts');
    await GuardianCircleService_1.GuardianCircleService.setGuardianActiveState(g1.id, false);
    const afterInactive = await GuardianCircleService_1.GuardianCircleService.getGuardianCircle('user_step1');
    assert(afterInactive.find((c) => c.id === g1.id)?.isActive === false, 'Guardian 1 set to Inactive state');
    await GuardianCircleService_1.GuardianCircleService.setGuardianActiveState(g1.id, true);
    console.log('  ✅ Priority reordering & active/inactive state verified');
    // 5. Per-Guardian Location Sharing Controls & Snapshot Privacy
    console.log('\n[5] Testing Per-Guardian Location Sharing Controls...');
    await GuardianCircleService_1.GuardianCircleService.setGuardianLocationSharing(g1.id, false);
    await GuardianCircleService_1.GuardianCircleService.setGuardianLocationSharing(g2.id, true);
    const permittedGuardians = await GuardianCircleService_1.GuardianCircleService.getGuardiansWithLocationPermission();
    assert(permittedGuardians.length === 1 && permittedGuardians[0].id === g2.id, 'Only Guardian 2 has explicit location-sharing permission');
    const snapshotRes = await GuardianCircleService_1.GuardianCircleService.shareOneTimeLocationSnapshot();
    assert(snapshotRes.sharedCount === 1, 'One-time location snapshot shared only with permitted guardian');
    safetyStore_1.useSafetyStore.getState().setLocationSharingEnabled(false);
    const pausedPermitted = await GuardianCircleService_1.GuardianCircleService.getGuardiansWithLocationPermission();
    assert(pausedPermitted.length === 0, 'Master location sharing pause stops all guardian location sharing');
    safetyStore_1.useSafetyStore.getState().setLocationSharingEnabled(true);
    console.log('  ✅ Location-sharing controls & privacy verified');
    // 6. Offline Behavior (SQLite Offline-First) + Canonical UUID Retention (F, G, H)
    console.log('\n[6] Testing Offline SQLite Behavior, UUID Retention & Non-Destructive Sync...');
    safetyStore_1.useSafetyStore.getState().setNetworkAvailable(false);
    const offlineGuardian = await GuardianCircleService_1.GuardianCircleService.addGuardian({
        userId: 'user_step1',
        name: 'Dr. Aris Thorne',
        phone: '+15550103',
        relationship: 'Doctor',
        isActive: false,
        locationSharingAllowed: true,
    });
    // (H) Offline-created guardian receives and retains a valid canonical UUID
    assert(!!offlineGuardian.id &&
        GuardianCircleService_1.GuardianCircleService.isValidUuid(offlineGuardian.id) &&
        !offlineGuardian.id.startsWith('contact_'), 'H. Offline-created guardian receives and retains a valid RFC 4122 UUID');
    const offlineCircle = await GuardianCircleService_1.GuardianCircleService.getGuardianCircle('user_step1');
    assert(offlineCircle.length === 3, 'All 3 guardians loaded offline from SQLite');
    // Reconnect and sync with Supabase (verifying F, G, H)
    safetyStore_1.useSafetyStore.getState().setNetworkAvailable(true);
    await GuardianCircleService_1.GuardianCircleService.syncWithSupabase('user_step1');
    const contactsAfterSync = await ContactDao_1.ContactDao.getAll();
    // (F) Syncing existing Supabase guardians does NOT create duplicate local rows
    assert(contactsAfterSync.length === 3, 'F. Syncing with Supabase does not create duplicate local guardian rows');
    // (H) Offline guardian was synced to Supabase with the exact same canonical UUID
    assert(supabaseMock.emergencyContactsTable.get(offlineGuardian.id)?.id === offlineGuardian.id, 'H. Offline-created guardian synced to Supabase with its exact canonical UUID');
    // (G) Existing guardian_circle_prefs (location_sharing_allowed, is_active, priority) are preserved during sync
    const g2AfterSync = await ContactDao_1.ContactDao.getById(g2.id);
    const offlineAfterSync = await ContactDao_1.ContactDao.getById(offlineGuardian.id);
    assert(g2AfterSync?.location_sharing_allowed === 1 &&
        g2AfterSync?.is_active === 1 &&
        g2AfterSync?.priority === 1 &&
        offlineAfterSync?.location_sharing_allowed === 1 &&
        offlineAfterSync?.is_active === 0, 'G. Existing guardian_circle_prefs (location_sharing_allowed, is_active, priority) are preserved during sync');
    console.log('  ✅ Offline SQLite persistence, canonical UUID sync & preference preservation verified');
    // 7. Guardian Deletion & Priority Normalization (D)
    console.log('\n[7] Testing Guardian Deletion...');
    await GuardianCircleService_1.GuardianCircleService.removeGuardian(offlineGuardian.id);
    const afterDelete = await ContactDao_1.ContactDao.getAll();
    assert(afterDelete.length === 2, 'Guardian removed from SQLite');
    // (D) Delete uses the canonical UUID in Supabase
    assert(!supabaseMock.emergencyContactsTable.has(offlineGuardian.id), 'D. Delete uses canonical UUID to remove guardian from Supabase emergency_contacts');
    assert(afterDelete[0].priority === 1 && afterDelete[1].priority === 2, 'Remaining guardian priorities normalized');
    console.log('  ✅ Guardian deletion verified');
    // 8. Existing SOS Contact Lookup, Direct Call/SMS & Unified SOS Pipeline Intact
    console.log('\n[8] Verifying Existing SOS Contact Lookup, Call/SMS & SOS Flows...');
    const sosContacts = await ContactDao_1.ContactDao.getAll();
    assert(sosContacts.length === 2 && sosContacts[0].priority === 1, 'Existing ContactDao.getAll() returns Rank 1 primary contact first for SOS lookup');
    let callCount = 0;
    let smsCount = 0;
    const origCall = TelephonyService_1.TelephonyService.initiateEmergencyCall.bind(TelephonyService_1.TelephonyService);
    const origSms = TelephonyService_1.TelephonyService.sendDirectSms.bind(TelephonyService_1.TelephonyService);
    TelephonyService_1.TelephonyService.initiateEmergencyCall = async (phone) => {
        callCount++;
        return origCall(phone);
    };
    TelephonyService_1.TelephonyService.sendDirectSms = async (phone, msg) => {
        smsCount++;
        return origSms(phone, msg);
    };
    try {
        // Individual Call action
        await TelephonyService_1.TelephonyService.initiateEmergencyCall(sosContacts[0].phone);
        assert(callCount === 1 && smsCount === 0, 'Direct Call action triggers call only (no SMS)');
        // Individual SMS action
        await TelephonyService_1.TelephonyService.sendDirectSms(sosContacts[0].phone, 'Test SMS');
        assert(callCount === 1 && smsCount === 1, 'Direct SMS action triggers SMS only (no Call)');
        // Unified SOS flow
        const sosRes = await SosService_1.SosService.executeSosDispatch('MANUAL');
        assert(sosRes.success === true, 'Unified MANUAL SOS dispatch succeeds');
        assert(callCount >= 2 && smsCount >= 2, 'Unified SOS dispatches both Call to Rank 1 and SMS to contacts');
        await SosService_1.SosService.cancelSosAlert();
    }
    finally {
        TelephonyService_1.TelephonyService.initiateEmergencyCall = origCall;
        TelephonyService_1.TelephonyService.sendDirectSms = origSms;
        safetyStore_1.useSafetyStore.getState().setNetworkAvailable(true);
    }
    console.log('  ✅ Existing SOS contact lookup, Call/SMS isolation & SOS pipeline verified');
    console.log('\n==========================================================');
    console.log('🎉 ALL STEP 1 GUARDIAN CIRCLE + STATUS TESTS PASSED 100%!');
    console.log('==========================================================');
}
if (typeof require !== 'undefined' && require.main === module) {
    runStep1GuardianCircleTests().catch((err) => {
        console.error('❌ Step 1 Guardian Circle Tests Failed:', err);
        process.exit(1);
    });
}
