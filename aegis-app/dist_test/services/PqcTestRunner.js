"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const PqcSecurityService_1 = require("./PqcSecurityService");
const SosService_1 = require("./SosService");
const SosLogDao_1 = require("../database/SosLogDao");
const AppDatabase_1 = require("../database/AppDatabase");
const InMemorySqliteMock_1 = require("../database/InMemorySqliteMock");
const DatabaseMigrations_1 = require("../database/DatabaseMigrations");
const nativeBridge_1 = require("./nativeBridge");
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
async function runPqcTests() {
    console.log('=== Starting AIZA Post-Quantum Cryptography (PQC) Test Suite ===');
    console.log('Target: NIST FIPS 203 (ML-KEM-1024) | NIST FIPS 204 (ML-DSA-87) | AES-256-GCM\n');
    // Initialize SQLite database for SOS integration tests
    const mock = new InMemorySqliteMock_1.InMemorySqliteMock();
    AppDatabase_1.AppDatabase.executeRaw = (sql, params) => mock.executeRaw(sql, params);
    for (const m of DatabaseMigrations_1.DB_MIGRATIONS) {
        await m.up(async (sql) => mock.executeRaw(sql));
    }
    let kemPublicKey = '';
    let kemPrivateKey = '';
    let dsaPublicKey = '';
    let dsaPrivateKey = '';
    let encapsulatedSecret = '';
    let encapsulatedCiphertext = '';
    let generatedSignature = '';
    const testAlertMessage = JSON.stringify({
        alertId: 'pqc-test-alert-001',
        userId: 'user-aiza-001',
        timestamp: Date.now(),
        source: 'MANUAL',
        latitude: 37.7749,
        longitude: -122.4194,
    });
    // Test 1: PQC native availability
    try {
        const isAvail = await PqcSecurityService_1.PqcSecurityService.isPqcAvailable();
        assert(isAvail === true, '1. PQC native availability: isPqcAvailable() returns true');
    }
    catch (err) {
        assert(false, '1. PQC native availability', err.message);
    }
    // Test 2: PQC status reports ACTIVE with full algorithms list
    try {
        const status = await PqcSecurityService_1.PqcSecurityService.getPqcStatus();
        assert(status.available === true &&
            status.status === 'PQC_ACTIVE' &&
            status.supportedAlgorithms.includes('ML-KEM-1024') &&
            status.supportedAlgorithms.includes('ML-DSA-87') &&
            status.supportedAlgorithms.includes('AES-256-GCM'), '2. PQC status reports ACTIVE with NIST FIPS 203 & 204 supported algorithms');
    }
    catch (err) {
        assert(false, '2. PQC status reports ACTIVE', err.message);
    }
    // Test 3: ML-KEM-1024 key generation (FIPS 203 byte lengths)
    try {
        const kemKeypair = await PqcSecurityService_1.PqcSecurityService.generatePqcKeyPair('ML-KEM-1024');
        kemPublicKey = kemKeypair.publicKey;
        kemPrivateKey = kemKeypair.privateKey;
        const pubBytes = Buffer.from(kemPublicKey, 'base64');
        const privBytes = Buffer.from(kemPrivateKey, 'base64');
        assert(kemKeypair.algorithm === 'ML-KEM-1024' &&
            kemKeypair.publicKeyLength === 1568 &&
            kemKeypair.privateKeyLength === 3168 &&
            pubBytes.length === 1568 &&
            privBytes.length === 3168, '3. ML-KEM-1024 key generation: Public key 1568 bytes, Private key 3168 bytes');
    }
    catch (err) {
        assert(false, '3. ML-KEM-1024 key generation', err.message);
    }
    // Test 4: ML-KEM encapsulation (FIPS 203 ciphertext 1568 bytes, shared secret 32 bytes)
    try {
        const encap = await PqcSecurityService_1.PqcSecurityService.kemEncapsulate(kemPublicKey);
        encapsulatedCiphertext = encap.cipherText;
        encapsulatedSecret = encap.sharedSecret;
        const ctBytes = Buffer.from(encap.cipherText, 'base64');
        const secBytes = Buffer.from(encap.sharedSecret, 'base64');
        assert(encap.cipherTextLength === 1568 &&
            encap.sharedSecretLength === 32 &&
            ctBytes.length === 1568 &&
            secBytes.length === 32, '4. ML-KEM encapsulation: Ciphertext 1568 bytes, Shared secret 32 bytes (256-bit entropy)');
    }
    catch (err) {
        assert(false, '4. ML-KEM encapsulation', err.message);
    }
    // Test 5: ML-KEM decapsulation
    let decapsulatedSecret = '';
    try {
        const decap = await PqcSecurityService_1.PqcSecurityService.kemDecapsulate(encapsulatedCiphertext, kemPrivateKey);
        decapsulatedSecret = decap.sharedSecret;
        const decapSecBytes = Buffer.from(decap.sharedSecret, 'base64');
        assert(decap.sharedSecretLength === 32 && decapSecBytes.length === 32, '5. ML-KEM decapsulation: Successfully extracted 32-byte shared secret');
    }
    catch (err) {
        assert(false, '5. ML-KEM decapsulation', err.message);
    }
    // Test 6: ML-KEM shared-secret equality (Key Agreement Agreement Theorem)
    try {
        const match = encapsulatedSecret === decapsulatedSecret &&
            Buffer.from(encapsulatedSecret, 'base64').equals(Buffer.from(decapsulatedSecret, 'base64'));
        assert(match === true, '6. ML-KEM shared-secret equality: Encapsulated and decapsulated secrets match exactly');
    }
    catch (err) {
        assert(false, '6. ML-KEM shared-secret equality', err.message);
    }
    // Test 7: ML-DSA-87 key generation (FIPS 204 byte lengths)
    try {
        const dsaKeypair = await PqcSecurityService_1.PqcSecurityService.generatePqcKeyPair('ML-DSA-87');
        dsaPublicKey = dsaKeypair.publicKey;
        dsaPrivateKey = dsaKeypair.privateKey;
        const pubBytes = Buffer.from(dsaPublicKey, 'base64');
        const privBytes = Buffer.from(dsaPrivateKey, 'base64');
        assert(dsaKeypair.algorithm === 'ML-DSA-87' &&
            dsaKeypair.publicKeyLength === 2592 &&
            dsaKeypair.privateKeyLength === 4896 &&
            pubBytes.length === 2592 &&
            privBytes.length === 4896, '7. ML-DSA-87 key generation: Public key 2592 bytes, Private key 4896 bytes');
    }
    catch (err) {
        assert(false, '7. ML-DSA-87 key generation', err.message);
    }
    // Test 8: ML-DSA-87 signing (FIPS 204 signature 4627 bytes)
    try {
        const signRes = await PqcSecurityService_1.PqcSecurityService.dsaSign(testAlertMessage, dsaPrivateKey);
        generatedSignature = signRes.signature;
        const sigBytes = Buffer.from(signRes.signature, 'base64');
        assert(signRes.algorithm === 'ML-DSA-87' &&
            signRes.signatureLength === 4627 &&
            sigBytes.length === 4627, '8. ML-DSA-87 signing: Signature generated exactly 4627 bytes');
    }
    catch (err) {
        assert(false, '8. ML-DSA-87 signing', err.message);
    }
    // Test 9: ML-DSA-87 valid signature verification
    try {
        const isValid = await PqcSecurityService_1.PqcSecurityService.dsaVerify(testAlertMessage, generatedSignature, dsaPublicKey);
        assert(isValid === true, '9. ML-DSA-87 valid signature verification: Signature accepted');
    }
    catch (err) {
        assert(false, '9. ML-DSA-87 valid signature verification', err.message);
    }
    // Test 10: ML-DSA-87 modified-message rejection
    try {
        const tamperedMessage = testAlertMessage + ' [TAMPERED_COORDINATES: 0.0, 0.0]';
        const isTamperedValid = await PqcSecurityService_1.PqcSecurityService.dsaVerify(tamperedMessage, generatedSignature, dsaPublicKey);
        assert(isTamperedValid === false, '10. ML-DSA-87 modified-message rejection: Modified payload signature rejected');
    }
    catch (err) {
        assert(false, '10. ML-DSA-87 modified-message rejection', err.message);
    }
    // Test 11: AES-256-GCM encryption
    let encryptedPacketJson = '';
    try {
        encryptedPacketJson = await PqcSecurityService_1.PqcSecurityService.encryptPayload(testAlertMessage, kemPublicKey);
        const packet = JSON.parse(encryptedPacketJson);
        // Decrypt to verify round-trip
        const decrypted = await PqcSecurityService_1.PqcSecurityService.decryptPayload(encryptedPacketJson, kemPrivateKey);
        assert(packet.algorithm === 'ML-KEM-1024+AES-256-GCM' &&
            typeof packet.ciphertext === 'string' &&
            packet.ciphertext.length > 0 &&
            decrypted === testAlertMessage, '11. AES-256-GCM encryption: Round-trip hybrid encryption and decryption verified');
    }
    catch (err) {
        assert(false, '11. AES-256-GCM encryption', err.message);
    }
    // Test 12: Random IV verification (Fresh 12-byte CSPRNG IV per encryption)
    try {
        const packet1Json = await PqcSecurityService_1.PqcSecurityService.encryptPayload(testAlertMessage, kemPublicKey);
        const packet2Json = await PqcSecurityService_1.PqcSecurityService.encryptPayload(testAlertMessage, kemPublicKey);
        const packet1 = JSON.parse(packet1Json);
        const packet2 = JSON.parse(packet2Json);
        const iv1 = Buffer.from(packet1.iv, 'base64');
        const iv2 = Buffer.from(packet2.iv, 'base64');
        assert(iv1.length === 12 &&
            iv2.length === 12 &&
            packet1.iv !== packet2.iv &&
            packet1.ciphertext !== packet2.ciphertext, '12. Random IV verification: Fresh distinct 12-byte IVs generated across operations');
    }
    catch (err) {
        assert(false, '12. Random IV verification', err.message);
    }
    // Test 13: Authentication/tamper detection (AES-256-GCM AEAD Tag verification)
    try {
        const packet = JSON.parse(encryptedPacketJson);
        const cipherBuf = Buffer.from(packet.ciphertext, 'base64');
        cipherBuf[0] ^= 0x01; // flip 1 bit in ciphertext
        packet.ciphertext = cipherBuf.toString('base64');
        const tamperedPacketJson = JSON.stringify(packet);
        let caughtTamper = false;
        try {
            await PqcSecurityService_1.PqcSecurityService.decryptPayload(tamperedPacketJson, kemPrivateKey);
        }
        catch (e) {
            caughtTamper =
                e.message.includes('AEADBadTagException') ||
                    e.message.includes('Tag mismatch') ||
                    e.message.includes('PQC_DECRYPT_ERROR') ||
                    e.message.includes('authentication tag');
        }
        assert(caughtTamper === true, '13. Authentication/tamper detection: Tampered ciphertext triggers AEAD tag failure');
    }
    catch (err) {
        assert(false, '13. Authentication/tamper detection', err.message);
    }
    // Test 14: Encrypted packet structure verification
    try {
        const packet = JSON.parse(encryptedPacketJson);
        const hasAlgo = packet.algorithm === 'ML-KEM-1024+AES-256-GCM';
        const hasKemCt = Buffer.from(packet.kemCiphertext, 'base64').length === 1568;
        const hasIv = Buffer.from(packet.iv, 'base64').length === 12;
        const hasTagLen = packet.tagLength === 128;
        const hasPubKey = typeof packet.recipientPublicKey === 'string';
        assert(hasAlgo && hasKemCt && hasIv && hasTagLen && hasPubKey, '14. Encrypted packet structure verification: Validated JSON packet fields & standard lengths');
    }
    catch (err) {
        assert(false, '14. Encrypted packet structure verification', err.message);
    }
    // Test 15: SOS encrypted_payload integration in SosService & SQLite SosLogDao
    let triggeredSosId = '';
    try {
        const sosResult = await SosService_1.SosService.executeSosDispatch('MANUAL');
        triggeredSosId = sosResult.alertId || '';
        // Verify in SQLite database record
        const dbRecord = await SosLogDao_1.SosLogDao.getById(triggeredSosId);
        const hasEncryptedPayload = dbRecord != null && dbRecord.encrypted_payload != null && dbRecord.encrypted_payload.length > 0;
        const parsedPacket = hasEncryptedPayload ? JSON.parse(dbRecord.encrypted_payload) : null;
        assert(sosResult.success === true &&
            hasEncryptedPayload &&
            parsedPacket?.algorithm === 'ML-KEM-1024+AES-256-GCM' &&
            dbRecord != null &&
            dbRecord.status === 'Pending_Sync' &&
            dbRecord.synced_to_cloud === 0, '15. SOS encrypted_payload integration: Real PQC encrypted packet stored in SQLite (Pending_Sync, synced=0)');
    }
    catch (err) {
        assert(false, '15. SOS encrypted_payload integration', err.message);
    }
    // Test 16: SOS ML-DSA signature integration
    try {
        const dbRecord = await SosLogDao_1.SosLogDao.getById(triggeredSosId);
        const hasSignature = dbRecord != null && dbRecord.signature != null;
        const parsedSig = hasSignature ? JSON.parse(dbRecord.signature) : null;
        const sigBytes = parsedSig?.signature ? Buffer.from(parsedSig.signature, 'base64') : null;
        assert(hasSignature &&
            parsedSig?.algorithm === 'ML-DSA-87' &&
            sigBytes != null &&
            sigBytes.length === 4627, '16. SOS ML-DSA signature integration: Real 4627-byte ML-DSA-87 signature stored in SQLite');
    }
    catch (err) {
        assert(false, '16. SOS ML-DSA signature integration', err.message);
    }
    // Test 17: No plaintext fallback when PQC is unavailable (Security Honesty)
    try {
        // Temporarily simulate platform where PQC is unavailable (e.g. iOS)
        const originalOS = nativeBridge_1.Platform.OS;
        nativeBridge_1.Platform.OS = 'ios';
        const isAvailOnIos = await PqcSecurityService_1.PqcSecurityService.isPqcAvailable();
        const statusOnIos = await PqcSecurityService_1.PqcSecurityService.getPqcStatus();
        let threwOnEncrypt = false;
        try {
            await PqcSecurityService_1.PqcSecurityService.encryptPayload('CRITICAL_SOS_PLAINTEXT');
        }
        catch (e) {
            threwOnEncrypt =
                e.message.includes('PQC_UNAVAILABLE') &&
                    e.message.includes('Plaintext fallback is strictly prohibited');
        }
        let threwOnSign = false;
        try {
            await PqcSecurityService_1.PqcSecurityService.signPayload('CRITICAL_SOS_PLAINTEXT');
        }
        catch (e) {
            threwOnSign =
                e.message.includes('PQC_UNAVAILABLE') &&
                    e.message.includes('Empty signature fallback is strictly prohibited');
        }
        let threwOnKeygen = false;
        try {
            await PqcSecurityService_1.PqcSecurityService.generatePqcKeyPair('ML-KEM-1024');
        }
        catch (e) {
            threwOnKeygen = e.message.includes('PQC_UNAVAILABLE');
        }
        // Restore platform
        nativeBridge_1.Platform.OS = originalOS;
        assert(isAvailOnIos === false &&
            statusOnIos.status === 'PQC_NATIVE_IMPLEMENTATION_PENDING' &&
            threwOnEncrypt === true &&
            threwOnSign === true &&
            threwOnKeygen === true, '17. No plaintext fallback when PQC unavailable: Explicit errors thrown, zero silent fallback');
    }
    catch (err) {
        nativeBridge_1.Platform.OS = 'android';
        assert(false, '17. No plaintext fallback when PQC is unavailable', err.message);
    }
    console.log(`\nAll ${passed}/${passed + failed} Post-Quantum Cryptography Tests finished.`);
    if (failed > 0) {
        process.exit(1);
    }
}
runPqcTests().catch(err => {
    console.error('Fatal PQC Test Runner Error:', err);
    process.exit(1);
});
