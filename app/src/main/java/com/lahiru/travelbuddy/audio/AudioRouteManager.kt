package com.lahiru.travelbuddy.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class AudioRouteManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onAudioRoutesChanged(available: Set<AudioRoute>, selected: AudioRoute)
    }

    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var selectedRoute = AudioRoute.EARPIECE
    private var started = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        if (started) {
            refresh()
            return
        }
        started = true
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refresh()
    }

    fun select(route: AudioRoute): Result<Unit> = runCatching {
        val devices = communicationDevices()
        val available = availableRoutes(devices)
        check(route in available) { "The selected audio output is not available." }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = devices.firstOrNull { it.toAudioRoute() == route }
            checkNotNull(device) { "The selected audio output is no longer connected." }
            check(audioManager.setCommunicationDevice(device)) {
                "Android could not switch the communication audio output."
            }
        } else {
            selectLegacyRoute(route)
        }
        selectedRoute = route
        notifyRoutes(available)
    }

    fun refresh() {
        val devices = communicationDevices()
        val available = availableRoutes(devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.toAudioRoute()?.let { selectedRoute = it }
        }
        if (selectedRoute !in available) {
            selectedRoute = when {
                AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
                else -> AudioRoute.SPEAKER
            }
        }
        notifyRoutes(available)
    }

    fun stop() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            clearLegacyRoute()
        }
        audioManager.mode = previousAudioMode
    }

    private fun communicationDevices(): List<AudioDeviceInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
    }.getOrDefault(emptyList())

    private fun availableRoutes(devices: List<AudioDeviceInfo>): Set<AudioRoute> {
        val routes = devices.mapNotNull { it.toAudioRoute() }.toMutableSet()
        // Android phones always provide a built-in communication speaker. Retain it as a
        // fallback if a vendor does not expose the built-in endpoints in the device list.
        routes += AudioRoute.SPEAKER
        if (applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            routes += AudioRoute.EARPIECE
        }
        if (!hasBluetoothPermission()) routes -= AudioRoute.BLUETOOTH
        return routes
    }

    private fun AudioDeviceInfo.toAudioRoute(): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> AudioRoute.BLUETOOTH
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun selectLegacyRoute(route: AudioRoute) {
        when (route) {
            AudioRoute.EARPIECE -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
            AudioRoute.SPEAKER -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
            }
            AudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun clearLegacyRoute() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.isSpeakerphoneOn = false
    }

    private fun hasBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

    private fun notifyRoutes(available: Set<AudioRoute>) {
        listener.onAudioRoutesChanged(available.toSet(), selectedRoute)
    }
}
