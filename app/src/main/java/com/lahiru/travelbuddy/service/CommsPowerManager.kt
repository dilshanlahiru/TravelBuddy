package com.lahiru.travelbuddy.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

class CommsPowerManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = applicationContext.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_LOCK_TAG_PREFIX:voice")
        .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifiLock = applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$WAKE_LOCK_TAG_PREFIX:wifi")
        .apply { setReferenceCounted(false) }

    private var active = false
    private val renewWakeLock = object : Runnable {
        override fun run() {
            if (!active) return
            acquireWakeLock()
            handler.postDelayed(this, WAKE_LOCK_RENEWAL_MS)
        }
    }

    fun start(): Result<Unit> = runCatching {
        if (active) return@runCatching
        active = true
        acquireWakeLock()
        runCatching {
            if (!wifiLock.isHeld) wifiLock.acquire()
        }
        handler.postDelayed(renewWakeLock, WAKE_LOCK_RENEWAL_MS)
    }.onFailure {
        stop()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(renewWakeLock)
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }

    private fun acquireWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    companion object {
        private const val WAKE_LOCK_TAG_PREFIX = "TravelBuddy:Comms"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEWAL_MS = 9 * 60 * 1000L
    }
}
