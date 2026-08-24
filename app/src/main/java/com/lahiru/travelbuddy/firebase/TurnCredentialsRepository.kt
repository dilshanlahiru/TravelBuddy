package com.lahiru.travelbuddy.firebase

import com.google.firebase.functions.FirebaseFunctions
import com.lahiru.travelbuddy.data.model.IceServerConfig

class TurnCredentialsRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTION_REGION),
) {
    fun fetch(onComplete: (Result<List<IceServerConfig>>) -> Unit) {
        functions.getHttpsCallable(FUNCTION_NAME)
            .call()
            .addOnSuccessListener { response ->
                onComplete(TurnCredentialsParser.parse(response.data))
            }
            .addOnFailureListener { error -> onComplete(Result.failure(error)) }
    }

    companion object {
        private const val FUNCTION_NAME = "getTurnCredentials"
        private const val FUNCTION_REGION = "asia-southeast1"
    }
}

object TurnCredentialsParser {
    fun parse(value: Any?): Result<List<IceServerConfig>> = runCatching {
        val payload = value as? Map<*, *>
            ?: error("TURN service returned an invalid response.")
        val rawServers = payload["iceServers"] as? List<*>
            ?: error("TURN service returned no ICE servers.")
        val servers = rawServers.mapNotNull { rawServer ->
            val server = rawServer as? Map<*, *> ?: return@mapNotNull null
            val urls = when (val rawUrls = server["urls"]) {
                is String -> listOf(rawUrls)
                is List<*> -> rawUrls.filterIsInstance<String>()
                else -> emptyList()
            }.filter { it.isNotBlank() && !it.contains(":53") }
            if (urls.isEmpty()) return@mapNotNull null
            IceServerConfig(
                urls = urls,
                username = server["username"] as? String ?: "",
                credential = server["credential"] as? String ?: "",
            )
        }
        check(servers.isNotEmpty()) { "TURN service returned no usable ICE servers." }
        check(servers.any { config -> config.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }) {
            "TURN service returned no relay server."
        }
        servers
    }
}
