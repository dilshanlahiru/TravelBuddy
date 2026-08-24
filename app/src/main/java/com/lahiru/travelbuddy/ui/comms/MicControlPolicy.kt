package com.lahiru.travelbuddy.ui.comms

enum class MicMode {
    PTT,
    TOGGLE,
}

enum class MicGesture {
    PRESS,
    RELEASE,
    CANCEL,
}

object MicControlPolicy {
    fun desiredState(
        mode: MicMode,
        gesture: MicGesture,
        currentlyEnabled: Boolean,
    ): Boolean? = when (mode) {
        MicMode.PTT -> when (gesture) {
            MicGesture.PRESS -> true
            MicGesture.RELEASE, MicGesture.CANCEL -> false
        }
        MicMode.TOGGLE -> when (gesture) {
            MicGesture.RELEASE -> !currentlyEnabled
            MicGesture.PRESS, MicGesture.CANCEL -> null
        }
    }
}
