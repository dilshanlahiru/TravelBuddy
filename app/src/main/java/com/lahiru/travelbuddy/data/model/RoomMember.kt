package com.lahiru.travelbuddy.data.model

import com.google.firebase.Timestamp

data class RoomMember(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val joinedAt: Timestamp? = null,
    val isVoiceJoined: Boolean = false,
    val isNavigating: Boolean = false,
    val isConnected: Boolean = false,
    val isSpeaking: Boolean = false,
    val micMode: String = MIC_MODE_PTT,
) {
    companion object {
        const val MIC_MODE_PTT = "PTT"
        const val MIC_MODE_TOGGLE = "TOGGLE"
    }
}
