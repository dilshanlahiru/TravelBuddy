package com.lahiru.travelbuddy.webrtc

import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class PeerConnectionClient(
    peerConnectionFactory: PeerConnectionFactory,
    localAudioTrack: AudioTrack,
    private val listener: Listener,
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onPeerError(message: String)
    }

    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private val peerConnection: PeerConnection

    init {
        val iceServer = PeerConnection.IceServer.builder(STUN_SERVER).createIceServer()
        val configuration = PeerConnection.RTCConfiguration(listOf(iceServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peerConnection = checkNotNull(
            peerConnectionFactory.createPeerConnection(configuration, createObserver()),
        ) { "Unable to create the WebRTC peer connection." }
        peerConnection.addTrack(localAudioTrack, listOf(LOCAL_STREAM_ID))
    }

    fun createOffer(onReady: (SessionDescription) -> Unit) {
        val constraints = audioOnlyConstraints()
        peerConnection.createOffer(
            CreateSdpObserver(
                onSuccess = { offer -> setLocalDescription(offer, onReady) },
                onFailure = listener::onPeerError,
            ),
            constraints,
        )
    }

    fun handleOffer(offer: SessionDescription, onAnswerReady: (SessionDescription) -> Unit) {
        setRemoteDescription(offer) {
            peerConnection.createAnswer(
                CreateSdpObserver(
                    onSuccess = { answer -> setLocalDescription(answer, onAnswerReady) },
                    onFailure = listener::onPeerError,
                ),
                audioOnlyConstraints(),
            )
        }
    }

    fun handleAnswer(answer: SessionDescription) {
        setRemoteDescription(answer) { }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (!remoteDescriptionSet) {
            pendingRemoteCandidates += candidate
            return
        }
        if (!peerConnection.addIceCandidate(candidate)) {
            listener.onPeerError("A remote ICE candidate could not be added.")
        }
    }

    fun close() {
        pendingRemoteCandidates.clear()
        peerConnection.close()
        peerConnection.dispose()
    }

    private fun setLocalDescription(
        description: SessionDescription,
        onReady: (SessionDescription) -> Unit,
    ) {
        peerConnection.setLocalDescription(
            SetSdpObserver(
                onSuccess = { onReady(description) },
                onFailure = listener::onPeerError,
            ),
            description,
        )
    }

    private fun setRemoteDescription(
        description: SessionDescription,
        onReady: () -> Unit,
    ) {
        peerConnection.setRemoteDescription(
            SetSdpObserver(
                onSuccess = {
                    remoteDescriptionSet = true
                    drainRemoteCandidates()
                    onReady()
                },
                onFailure = listener::onPeerError,
            ),
            description,
        )
    }

    private fun drainRemoteCandidates() {
        val candidates = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        candidates.forEach(::addRemoteIceCandidate)
    }

    private fun createObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            listener.onConnectionStateChanged(newState)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onLocalIceCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) {
            stream.audioTracks.forEach { it.setEnabled(true) }
        }

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(dataChannel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            receiver.track()?.setEnabled(true)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            transceiver.receiver.track()?.setEnabled(true)
        }
    }

    private fun audioOnlyConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
    }

    private class CreateSdpObserver(
        private val onSuccess: (SessionDescription) -> Unit,
        private val onFailure: (String) -> Unit,
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onSuccess(description)
        override fun onCreateFailure(error: String) = onFailure(error)
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private class SetSdpObserver(
        private val onSuccess: () -> Unit,
        private val onFailure: (String) -> Unit,
    ) : SdpObserver {
        override fun onSetSuccess() = onSuccess()
        override fun onSetFailure(error: String) = onFailure(error)
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
    }

    companion object {
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
        private const val LOCAL_STREAM_ID = "travel_buddy_audio_stream"
    }
}
