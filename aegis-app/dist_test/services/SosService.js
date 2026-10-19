"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.SosService = void 0;
const safetyStore_1 = require("../store/safetyStore");
const SirenService_1 = require("./SirenService");
const HardwareService_1 = require("./HardwareService");
const BackgroundService_1 = require("./BackgroundService");
const TelephonyService_1 = require("./TelephonyService");
const PqcSecurityService_1 = require("./PqcSecurityService");
const SosLogDao_1 = require("../database/SosLogDao");
const ContactDao_1 = require("../database/ContactDao");
exports.SosService = {
    /**
     * Executes the unified emergency SOS alert workflow across native hardware,
     * telephony, and local SQLite database persistence.
     *
     * In Phase 5:
     * - Hardware warnings (siren & torch) are activated.
     * - Telephony emergency call and SMS are dispatched.
     * - The alert is saved LOCALLY to the SQLite database (SosLogDao) with:
     *   status = 'Pending_Sync'
     *   synced_to_cloud = 0
     * - No automatic cloud upload, Storage upload, or Realtime synchronization is performed.
     */
    async executeSosDispatch(source) {
        const timestamp = Date.now();
        const alertId = `sos_${timestamp}_${Math.random().toString(36).substring(2, 8)}`;
        const store = safetyStore_1.useSafetyStore.getState();
        const userId = store.currentUser?.id || store.userProfile?.id || 'offline_user';
        // 1. Update Reactive Zustand State
        store.triggerSos(source);
        // 2. Hardware Audio & Visual Warning
        let sirenActive = false;
        let torchActive = false;
        try {
            const sirenRes = await SirenService_1.SirenService.playEmergencySiren();
            sirenActive = sirenRes.success;
        }
        catch (e) {
            console.warn('Emergency siren trigger non-fatal error:', e);
        }
        try {
            const torchRes = await HardwareService_1.HardwareService.toggleTorch(true);
            torchActive = torchRes.enabled;
        }
        catch (e) {
            console.warn('Camera torch strobe non-fatal error:', e);
        }
        // 3. Keep Active in Foreground Background Service
        try {
            await BackgroundService_1.BackgroundService.startService();
        }
        catch (e) {
            console.warn('Foreground background service start non-fatal error:', e);
        }
        // 4. Determine Best Available Location
        const lat = store.currentLocation?.latitude ?? store.lastKnownLocation?.latitude ?? 0;
        const lng = store.currentLocation?.longitude ?? store.lastKnownLocation?.longitude ?? 0;
        const isLocationAvailable = lat !== 0 || lng !== 0;
        const isLastKnown = !store.currentLocation && !!store.lastKnownLocation;
        // 5. Post-Quantum Cryptography Preparation
        const rawPayload = JSON.stringify({
            alertId,
            userId,
            timestamp,
            source,
            latitude: lat,
            longitude: lng,
        });
        let encryptedPayload = null;
        let signature = null;
        try {
            encryptedPayload = await PqcSecurityService_1.PqcSecurityService.encryptPayload(rawPayload);
            signature = await PqcSecurityService_1.PqcSecurityService.signPayload(rawPayload);
        }
        catch (e) {
            console.warn('PQC processing note:', e);
        }
        // 6. Emergency Contacts Dispatch (Phone Call & SMS)
        let callDispatchedTo;
        let smsSentCount = 0;
        try {
            // Fetch contacts from local SQLite first, fallback to store
            const dbContacts = await ContactDao_1.ContactDao.getAll();
            const contactsToNotify = dbContacts.length > 0
                ? dbContacts.map((c) => ({ name: c.name, phone: c.phone, priority: c.priority }))
                : store.contacts.map((c) => ({ name: c.name, phone: c.phoneNumber, priority: c.priorityOrder }));
            if (contactsToNotify.length > 0) {
                // Priority 1: Direct Voice Call
                const primaryContact = contactsToNotify[0];
                if (primaryContact.phone) {
                    try {
                        await TelephonyService_1.TelephonyService.initiateEmergencyCall(primaryContact.phone);
                        callDispatchedTo = primaryContact.phone;
                    }
                    catch (e) {
                        console.warn('Emergency call failed:', e);
                    }
                }
                // Priority 1+: Direct SMS with coordinates link
                const locationSnippet = isLocationAvailable
                    ? `https://maps.google.com/?q=${lat},${lng}`
                    : 'Location unavailable';
                const emergencySmsText = `[AIZA SOS ALERT] Emergency triggered via ${source}. I need urgent assistance! Location: ${locationSnippet}`;
                for (const contact of contactsToNotify) {
                    if (contact.phone) {
                        try {
                            await TelephonyService_1.TelephonyService.sendDirectSms(contact.phone, emergencySmsText);
                            smsSentCount++;
                        }
                        catch (e) {
                            console.warn(`SMS dispatch failed for ${contact.name}:`, e);
                        }
                    }
                }
            }
        }
        catch (e) {
            console.warn('Contacts dispatch error:', e);
        }
        // 7. Local Offline SQLite Storage Only (Phase 5 constraint: Pending_Sync, synced_to_cloud = 0)
        const sosAlertRecord = {
            id: alertId,
            userId,
            triggerSource: source,
            latitude: lat,
            longitude: lng,
            isLastKnownLocation: isLastKnown,
            accuracy: store.currentLocation?.accuracy ?? 10,
            timestamp,
            syncStatus: 'Pending_Sync',
            encryptedPayload,
            signature,
        };
        try {
            await SosLogDao_1.SosLogDao.insert({
                id: alertId,
                user_id: userId,
                trigger_type: source,
                latitude: lat,
                longitude: lng,
                location_available: isLocationAvailable ? 1 : 0,
                location_is_last_known: isLastKnown ? 1 : 0,
                timestamp,
                status: 'Pending_Sync',
                encrypted_payload: encryptedPayload,
                signature: signature,
                voice_clip_local_path: null,
                voice_clip_url: null,
                synced_to_cloud: 0,
                created_at: timestamp,
            });
        }
        catch (e) {
            console.warn('Local SOS log insert non-fatal error:', e);
        }
        return {
            success: true,
            alertId,
            source,
            callDispatchedTo,
            smsSentCount,
            sirenActive,
            torchActive,
        };
    },
    /**
     * Cancels active emergency SOS state, turns off siren & torch.
     */
    async cancelSosAlert() {
        const store = safetyStore_1.useSafetyStore.getState();
        store.cancelSos();
        try {
            await SirenService_1.SirenService.stopEmergencySiren();
        }
        catch (e) {
            console.warn('Stop siren error:', e);
        }
        try {
            await HardwareService_1.HardwareService.toggleTorch(false);
        }
        catch (e) {
            console.warn('Stop torch error:', e);
        }
    },
};
