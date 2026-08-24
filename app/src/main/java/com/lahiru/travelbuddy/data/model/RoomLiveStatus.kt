package com.lahiru.travelbuddy.data.model

data class RoomLiveStatus(
    val uid: String = "",
    val isConnected: Boolean = false,
    val isSpeaking: Boolean = false,
    val micMode: String = RoomMember.MIC_MODE_PTT,
    val updatedAt: Long = 0L,
)
