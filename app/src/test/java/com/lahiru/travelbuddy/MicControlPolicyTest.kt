package com.lahiru.travelbuddy

import com.lahiru.travelbuddy.ui.comms.MicControlPolicy
import com.lahiru.travelbuddy.ui.comms.MicGesture
import com.lahiru.travelbuddy.ui.comms.MicMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicControlPolicyTest {
    @Test
    fun pushToTalkEnablesOnlyWhilePressed() {
        assertEquals(true, MicControlPolicy.desiredState(MicMode.PTT, MicGesture.PRESS, false))
        assertEquals(false, MicControlPolicy.desiredState(MicMode.PTT, MicGesture.RELEASE, true))
        assertEquals(false, MicControlPolicy.desiredState(MicMode.PTT, MicGesture.CANCEL, true))
    }

    @Test
    fun toggleChangesOnlyOnRelease() {
        assertNull(MicControlPolicy.desiredState(MicMode.TOGGLE, MicGesture.PRESS, false))
        assertEquals(true, MicControlPolicy.desiredState(MicMode.TOGGLE, MicGesture.RELEASE, false))
        assertEquals(false, MicControlPolicy.desiredState(MicMode.TOGGLE, MicGesture.RELEASE, true))
        assertNull(MicControlPolicy.desiredState(MicMode.TOGGLE, MicGesture.CANCEL, true))
    }

}
