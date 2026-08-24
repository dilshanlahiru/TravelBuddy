package com.lahiru.travelbuddy.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lahiru.travelbuddy.data.model.IceServerConfig
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcManager(context: Context) {
    interface Listener {
        fun onPeerStatusesChanged(statuses: Map<String, PeerStatus>)
        fun onWebRtcError(remoteUid: String?, message: String)
    }

    enum class PeerStatus {
        WAITING, SIGNALING, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED,
    }

    private data class PeerSession(
        var peerConnection: PeerConnectionClient? = null,
        var signaling: SignalingClient? = null,
        var status: PeerStatus = PeerStatus.SIGNALING,
        var reconnectAttempts: Int = 0,
        var reconnectRunnable: Runnable? = null,
    )

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val peers = mutableMapOf<String, PeerSession>()
    private val desiredRemoteUids = mutableSetOf<String>()
    private var audioDeviceModule: AudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var iceServers: List<PeerConnection.IceServer> = defaultIceServers()
    private var roomId = ""
    private var localUid = ""
    private var listener: Listener? = null

    val isStarted: Boolean
        @Synchronized get() = localAudioTrack != null

    val isMicEnabled: Boolean
        @Synchronized get() = localAudioTrack?.enabled() == true

    @Synchronized
    fun startLocalAudio(): Result<Unit> = runCatching {
        if (localAudioTrack != null) return@runCatching
        initializeWebRtcOnce(applicationContext)
        audioDeviceModule = JavaAudioDeviceModule.builder(applicationContext)
            .createAudioDeviceModule()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(checkNotNull(audioDeviceModule))
            .createPeerConnectionFactory()
        localAudioSource = checkNotNull(peerConnectionFactory).createAudioSource(MediaConstraints())
        localAudioTrack = checkNotNull(peerConnectionFactory).createAudioTrack(
            LOCAL_AUDIO_TRACK_ID,
            checkNotNull(localAudioSource),
        )
        check(checkNotNull(localAudioTrack).setEnabled(false)) {
            "Unable to mute the local audio track."
        }
    }.onFailure { disposeResources() }

    @Synchronized
    fun setMicEnabled(enabled: Boolean): Result<Unit> = runCatching {
        val track = checkNotNull(localAudioTrack) { "Local audio is not initialized." }
        if (track.enabled() == enabled) return@runCatching
        check(track.setEnabled(enabled)) { "Unable to change microphone state." }
        check(track.enabled() == enabled) { "Microphone state did not change." }
    }

    @Synchronized
    fun configureIceServers(configs: List<IceServerConfig>): Result<Unit> = runCatching {
        check(peers.isEmpty()) { "ICE servers cannot be changed while peers are connected." }
        val configured = configs.flatMap { config ->
            config.urls.map { url ->
                PeerConnection.IceServer.builder(url)
                    .setUsername(config.username)
                    .setPassword(config.credential)
                    .createIceServer()
            }
        }
        check(configured.isNotEmpty()) { "No ICE servers were provided." }
        iceServers = configured
    }

    fun joinRoom(roomId: String, localUid: String, listener: Listener): Result<Unit> = runCatching {
        check(localAudioTrack != null && peerConnectionFactory != null) {
            "Local audio is not initialized."
        }
        if (this.roomId != roomId || this.localUid != localUid) {
            disconnectAllPeers(removeSignals = true)
            desiredRemoteUids.clear()
        }
        this.roomId = roomId
        this.localUid = localUid
        this.listener = listener
        notifyPeerStatuses()
    }

    fun updateRoomMembers(memberUids: Collection<String>) {
        if (roomId.isBlank() || localUid.isBlank()) return
        val desired = PeerSetPolicy.remoteMembers(localUid, memberUids)
        desiredRemoteUids.clear()
        desiredRemoteUids += desired
        (peers.keys - desired).forEach { disconnectPeer(it, removeSignals = true) }
        (desired - peers.keys).sorted().forEach { connectToPeer(it) }
        notifyPeerStatuses()
    }

    fun leaveRoom() {
        localAudioTrack?.setEnabled(false)
        desiredRemoteUids.clear()
        disconnectAllPeers(removeSignals = true)
        listener = null
        roomId = ""
        localUid = ""
        disposeResources()
    }

    private fun connectToPeer(remoteUid: String, reconnectAttempts: Int = 0) {
        if (remoteUid !in desiredRemoteUids || peers.containsKey(remoteUid)) return
        val session = PeerSession(reconnectAttempts = reconnectAttempts)
        peers[remoteUid] = session
        notifyPeerStatuses()

        runCatching {
            session.peerConnection = PeerConnectionClient(
                checkNotNull(peerConnectionFactory),
                checkNotNull(localAudioTrack),
                iceServers,
                object : PeerConnectionClient.Listener {
                    override fun onLocalIceCandidate(candidate: IceCandidate) {
                        mainHandler.post {
                            if (peers[remoteUid] === session) {
                                session.signaling?.sendIceCandidate(candidate)
                            }
                        }
                    }

                    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                        mainHandler.post { handlePeerState(remoteUid, session, state) }
                    }

                    override fun onPeerError(message: String) {
                        mainHandler.post { handlePeerError(remoteUid, session, message) }
                    }
                },
            )
            session.signaling = SignalingClient(
                roomId,
                localUid,
                remoteUid,
                object : SignalingClient.Listener {
                    override fun onSignalingReady(isInitiator: Boolean) {
                        mainHandler.post {
                            if (peers[remoteUid] !== session) return@post
                            updatePeerStatus(remoteUid, session, PeerStatus.SIGNALING)
                            if (isInitiator) {
                                session.peerConnection?.createOffer { offer ->
                                    session.signaling?.sendOffer(offer)
                                }
                            }
                        }
                    }

                    override fun onOffer(sessionId: String, description: SessionDescription) {
                        mainHandler.post {
                            if (peers[remoteUid] !== session) return@post
                            session.peerConnection?.handleOffer(description) { answer ->
                                session.signaling?.sendAnswer(answer)
                            }
                        }
                    }

                    override fun onAnswer(description: SessionDescription) {
                        mainHandler.post {
                            if (peers[remoteUid] === session) {
                                session.peerConnection?.handleAnswer(description)
                            }
                        }
                    }

                    override fun onRemoteIceCandidate(candidate: IceCandidate) {
                        mainHandler.post {
                            if (peers[remoteUid] === session) {
                                session.peerConnection?.addRemoteIceCandidate(candidate)
                            }
                        }
                    }

                    override fun onSignalingError(message: String) {
                        mainHandler.post { handlePeerError(remoteUid, session, message) }
                    }
                },
            ).also(SignalingClient::start)
        }.onFailure { error ->
            handlePeerError(
                remoteUid,
                session,
                error.localizedMessage ?: "Unable to create a peer connection.",
            )
        }
    }

    private fun handlePeerState(
        remoteUid: String,
        session: PeerSession,
        state: PeerConnection.PeerConnectionState,
    ) {
        if (peers[remoteUid] !== session) return
        val status = state.toPeerStatus()
        Log.i(TAG, "Peer ${remoteUid.take(6)} state=$status attempt=${session.reconnectAttempts}")
        updatePeerStatus(remoteUid, session, status)
        when (status) {
            PeerStatus.CONNECTED -> {
                session.reconnectAttempts = 0
                session.reconnectRunnable?.let(mainHandler::removeCallbacks)
                session.reconnectRunnable = null
            }
            PeerStatus.DISCONNECTED -> beginIceRecovery(remoteUid, session)
            PeerStatus.FAILED -> {
                session.reconnectRunnable?.let(mainHandler::removeCallbacks)
                session.reconnectRunnable = null
                scheduleReconnect(
                    remoteUid,
                    session,
                    RECONNECT_DELAYS_MS[session.reconnectAttempts.coerceAtMost(
                        RECONNECT_DELAYS_MS.lastIndex,
                    )],
                )
            }
            else -> Unit
        }
    }

    private fun handlePeerError(remoteUid: String, session: PeerSession, message: String) {
        if (peers[remoteUid] !== session) return
        listener?.onWebRtcError(remoteUid, message)
        updatePeerStatus(remoteUid, session, PeerStatus.FAILED)
        scheduleReconnect(
            remoteUid,
            session,
            RECONNECT_DELAYS_MS[session.reconnectAttempts.coerceAtMost(
                RECONNECT_DELAYS_MS.lastIndex,
            )],
        )
    }

    private fun beginIceRecovery(remoteUid: String, session: PeerSession) {
        if (session.reconnectRunnable != null || remoteUid !in desiredRemoteUids) return
        Log.i(TAG, "Restarting ICE for peer ${remoteUid.take(6)} before rebuilding")
        session.peerConnection?.restartIce(
            createRestartOffer = localUid < remoteUid,
        ) { offer ->
            if (peers[remoteUid] === session) session.signaling?.sendOffer(offer)
        }
        scheduleReconnect(remoteUid, session, DISCONNECTED_RECOVERY_GRACE_MS)
    }

    private fun scheduleReconnect(remoteUid: String, session: PeerSession, delayMs: Long) {
        if (session.reconnectRunnable != null || remoteUid !in desiredRemoteUids) return
        if (session.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        val nextAttempt = session.reconnectAttempts + 1
        val runnable = Runnable {
            if (peers[remoteUid] !== session || remoteUid !in desiredRemoteUids) return@Runnable
            disconnectPeer(remoteUid, removeSignals = true)
            connectToPeer(remoteUid, reconnectAttempts = nextAttempt)
        }
        session.reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun updatePeerStatus(remoteUid: String, session: PeerSession, status: PeerStatus) {
        if (peers[remoteUid] !== session || session.status == status) return
        session.status = status
        notifyPeerStatuses()
    }

    private fun disconnectPeer(remoteUid: String, removeSignals: Boolean) {
        val session = peers.remove(remoteUid) ?: return
        session.reconnectRunnable?.let(mainHandler::removeCallbacks)
        session.signaling?.stop(removeSignals)
        session.peerConnection?.close()
        notifyPeerStatuses()
    }

    private fun disconnectAllPeers(removeSignals: Boolean) {
        peers.keys.toList().forEach { disconnectPeer(it, removeSignals) }
    }

    private fun notifyPeerStatuses() {
        listener?.onPeerStatusesChanged(peers.mapValues { it.value.status }.toSortedMap())
    }

    private fun PeerConnection.PeerConnectionState.toPeerStatus(): PeerStatus = when (this) {
        PeerConnection.PeerConnectionState.NEW -> PeerStatus.SIGNALING
        PeerConnection.PeerConnectionState.CONNECTING -> PeerStatus.CONNECTING
        PeerConnection.PeerConnectionState.CONNECTED -> PeerStatus.CONNECTED
        PeerConnection.PeerConnectionState.DISCONNECTED -> PeerStatus.DISCONNECTED
        PeerConnection.PeerConnectionState.FAILED -> PeerStatus.FAILED
        PeerConnection.PeerConnectionState.CLOSED -> PeerStatus.CLOSED
    }

    private fun disposeResources() {
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
    }

    companion object {
        private const val LOCAL_AUDIO_TRACK_ID = "travel_buddy_local_audio"
        private const val DEFAULT_STUN_SERVER = "stun:stun.cloudflare.com:3478"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val DISCONNECTED_RECOVERY_GRACE_MS = 20_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L)
        private val isWebRtcInitialized = AtomicBoolean(false)
        private const val TAG = "TravelBuddyWebRtc"

        private fun defaultIceServers(): List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder(DEFAULT_STUN_SERVER).createIceServer(),
        )

        private fun initializeWebRtcOnce(context: Context) {
            if (isWebRtcInitialized.compareAndSet(false, true)) {
                try {
                    val options = PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(options)
                } catch (error: RuntimeException) {
                    isWebRtcInitialized.set(false)
                    throw error
                }
            }
        }
    }
}

object PeerSetPolicy {
    fun remoteMembers(localUid: String, memberUids: Collection<String>): Set<String> = memberUids
        .asSequence()
        .filter { it.isNotBlank() && it != localUid }
        .toSortedSet()
}
