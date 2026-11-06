package com.lahiru.travelbuddy.ui.navigation

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.ListenerRegistration
import com.lahiru.travelbuddy.R
import com.lahiru.travelbuddy.data.model.GroupMemberLocation
import com.lahiru.travelbuddy.data.model.GroupRoute
import com.lahiru.travelbuddy.data.model.RoutePoint
import com.lahiru.travelbuddy.databinding.ActivityGroupNavigationBinding
import com.lahiru.travelbuddy.firebase.GroupNavigationRepository
import com.lahiru.travelbuddy.firebase.RoomRepository
import com.lahiru.travelbuddy.service.NavigationForegroundService
import com.lahiru.travelbuddy.ui.room.RoomNavigation
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.max
import kotlin.math.roundToInt

class GroupNavigationActivity : ComponentActivity(), NavigationForegroundService.StateListener {

    private lateinit var binding: ActivityGroupNavigationBinding
    private val routeRepository = GroupNavigationRepository()
    private val roomRepository = RoomRepository()
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val handler = Handler(Looper.getMainLooper())
    private var map: MapLibreMap? = null
    private var mapStyle: Style? = null
    private var routeListener: ValueEventListener? = null
    private var memberLocationsListener: ValueEventListener? = null
    private var memberListener: ListenerRegistration? = null
    private var pendingRoute: GroupRoute? = null
    private var selectedDestination: RoutePoint? = null
    private var isOwner = false
    private var isBusy = false
    private var roomId = ""
    private var currentUid = ""
    private var lastRenderedRevision = Long.MIN_VALUE
    private var oneShotLocationListener: LocationListener? = null
    private var navigationService: NavigationForegroundService? = null
    private var isNavigationServiceBound = false
    private var latestLocation: Location? = null
    private var memberLocations: List<GroupMemberLocation> = emptyList()
    private var memberNames: Map<String, String> = emptyMap()
    private var hasCenteredOnUser = false
    private var isFollowingNavigation = false
    private var offRouteSampleCount = 0
    private var isOffRoute = false
    private var isSearchingDestination = false

    private val navigationServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? NavigationForegroundService.LocalBinder ?: return
            navigationService = localBinder.getService()
            isNavigationServiceBound = true
            navigationService?.setStateListener(this@GroupNavigationActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navigationService = null
            isNavigationServiceBound = false
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) {
            startNavigationService()
        } else {
            Toast.makeText(
                this,
                R.string.group_route_location_permission,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityGroupNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapView.onCreate(savedInstanceState)

        roomId = intent.getStringExtra(RoomNavigation.EXTRA_ROOM_ID).orEmpty()
        if (roomId.isBlank()) {
            Toast.makeText(this, R.string.room_details_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.backToCommsButton.setOnClickListener { finish() }
        binding.leaveNavigationButton.setOnClickListener {
            val activeService = navigationService
            activeService?.setStateListener(null)
            if (activeService != null) {
                activeService.stopNavigation()
            } else {
                startService(
                    Intent(this, NavigationForegroundService::class.java).apply {
                        action = NavigationForegroundService.ACTION_LEAVE_NAVIGATION
                    },
                )
            }
            if (isNavigationServiceBound) {
                unbindService(navigationServiceConnection)
                isNavigationServiceBound = false
            }
            navigationService = null
            finish()
        }
        binding.shareRouteButton.setOnClickListener { shareSelectedRoute() }
        binding.clearRouteButton.setOnClickListener { clearSharedRoute() }
        binding.destinationSearchButton.setOnClickListener { searchDestination() }
        binding.destinationSearchInput.setOnEditorActionListener { _, _, _ ->
            searchDestination()
            true
        }
        binding.followNavigationButton.setOnClickListener {
            isFollowingNavigation = !isFollowingNavigation
            updateFollowButton()
            latestLocation?.let(::updateNavigationProgress)
        }
        binding.recalculateRouteButton.setOnClickListener { recalculateSharedRoute() }

        binding.mapView.getMapAsync { readyMap ->
            map = readyMap
            val attributionBottomMargin = (210 * resources.displayMetrics.density).toInt()
            readyMap.uiSettings.setAttributionMargins(12, 12, 12, attributionBottomMargin)
            readyMap.uiSettings.setLogoMargins(12, 12, 12, attributionBottomMargin)
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(DEFAULT_CENTER)
                .zoom(DEFAULT_ZOOM)
                .build()
            readyMap.addOnCameraIdleListener { renderLocationSummary() }
            readyMap.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                mapStyle = style
                readyMap.addOnMapLongClickListener { point ->
                    if (!isOwner || isBusy) return@addOnMapLongClickListener false
                    selectDestination(RoutePoint(point.latitude, point.longitude))
                    true
                }
                pendingRoute?.let(::renderRoute)
                renderMemberLocations()
                latestLocation?.let(::updateNavigationProgress)
            }
        }

        loadOwnerState()
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        attachRouteListener()
        attachMemberLocationsListener()
        attachMemberListener()
        bindToNavigationService()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        routeListener?.let { routeRepository.removeRouteListener(roomId, it) }
        routeListener = null
        memberLocationsListener?.let {
            routeRepository.removeMemberLocationsListener(roomId, it)
        }
        memberLocationsListener = null
        memberListener?.remove()
        memberListener = null
        if (isNavigationServiceBound) {
            navigationService?.setStateListener(null)
            unbindService(navigationServiceConnection)
            isNavigationServiceBound = false
            navigationService = null
        }
        binding.mapView.onStop()
        super.onStop()
    }

    private fun attachMemberLocationsListener() {
        if (memberLocationsListener != null) return
        memberLocationsListener = routeRepository.listenToMemberLocations(
            roomId = roomId,
            onLocationsChanged = { locations ->
                memberLocations = locations
                renderMemberLocations()
            },
            onError = {
                Toast.makeText(this, R.string.group_location_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun attachMemberListener() {
        if (memberListener != null) return
        memberListener = roomRepository.listenToMembers(
            roomId = roomId,
            onMembersChanged = { members ->
                memberNames = members.associate { it.uid to it.displayName }
                renderLocationSummary()
            },
            onError = {},
        )
    }

    private fun attachRouteListener() {
        if (routeListener != null) return
        routeListener = routeRepository.listenToRoute(
            roomId = roomId,
            onRouteChanged = { route ->
                val isFirstSharedRoute = pendingRoute == null && route != null
                pendingRoute = route
                if (isFirstSharedRoute) isFollowingNavigation = true
                if (route == null) {
                    removeRenderedRoute()
                } else {
                    renderRoute(route)
                }
                renderStatus(route)
                latestLocation?.let(::updateNavigationProgress)
            },
            onError = { error ->
                Toast.makeText(
                    this,
                    error.localizedMessage ?: getString(R.string.group_route_load_failed),
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    override fun onDestroy() {
        oneShotLocationListener?.let { locationManager.removeUpdates(it) }
        oneShotLocationListener = null
        binding.mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onNavigationStateChanged(
        active: Boolean,
        location: Location?,
        route: GroupRoute?,
        progress: RouteProgress?,
    ) {
        if (location != null) {
            latestLocation = location
            updateNavigationProgress(location)
        }
        if (!active && !isFinishing) {
            binding.memberLocationStatusText.setText(R.string.navigation_service_start_failed)
        }
    }

    override fun onNavigationError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun bindToNavigationService() {
        if (isNavigationServiceBound) return
        isNavigationServiceBound = bindService(
            Intent(this, NavigationForegroundService::class.java),
            navigationServiceConnection,
            0,
        )
        if (!isNavigationServiceBound) {
            Toast.makeText(
                this,
                R.string.navigation_service_start_failed,
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    private fun startNavigationService() {
        val serviceIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = NavigationForegroundService.ACTION_JOIN_NAVIGATION
            putExtra(RoomNavigation.EXTRA_ROOM_ID, roomId)
            putExtra(RoomNavigation.EXTRA_ROOM_CODE, intent.getStringExtra(RoomNavigation.EXTRA_ROOM_CODE))
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        bindToNavigationService()
    }

    private fun loadOwnerState() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        currentUid = uid
        roomRepository.getRoom(roomId) { result ->
            result.onSuccess { room ->
                isOwner = uid.isNotBlank() && room.createdBy == uid
                binding.shareRouteButton.visibility = if (isOwner) View.VISIBLE else View.GONE
                binding.clearRouteButton.visibility = if (isOwner) View.VISIBLE else View.GONE
                binding.destinationSearchContainer.visibility =
                    if (isOwner) View.VISIBLE else View.GONE
                renderStatus(pendingRoute)
                latestLocation?.let(::updateNavigationProgress)
            }.onFailure { error ->
                Toast.makeText(this, error.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun selectDestination(destination: RoutePoint) {
        selectedDestination = destination
        binding.shareRouteButton.isEnabled = true
        binding.routeStatusText.setText(R.string.group_route_destination_selected)
        showDestinationSymbol(destination)
    }

    private fun searchDestination() {
        if (!isOwner || isSearchingDestination) return
        val query = binding.destinationSearchInput.text.toString().trim()
        if (query.length < 3) {
            binding.destinationSearchInput.error =
                getString(R.string.group_destination_search_short)
            return
        }
        hideKeyboard()
        setDestinationSearchBusy(true)
        routeRepository.searchDestinations(
            roomId = roomId,
            query = query,
            focus = latestLocation?.let { RoutePoint(it.latitude, it.longitude) },
        ) { result ->
            setDestinationSearchBusy(false)
            result.onSuccess { destinations ->
                if (destinations.isEmpty()) {
                    Toast.makeText(
                        this,
                        R.string.group_destination_search_empty,
                        Toast.LENGTH_LONG,
                    ).show()
                    return@onSuccess
                }
                val labels = destinations.map { it.label }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.group_destination_results_title)
                    .setItems(labels) { _, index ->
                        val destination = destinations[index]
                        binding.destinationSearchInput.setText(destination.label)
                        selectDestination(destination.point)
                        map?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(
                                    destination.point.latitude,
                                    destination.point.longitude,
                                ),
                                DESTINATION_ZOOM,
                            ),
                        )
                    }
                    .show()
            }.onFailure { error ->
                Toast.makeText(
                    this,
                    error.localizedMessage
                        ?: getString(R.string.group_destination_search_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun setDestinationSearchBusy(busy: Boolean) {
        isSearchingDestination = busy
        binding.destinationSearchInput.isEnabled = !busy
        binding.destinationSearchButton.isEnabled = !busy
        binding.destinationSearchButton.setText(
            if (busy) R.string.group_destination_searching
            else R.string.group_destination_search,
        )
    }

    private fun hideKeyboard() {
        binding.destinationSearchInput.clearFocus()
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(binding.destinationSearchInput.windowToken, 0)
    }

    private fun shareSelectedRoute() {
        val destination = selectedDestination ?: return
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }
        setBusy(true)
        findCurrentLocation { location ->
            if (location == null) {
                setBusy(false)
                Toast.makeText(
                    this,
                    R.string.group_route_location_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
                return@findCurrentLocation
            }
            routeRepository.setRoute(
                roomId = roomId,
                origin = RoutePoint(location.latitude, location.longitude),
                destination = destination,
            ) { result ->
                setBusy(false)
                result.onSuccess {
                    selectedDestination = null
                    binding.shareRouteButton.isEnabled = false
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.localizedMessage ?: getString(R.string.group_route_share_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun clearSharedRoute() {
        selectedDestination = null
        binding.shareRouteButton.isEnabled = false
        setBusy(true)
        routeRepository.clearRoute(roomId) { result ->
            setBusy(false)
            result.onFailure { error ->
                Toast.makeText(this, error.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun recalculateSharedRoute() {
        val route = pendingRoute ?: return
        val location = latestLocation ?: return
        if (!isOwner || isBusy) return
        setBusy(true)
        binding.routeStatusText.setText(R.string.group_route_recalculating)
        routeRepository.setRoute(
            roomId = roomId,
            origin = RoutePoint(location.latitude, location.longitude),
            destination = RoutePoint(
                route.destinationLatitude,
                route.destinationLongitude,
            ),
        ) { result ->
            setBusy(false)
            result.onFailure { error ->
                Toast.makeText(
                    this,
                    error.localizedMessage ?: getString(R.string.group_route_share_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        binding.routeProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.shareRouteButton.isEnabled = !busy && selectedDestination != null
        binding.clearRouteButton.isEnabled = !busy
        binding.recalculateRouteButton.isEnabled = !busy
        if (busy) {
            binding.routeStatusText.setText(R.string.group_route_sharing)
        } else {
            renderStatus(pendingRoute)
        }
    }

    private fun renderStatus(route: GroupRoute?) {
        if (isBusy) return
        binding.routeStatusText.text = when {
            route != null -> {
                val summary = getString(
                    R.string.group_route_summary,
                    route.distanceMeters / 1000.0,
                    (route.durationSeconds / 60.0).toInt().coerceAtLeast(1),
                )
                if (isOwner) summary else "$summary\n${getString(R.string.group_route_read_only)}"
            }
            isOwner -> getString(R.string.group_route_owner_help)
            else -> getString(R.string.group_route_waiting)
        }
    }

    private fun renderRoute(route: GroupRoute) {
        val readyMap = map ?: return
        val style = mapStyle ?: return
        if (route.geometry.size < 2) return
        val points = route.geometry.map { LatLng(it.latitude, it.longitude) }
        val line = LineString.fromLngLats(
            route.geometry.map { org.maplibre.geojson.Point.fromLngLat(it.longitude, it.latitude) },
        )
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        if (source == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, Feature.fromGeometry(line)))
            style.addLayer(
                LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    lineColor(ROUTE_COLOR),
                    lineWidth(6f),
                    lineOpacity(0.9f),
                ),
            )
        } else {
            source.setGeoJson(Feature.fromGeometry(line))
        }
        showDestinationSymbol(RoutePoint(route.destinationLatitude, route.destinationLongitude))
        if (lastRenderedRevision != route.revision) {
            lastRenderedRevision = route.revision
            offRouteSampleCount = 0
            isOffRoute = false
            if (!isFollowingNavigation) {
                val bounds = LatLngBounds.Builder().includes(points).build()
                readyMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING))
            }
        }
        updateFollowButton()
    }

    private fun renderMemberLocations() {
        val style = mapStyle ?: return
        val freshLocations = memberLocations.filter(::isFreshLocation)
        val selfLocation = freshLocations.firstOrNull { it.uid == currentUid }
        val otherLocations = freshLocations.filter { it.uid != currentUid }
        updateLocationLayer(
            style = style,
            sourceId = OTHER_LOCATIONS_SOURCE_ID,
            layerId = OTHER_LOCATIONS_LAYER_ID,
            locations = otherLocations,
            color = OTHER_MEMBER_COLOR,
            radius = 10f,
        )
        updateLocationLayer(
            style = style,
            sourceId = SELF_LOCATION_SOURCE_ID,
            layerId = SELF_LOCATION_LAYER_ID,
            locations = listOfNotNull(selfLocation),
            color = SELF_MEMBER_COLOR,
            radius = 12f,
        )
        if (!hasCenteredOnUser && lastRenderedRevision == Long.MIN_VALUE && selfLocation != null) {
            hasCenteredOnUser = true
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(selfLocation.latitude, selfLocation.longitude),
                    MEMBER_ZOOM,
                ),
            )
        }
        renderLocationSummary()
    }

    private fun updateLocationLayer(
        style: Style,
        sourceId: String,
        layerId: String,
        locations: List<GroupMemberLocation>,
        color: String,
        radius: Float,
    ) {
        val collection = FeatureCollection.fromFeatures(
            locations.map { location ->
                Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude))
            },
        )
        val source = style.getSourceAs<GeoJsonSource>(sourceId)
        if (source == null) {
            style.addSource(GeoJsonSource(sourceId, collection))
            style.addLayer(
                CircleLayer(layerId, sourceId).withProperties(
                    circleColor(color),
                    circleRadius(radius),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f),
                ),
            )
        } else {
            source.setGeoJson(collection)
        }
    }

    private fun renderLocationSummary() {
        val freshLocations = memberLocations.filter(::isFreshLocation)
        if (freshLocations.isEmpty()) {
            binding.memberLocationStatusText.setText(R.string.group_location_waiting)
            return
        }
        val visibleBounds = map?.projection?.visibleRegion?.latLngBounds
        val visibleLocations = freshLocations.filter { location ->
            visibleBounds?.contains(LatLng(location.latitude, location.longitude)) != false
        }
        val lines = visibleLocations.map { location ->
            val name = memberName(location.uid)
            if (location.hasSpeed) {
                getString(
                    R.string.group_location_speed,
                    name,
                    (location.speedMetersPerSecond * METERS_PER_SECOND_TO_KMH).roundToInt(),
                )
            } else {
                getString(R.string.group_location_speed_unavailable, name)
            }
        }.toMutableList()
        val self = freshLocations.firstOrNull { it.uid == currentUid }
        if (self != null) {
            freshLocations.asSequence()
                .filter { it.uid != currentUid }
                .map { it to distanceMeters(self, it) }
                .minByOrNull { it.second }
                ?.let { (nearest, distance) ->
                    lines.add(
                        0,
                        getString(
                            R.string.group_location_nearest,
                            memberName(nearest.uid),
                            formatDistance(distance),
                        ),
                    )
                }
        }
        if (lines.isEmpty()) lines += getString(R.string.group_location_sharing)
        binding.memberLocationStatusText.text = lines.joinToString("\n")
    }

    private fun memberName(uid: String): String = when {
        uid == currentUid -> getString(R.string.group_location_you)
        memberNames[uid].isNullOrBlank() -> uid.take(6)
        else -> memberNames.getValue(uid)
    }

    private fun isFreshLocation(location: GroupMemberLocation): Boolean =
        location.updatedAt > 0 &&
            System.currentTimeMillis() - location.updatedAt <= LOCATION_STALE_MILLIS

    private fun distanceMeters(
        first: GroupMemberLocation,
        second: GroupMemberLocation,
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            first.latitude,
            first.longitude,
            second.latitude,
            second.longitude,
            result,
        )
        return result[0]
    }

    private fun formatDistance(distanceMeters: Float): String =
        if (distanceMeters < 1000f) "${distanceMeters.roundToInt()} m"
        else String.format(java.util.Locale.getDefault(), "%.1f km", distanceMeters / 1000f)

    private fun removeRenderedRoute() {
        mapStyle?.removeLayer(ROUTE_LAYER_ID)
        mapStyle?.removeSource(ROUTE_SOURCE_ID)
        mapStyle?.removeLayer(DESTINATION_LAYER_ID)
        mapStyle?.removeSource(DESTINATION_SOURCE_ID)
        lastRenderedRevision = Long.MIN_VALUE
        isFollowingNavigation = false
        offRouteSampleCount = 0
        isOffRoute = false
        binding.navigationInstructionText.visibility = View.GONE
        binding.navigationProgressText.visibility = View.GONE
        binding.offRouteStatusText.visibility = View.GONE
        binding.followNavigationButton.visibility = View.GONE
        binding.recalculateRouteButton.visibility = View.GONE
    }

    private fun showDestinationSymbol(destination: RoutePoint) {
        val style = mapStyle ?: return
        val point = Feature.fromGeometry(
            org.maplibre.geojson.Point.fromLngLat(
                destination.longitude,
                destination.latitude,
            ),
        )
        val source = style.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE_ID)
        if (source == null) {
            style.addSource(GeoJsonSource(DESTINATION_SOURCE_ID, point))
            style.addLayer(
                CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID).withProperties(
                    circleColor("#D32F2F"),
                    circleRadius(9f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f),
                ),
            )
        } else {
            source.setGeoJson(point)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateNavigationProgress(location: Location) {
        val route = pendingRoute ?: run {
            binding.navigationInstructionText.visibility = View.GONE
            binding.navigationProgressText.visibility = View.GONE
            binding.offRouteStatusText.visibility = View.GONE
            binding.recalculateRouteButton.visibility = View.GONE
            return
        }
        val progress = RouteProgressCalculator.calculate(
            route = route,
            latitude = location.latitude,
            longitude = location.longitude,
        ) ?: return

        val offRouteThreshold = max(
            OFF_ROUTE_MIN_DISTANCE_METERS,
            location.accuracy.toDouble() * OFF_ROUTE_ACCURACY_MULTIPLIER,
        )
        if (progress.distanceFromRouteMeters > offRouteThreshold) {
            offRouteSampleCount++
        } else {
            offRouteSampleCount = 0
        }
        isOffRoute = offRouteSampleCount >= OFF_ROUTE_REQUIRED_SAMPLES

        binding.navigationInstructionText.visibility = View.VISIBLE
        binding.navigationProgressText.visibility = View.VISIBLE
        if (progress.arrived) {
            binding.navigationInstructionText.setText(R.string.group_navigation_arrived)
            binding.navigationProgressText.text = getString(
                R.string.group_navigation_progress,
                formatDistance(0f),
                0,
            )
        } else {
            val instruction = progress.instruction?.instruction
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.group_navigation_continue)
            binding.navigationInstructionText.text = getString(
                R.string.group_navigation_instruction,
                instruction,
                formatDistance(progress.distanceToInstructionEndMeters.toFloat()),
            )
            binding.navigationProgressText.text = getString(
                R.string.group_navigation_progress,
                formatDistance(progress.remainingDistanceMeters.toFloat()),
                (progress.remainingDurationSeconds / 60.0).roundToInt().coerceAtLeast(1),
            )
        }
        binding.offRouteStatusText.visibility = if (isOffRoute) View.VISIBLE else View.GONE
        if (isOffRoute) {
            binding.offRouteStatusText.setText(
                if (isOwner) R.string.group_route_off_route_owner
                else R.string.group_route_off_route,
            )
        }
        binding.recalculateRouteButton.visibility =
            if (isOwner && isOffRoute && !progress.arrived) View.VISIBLE else View.GONE
        updateFollowButton()
        if (isFollowingNavigation) followLocation(location, progress.routeBearingDegrees)
    }

    private fun updateFollowButton() {
        binding.followNavigationButton.visibility =
            if (pendingRoute == null) View.GONE else View.VISIBLE
        binding.followNavigationButton.setText(
            if (isFollowingNavigation) R.string.group_navigation_pause_follow
            else R.string.group_navigation_follow,
        )
    }

    private fun followLocation(location: Location, routeBearingDegrees: Double) {
        val currentMap = map ?: return
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val builder = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(NavigationCameraPolicy.zoomForSpeed(speed))
            .tilt(NAVIGATION_TILT)
            .bearing(routeBearingDegrees)
        currentMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(builder.build()),
            CAMERA_ANIMATION_MILLIS,
        )
    }

    private fun enabledLocationProviders(): List<String> = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).filter { provider ->
        (provider != LocationManager.GPS_PROVIDER || hasFineLocationPermission()) &&
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    @Suppress("MissingPermission")
    private fun findCurrentLocation(onResult: (Location?) -> Unit) {
        latestLocation?.takeIf { location ->
            System.currentTimeMillis() - location.time <= RECENT_LOCATION_MILLIS
        }?.let { location ->
            onResult(location)
            return
        }
        val providers = enabledLocationProviders()
        if (providers.isEmpty()) {
            onResult(null)
            return
        }

        val fallback = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        var delivered = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (delivered) return
                delivered = true
                handler.removeCallbacksAndMessages(LOCATION_CALLBACK_TOKEN)
                locationManager.removeUpdates(this)
                oneShotLocationListener = null
                onResult(location)
            }
        }
        oneShotLocationListener = listener
        providers.forEach { provider ->
            runCatching { locationManager.requestLocationUpdates(provider, 0L, 0f, listener) }
        }
        handler.postAtTime(
            {
                if (delivered) return@postAtTime
                delivered = true
                locationManager.removeUpdates(listener)
                oneShotLocationListener = null
                onResult(fallback)
            },
            LOCATION_CALLBACK_TOKEN,
            android.os.SystemClock.uptimeMillis() + LOCATION_TIMEOUT_MILLIS,
        )
    }

    companion object {
        private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val ROUTE_SOURCE_ID = "shared-route-source"
        private const val ROUTE_LAYER_ID = "shared-route-layer"
        private const val DESTINATION_SOURCE_ID = "route-destination-source"
        private const val DESTINATION_LAYER_ID = "route-destination-layer"
        private const val OTHER_LOCATIONS_SOURCE_ID = "group-member-locations-source"
        private const val OTHER_LOCATIONS_LAYER_ID = "group-member-locations-layer"
        private const val SELF_LOCATION_SOURCE_ID = "self-location-source"
        private const val SELF_LOCATION_LAYER_ID = "self-location-layer"
        private const val ROUTE_COLOR = "#00695C"
        private const val OTHER_MEMBER_COLOR = "#00A884"
        private const val SELF_MEMBER_COLOR = "#1976D2"
        private const val DEFAULT_ZOOM = 7.0
        private const val MEMBER_ZOOM = 15.0
        private const val DESTINATION_ZOOM = 15.0
        private const val NAVIGATION_TILT = 50.0
        private const val CAMERA_ANIMATION_MILLIS = 900
        private const val CAMERA_PADDING = 96
        private const val LOCATION_TIMEOUT_MILLIS = 10_000L
        private const val LOCATION_STALE_MILLIS = 120_000L
        private const val RECENT_LOCATION_MILLIS = 30_000L
        private const val METERS_PER_SECOND_TO_KMH = 3.6
        private const val OFF_ROUTE_MIN_DISTANCE_METERS = 60.0
        private const val OFF_ROUTE_ACCURACY_MULTIPLIER = 1.5
        private const val OFF_ROUTE_REQUIRED_SAMPLES = 2
        private val LOCATION_CALLBACK_TOKEN = Any()
        private val DEFAULT_CENTER = LatLng(7.8731, 80.7718)
    }
}
