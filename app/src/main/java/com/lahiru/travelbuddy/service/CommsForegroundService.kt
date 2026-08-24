package com.lahiru.travelbuddy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.Person
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lahiru.travelbuddy.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.database.ValueEventListener
import com.lahiru.travelbuddy.data.model.RoomMember
import com.lahiru.travelbuddy.data.model.RoomLiveStatus
import com.lahiru.travelbuddy.audio.AudioRoute
import com.lahiru.travelbuddy.audio.AudioRouteManager
import com.lahiru.travelbuddy.overlay.FloatingPttOverlayController
import com.lahiru.travelbuddy.firebase.RoomRepository
import com.lahiru.travelbuddy.firebase.SignalingRepository
import com.lahiru.travelbuddy.firebase.TurnCredentialsRepository
import com.lahiru.travelbuddy.ui.comms.CommsRoomActivity
import com.lahiru.travelbuddy.ui.room.RoomNavigation
import com.lahiru.travelbuddy.webrtc.WebRtcManager
import com.lahiru.travelbuddy.telecom.TravelBuddyCallController

class CommsForegroundService : Service(), WebRtcManager.Listener, AudioRouteManager.Listener,
    FloatingPttOverlayController.Listener, TravelBuddyCallController.Listener {

    interface StateListener {
        fun onAudioReady(micEnabled: Boolean)
        fun onMicStateChanged(enabled: Boolean)
        fun onPeerStatusesChanged(statuses: Map<String, WebRtcManager.PeerStatus>)
        fun onRoomStatusesChanged(statuses: Map<String, RoomLiveStatus>)
        fun onAudioRoutesChanged(available: Set<AudioRoute>, selected: AudioRoute)
        fun onCommsError(message: String, fatal: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): CommsForegroundService = this@CommsForegroundService
    }

    private val binder = LocalBinder()
    private lateinit var webRtcManager: WebRtcManager
    private lateinit var audioRouteManager: AudioRouteManager
    private lateinit var floatingPttOverlay: FloatingPttOverlayController
    private lateinit var commsPowerManager: CommsPowerManager
    private var telecomCallController: TravelBuddyCallController? = null
    private var listener: StateListener? = null
    private var roomId: String = ""
    private var roomCode: String = ""
    private var localUid: String = ""
    private var micMode: String = RoomMember.MIC_MODE_PTT
    private var peerStatuses: Map<String, WebRtcManager.PeerStatus> = emptyMap()
    private var roomStatuses: Map<String, RoomLiveStatus> = emptyMap()
    private var memberListener: ListenerRegistration? = null
    private var roomStatusListener: ValueEventListener? = null
    private var isStopping = false
    private var isSessionStarting = false
    private var presenceRegistered = false
    private var availableAudioRoutes: Set<AudioRoute> = emptySet()
    private var selectedAudioRoute: AudioRoute = AudioRoute.EARPIECE
    private var isRoomUiVisible = false
    private var floatingPttEnabled = false
    private var desiredToggleMicEnabled = false
    private val roomRepository = RoomRepository()
    private val signalingRepository = SignalingRepository()
    private val turnCredentialsRepository = TurnCredentialsRepository()
    private val mainHandler = Handler(Looper.getMainLooper())

    val isAudioReady: Boolean
        get() = webRtcManager.isStarted

    val isMicEnabled: Boolean
        get() = webRtcManager.isMicEnabled

    val isFloatingPttEnabled: Boolean
        get() = floatingPttEnabled

    val currentMicMode: String
        get() = micMode

    override fun onCreate() {
        super.onCreate()
        webRtcManager = WebRtcManager(applicationContext)
        audioRouteManager = AudioRouteManager(applicationContext, this)
        floatingPttOverlay = FloatingPttOverlayController(applicationContext, this)
        commsPowerManager = CommsPowerManager(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            telecomCallController = runCatching {
                TravelBuddyCallController(applicationContext, this)
            }.getOrElse { error ->
                notifyError(
                    error.localizedMessage ?: getString(R.string.telecom_registration_failed),
                    fatal = false,
                )
                null
            }
        }
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).let { preferences ->
            floatingPttEnabled = preferences.getBoolean(PREF_FLOATING_PTT, false)
            micMode = preferences.getString(PREF_MIC_MODE, RoomMember.MIC_MODE_PTT)
                ?: RoomMember.MIC_MODE_PTT
            desiredToggleMicEnabled = preferences.getBoolean(PREF_TOGGLE_MIC_ENABLED, false)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            stopComms()
            return START_NOT_STICKY
        }
        val preferences = getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
        roomId = intent?.getStringExtra(RoomNavigation.EXTRA_ROOM_ID).orEmpty().ifBlank {
            roomId.ifBlank { preferences.getString(PREF_ROOM_ID, "").orEmpty() }
        }
        roomCode = intent?.getStringExtra(RoomNavigation.EXTRA_ROOM_CODE).orEmpty().ifBlank {
            roomCode.ifBlank { preferences.getString(PREF_ROOM_CODE, "").orEmpty() }
        }
        preferences.edit()
            .putString(PREF_ROOM_ID, roomId)
            .putString(PREF_ROOM_CODE, roomCode)
            .apply()
        promoteToForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            telecomCallController?.start(roomCode)
        }
        webRtcManager.startLocalAudio()
            .onSuccess {
                restoreToggleMicState()
                commsPowerManager.start().onFailure { error ->
                    notifyError(
                        error.localizedMessage ?: getString(R.string.background_comms_failed),
                        fatal = false,
                    )
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    telecomCallController == null
                ) {
                    audioRouteManager.start()
                }
                updateFloatingPttOverlay()
                if (localUid.isBlank()) startRoomSession()
                publishStatus()
                mainHandler.post {
                    listener?.onAudioReady(webRtcManager.isMicEnabled)
                    listener?.onPeerStatusesChanged(peerStatuses)
                    listener?.onRoomStatusesChanged(roomStatuses)
                    listener?.onAudioRoutesChanged(availableAudioRoutes, selectedAudioRoute)
                }
            }
            .onFailure { error ->
                notifyError(error.localizedMessage ?: getString(R.string.audio_start_failed))
                stopComms()
            }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStateListener(newListener: StateListener?) {
        listener = newListener
        if (newListener != null && webRtcManager.isStarted) {
            newListener.onAudioReady(webRtcManager.isMicEnabled)
            newListener.onPeerStatusesChanged(peerStatuses)
            newListener.onRoomStatusesChanged(roomStatuses)
            newListener.onAudioRoutesChanged(availableAudioRoutes, selectedAudioRoute)
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        Log.i(
            TAG,
            "User mic request enabled=$enabled mode=$micMode uiVisible=$isRoomUiVisible",
        )
        desiredToggleMicEnabled = enabled && micMode == RoomMember.MIC_MODE_TOGGLE
        persistDesiredMicState()
        applyMicEnabled(enabled)
    }

    private fun applyMicEnabled(enabled: Boolean) {
        webRtcManager.setMicEnabled(enabled)
            .onSuccess {
                publishStatus()
                floatingPttOverlay.setMicActive(enabled)
                mainHandler.post { listener?.onMicStateChanged(enabled) }
            }
            .onFailure { error ->
                floatingPttOverlay.setMicActive(false)
                notifyError(error.localizedMessage ?: getString(R.string.mic_state_failed))
            }
    }

    private fun persistDesiredMicState() {
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit().putBoolean(PREF_TOGGLE_MIC_ENABLED, desiredToggleMicEnabled).apply()
    }

    fun setMicMode(mode: String) {
        micMode = mode
        if (mode != RoomMember.MIC_MODE_TOGGLE) desiredToggleMicEnabled = false
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit()
            .putString(PREF_MIC_MODE, mode)
            .putBoolean(PREF_TOGGLE_MIC_ENABLED, desiredToggleMicEnabled)
            .apply()
        publishStatus()
        updateFloatingPttOverlay()
    }

    fun setRoomUiVisible(visible: Boolean) {
        isRoomUiVisible = visible
        updateFloatingPttOverlay()
    }

    fun setFloatingPttEnabled(enabled: Boolean) {
        floatingPttEnabled = enabled
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit().putBoolean(PREF_FLOATING_PTT, enabled).apply()
        updateFloatingPttOverlay()
    }

    fun refreshAudioRoutes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && telecomCallController != null) {
            telecomCallController?.refreshAudioRoutes()
        } else {
            audioRouteManager.refresh()
        }
    }

    fun selectAudioRoute(route: AudioRoute) {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            telecomCallController != null
        ) {
            telecomCallController?.selectAudioRoute(route) ?: Result.failure(
                IllegalStateException("The Android call is not ready."),
            )
        } else {
            audioRouteManager.select(route)
        }
        result.onFailure { error ->
            notifyError(
                error.localizedMessage ?: getString(R.string.audio_route_failed),
                fatal = false,
            )
        }
    }

    override fun onAudioRoutesChanged(available: Set<AudioRoute>, selected: AudioRoute) {
        availableAudioRoutes = available
        selectedAudioRoute = selected
        mainHandler.post { listener?.onAudioRoutesChanged(available, selected) }
    }

    override fun onPeerStatusesChanged(statuses: Map<String, WebRtcManager.PeerStatus>) {
        peerStatuses = statuses
        publishStatus()
        updateFloatingPttOverlay()
        mainHandler.post { listener?.onPeerStatusesChanged(statuses) }
    }

    override fun onWebRtcError(remoteUid: String?, message: String) {
        val peerLabel = remoteUid?.take(6)?.let { "Peer $it: " }.orEmpty()
        notifyError(peerLabel + message, fatal = false)
    }

    override fun onOverlayMicPressed(enabled: Boolean) {
        val canTalk = peerStatuses.values.any { it == WebRtcManager.PeerStatus.CONNECTED }
        if (enabled && (!canTalk || micMode != RoomMember.MIC_MODE_PTT)) {
            floatingPttOverlay.setMicActive(false)
            return
        }
        setMicEnabled(enabled)
    }

    override fun onOverlayError(message: String) {
        notifyError(message, fatal = false)
    }

    override fun onTelecomCallActive() {
        if (micMode == RoomMember.MIC_MODE_TOGGLE && desiredToggleMicEnabled) {
            applyMicEnabled(true)
        }
    }

    override fun onTelecomCallInactive() {
        // Telecom already owns audio focus while a call is temporarily held. Keep the WebRTC
        // track and the user's Toggle-mode intent enabled so capture can resume without the UI
        // turning the microphone off when Android activates the call again.
    }

    override fun onTelecomCallDisconnected() {
        mainHandler.post { stopComms() }
    }

    override fun onTelecomAudioRoutesChanged(
        available: Set<AudioRoute>,
        selected: AudioRoute,
    ) {
        onAudioRoutesChanged(available, selected)
    }

    override fun onTelecomError(message: String) {
        notifyError(message, fatal = false)
    }

    fun stopComms() {
        if (isStopping) return
        isStopping = true
        memberListener?.remove()
        memberListener = null
        removeRoomStatusListener()
        presenceRegistered = false
        if (localUid.isNotBlank() && roomId.isNotBlank()) {
            signalingRepository.removeRoomStatus(roomId, localUid)
            roomRepository.leaveRoom(roomId, localUid) { result ->
                result.exceptionOrNull()?.let { error ->
                    notifyError(
                        error.localizedMessage ?: getString(R.string.leave_room_failed),
                        fatal = false,
                    )
                }
            }
        }
        floatingPttOverlay.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            telecomCallController?.stop()
        }
        clearPersistedSession()
        commsPowerManager.stop()
        webRtcManager.leaveRoom()
        audioRouteManager.stop()
        listener?.onMicStateChanged(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        memberListener?.remove()
        memberListener = null
        removeRoomStatusListener()
        presenceRegistered = false
        if (localUid.isNotBlank() && roomId.isNotBlank()) {
            signalingRepository.removeRoomStatus(roomId, localUid)
        }
        listener = null
        floatingPttOverlay.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            telecomCallController?.release()
        }
        telecomCallController = null
        commsPowerManager.stop()
        webRtcManager.leaveRoom()
        audioRouteManager.stop()
        super.onDestroy()
    }

    private fun listenToRoomMembers() {
        if (memberListener != null) return
        memberListener = roomRepository.listenToMembers(
            roomId = roomId,
            onMembersChanged = { members ->
                webRtcManager.updateRoomMembers(members.map { it.uid })
            },
            onError = { error ->
                notifyError(error.localizedMessage ?: getString(R.string.member_list_failed))
            },
        )
    }

    private fun restoreToggleMicState() {
        if (micMode != RoomMember.MIC_MODE_TOGGLE || !desiredToggleMicEnabled) return
        webRtcManager.setMicEnabled(true).onFailure {
            desiredToggleMicEnabled = false
            getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
                .edit().putBoolean(PREF_TOGGLE_MIC_ENABLED, false).apply()
        }
    }

    private fun clearPersistedSession() {
        desiredToggleMicEnabled = false
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit()
            .remove(PREF_ROOM_ID)
            .remove(PREF_ROOM_CODE)
            .putString(PREF_MIC_MODE, RoomMember.MIC_MODE_PTT)
            .putBoolean(PREF_TOGGLE_MIC_ENABLED, false)
            .apply()
    }

    private fun startRoomSession() {
        if (isSessionStarting || isStopping || localUid.isNotBlank()) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isBlank() || roomId.isBlank()) {
            notifyError(getString(R.string.room_details_missing))
            stopComms()
            return
        }
        isSessionStarting = true
        turnCredentialsRepository.fetch { credentialsResult ->
            mainHandler.post {
                if (isStopping) return@post
                runCatching {
                    val credentials = credentialsResult.getOrThrow()
                    webRtcManager.configureIceServers(credentials).getOrThrow()
                }.onFailure {
                    notifyError(getString(R.string.turn_service_unavailable), fatal = false)
                }

                webRtcManager.joinRoom(roomId, uid, this)
                    .onFailure { error ->
                        isSessionStarting = false
                        notifyError(
                            error.localizedMessage ?: getString(R.string.audio_start_failed),
                        )
                        stopComms()
                        return@post
                    }
                localUid = uid
                signalingRepository.registerRoomPresence(roomId, uid) { result ->
                    result.onSuccess {
                        presenceRegistered = true
                        publishStatus()
                    }.onFailure { error ->
                        notifyError(
                            error.localizedMessage ?: getString(R.string.room_status_failed),
                            fatal = false,
                        )
                    }
                }
                listenToRoomMembers()
                listenToRoomStatuses()
                isSessionStarting = false
            }
        }
    }

    private fun listenToRoomStatuses() {
        if (roomStatusListener != null) return
        roomStatusListener = signalingRepository.observeRoomStatuses(
            roomId = roomId,
            onStatusesChanged = { statuses ->
                roomStatuses = statuses
                mainHandler.post { listener?.onRoomStatusesChanged(statuses) }
            },
            onError = { error ->
                notifyError(
                    error.localizedMessage ?: getString(R.string.room_status_failed),
                    fatal = false,
                )
            },
        )
    }

    private fun removeRoomStatusListener() {
        roomStatusListener?.let { signalingRepository.removeRoomStatusesListener(roomId, it) }
        roomStatusListener = null
        roomStatuses = emptyMap()
    }

    private fun publishStatus() {
        if (isStopping || !presenceRegistered || roomId.isBlank() || localUid.isBlank()) return
        signalingRepository.updateRoomStatus(
            roomId = roomId,
            uid = localUid,
            isConnected = peerStatuses.values.any { it == WebRtcManager.PeerStatus.CONNECTED },
            isSpeaking = webRtcManager.isMicEnabled,
            micMode = micMode,
        )
    }

    private fun updateFloatingPttOverlay() {
        val shouldShow = floatingPttEnabled &&
            !isRoomUiVisible &&
            !isStopping &&
            micMode == RoomMember.MIC_MODE_PTT &&
            webRtcManager.isStarted &&
            peerStatuses.values.any { it == WebRtcManager.PeerStatus.CONNECTED } &&
            Settings.canDrawOverlays(this)
        if (shouldShow) floatingPttOverlay.show() else floatingPttOverlay.hide()
    }

    private fun notifyError(message: String, fatal: Boolean = true) {
        mainHandler.post { listener?.onCommsError(message, fatal) }
    }

    private fun promoteToForeground() {
        val serviceType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            serviceType,
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, CommsRoomActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(RoomNavigation.EXTRA_ROOM_ID, roomId)
            putExtra(RoomNavigation.EXTRA_ROOM_CODE, roomCode)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val leavePendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CommsForegroundService::class.java).apply { action = ACTION_LEAVE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val roomPerson = Person.Builder()
            .setName(getString(R.string.comms_notification_room, roomCode))
            .setImportant(true)
            .build()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.comms_notification_title))
            .setContentText(getString(R.string.comms_notification_text))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(roomPerson, leavePendingIntent))
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.comms_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.comms_notification_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.lahiru.travelbuddy.action.START_COMMS"
        const val ACTION_LEAVE = "com.lahiru.travelbuddy.action.LEAVE_COMMS"
        private const val NOTIFICATION_CHANNEL_ID = "travel_buddy_calls_v2"
        private const val NOTIFICATION_ID = 1001
        private const val SERVICE_PREFERENCES = "comms_service_preferences"
        private const val PREF_FLOATING_PTT = "floating_ptt_enabled"
        private const val PREF_ROOM_ID = "active_room_id"
        private const val PREF_ROOM_CODE = "active_room_code"
        private const val PREF_MIC_MODE = "mic_mode"
        private const val PREF_TOGGLE_MIC_ENABLED = "toggle_mic_enabled"
        private const val TAG = "TravelBuddyComms"
    }
}
