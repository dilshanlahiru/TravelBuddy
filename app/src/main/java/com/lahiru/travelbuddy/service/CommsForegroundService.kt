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
import com.lahiru.travelbuddy.ui.comms.CommsRoomActivity
import com.lahiru.travelbuddy.ui.room.RoomNavigation
import com.lahiru.travelbuddy.webrtc.WebRtcManager

class CommsForegroundService : Service(), WebRtcManager.Listener, AudioRouteManager.Listener,
    FloatingPttOverlayController.Listener {

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
    private var presenceRegistered = false
    private var availableAudioRoutes: Set<AudioRoute> = emptySet()
    private var selectedAudioRoute: AudioRoute = AudioRoute.EARPIECE
    private var isRoomUiVisible = false
    private var floatingPttEnabled = false
    private val roomRepository = RoomRepository()
    private val signalingRepository = SignalingRepository()
    private val mainHandler = Handler(Looper.getMainLooper())

    val isAudioReady: Boolean
        get() = webRtcManager.isStarted

    val isMicEnabled: Boolean
        get() = webRtcManager.isMicEnabled

    val isFloatingPttEnabled: Boolean
        get() = floatingPttEnabled

    override fun onCreate() {
        super.onCreate()
        webRtcManager = WebRtcManager(applicationContext)
        audioRouteManager = AudioRouteManager(applicationContext, this)
        floatingPttOverlay = FloatingPttOverlayController(applicationContext, this)
        floatingPttEnabled = getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .getBoolean(PREF_FLOATING_PTT, false)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            stopComms()
            return START_NOT_STICKY
        }
        roomId = intent?.getStringExtra(RoomNavigation.EXTRA_ROOM_ID).orEmpty().ifBlank { roomId }
        roomCode = intent?.getStringExtra(RoomNavigation.EXTRA_ROOM_CODE).orEmpty().ifBlank { roomCode }
        promoteToForeground()
        webRtcManager.startLocalAudio()
            .onSuccess {
                audioRouteManager.start()
                updateFloatingPttOverlay()
                if (localUid.isBlank()) {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    if (uid.isBlank() || roomId.isBlank()) {
                        notifyError(getString(R.string.room_details_missing))
                        stopComms()
                        return@onSuccess
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
                    webRtcManager.joinRoom(roomId, uid, this)
                        .onFailure { error ->
                            notifyError(
                                error.localizedMessage ?: getString(R.string.audio_start_failed),
                            )
                            stopComms()
                            return@onSuccess
                        }
                    listenToRoomMembers()
                    listenToRoomStatuses()
                }
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
        return START_NOT_STICKY
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

    fun setMicMode(mode: String) {
        micMode = mode
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
        audioRouteManager.refresh()
    }

    fun selectAudioRoute(route: AudioRoute) {
        audioRouteManager.select(route).onFailure { error ->
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
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.comms_notification_title))
            .setContentText(getString(R.string.comms_notification_text))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.comms_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.comms_notification_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.lahiru.travelbuddy.action.START_COMMS"
        const val ACTION_LEAVE = "com.lahiru.travelbuddy.action.LEAVE_COMMS"
        private const val NOTIFICATION_CHANNEL_ID = "travel_buddy_comms"
        private const val NOTIFICATION_ID = 1001
        private const val SERVICE_PREFERENCES = "comms_service_preferences"
        private const val PREF_FLOATING_PTT = "floating_ptt_enabled"
    }
}
