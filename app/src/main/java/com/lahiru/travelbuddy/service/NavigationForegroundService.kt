package com.lahiru.travelbuddy.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import com.lahiru.travelbuddy.R
import com.lahiru.travelbuddy.data.model.GroupRoute
import com.lahiru.travelbuddy.firebase.GroupNavigationRepository
import com.lahiru.travelbuddy.ui.comms.CommsRoomActivity
import com.lahiru.travelbuddy.ui.navigation.GroupNavigationActivity
import com.lahiru.travelbuddy.ui.navigation.RouteProgress
import com.lahiru.travelbuddy.ui.navigation.RouteProgressCalculator
import com.lahiru.travelbuddy.ui.room.RoomNavigation
import java.util.Locale
import kotlin.math.roundToInt

class NavigationForegroundService : Service() {

    interface StateListener {
        fun onNavigationStateChanged(
            active: Boolean,
            location: Location?,
            route: GroupRoute?,
            progress: RouteProgress?,
        )

        fun onNavigationError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): NavigationForegroundService = this@NavigationForegroundService
    }

    private val binder = LocalBinder()
    private val repository = GroupNavigationRepository()
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private var listener: StateListener? = null
    private var routeListener: ValueEventListener? = null
    private var locationListener: LocationListener? = null
    private var roomId = ""
    private var roomCode = ""
    private var uid = ""
    private var currentRoute: GroupRoute? = null
    private var latestLocation: Location? = null
    private var latestProgress: RouteProgress? = null
    private var isStarting = false
    private var isStopping = false

    val isNavigationActive: Boolean
        get() = uid.isNotBlank() && locationListener != null && !isStopping

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE_NAVIGATION) {
            stopNavigation()
            return START_NOT_STICKY
        }
        val preferences = getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
        roomId = intent?.getStringExtra(RoomNavigation.EXTRA_ROOM_ID).orEmpty().ifBlank {
            roomId.ifBlank { preferences.getString(PREF_ROOM_ID, "").orEmpty() }
        }
        roomCode = intent?.getStringExtra(RoomNavigation.EXTRA_ROOM_CODE).orEmpty().ifBlank {
            roomCode.ifBlank { preferences.getString(PREF_ROOM_CODE, "").orEmpty() }
        }
        if (roomId.isBlank() || !hasLocationPermission()) {
            notifyError(getString(R.string.navigation_service_start_failed))
            stopNavigation()
            return START_NOT_STICKY
        }
        promoteToForeground()
        startNavigation()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStateListener(newListener: StateListener?) {
        listener = newListener
        newListener?.onNavigationStateChanged(
            isNavigationActive,
            latestLocation,
            currentRoute,
            latestProgress,
        )
    }

    @Suppress("MissingPermission")
    private fun startNavigation() {
        if (isStarting || isNavigationActive) return
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (currentUid.isBlank()) {
            notifyError(getString(R.string.room_details_missing))
            stopNavigation()
            return
        }
        isStarting = true
        isStopping = false
        uid = currentUid
        repository.registerNavigationPresence(roomId, uid) { result ->
            result.onFailure { error ->
                isStarting = false
                notifyError(
                    error.localizedMessage ?: getString(R.string.navigation_service_start_failed),
                )
                stopNavigation()
                return@registerNavigationPresence
            }
            getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit()
                .putString(PREF_ROOM_ID, roomId)
                .putString(PREF_ROOM_CODE, roomCode)
                .apply()
            attachRouteListener()
            val providers = enabledLocationProviders()
            if (providers.isEmpty()) {
                isStarting = false
                notifyError(getString(R.string.group_route_location_unavailable))
                stopNavigation()
                return@registerNavigationPresence
            }
            val updates = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    publishLocation(location)
                }
            }
            locationListener = updates
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_UPDATE_INTERVAL_MILLIS,
                    0f,
                    updates,
                )
            }
            providers.mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }?.let(::publishLocation)
            isStarting = false
            notifyStateChanged()
        }
    }

    private fun attachRouteListener() {
        if (routeListener != null) return
        routeListener = repository.listenToRoute(
            roomId = roomId,
            onRouteChanged = { route ->
                currentRoute = route
                latestProgress = latestLocation?.let { calculateProgress(route, it) }
                updateNotification()
                notifyStateChanged()
            },
            onError = { error ->
                notifyError(
                    error.localizedMessage ?: getString(R.string.group_route_load_failed),
                )
                stopNavigation()
            },
        )
    }

    private fun publishLocation(location: Location) {
        if (isStopping || uid.isBlank()) return
        latestLocation = location
        latestProgress = calculateProgress(currentRoute, location)
        repository.refreshNavigationPresence(roomId, uid)
        repository.publishLocation(
            roomId = roomId,
            uid = uid,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toDouble(),
            speedMetersPerSecond = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            hasSpeed = location.hasSpeed(),
            bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
        )
        updateNotification()
        notifyStateChanged()
    }

    private fun calculateProgress(route: GroupRoute?, location: Location): RouteProgress? =
        route?.let {
            RouteProgressCalculator.calculate(it, location.latitude, location.longitude)
        }

    fun stopNavigation() {
        if (isStopping) return
        isStopping = true
        val stoppingRoomId = roomId
        val stoppingUid = uid
        locationListener?.let(locationManager::removeUpdates)
        locationListener = null
        routeListener?.let { repository.removeRouteListener(roomId, it) }
        routeListener = null
        if (stoppingRoomId.isNotBlank() && stoppingUid.isNotBlank()) {
            repository.stopNavigation(stoppingRoomId, stoppingUid)
        }
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit().clear().apply()
        uid = ""
        roomId = ""
        roomCode = ""
        currentRoute = null
        latestProgress = null
        isStarting = false
        listener?.onNavigationStateChanged(false, latestLocation, null, null)
        listener = null
        latestLocation = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!isStopping) stopNavigation()
        super.onDestroy()
    }

    private fun notifyStateChanged() {
        listener?.onNavigationStateChanged(
            isNavigationActive,
            latestLocation,
            currentRoute,
            latestProgress,
        )
    }

    private fun notifyError(message: String) {
        listener?.onNavigationError(message)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun enabledLocationProviders(): List<String> = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).filter { provider ->
        (provider != LocationManager.GPS_PROVIDER || hasFineLocationPermission()) &&
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    private fun promoteToForeground() {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            serviceType,
        )
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, GroupNavigationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(RoomNavigation.EXTRA_ROOM_ID, roomId)
            putExtra(RoomNavigation.EXTRA_ROOM_CODE, roomCode)
        }
        val leaveIntent = Intent(this, NavigationForegroundService::class.java).apply {
            action = ACTION_LEAVE_NAVIGATION
        }
        val text = notificationText()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.navigation_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    20,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                getString(R.string.leave_navigation),
                PendingIntent.getService(
                    this,
                    21,
                    leaveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notificationText(): String {
        val progress = latestProgress
            ?: return getString(R.string.navigation_notification_sharing, roomCode)
        if (progress.arrived) return getString(R.string.group_navigation_arrived)
        val instruction = progress.instruction?.instruction
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.group_navigation_continue)
        return getString(
            R.string.navigation_notification_progress,
            instruction,
            formatDistance(progress.remainingDistanceMeters),
            (progress.remainingDurationSeconds / 60.0).roundToInt().coerceAtLeast(1),
        )
    }

    private fun formatDistance(distanceMeters: Double): String =
        if (distanceMeters < 1000.0) "${distanceMeters.roundToInt()} m"
        else String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1000.0)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.navigation_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.navigation_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_JOIN_NAVIGATION =
            "com.lahiru.travelbuddy.action.JOIN_NAVIGATION"
        const val ACTION_LEAVE_NAVIGATION =
            "com.lahiru.travelbuddy.action.LEAVE_NAVIGATION"
        private const val NOTIFICATION_CHANNEL_ID = "travel_buddy_navigation_v1"
        private const val NOTIFICATION_ID = 1002
        private const val SERVICE_PREFERENCES = "navigation_service_preferences"
        private const val PREF_ROOM_ID = "active_room_id"
        private const val PREF_ROOM_CODE = "active_room_code"
        private const val LOCATION_UPDATE_INTERVAL_MILLIS = 3_000L
    }
}
