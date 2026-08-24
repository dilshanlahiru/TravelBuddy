package com.lahiru.travelbuddy.data.model

import com.google.firebase.Timestamp

data class Room(
    val roomId: String = "",
    val roomCode: String = "",
    val createdBy: String = "",
    val createdAt: Timestamp? = null,
    val status: String = STATUS_ACTIVE,
    val memberCount: Long = 0,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_CLOSED = "CLOSED"
    }
}
