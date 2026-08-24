package com.lahiru.travelbuddy.data.model

data class IceServerConfig(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
)
