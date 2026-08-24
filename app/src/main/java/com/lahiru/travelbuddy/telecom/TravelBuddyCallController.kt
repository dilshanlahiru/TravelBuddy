package com.lahiru.travelbuddy.telecom

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.annotation.RequiresApi
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import com.lahiru.travelbuddy.audio.AudioRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
class TravelBuddyCallController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onTelecomCallActive()
        fun onTelecomCallInactive()
        fun onTelecomCallDisconnected()
        fun onTelecomAudioRoutesChanged(available: Set<AudioRoute>, selected: AudioRoute)
        fun onTelecomError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callsManager = CallsManager(context.applicationContext).apply {
        registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }
    private var callJob: Job? = null
    private var callControlScope: CallControlScope? = null
    private var availableCallEndpoints: List<CallEndpointCompat> = emptyList()
    private var selectedCallEndpoint: CallEndpointCompat? = null

    fun start(roomCode: String) {
        if (callJob?.isActive == true) return
        val attributes = CallAttributesCompat(
            displayName = "Travel Buddy room $roomCode",
            address = Uri.parse("travelbuddy:room/$roomCode"),
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        callJob = scope.launch {
            runCatching {
                callsManager.addCall(
                    attributes,
                    onAnswer = { },
                    onDisconnect = {
                        callControlScope?.disconnect(it)
                        listener.onTelecomCallDisconnected()
                    },
                    onSetActive = {
                        callControlScope?.setActive()
                        listener.onTelecomCallActive()
                    },
                    onSetInactive = {
                        callControlScope?.setInactive()
                        listener.onTelecomCallInactive()
                    },
                ) {
                    callControlScope = this
                    launch {
                        setActive()
                        listener.onTelecomCallActive()
                    }
                    launch {
                        availableEndpoints.collect { endpoints ->
                            availableCallEndpoints = endpoints
                            notifyAudioRoutes()
                        }
                    }
                    launch {
                        currentCallEndpoint.collect { endpoint ->
                            selectedCallEndpoint = endpoint
                            notifyAudioRoutes()
                        }
                    }
                }
            }.onFailure { error ->
                listener.onTelecomError(
                    error.localizedMessage ?: "Android could not register the ongoing call.",
                )
            }
            callControlScope = null
        }
    }

    fun refreshAudioRoutes() {
        notifyAudioRoutes()
    }

    fun selectAudioRoute(route: AudioRoute): Result<Unit> = runCatching {
        val activeCall = checkNotNull(callControlScope) { "The Android call is not ready." }
        val endpoint = availableCallEndpoints.firstOrNull { it.toAudioRoute() == route }
        checkNotNull(endpoint) { "The selected audio output is not available." }
        scope.launch {
            runCatching { activeCall.requestEndpointChange(endpoint) }
                .onFailure { error ->
                    listener.onTelecomError(
                        error.localizedMessage ?: "Android could not switch the call audio output.",
                    )
                }
        }
    }

    fun stop() {
        val activeCall = callControlScope
        if (activeCall == null) {
            callJob?.cancel()
            callJob = null
            return
        }
        scope.launch {
            runCatching {
                activeCall.disconnect(DisconnectCause(DisconnectCause.LOCAL))
            }
            callJob?.cancel()
            callJob = null
            callControlScope = null
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun notifyAudioRoutes() {
        val available = availableCallEndpoints.mapNotNull { it.toAudioRoute() }.toSet()
        val selected = selectedCallEndpoint?.toAudioRoute()
            ?: when {
                AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
                AudioRoute.SPEAKER in available -> AudioRoute.SPEAKER
                else -> return
            }
        listener.onTelecomAudioRoutesChanged(available, selected)
    }

    private fun CallEndpointCompat.toAudioRoute(): AudioRoute? = when (type) {
        CallEndpointCompat.TYPE_EARPIECE,
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute.EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> AudioRoute.SPEAKER
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute.BLUETOOTH
        else -> null
    }
}
