package com.lahiru.travelbuddy

import com.lahiru.travelbuddy.firebase.TurnCredentialsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnCredentialsParserTest {
    @Test
    fun parsesCloudflareIceServersAndDropsPort53() {
        val result = TurnCredentialsParser.parse(
            mapOf(
                "iceServers" to listOf(
                    mapOf("urls" to listOf("stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53")),
                    mapOf(
                        "urls" to listOf(
                            "turn:turn.cloudflare.com:3478?transport=udp",
                            "turns:turn.cloudflare.com:443?transport=tcp",
                        ),
                        "username" to "temporary-user",
                        "credential" to "temporary-password",
                    ),
                ),
            ),
        ).getOrThrow()

        assertEquals(2, result.size)
        assertTrue(result.flatMap { it.urls }.none { it.contains(":53") })
        assertEquals("temporary-user", result[1].username)
    }

    @Test
    fun rejectsResponseWithoutTurnRelay() {
        val result = TurnCredentialsParser.parse(
            mapOf("iceServers" to listOf(mapOf("urls" to "stun:stun.cloudflare.com:3478"))),
        )

        assertTrue(result.isFailure)
    }
}
