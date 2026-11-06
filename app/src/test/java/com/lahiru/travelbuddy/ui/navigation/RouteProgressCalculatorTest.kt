package com.lahiru.travelbuddy.ui.navigation

import com.lahiru.travelbuddy.data.model.GroupRoute
import com.lahiru.travelbuddy.data.model.RouteInstruction
import com.lahiru.travelbuddy.data.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressCalculatorTest {
    private val route = GroupRoute(
        destinationLatitude = 0.0,
        destinationLongitude = 0.002,
        distanceMeters = 222.0,
        durationSeconds = 120.0,
        geometry = listOf(
            RoutePoint(0.0, 0.0),
            RoutePoint(0.0, 0.001),
            RoutePoint(0.0, 0.002),
        ),
        instructions = listOf(
            RouteInstruction("Head east", 111.0, 60.0, 11, 0, 1),
            RouteInstruction("Arrive", 111.0, 60.0, 10, 1, 2),
        ),
    )

    @Test
    fun calculatesRemainingDistanceAndCurrentInstruction() {
        val progress = requireNotNull(RouteProgressCalculator.calculate(route, 0.0, 0.0005))

        assertEquals("Head east", progress.instruction?.instruction)
        assertEquals(166.5, progress.remainingDistanceMeters, 2.0)
        assertEquals(55.5, progress.distanceToInstructionEndMeters, 2.0)
        assertEquals(90.0, progress.routeBearingDegrees, 0.5)
        assertFalse(progress.arrived)
    }

    @Test
    fun detectsOffRouteDistanceAndArrival() {
        val offRoute = requireNotNull(RouteProgressCalculator.calculate(route, 0.001, 0.001))
        val arrival = requireNotNull(RouteProgressCalculator.calculate(route, 0.0, 0.00195))

        assertTrue(offRoute.distanceFromRouteMeters > 100.0)
        assertTrue(arrival.arrived)
        assertEquals("Arrive", arrival.instruction?.instruction)
    }

    @Test
    fun cameraZoomsOutAsSpeedIncreases() {
        assertEquals(17.4, NavigationCameraPolicy.zoomForSpeed(0.0), 0.01)
        assertEquals(16.5, NavigationCameraPolicy.zoomForSpeed(10.0), 0.01)
        assertEquals(14.5, NavigationCameraPolicy.zoomForSpeed(40.0), 0.01)
    }
}
