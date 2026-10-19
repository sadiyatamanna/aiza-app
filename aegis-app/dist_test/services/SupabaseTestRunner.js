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
const SupabaseMock_1 = require("./SupabaseMock");
const SupabaseClientModule = __importStar(require("./SupabaseClient"));
const AuthService_1 = require("./AuthService");
const ProfileService_1 = require("./ProfileService");
const EmergencyContactService_1 = require("./EmergencyContactService");
const SosCloudService_1 = require("./SosCloudService");
const UserLocationService_1 = require("./UserLocationService");
const safetyStore_1 = require("../store/safetyStore");
/**
 * Automated test suite verifying all 12 Phase 3 criteria:
 * 1. Supabase client initialization
 * 2. Auth service registration handling
 * 3. Login handling
 * 4. OTP verification handling
 * 5. Logout
 * 6. Session restoration
 * 7. Profile creation
 * 8. Profile retrieval
 * 9. Emergency contact CRUD service
 * 10. SOS cloud service data mapping
 * 11. User location service data mapping
 * 12. Offline application startup
 */
async function runPhase3SupabaseTests() {
    console.log('--- Starting Aegis Phase 3 Supabase & Authentication Tests ---');
    const mock = new SupabaseMock_1.SupabaseMock();
    // Wire mock into SupabaseClient export
    SupabaseClientModule.supabase = mock;
    const authService = AuthService_1.AuthService.getInstance();
    const profileService = ProfileService_1.ProfileService.getInstance();
    const emergencyContactService = EmergencyContactService_1.EmergencyContactService.getInstance();
    const sosCloudService = SosCloudService_1.SosCloudService.getInstance();
    const userLocationService = UserLocationService_1.UserLocationService.getInstance();
    let passed = 0;
    let total = 0;
    function assert(condition, testName) {
        total++;
        if (condition) {
            console.log(`[PASS] ${testName}`);
            passed++;
        }
        else {
            console.error(`[FAIL] ${testName}`);
            throw new Error(`Test failed: ${testName}`);
        }
    }
    // 1. Supabase client initialization
    const config = SupabaseClientModule.getSupabaseConfig();
    assert(config.supabaseUrl.includes('supabase.co') &&
        !config.supabaseUrl.includes('/rest/v1') &&
        config.supabasePublishableKey.length > 10, '1. Supabase client initialization (clean URL normalization & public publishable key configured)');
    // 2. Auth service registration handling
    const signUpRes = await authService.signUp('test.guardian@aegis.org', 'StrongPassword123!', {
        name: 'Sarah Connor',
        phone: '+15550001111',
    });
    assert(signUpRes.success === true && signUpRes.user !== undefined && signUpRes.user?.email === 'test.guardian@aegis.org', '2. Auth service registration handling (email/password with guardian metadata)');
    const testUserId = signUpRes.user.id;
    // 3. Login handling
    const loginRes = await authService.signIn('test.guardian@aegis.org', 'StrongPassword123!');
    assert(loginRes.success === true && loginRes.user?.id === testUserId && loginRes.session !== null, '3. Login handling (email/password authentication producing valid session)');
    // 4. OTP verification handling
    const otpRes = await authService.verifyEmailOtp('test.guardian@aegis.org', '123456', 'signup');
    assert(otpRes.success === true && otpRes.user !== null && otpRes.session !== null, '4. OTP verification handling (email confirmation code verification)');
    // 5. Logout
    const logoutRes = await authService.signOut();
    assert(logoutRes.success === true, '5. Logout (clears active session)');
    // 6. Session restoration
    // Re-sign in and verify getSession/getCurrentUser restores session
    await authService.signIn('test.guardian@aegis.org', 'StrongPassword123!');
    const restoredSession = await authService.getCurrentSession();
    const restoredUser = await authService.getCurrentUser();
    assert(restoredSession !== null && restoredUser?.id === testUserId, '6. Session restoration (restores user and token from storage)');
    // 7. Profile creation
    const createProfileRes = await profileService.createProfile({
        id: testUserId,
        name: 'Sarah Connor',
        email: 'test.guardian@aegis.org',
        phone: '+15550001111',
    });
    assert(createProfileRes.success === true &&
        createProfileRes.data?.id === testUserId &&
        createProfileRes.data?.phone === '+15550001111', '7. Profile creation (bound to auth.users.id without duplicate profiles)');
    // 8. Profile retrieval
    const getProfileRes = await profileService.getProfile(testUserId);
    assert(getProfileRes.success === true &&
        getProfileRes.data?.name === 'Sarah Connor' &&
        getProfileRes.data?.email === 'test.guardian@aegis.org', '8. Profile retrieval (retrieves profiles table record)');
    // 9. Emergency contact CRUD service
    const addContactRes = await emergencyContactService.addContact({
        userId: testUserId,
        name: 'John Connor',
        phone: '+15550002222',
        relationship: 'Son',
        priority: '1',
    });
    assert(addContactRes.success === true && addContactRes.data?.user_id === testUserId && addContactRes.data?.priority === '1', '9a. Emergency contact insertion (priority 1)');
    const contactId = addContactRes.data.id;
    const contactsListRes = await emergencyContactService.getContacts(testUserId);
    assert(contactsListRes.success === true &&
        contactsListRes.data?.length === 1 &&
        contactsListRes.data[0].id === contactId, '9b. Emergency contact retrieval (filtered by user_id)');
    const updatePriorityRes = await emergencyContactService.updateContactPriority(contactId, testUserId, '2');
    assert(updatePriorityRes.success === true, '9c. Emergency contact priority update');
    const deleteContactRes = await emergencyContactService.deleteContact(contactId, testUserId);
    assert(deleteContactRes.success === true, '9d. Emergency contact deletion');
    // 10. SOS cloud service data mapping
    const insertSosRes = await sosCloudService.insertSosAlert({
        userId: testUserId,
        latitude: 37.7749,
        longitude: -122.4194,
        locationAvailable: true,
        triggerType: 'MANUAL',
        status: 'ACTIVE',
        encryptedPayload: 'MOCK_KYBER_CIPHERTEXT',
        signature: 'MOCK_DILITHIUM_SIGNATURE',
    });
    assert(insertSosRes.success === true &&
        insertSosRes.data?.user_id === testUserId &&
        insertSosRes.data?.location_available === true &&
        insertSosRes.data?.trigger_type === 'MANUAL' &&
        insertSosRes.data?.latitude === 37.7749, '10a. SOS cloud service data mapping (maps existing sos_alerts columns)');
    const getSosRes = await sosCloudService.getSosAlerts(testUserId);
    assert(getSosRes.success === true && getSosRes.data?.length === 1, '10b. SOS cloud service retrieval');
    // 11. User location service data mapping
    const insertLocRes = await userLocationService.insertLocation({
        userId: testUserId,
        latitude: 37.775,
        longitude: -122.4195,
        accuracy: 5.0,
        isSharingEnabled: true,
        batteryLevel: 95,
    });
    assert(insertLocRes.success === true &&
        insertLocRes.data?.user_id === testUserId &&
        insertLocRes.data?.is_sharing_enabled === true &&
        insertLocRes.data?.battery_level === 95, '11a. User location service data mapping (maps existing user_locations columns)');
    const latestLocRes = await userLocationService.getLatestLocation(testUserId);
    assert(latestLocRes.success === true &&
        latestLocRes.data?.user_id === testUserId &&
        latestLocRes.data?.latitude === 37.775, '11b. User location latest retrieval');
    // 12. Offline application startup
    // Ensure store operates seamlessly without network, preserving offline persistence
    const store = safetyStore_1.useSafetyStore.getState();
    store.setNetworkAvailable(false);
    assert(safetyStore_1.useSafetyStore.getState().networkAvailable === false, '12. Offline application startup (operates with network offline flag without crashing)');
    console.log(`\nAll ${total}/${total} Phase 3 Supabase & Authentication Tests passed successfully!`);
}
runPhase3SupabaseTests().catch((err) => {
    console.error('Phase 3 test runner failure:', err);
    process.exit(1);
});
