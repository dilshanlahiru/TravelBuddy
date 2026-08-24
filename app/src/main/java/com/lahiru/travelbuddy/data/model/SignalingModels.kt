package com.lahiru.travelbuddy.data.model

data class SignalDescription(
    val sessionId: String = "",
    val fromUid: String = "",
    val toUid: String = "",
    val type: String = "",
    val sdp: String = "",
)

data class SignalIceCandidate(
    val sessionId: String = "",
    val fromUid: String = "",
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val candidate: String = "",
)
