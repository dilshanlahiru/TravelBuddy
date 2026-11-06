package com.lahiru.travelbuddy.ui.navigation

import com.lahiru.travelbuddy.data.model.GroupRoute
import com.lahiru.travelbuddy.data.model.RouteInstruction
import com.lahiru.travelbuddy.data.model.RoutePoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteProgress(
    val distanceFromRouteMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val instruction: RouteInstruction?,
    val distanceToInstructionEndMeters: Double,
    val routeBearingDegrees: Double,
    val arrived: Boolean,
)

object RouteProgressCalculator {
    fun calculate(
        route: GroupRoute,
        latitude: Double,
        longitude: Double,
    ): RouteProgress? {
        if (route.geometry.size < 2) return null

        val cumulative = DoubleArray(route.geometry.size)
        for (index in 1 until route.geometry.size) {
            cumulative[index] = cumulative[index - 1] +
                distanceMeters(route.geometry[index - 1], route.geometry[index])
        }
        val geometryDistance = cumulative.last()
        if (geometryDistance <= 0.0) return null

        var nearestDistance = Double.MAX_VALUE
        var alongGeometry = 0.0
        var progressIndex = 0.0
        var routeBearing = 0.0
        for (index in 0 until route.geometry.lastIndex) {
            val start = route.geometry[index]
            val end = route.geometry[index + 1]
            val projection = project(latitude, longitude, start, end)
            if (projection.distanceMeters < nearestDistance) {
                nearestDistance = projection.distanceMeters
                val segmentDistance = cumulative[index + 1] - cumulative[index]
                alongGeometry = cumulative[index] + segmentDistance * projection.fraction
                progressIndex = index + projection.fraction
                routeBearing = bearingDegrees(start, end)
            }
        }

        val scale = if (route.distanceMeters > 0.0) {
            route.distanceMeters / geometryDistance
        } else {
            1.0
        }
        val remainingGeometry = (geometryDistance - alongGeometry).coerceAtLeast(0.0)
        val remainingDistance = remainingGeometry * scale
        val remainingDuration = if (route.distanceMeters > 0.0) {
            route.durationSeconds * (remainingDistance / route.distanceMeters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val instruction = route.instructions.firstOrNull {
            progressIndex <= it.endGeometryIndex + INDEX_TOLERANCE
        } ?: route.instructions.lastOrNull()
        val instructionEndDistance = instruction?.let {
            val endIndex = it.endGeometryIndex.coerceIn(0, cumulative.lastIndex)
            ((cumulative[endIndex] - alongGeometry).coerceAtLeast(0.0) * scale)
        } ?: remainingDistance

        return RouteProgress(
            distanceFromRouteMeters = nearestDistance,
            remainingDistanceMeters = remainingDistance,
            remainingDurationSeconds = remainingDuration,
            instruction = instruction,
            distanceToInstructionEndMeters = instructionEndDistance,
            routeBearingDegrees = routeBearing,
            arrived = remainingDistance <= ARRIVAL_DISTANCE_METERS &&
                nearestDistance <= ARRIVAL_ROUTE_TOLERANCE_METERS,
        )
    }

    private fun project(
        latitude: Double,
        longitude: Double,
        start: RoutePoint,
        end: RoutePoint,
    ): Projection {
        val referenceLatitude = Math.toRadians((start.latitude + end.latitude + latitude) / 3.0)
        val startX = longitudeMeters(start.longitude - longitude, referenceLatitude)
        val startY = latitudeMeters(start.latitude - latitude)
        val endX = longitudeMeters(end.longitude - longitude, referenceLatitude)
        val endY = latitudeMeters(end.latitude - latitude)
        val segmentX = endX - startX
        val segmentY = endY - startY
        val lengthSquared = segmentX * segmentX + segmentY * segmentY
        val fraction = if (lengthSquared == 0.0) {
            0.0
        } else {
            (-(startX * segmentX + startY * segmentY) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val projectedX = startX + segmentX * fraction
        val projectedY = startY + segmentY * fraction
        return Projection(sqrt(projectedX * projectedX + projectedY * projectedY), fraction)
    }

    private fun distanceMeters(first: RoutePoint, second: RoutePoint): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val a = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
    }

    private fun bearingDegrees(first: RoutePoint, second: RoutePoint): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val y = sin(longitudeDelta) * cos(secondLatitude)
        val x = cos(firstLatitude) * sin(secondLatitude) -
            sin(firstLatitude) * cos(secondLatitude) * cos(longitudeDelta)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun latitudeMeters(latitudeDelta: Double): Double =
        latitudeDelta * PI / 180.0 * EARTH_RADIUS_METERS

    private fun longitudeMeters(longitudeDelta: Double, latitudeRadians: Double): Double =
        longitudeDelta * PI / 180.0 * EARTH_RADIUS_METERS * cos(latitudeRadians)

    private data class Projection(val distanceMeters: Double, val fraction: Double)

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val ARRIVAL_DISTANCE_METERS = 35.0
    private const val ARRIVAL_ROUTE_TOLERANCE_METERS = 60.0
    private const val INDEX_TOLERANCE = 0.05
}

object NavigationCameraPolicy {
    fun zoomForSpeed(speedMetersPerSecond: Double): Double {
        val speedKilometersPerHour = speedMetersPerSecond.coerceAtLeast(0.0) * 3.6
        return (MAX_ZOOM - speedKilometersPerHour / ZOOM_SPEED_DIVISOR)
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private const val MIN_ZOOM = 14.5
    private const val MAX_ZOOM = 17.4
    private const val ZOOM_SPEED_DIVISOR = 40.0
}
