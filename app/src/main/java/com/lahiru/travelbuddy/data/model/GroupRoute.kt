package com.lahiru.travelbuddy.data.model

data class RoutePoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

data class RouteInstruction(
    val instruction: String = "",
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val type: Int = 0,
    val startGeometryIndex: Int = 0,
    val endGeometryIndex: Int = 0,
)

data class DestinationSearchResult(
    val label: String,
    val point: RoutePoint,
)

data class GroupRoute(
    val originLatitude: Double = 0.0,
    val originLongitude: Double = 0.0,
    val destinationLatitude: Double = 0.0,
    val destinationLongitude: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val geometry: List<RoutePoint> = emptyList(),
    val instructions: List<RouteInstruction> = emptyList(),
    val revision: Long = 0,
    val updatedBy: String = "",
)
