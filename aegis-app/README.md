# AIZA - Cross-Platform Architecture

Cross-platform TypeScript architecture for **AIZA** (Autonomous Intelligent Zero-latency Armor), built with React Native and focused on high-reliability personal safety.

## Status: Streamlined Core Safety Architecture
- **Protected Native Android Project**: Preserved 100% untouched in `/app` and root Gradle files.
- **Cross-Platform Target**: Maintained in `/aegis-app` with future work focused on iOS support and cross-platform safety parity.
- **Data & Feature Cleanup**: Removed unneeded complexity (chat, medical records, third-party social logins) to guarantee zero latency and maximal reliability during critical emergency events.

---

## 1. Authentication & Identity
* **Email & Password Authentication**: Supabase Auth with secure email verification and OTP activation.
* **Streamlined Security**:
  * Google Sign-In removed.
  * Phone OTP login removed.
  * Local offline bypass mode supported for immediate emergency access without network latency.

---

## 2. Removed Unneeded Features
* **People Who Saved Me**: Completely removed from navigation, data structures, and views.
* **Chat Integration**: Completely removed all in-app chat UI, Supabase Realtime message channels, and chat queries.
* **Medical Information**: Completely removed medical data storage (blood group, allergies, chronic conditions, emergency medical IDs) to minimize data liability and streamline profiles.
* **Minimalist UI**: Eliminated unnecessary card nesting, duplicate badges, and redundant containers across the interface.

---

## 3. Retained Core Safety Features
* **Emergency SOS Activation**:
  * One-tap instant SOS dispatch with cancel countdown and pulsing emergency state.
  * Audio siren and camera flashlight strobe deterrents.
* **Direct Emergency Calling**:
  * Priority 1 guardian automatically triggered for direct phone call via device telephony.
* **Emergency SMS with Live GPS**:
  * Automated SMS dispatch to registered guardians containing real-time GPS coordinates and Google Maps link.
* **Guardian Circle Management**:
  * Priority-ordered contacts (Rank 1 for direct calling, Rank 2+ for SMS dispatches).
  * Per-guardian active status and explicit user-controlled location-sharing permissions.
* **Real-Time Location & Safety Radar**:
  * High-accuracy device GPS tracking.
  * Nearby safety POIs (Police, Hospitals, Pharmacies, Shelters) with offline SQLite caching.
* **Immutable Emergency History**:
  * Local offline-first audit log storing all emergency events with trigger source, timestamps, and coordinates.
  * Formatted strictly as pure event history with no "sent" or "received" message states.
* **Multi-Modal Hardware Triggers**:
  * Bluetooth SPP wearable trigger (ESP32 SOS-WEAR).
  * Accelerometer shake detection and voice triggers.
* **Post-Quantum Cryptography (PQC)**:
  * NIST FIPS 203 (ML-KEM-1024) and FIPS 204 (ML-DSA-87) payload protection.
* **Future Platform Scope**:
  * Future engineering work is dedicated strictly to iOS support and native iOS background/sensor parity.

---

## 4. Tech Stack & Navigation
* **State Management**: `zustand` (`src/store/safetyStore.ts`)
* **Local Storage**: SQLite (`src/database/AppDatabase.ts`)
* **Cloud Services**: `@supabase/supabase-js` (`src/services/SupabaseClient.ts` for Auth, Profiles, Guardians, and SOS Logs)
* **Theme**: Dark Obsidian (`#0F0F12`) with Magenta accents (`#E91E63`, `#FF2E63`)
* **Navigation**: 5 streamlined tabs:
  1. **Home** (🚨): SOS dispatch button, system arm state, GPS telemetry, quick controls.
  2. **Guardians** (👥): Priority contact management, location permissions, call & SMS actions.
  3. **Radar** (📍): Real-time map, POI safety facilities, safe zones.
  4. **History** (📋): Local audit trail of past emergency events.
  5. **Profile** (🛡️): User identity, contact details, protection telemetry, sign out.

---

## Running the Project
```bash
cd aegis-app
npm install
npm test
```
