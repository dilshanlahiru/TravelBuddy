package com.lahiru.travelbuddy.firebase

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.database.ServerValue
import com.lahiru.travelbuddy.data.model.GroupMemberLocation
import com.lahiru.travelbuddy.data.model.GroupRoute
import com.lahiru.travelbuddy.data.model.NavigationLiveStatus
import com.lahiru.travelbuddy.data.model.DestinationSearchResult
import com.lahiru.travelbuddy.data.model.RouteInstruction
import com.lahiru.travelbuddy.data.model.RoutePoint

class GroupNavigationRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION),
) {
    fun listenToRoute(
        roomId: String,
        onRouteChanged: (GroupRoute?) -> Unit,
        onError: (Exception) -> Unit,
    ): ValueEventListener {
        val reference = database.reference.child(GROUP_TRIPS_PATH).child(roomId).child(ROUTE_PATH)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onRouteChanged(if (snapshot.exists()) snapshot.toGroupRoute() else null)
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        return listener
    }

    fun removeRouteListener(roomId: String, listener: ValueEventListener) {
        database.reference.child(GROUP_TRIPS_PATH).child(roomId).child(ROUTE_PATH)
            .removeEventListener(listener)
    }

    fun listenToMemberLocations(
        roomId: String,
        onLocationsChanged: (List<GroupMemberLocation>) -> Unit,
        onError: (Exception) -> Unit,
    ): ValueEventListener {
        val reference = database.reference.child(GROUP_TRIPS_PATH).child(roomId)
            .child(LOCATIONS_PATH)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onLocationsChanged(snapshot.children.mapNotNull { it.toMemberLocation() })
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        return listener
    }

    fun removeMemberLocationsListener(roomId: String, listener: ValueEventListener) {
        database.reference.child(GROUP_TRIPS_PATH).child(roomId).child(LOCATIONS_PATH)
            .removeEventListener(listener)
    }

    fun observeNavigationStatuses(
        roomId: String,
        onStatusesChanged: (Map<String, NavigationLiveStatus>) -> Unit,
        onError: (Exception) -> Unit,
    ): ValueEventListener {
        val reference = database.reference.child(NAVIGATION_STATUS_PATH)
            .child(roomId).child(MEMBERS_PATH)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onStatusesChanged(
                    snapshot.children.mapNotNull { child ->
                        val uid = child.key.orEmpty()
                        if (uid.isBlank()) return@mapNotNull null
                        val status = child.getValue(NavigationLiveStatus::class.java)
                            ?: NavigationLiveStatus()
                        uid to status.copy(uid = uid)
                    }.toMap(),
                )
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        return listener
    }

    fun removeNavigationStatusesListener(roomId: String, listener: ValueEventListener) {
        database.reference.child(NAVIGATION_STATUS_PATH).child(roomId).child(MEMBERS_PATH)
            .removeEventListener(listener)
    }

    fun registerNavigationPresence(
        roomId: String,
        uid: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val statusReference = navigationStatusReference(roomId, uid)
        val locationReference = locationReference(roomId, uid)
        statusReference.onDisconnect().removeValue { statusError, _ ->
            if (statusError != null) {
                onComplete(Result.failure(statusError.toException()))
                return@removeValue
            }
            locationReference.onDisconnect().removeValue { locationError, _ ->
                if (locationError != null) {
                    onComplete(Result.failure(locationError.toException()))
                    return@removeValue
                }
                statusReference.setValue(
                    mapOf(
                        "active" to true,
                        "updatedAt" to ServerValue.TIMESTAMP,
                    ),
                ).addOnSuccessListener { onComplete(Result.success(Unit)) }
                    .addOnFailureListener { onComplete(Result.failure(it)) }
            }
        }
    }

    fun refreshNavigationPresence(roomId: String, uid: String) {
        navigationStatusReference(roomId, uid).updateChildren(
            mapOf(
                "active" to true,
                "updatedAt" to ServerValue.TIMESTAMP,
            ),
        )
    }

    fun stopNavigation(roomId: String, uid: String, onComplete: () -> Unit = {}) {
        val statusReference = navigationStatusReference(roomId, uid)
        val locationReference = locationReference(roomId, uid)
        statusReference.onDisconnect().cancel()
        locationReference.onDisconnect().cancel()
        database.reference.updateChildren(
            mapOf(
                "$NAVIGATION_STATUS_PATH/$roomId/$MEMBERS_PATH/$uid" to null,
                "$GROUP_TRIPS_PATH/$roomId/$LOCATIONS_PATH/$uid" to null,
            ),
        ).addOnCompleteListener { onComplete() }
    }

    fun registerLocationDisconnect(
        roomId: String,
        uid: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        locationReference(roomId, uid).onDisconnect().removeValue { error, _ ->
            if (error == null) onComplete(Result.success(Unit))
            else onComplete(Result.failure(error.toException()))
        }
    }

    fun publishLocation(
        roomId: String,
        uid: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        speedMetersPerSecond: Double,
        hasSpeed: Boolean,
        bearingDegrees: Double,
    ) {
        locationReference(roomId, uid).setValue(
            mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "accuracyMeters" to accuracyMeters.coerceAtLeast(0.0),
                "speedMetersPerSecond" to speedMetersPerSecond.coerceAtLeast(0.0),
                "hasSpeed" to hasSpeed,
                "bearingDegrees" to bearingDegrees.mod(360.0),
                "updatedAt" to ServerValue.TIMESTAMP,
            ),
        )
    }

    fun stopSharingLocation(roomId: String, uid: String) {
        val reference = locationReference(roomId, uid)
        reference.onDisconnect().cancel()
        reference.removeValue()
    }

    fun setRoute(
        roomId: String,
        origin: RoutePoint,
        destination: RoutePoint,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        functions.getHttpsCallable(SET_ROUTE_FUNCTION)
            .call(
                mapOf(
                    "roomId" to roomId,
                    "origin" to origin.toValues(),
                    "destination" to destination.toValues(),
                ),
            )
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun clearRoute(roomId: String, onComplete: (Result<Unit>) -> Unit) {
        functions.getHttpsCallable(CLEAR_ROUTE_FUNCTION)
            .call(mapOf("roomId" to roomId))
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun searchDestinations(
        roomId: String,
        query: String,
        focus: RoutePoint?,
        onComplete: (Result<List<DestinationSearchResult>>) -> Unit,
    ) {
        val data = mutableMapOf<String, Any>(
            "roomId" to roomId,
            "query" to query,
        )
        focus?.let { data["focus"] = it.toValues() }
        functions.getHttpsCallable(SEARCH_DESTINATIONS_FUNCTION)
            .call(data)
            .addOnSuccessListener { result ->
                runCatching { result.data.toDestinationSearchResults() }
                    .fold(
                        onSuccess = { onComplete(Result.success(it)) },
                        onFailure = { onComplete(Result.failure(it)) },
                    )
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    private fun DataSnapshot.toGroupRoute(): GroupRoute = GroupRoute(
        originLatitude = child("originLatitude").getValue(Double::class.java) ?: 0.0,
        originLongitude = child("originLongitude").getValue(Double::class.java) ?: 0.0,
        destinationLatitude = child("destinationLatitude").getValue(Double::class.java) ?: 0.0,
        destinationLongitude = child("destinationLongitude").getValue(Double::class.java) ?: 0.0,
        distanceMeters = child("distanceMeters").getValue(Double::class.java) ?: 0.0,
        durationSeconds = child("durationSeconds").getValue(Double::class.java) ?: 0.0,
        geometry = child("geometry").children.map { point ->
            RoutePoint(
                latitude = point.child("latitude").getValue(Double::class.java) ?: 0.0,
                longitude = point.child("longitude").getValue(Double::class.java) ?: 0.0,
            )
        },
        instructions = child("instructions").children.mapNotNull { step ->
            val instruction = step.child("instruction").getValue(String::class.java)
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RouteInstruction(
                instruction = instruction,
                distanceMeters = step.child("distanceMeters").numberValue(),
                durationSeconds = step.child("durationSeconds").numberValue(),
                type = step.child("type").getValue(Long::class.java)?.toInt() ?: 0,
                startGeometryIndex = step.child("startGeometryIndex")
                    .getValue(Long::class.java)?.toInt() ?: 0,
                endGeometryIndex = step.child("endGeometryIndex")
                    .getValue(Long::class.java)?.toInt() ?: 0,
            )
        },
        revision = child("revision").getValue(Long::class.java) ?: 0,
        updatedBy = child("updatedBy").getValue(String::class.java).orEmpty(),
    )

    private fun DataSnapshot.numberValue(): Double =
        getValue(Double::class.java)
            ?: getValue(Long::class.java)?.toDouble()
            ?: 0.0

    private fun DataSnapshot.toMemberLocation(): GroupMemberLocation? {
        val uid = key ?: return null
        val latitude = child("latitude").getValue(Double::class.java) ?: return null
        val longitude = child("longitude").getValue(Double::class.java) ?: return null
        return GroupMemberLocation(
            uid = uid,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = child("accuracyMeters").getValue(Double::class.java) ?: 0.0,
            speedMetersPerSecond = child("speedMetersPerSecond")
                .getValue(Double::class.java) ?: 0.0,
            hasSpeed = child("hasSpeed").getValue(Boolean::class.java) ?: false,
            bearingDegrees = child("bearingDegrees").getValue(Double::class.java) ?: 0.0,
            updatedAt = child("updatedAt").getValue(Long::class.java) ?: 0L,
        )
    }

    private fun locationReference(roomId: String, uid: String) = database.reference
        .child(GROUP_TRIPS_PATH).child(roomId).child(LOCATIONS_PATH).child(uid)

    private fun navigationStatusReference(roomId: String, uid: String) = database.reference
        .child(NAVIGATION_STATUS_PATH).child(roomId).child(MEMBERS_PATH).child(uid)

    private fun RoutePoint.toValues(): Map<String, Double> = mapOf(
        "latitude" to latitude,
        "longitude" to longitude,
    )

    private fun Any?.toDestinationSearchResults(): List<DestinationSearchResult> {
        val payload = this as? Map<*, *>
            ?: throw IllegalStateException("Destination search returned invalid data.")
        val results = payload["results"] as? List<*> ?: return emptyList()
        return results.mapNotNull { value ->
            val item = value as? Map<*, *> ?: return@mapNotNull null
            val label = item["label"] as? String ?: return@mapNotNull null
            val latitude = (item["latitude"] as? Number)?.toDouble()
                ?: return@mapNotNull null
            val longitude = (item["longitude"] as? Number)?.toDouble()
                ?: return@mapNotNull null
            DestinationSearchResult(label, RoutePoint(latitude, longitude))
        }
    }

    companion object {
        private const val FUNCTIONS_REGION = "asia-southeast1"
        private const val GROUP_TRIPS_PATH = "group_trips"
        private const val NAVIGATION_STATUS_PATH = "navigation_status"
        private const val MEMBERS_PATH = "members"
        private const val ROUTE_PATH = "route"
        private const val LOCATIONS_PATH = "locations"
        private const val SET_ROUTE_FUNCTION = "setGroupRoute"
        private const val CLEAR_ROUTE_FUNCTION = "clearGroupRoute"
        private const val SEARCH_DESTINATIONS_FUNCTION = "searchDestinations"
    }
}
