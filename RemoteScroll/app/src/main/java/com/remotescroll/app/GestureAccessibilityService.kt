package com.remotescroll.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Performs swipes and taps with dispatchGesture().
 *
 * It also filters key events. Remotes that connect as a Bluetooth HID keyboard send media
 * keys through the input pipeline, and this catches them before any app does. Remotes
 * that use AVRCP (most headphones and media remotes) reach [RemoteMediaService]'s
 * MediaSession instead. A key consumed here never reaches the MediaSession, so a press
 * is never handled twice.
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GestureA11yService"
        private const val SWIPE_DURATION_MS = 200L
        private const val TAP_DURATION_MS = 50L

        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        /** True if the user turned this service on in system Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, GestureAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }

    private var keyHandler: MediaKeyHandler? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        keyHandler = MediaKeyHandler(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only take over media keys while the user has RemoteScroll switched on.
        // Otherwise music apps etc. keep working normally.
        if (!RemoteMediaService.isRunning) return false
        return keyHandler?.onKeyEvent(event) ?: false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance === this) instance = null
        keyHandler?.release()
        keyHandler = null
    }

    // ---------------------------------------------------------------- gestures

    /** Finger moves from 70% to 30% of the height: TikTok shows the next video. */
    fun swipeUp() = verticalSwipe(fromFraction = 0.70f, toFraction = 0.30f)

    /** Finger moves from 30% to 70% of the height: TikTok shows the previous video. */
    fun swipeDown() = verticalSwipe(fromFraction = 0.30f, toFraction = 0.70f)

    /** Single tap in the centre: TikTok pauses/resumes. */
    fun tapCenter() {
        val size = screenSize()
        val path = Path().apply { moveTo(size.x / 2f, size.y / 2f) }
        dispatch(path, TAP_DURATION_MS, "tap")
    }

    private fun verticalSwipe(fromFraction: Float, toFraction: Float) {
        val size = screenSize()
        val x = size.x / 2f
        val path = Path().apply {
            moveTo(x, clampY(size.y * fromFraction, size.y))
            lineTo(x, clampY(size.y * toFraction, size.y))
        }
        dispatch(path, SWIPE_DURATION_MS, "swipe")
    }

    private fun clampY(y: Float, height: Int) = y.coerceIn(1f, (height - 1).toFloat())

    private fun dispatch(path: Path, durationMs: Long, label: String) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "$label completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "$label cancelled")
            }
        }, null)

        if (!accepted) Log.w(TAG, "$label rejected by the system")
    }

    /**
     * Current physical size of the default display, in pixels, for the current rotation.
     * Read again on every gesture, so it follows rotation and folding/unfolding.
     */
    private fun screenSize(): Point {
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) {
            val dm = resources.displayMetrics
            size.set(dm.widthPixels, dm.heightPixels)
        }
        return size
    }
}
