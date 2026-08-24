package com.lahiru.travelbuddy.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.lahiru.travelbuddy.R
import kotlin.math.abs

class FloatingPttOverlayController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onOverlayMicPressed(enabled: Boolean)
        fun onOverlayError(message: String)
    }

    private val applicationContext = context.applicationContext
    private val windowManager = applicationContext.getSystemService(WindowManager::class.java)
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var overlayButton: ImageButton? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isPressed = false

    val isShowing: Boolean
        get() = overlayButton != null

    fun show() {
        if (isShowing || !Settings.canDrawOverlays(applicationContext)) return
        val size = dp(64)
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(PREF_X, dp(16)).coerceIn(0, maxOverlayX(size))
            y = preferences.getInt(PREF_Y, dp(160)).coerceIn(0, maxOverlayY(size))
        }
        val button = ImageButton(applicationContext).apply {
            setImageResource(R.drawable.outline_adaptive_audio_mic_24)
            setColorFilter(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            elevation = dp(8).toFloat()
            contentDescription = applicationContext.getString(R.string.floating_ptt_description)
            background = circleDrawable(MUTED_COLOR)
            setOnTouchListener(createTouchListener(this, params))
        }
        runCatching { windowManager.addView(button, params) }
            .onSuccess {
                overlayButton = button
                layoutParams = params
            }
            .onFailure { error ->
                listener.onOverlayError(
                    error.localizedMessage
                        ?: applicationContext.getString(R.string.floating_ptt_failed),
                )
            }
    }

    fun hide() {
        if (isPressed) setPressed(false)
        val button = overlayButton ?: return
        runCatching { windowManager.removeView(button) }
        overlayButton = null
        layoutParams = null
    }

    fun setMicActive(active: Boolean) {
        isPressed = active
        overlayButton?.background = circleDrawable(if (active) LIVE_COLOR else MUTED_COLOR)
    }

    private fun createTouchListener(
        button: ImageButton,
        params: WindowManager.LayoutParams,
    ) = android.view.View.OnTouchListener { view, event ->
        var handled = true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                button.tag = DragState(event.rawX, event.rawY, params.x, params.y)
                setPressed(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val state = button.tag as? DragState ?: return@OnTouchListener true
                val deltaX = event.rawX - state.downRawX
                val deltaY = event.rawY - state.downRawY
                if (!state.dragging &&
                    (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)
                ) {
                    state.dragging = true
                    setPressed(false)
                }
                if (state.dragging) {
                    params.x = (state.startX + deltaX.toInt())
                        .coerceIn(0, maxOverlayX(params.width))
                    params.y = (state.startY + deltaY.toInt())
                        .coerceIn(0, maxOverlayY(params.height))
                    runCatching { windowManager.updateViewLayout(button, params) }
                }
            }
            MotionEvent.ACTION_UP -> {
                val state = button.tag as? DragState
                setPressed(false)
                if (state?.dragging == true) savePosition(params)
                view.performClick()
                button.tag = null
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                setPressed(false)
                button.tag = null
            }
            else -> handled = false
        }
        handled
    }

    private fun setPressed(pressed: Boolean) {
        if (isPressed == pressed) return
        isPressed = pressed
        overlayButton?.background = circleDrawable(if (pressed) LIVE_COLOR else MUTED_COLOR)
        listener.onOverlayMicPressed(pressed)
    }

    private fun savePosition(params: WindowManager.LayoutParams) {
        preferences.edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
    }

    private fun circleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), ContextCompat.getColor(applicationContext, android.R.color.white))
    }

    private fun dp(value: Int): Int = (value * applicationContext.resources.displayMetrics.density)
        .toInt()

    private fun maxOverlayX(size: Int): Int =
        (applicationContext.resources.displayMetrics.widthPixels - size).coerceAtLeast(0)

    private fun maxOverlayY(size: Int): Int =
        (applicationContext.resources.displayMetrics.heightPixels - size).coerceAtLeast(0)

    private val touchSlop: Int
        get() = ViewConfiguration.get(applicationContext).scaledTouchSlop

    private data class DragState(
        val downRawX: Float,
        val downRawY: Float,
        val startX: Int,
        val startY: Int,
        var dragging: Boolean = false,
    )

    companion object {
        private const val PREFERENCES_NAME = "floating_ptt_overlay"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val MUTED_COLOR = 0xDD455A64.toInt()
        private const val LIVE_COLOR = 0xDD008577.toInt()
    }
}
