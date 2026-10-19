package com.example.bridge

import android.view.View
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * React Native Package registering Aegis Women & Personal Safety Android Native Bridges:
 * - Telephony (Direct call & SMS)
 * - Bluetooth Classic SPP (ESP32 SOS-WEAR button listener with 5s cooldown)
 * - Foreground Background Service
 * - Hardware Alert (Siren & Torch)
 * - Post-Quantum Cryptography Bridge (FIPS 203/204 stub)
 */
class AegisNativePackage : ReactPackage {

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(
            AegisTelephonyModule(reactContext),
            AegisBluetoothModule(reactContext),
            AegisBackgroundServiceModule(reactContext),
            AegisHardwareModule(reactContext),
            AegisPqcModule(reactContext)
        )
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<View, *>> {
        return emptyList()
    }
}
