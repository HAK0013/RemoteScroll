package com.remotescroll.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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
        /** Gap between the two taps of a double tap (TikTok's "like"). */
        private const val DOUBLE_TAP_GAP_MS = 100L

        /**
         * Words in the label of TikTok's Save / Favorites button, in the languages TikTok
         * is most used in around here. Lower case.
         */
        private val SAVE_WORDS = listOf(
            "favorite", "favourite", "bookmark", "save",  // English
            "yêu thích", "lưu",                           // Vietnamese
            "รายการโปรด", "บันทึก",                         // Thai
            "kegemaran", "simpan", "favorit",             // Malay / Indonesian
            "收藏",                                        // Chinese
            "お気に入り", "즐겨찾기"                         // Japanese / Korean
        )

        /** Time for the window manager to apply FLAG_NOT_TOUCHABLE before we inject. */
        private const val PASS_THROUGH_SETTLE_MS = 60L
        /** Keep the overlay see-through a moment longer so the gesture's last events land. */
        private const val PASS_THROUGH_RESTORE_MS = 80L

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
    private val handler = Handler(Looper.getMainLooper())
    private var lockOverlay: LockOverlay? = null

    val isLocked: Boolean get() = lockOverlay?.isShowing == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        keyHandler = MediaKeyHandler(this)
        lockOverlay = LockOverlay(this).apply {
            onUnlockedByUser = { RemoteMediaService.refreshNotification(this@GestureAccessibilityService) }
        }
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
        handler.removeCallbacksAndMessages(null)
        lockOverlay?.hide()
        lockOverlay = null
        keyHandler?.release()
        keyHandler = null
    }

    // ------------------------------------------------------------- pocket lock

    /** Black, touch-blocking overlay on top of TikTok. See [LockOverlay]. */
    fun showLock() {
        lockOverlay?.show()
        RemoteMediaService.refreshNotification(this)
    }

    fun hideLock() {
        lockOverlay?.hide()
        RemoteMediaService.refreshNotification(this)
    }

    fun toggleLock() = if (isLocked) hideLock() else showLock()

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

    /** Two quick taps in the centre: TikTok likes the video. */
    fun doubleTapCenter() {
        val size = screenSize()
        val x = size.x / 2f
        val y = size.y / 2f
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0L, TAP_DURATION_MS))
            .addStroke(
                GestureDescription.StrokeDescription(
                    tapPath(x, y), TAP_DURATION_MS + DOUBLE_TAP_GAP_MS, TAP_DURATION_MS
                )
            )
            .build()
        dispatch(gesture, "double tap")
    }

    /**
     * Presses TikTok's Save / Favorites button on the video that is on screen. The button
     * moves between phones and TikTok versions, so it is found by its accessibility label
     * rather than by position. Works under pocket lock too: the click goes straight to the
     * button instead of through the screen.
     *
     * @return false if no such button is visible.
     */
    fun pressSaveButton(): Boolean {
        val screen = screenSize()
        val visibleArea = Rect(0, 0, screen.x, screen.y)
        val node = appRoots().firstNotNullOfOrNull { findSaveNode(it, visibleArea) }
            ?: return false

        // Click the button, or its nearest clickable parent (the label often sits on an
        // image inside a clickable layout).
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true

        // Fallback: tap the middle of the button.
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        dispatch(tapPath(bounds.exactCenterX(), bounds.exactCenterY()), TAP_DURATION_MS, "save tap")
        return true
    }

    /** Root nodes of the app windows on screen, the focused one (TikTok) first. */
    private fun appRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNullTo(roots) { it.root }
        return roots
    }

    /** Depth-first search for a visible node whose label matches [SAVE_WORDS]. */
    private fun findSaveNode(node: AccessibilityNodeInfo, visibleArea: Rect): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val label = (node.contentDescription ?: node.text)?.toString()?.lowercase()
        if (label != null && SAVE_WORDS.any { it in label }) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            // TikTok's side buttons are on the right half; skip off-screen videos.
            if (Rect.intersects(bounds, visibleArea) && bounds.centerX() > visibleArea.width() / 2) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSaveNode(child, visibleArea)?.let { return it }
        }
        return null
    }

    private fun tapPath(x: Float, y: Float) = Path().apply { moveTo(x, y) }

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

    private fun dispatch(path: Path, durationMs: Long, label: String) = dispatch(
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build(),
        label
    )

    private fun dispatch(gesture: GestureDescription, label: String) {
        val overlay = lockOverlay
        if (overlay == null || !overlay.isShowing) {
            dispatchNow(gesture, label) {}
            return
        }
        // Pocket lock is on: let this gesture fall through the overlay to TikTok.
        overlay.beginPassThrough()
        handler.postDelayed({
            dispatchNow(gesture, label) {
                handler.postDelayed({ overlay.endPassThrough() }, PASS_THROUGH_RESTORE_MS)
            }
        }, PASS_THROUGH_SETTLE_MS)
    }

    private fun dispatchNow(gesture: GestureDescription, label: String, onDone: () -> Unit) {
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "$label completed")
                onDone()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "$label cancelled")
                onDone()
            }
        }, handler)

        if (!accepted) {
            Log.w(TAG, "$label rejected by the system")
            onDone()
        }
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
