package com.lahiru.travelbuddy.data.model

data class GroupMemberLocation(
    val uid: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val speedMetersPerSecond: Double = 0.0,
    val hasSpeed: Boolean = false,
    val bearingDegrees: Double = 0.0,
    val updatedAt: Long = 0,
)
