package com.example.data

data class GuardianContact(
    val priority: Int, // numeric ranking 1, 2, 3...
    val name: String,
    val phone: String,
    val email: String
)

data class SOSHistory(
    val timestamp: Long,
    val location: String, // e.g. "Lat: 37.7749, Lng: -122.4194"
    val senderInfo: String,
    val receiverInfo: String
)
