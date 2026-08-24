package com.lahiru.travelbuddy.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerSelectionPolicyTest {
    @Test
    fun selectsEveryOtherMemberDeterministically() {
        assertEquals(
            sortedSetOf("member-a", "member-z"),
            PeerSetPolicy.remoteMembers(
                localUid = "member-local",
                memberUids = listOf("member-z", "member-local", "member-a"),
            ),
        )
    }

    @Test
    fun returnsEmptySetWhenOnlyLocalMemberExists() {
        assertEquals(
            emptySet<String>(),
            PeerSetPolicy.remoteMembers("member-local", listOf("member-local")),
        )
    }
}
