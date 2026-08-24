package com.lahiru.travelbuddy.data.model

import com.google.firebase.Timestamp

data class AppUser(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val createdAt: Timestamp? = null,
    val lastLoginAt: Timestamp? = null,
)
