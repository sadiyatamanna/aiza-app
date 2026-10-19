package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "sos_logs")
data class SosLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sosId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val locationStatus: String = "ACTIVE_GPS", // "ACTIVE_GPS", "LAST_KNOWN", "UNAVAILABLE"
    val status: String = "Sent", // "Sent", "Received", "Resolved", "Pending", "Pending_Sync"
    val triggerSource: String = "Manual", // "Manual", "Shake", "Voice", "Bluetooth"
    val encryptedPayload: String = "",
    val eventType: String = "SENT", // "SENT" or "RECEIVED"
    val contacts: String = "", // for SENT, e.g. "Mom, Dad"
    val senderInfo: String = "", // for RECEIVED, e.g. "Friend (+919876543210)"
    val receiverInfo: String = "", // receiver info compatible with SOSHistory
    val senderLatitude: Double = 0.0,
    val senderLongitude: Double = 0.0,
    val voiceClipPath: String = "",
    val voiceClipUrl: String = ""
)
