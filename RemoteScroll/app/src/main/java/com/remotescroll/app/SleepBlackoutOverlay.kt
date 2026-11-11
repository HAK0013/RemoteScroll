package com.remotescroll.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import kotlin.math.abs

/**
 * Audio-Only "Podcast / Sleep" Blackout Mode:
 *
 * Fullscreen 100% true-black (#000000) AMOLED overlay drawn with TYPE_ACCESSIBILITY_OVERLAY.
 * Keeps TikTok/Shorts running and audio playing in foreground without interruption.
 *
 * Features:
 * - 0% OLED pixel light emission + minimum hardware screen backlight (0.01f).
 * - Gesture controls in total darkness:
 *     * Swipe UP -> Next video (service.swipeUp())
 *     * Swipe DOWN -> Previous video (service.swipeDown())
 *     * Double Tap -> Wake screen / exit Sleep Blackout Mode
 *     * Single Tap -> Shows ultra-minimal HUD (clock, battery %, pause/play) for 2.8s
 * - Remote media buttons (Volume / Next / Back / PlayPause) continue working seamlessly with pass-through.
 */
class SleepBlackoutOverlay(private val service: GestureAccessibilityService) {

    companion object {
        private const val TAG = "SleepBlackoutOverlay"
        private const val PREFS = "remote_scroll"
        private const val KEY_SLEEP_GESTURES = "sleep_gestures_enabled"
        private const val DOUBLE_TAP_TIMEOUT_MS = 350L
        private const val HUD_VISIBLE_MS = 2800L
        private const val BADGE_VISIBLE_MS = 1200L

        fun isSleepGesturesEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SLEEP_GESTURES, true)

        fun setSleepGesturesEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SLEEP_GESTURES, enabled).apply()
        }
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var hudContainer: View? = null
    private var textClock: TextView? = null
    private var textBatteryStatus: TextView? = null
    private var textGestureBadge: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var passThroughCount = 0

    // Touch gesture state
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    private val hideHudRunnable = Runnable {
        hudContainer?.animate()?.alpha(0f)?.setDuration(400)?.start()
    }

    private val hideBadgeRunnable = Runnable {
        textGestureBadge?.animate()?.alpha(0f)?.setDuration(300)?.start()
    }

    private val singleTapRunnable = Runnable {
        flashHud()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (root != null) return

        val inflater = LayoutInflater.from(service)
        val view = try {
            inflater.inflate(R.layout.overlay_sleep_blackout, null) as FrameLayout
        } catch (e: Exception) {
            Log.e(TAG, "Could not inflate overlay_sleep_blackout", e)
            return
        }

        hudContainer = view.findViewById(R.id.hudContainer)
        textClock = view.findViewById(R.id.textClock)
        textBatteryStatus = view.findViewById(R.id.textBatteryStatus)
        textGestureBadge = view.findViewById(R.id.textGestureBadge)

        val btnPausePlay = view.findViewById<View>(R.id.btnHudPausePlay)
        val btnWake = view.findViewById<View>(R.id.btnHudWake)

        btnPausePlay?.setOnClickListener {
            service.tapCenter()
            flashBadge("⏯️ Pause / Play")
            flashHud()
        }

        btnWake?.setOnClickListener {
            hide()
        }

        view.setOnTouchListener(touchListener)

        val lp = buildParams()
        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Could not add sleep blackout overlay", e)
            return
        }

        root = view
        params = lp
        passThroughCount = 0

        FeedbackSound.lockOn()
        flashHud() // Brief HUD glance on start so user knows mode is active and how to exit
        Log.i(TAG, "Audio-Only Sleep Blackout mode ON")
    }

    fun hide() {
        val view = root ?: return
        handler.removeCallbacks(hideHudRunnable)
        handler.removeCallbacks(hideBadgeRunnable)
        handler.removeCallbacks(singleTapRunnable)

        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove sleep blackout overlay", e)
        }

        root = null
        hudContainer = null
        textClock = null
        textBatteryStatus = null
        textGestureBadge = null
        params = null
        passThroughCount = 0

        FeedbackSound.lockOff()
        Toast.makeText(service, "☀️ Screen Awake", Toast.LENGTH_SHORT).show()
        onDismissed?.invoke()
        Log.i(TAG, "Audio-Only Sleep Blackout mode OFF")
    }

    /** Let touches (injected gestures) fall through to TikTok. Pair with [endPassThrough]. */
    fun beginPassThrough() {
        if (root == null) return
        passThroughCount++
        if (passThroughCount == 1) setTouchable(false)
    }

    fun endPassThrough() {
        if (root == null || passThroughCount == 0) return
        passThroughCount--
        if (passThroughCount == 0) setTouchable(true)
    }

    private fun setTouchable(touchable: Boolean) {
        val view = root ?: return
        val lp = params ?: return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Could not update sleep blackout overlay touchable", e)
        }
    }

    private fun updateHudContent() {
        val clock = textClock ?: return
        val batteryText = textBatteryStatus ?: return

        try {
            clock.text = DateFormat.getTimeFormat(service).format(Date())
        } catch (_: Exception) {
            clock.text = "--:--"
        }

        val bm = service.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        batteryText.text = if (level in 0..100) {
            "🔋 $level%  •  🌙 Sleep Blackout Mode"
        } else {
            "🌙 Sleep Blackout Mode"
        }
    }

    private fun flashHud() {
        val hud = hudContainer ?: return
        handler.removeCallbacks(hideHudRunnable)
        updateHudContent()

        hud.animate().cancel()
        hud.alpha = 1f
        handler.postDelayed(hideHudRunnable, HUD_VISIBLE_MS)
    }

    private fun flashBadge(text: String) {
        val badge = textGestureBadge ?: return
        handler.removeCallbacks(hideBadgeRunnable)
        badge.text = text
        badge.animate().cancel()
        badge.alpha = 0.95f
        handler.postDelayed(hideBadgeRunnable, BADGE_VISIBLE_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downTime = SystemClock.uptimeMillis()
                true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val upX = event.rawX
                val upY = event.rawY
                val dx = upX - downX
                val dy = upY - downY
                val dt = now - downTime

                // 1. Check for Vertical Swipe (in darkness: skip next/prev)
                if (abs(dy) > abs(dx) && abs(dy) > 90f) {
                    handler.removeCallbacks(singleTapRunnable)
                    if (isSleepGesturesEnabled(service)) {
                        if (dy < 0) {
                            // Swiped UP -> Next video
                            service.swipeUp()
                            flashBadge("▼ Next Video")
                        } else {
                            // Swiped DOWN -> Previous video
                            service.swipeDown()
                            flashBadge("▲ Prev Video")
                        }
                    }
                    return@OnTouchListener true
                }

                // 2. Check for Tap (Double-tap to wake or Single-tap for HUD)
                if (abs(dx) < 50f && abs(dy) < 50f && dt < 400L) {
                    if (now - lastTapTime < DOUBLE_TAP_TIMEOUT_MS) {
                        // Double Tap confirmed! Wake screen!
                        handler.removeCallbacks(singleTapRunnable)
                        lastTapTime = 0L
                        hide()
                        return@OnTouchListener true
                    } else {
                        // First Tap: wait briefly to distinguish from double tap
                        lastTapTime = now
                        handler.removeCallbacks(singleTapRunnable)
                        handler.postDelayed(singleTapRunnable, DOUBLE_TAP_TIMEOUT_MS)
                        return@OnTouchListener true
                    }
                }
                true
            }
            else -> true
        }
    }

    private fun buildParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Not focusable: TikTok keeps input focus and continues audio uninterrupted
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 0.01f requests the minimum hardware display backlight
        screenBrightness = 0.01f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(0)
        }
    }
}
