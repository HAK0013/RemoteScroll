package com.remotescroll.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * "Pocket lock": a black full-screen overlay drawn on top of TikTok by the accessibility
 * service (TYPE_ACCESSIBILITY_OVERLAY, so no "draw over other apps" permission is needed).
 *
 * Why not the real lock screen? When the phone is really locked, Android pauses TikTok
 * and shows the keyguard, and no app may swipe through a secure keyguard. This overlay
 * keeps TikTok in the foreground and playing while the screen looks off:
 *  - blocks accidental touches (pocket, bag, bed);
 *  - keeps the screen from timing out and locking for real (FLAG_KEEP_SCREEN_ON);
 *  - stays all black, so OLED screens use almost no power.
 *
 * Remote gestures still reach TikTok: just before a gesture is dispatched the overlay
 * stops taking touches ([beginPassThrough]), and it blocks them again afterwards.
 *
 * Unlock: hold a finger anywhere for [UNLOCK_HOLD_MS], or hold Play/Pause on the remote.
 * Must be used on the main thread.
 */
class LockOverlay(private val context: Context) {

    companion object {
        private const val TAG = "LockOverlay"
        private const val UNLOCK_HOLD_MS = 2_000L
        private const val HINT_VISIBLE_MS = 3_000L
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var hint: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var passThroughCount = 0

    /** Called after the overlay is removed by the user (hold to unlock). */
    var onUnlockedByUser: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    private val hideHint = Runnable { hint?.animate()?.alpha(0f)?.setDuration(400)?.start() }
    private val unlockRunnable = Runnable {
        hide()
        onUnlockedByUser?.invoke()
    }

    fun show() {
        if (root != null) return
        val text = TextView(context).apply {
            setText(R.string.lock_hint)
            setTextColor(Color.rgb(110, 110, 110))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val view = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                text,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            setOnTouchListener(touchListener)
        }
        val lp = buildParams()
        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Could not add lock overlay", e)
            return
        }
        root = view
        hint = text
        params = lp
        passThroughCount = 0
        flashHint()
        Log.i(TAG, "Pocket lock on")
    }

    fun hide() {
        val view = root ?: return
        handler.removeCallbacks(hideHint)
        handler.removeCallbacks(unlockRunnable)
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove lock overlay", e)
        }
        root = null
        hint = null
        params = null
        passThroughCount = 0
        Log.i(TAG, "Pocket lock off")
    }

    /** Let touches (our injected gesture) fall through to TikTok. Pair with [endPassThrough]. */
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
            Log.w(TAG, "Could not update lock overlay", e)
        }
    }

    private fun flashHint() {
        val text = hint ?: return
        handler.removeCallbacks(hideHint)
        text.animate().cancel()
        text.alpha = 1f
        handler.postDelayed(hideHint, HINT_VISIBLE_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flashHint()
                handler.removeCallbacks(unlockRunnable)
                handler.postDelayed(unlockRunnable, UNLOCK_HOLD_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN -> handler.removeCallbacks(unlockRunnable)
        }
        true // swallow every touch
    }

    private fun buildParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Not focusable: TikTok keeps input focus and keeps playing.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Ask for minimum backlight (honoured on many devices, ignored on others).
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
