package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast action: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = PreferencesManager(context)
            if (!prefs.isLoggedIn) {
                Log.d("BootReceiver", "User is logged out. Skipping background service start on boot.")
                return
            }
            val anyTriggerEnabled = prefs.isShakeEnabled || prefs.isBluetoothEnabled || prefs.isVoiceSosEnabled
            if (!anyTriggerEnabled) {
                Log.d("BootReceiver", "All safety trigger settings disabled. Skipping background service start on boot.")
                return
            }

            Log.d("BootReceiver", "Device boot completed. Starting AegisBackgroundService for active user...")
            val serviceIntent = Intent(context, AegisBackgroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("BootReceiver", "AegisBackgroundService start request sent successfully on boot.")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start AegisBackgroundService on boot", e)
            }
        }
    }
}

