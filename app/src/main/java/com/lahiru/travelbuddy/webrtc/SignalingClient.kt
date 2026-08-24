package com.lahiru.travelbuddy.webrtc

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.lahiru.travelbuddy.data.model.SignalDescription
import com.lahiru.travelbuddy.data.model.SignalIceCandidate
import com.lahiru.travelbuddy.firebase.SignalingRepository
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

class SignalingClient(
    private val roomId: String,
    private val localUid: String,
    private val remoteUid: String,
    private val listener: Listener,
    private val repository: SignalingRepository = SignalingRepository(),
) {
    interface Listener {
        fun onSignalingReady(isInitiator: Boolean)
        fun onOffer(sessionId: String, description: SessionDescription)
        fun onAnswer(description: SessionDescription)
        fun onRemoteIceCandidate(candidate: IceCandidate)
        fun onSignalingError(message: String)
    }

    private val isInitiator = localUid < remoteUid
    private val pairReference: DatabaseReference =
        repository.pairReference(roomId, localUid, remoteUid)
    private var currentSessionId: String = if (isInitiator) UUID.randomUUID().toString() else ""
    private var descriptionListener: ValueEventListener? = null
    private var candidateListener: ChildEventListener? = null
    private val pendingCandidates = mutableMapOf<String, MutableList<SignalIceCandidate>>()
    private var lastDescriptionSdp: String? = null

    fun start() {
        if (isInitiator) {
            repository.clearPair(pairReference) { result ->
                result.onSuccess {
                    attachAnswerListener()
                    attachCandidateListener()
                    listener.onSignalingReady(true)
                }.onFailure(::reportError)
            }
        } else {
            attachOfferListener()
            attachCandidateListener()
            listener.onSignalingReady(false)
        }
    }

    fun sendOffer(description: SessionDescription) {
        sendDescription(SignalingRepository.OFFER_CHILD, description)
    }

    fun sendAnswer(description: SessionDescription) {
        if (currentSessionId.isBlank()) {
            listener.onSignalingError("Cannot send an answer before receiving an offer.")
            return
        }
        sendDescription(SignalingRepository.ANSWER_CHILD, description)
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        if (currentSessionId.isBlank()) return
        repository.sendCandidate(
            pairReference,
            SignalIceCandidate(
                sessionId = currentSessionId,
                fromUid = localUid,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
                candidate = candidate.sdp,
            ),
        ) { result -> result.exceptionOrNull()?.let(::reportError) }
    }

    fun stop(removeSignals: Boolean) {
        descriptionListener?.let { activeListener ->
            val child = if (isInitiator) {
                SignalingRepository.ANSWER_CHILD
            } else {
                SignalingRepository.OFFER_CHILD
            }
            repository.removeDescriptionListener(pairReference, child, activeListener)
        }
        descriptionListener = null
        candidateListener?.let { activeListener ->
            repository.removeCandidateListener(pairReference, remoteUid, activeListener)
        }
        candidateListener = null
        pendingCandidates.clear()
        // Only the deterministic offerer clears the shared pair. If both sides clear while
        // reconnecting, the answerer can erase a newly-written offer from the initiator.
        if (removeSignals && isInitiator) {
            repository.clearPair(pairReference) { }
        }
    }

    private fun sendDescription(childName: String, description: SessionDescription) {
        repository.sendDescription(
            pairReference = pairReference,
            childName = childName,
            description = SignalDescription(
                sessionId = currentSessionId,
                fromUid = localUid,
                toUid = remoteUid,
                type = description.type.canonicalForm(),
                sdp = description.description,
            ),
        ) { result -> result.exceptionOrNull()?.let(::reportError) }
    }

    private fun attachOfferListener() {
        descriptionListener = repository.observeDescription(
            pairReference,
            SignalingRepository.OFFER_CHILD,
            onDescription = { signal ->
                if (signal == null || signal.toUid != localUid || signal.fromUid != remoteUid ||
                    signal.sessionId.isBlank() || signal.sdp.isBlank() || signal.sdp == lastDescriptionSdp
                ) return@observeDescription
                lastDescriptionSdp = signal.sdp
                currentSessionId = signal.sessionId
                listener.onOffer(
                    signal.sessionId,
                    SessionDescription(SessionDescription.Type.OFFER, signal.sdp),
                )
                flushPendingCandidates(signal.sessionId)
            },
            onError = ::reportError,
        )
    }

    private fun attachAnswerListener() {
        descriptionListener = repository.observeDescription(
            pairReference,
            SignalingRepository.ANSWER_CHILD,
            onDescription = { signal ->
                if (signal == null || signal.sessionId != currentSessionId ||
                    signal.toUid != localUid || signal.fromUid != remoteUid ||
                    signal.sdp.isBlank() || signal.sdp == lastDescriptionSdp
                ) return@observeDescription
                lastDescriptionSdp = signal.sdp
                listener.onAnswer(SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))
            },
            onError = ::reportError,
        )
    }

    private fun attachCandidateListener() {
        candidateListener = repository.observeCandidates(
            pairReference,
            remoteUid,
            onCandidate = { signal ->
                if (signal.fromUid != remoteUid || signal.candidate.isBlank()) return@observeCandidates
                if (signal.sessionId == currentSessionId && currentSessionId.isNotBlank()) {
                    listener.onRemoteIceCandidate(signal.toIceCandidate())
                } else {
                    pendingCandidates.getOrPut(signal.sessionId) { mutableListOf() }.add(signal)
                }
            },
            onError = ::reportError,
        )
    }

    private fun flushPendingCandidates(sessionId: String) {
        pendingCandidates.remove(sessionId).orEmpty().forEach { signal ->
            listener.onRemoteIceCandidate(signal.toIceCandidate())
        }
        pendingCandidates.keys.filter { it != sessionId }.forEach(pendingCandidates::remove)
    }

    private fun SignalIceCandidate.toIceCandidate(): IceCandidate = IceCandidate(
        sdpMid,
        sdpMLineIndex,
        candidate,
    )

    private fun reportError(error: Throwable) {
        listener.onSignalingError(error.localizedMessage ?: "Firebase signaling failed.")
    }
}
