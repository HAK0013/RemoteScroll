package com.remotescroll.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

/**
 * Turns raw media-key events (ACTION_DOWN / ACTION_UP) into actions.
 *
 *  - NEXT short press      -> swipe up (next video)
 *  - PREVIOUS short press  -> swipe down (previous video)
 *  - NEXT held > 500 ms    -> media volume +1 step
 *  - PREVIOUS held > 500ms -> media volume -1 step
 *  - PLAY/PAUSE            -> tap the centre of the screen (pause/resume)
 *  - PLAY/PAUSE twice      -> double-tap the centre (like the video)
 *  - PLAY/PAUSE held       -> pocket lock on/off (see [LockOverlay])
 *
 * A long press fires as soon as the threshold is reached while the key is still held,
 * so the user gets feedback without having to let go. The matching ACTION_UP is then ignored.
 *
 * Used by both input paths (MediaSession in [RemoteMediaService] and key filtering in
 * [GestureAccessibilityService]). Must be called on the main thread.
 */
class MediaKeyHandler(private val context: Context) {

    companion object {
        private const val TAG = "MediaKeyHandler"
        const val LONG_PRESS_MS = 500L
        /** A second Play/Pause press within this window counts as a double click. */
        const val DOUBLE_CLICK_MS = 350L

        /** Play/pause variants: different remotes send different codes for the same button. */
        private val PLAY_PAUSE_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK
        )

        private val LONG_PRESS_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ) + PLAY_PAUSE_KEYS

        val HANDLED_KEYS = LONG_PRESS_KEYS
    }

    private class PressState(val downTime: Long) {
        var longFired = false
        var longPressRunnable: Runnable? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val presses = HashMap<Int, PressState>()

    /** Pending single Play/Pause action, cancelled if a second press arrives in time. */
    private var pendingSingleTap: Runnable? = null

    fun handles(keyCode: Int): Boolean = keyCode in HANDLED_KEYS

    /** @return true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!handles(event.keyCode)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(event)
            KeyEvent.ACTION_UP -> onUp(event)
        }
        return true
    }

    /** Short-press entry point for transport-control callbacks that have no DOWN/UP pair. */
    fun performShortPress(keyCode: Int) = onShortPress(keyCode)

    fun release() {
        pendingSingleTap?.let { handler.removeCallbacks(it) }
        pendingSingleTap = null
        presses.values.forEach { state -> state.longPressRunnable?.let { handler.removeCallbacks(it) } }
        presses.clear()
    }

    private fun onDown(event: KeyEvent) {
        val code = event.keyCode
        // Auto-repeat DOWN events while the key is held: we time the hold ourselves.
        if (event.repeatCount > 0 || presses.containsKey(code)) return

        val state = PressState(eventTime(event))
        presses[code] = state

        if (code in LONG_PRESS_KEYS) {
            val runnable = Runnable {
                state.longFired = true
                onLongPress(code)
            }
            state.longPressRunnable = runnable
            handler.postDelayed(runnable, LONG_PRESS_MS)
        }
    }

    private fun onUp(event: KeyEvent) {
        val code = event.keyCode
        val state = presses.remove(code)

        if (state == null) {
            // UP without a DOWN (some remotes and stacks only deliver UP): treat as a short press.
            onShortPress(code)
            return
        }

        state.longPressRunnable?.let { handler.removeCallbacks(it) }
        if (state.longFired) return

        // Events can arrive late and in a batch, so use the event timestamps.
        val heldFor = eventTime(event) - state.downTime
        if (code in LONG_PRESS_KEYS && heldFor > LONG_PRESS_MS) {
            onLongPress(code)
        } else {
            onShortPress(code)
        }
    }

    private fun eventTime(event: KeyEvent): Long =
        if (event.eventTime > 0) event.eventTime else SystemClock.uptimeMillis()

    private fun onShortPress(code: Int) {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not connected; cannot dispatch gesture")
            Toast.makeText(context, R.string.toast_enable_accessibility, Toast.LENGTH_SHORT).show()
            return
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> service.swipeUp()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> service.swipeDown()
            in PLAY_PAUSE_KEYS -> onPlayPauseClick()
        }
    }

    /**
     * Single click waits [DOUBLE_CLICK_MS] for a second click: one click pauses/resumes,
     * two clicks like the video. (The single action is delayed by that window.)
     */
    private fun onPlayPauseClick() {
        val pending = pendingSingleTap
        if (pending != null) {
            handler.removeCallbacks(pending)
            pendingSingleTap = null
            GestureAccessibilityService.instance?.doubleTapCenter()
            return
        }
        val single = Runnable {
            pendingSingleTap = null
            GestureAccessibilityService.instance?.tapCenter()
        }
        pendingSingleTap = single
        handler.postDelayed(single, DOUBLE_CLICK_MS)
    }

    private fun onLongPress(code: Int) {
        if (code in PLAY_PAUSE_KEYS) {
            // A hold right after a click: drop the pending single tap.
            pendingSingleTap?.let { handler.removeCallbacks(it) }
            pendingSingleTap = null
            val service = GestureAccessibilityService.instance
            if (service == null) {
                Toast.makeText(context, R.string.toast_enable_accessibility, Toast.LENGTH_SHORT).show()
            } else {
                service.toggleLock()
            }
            return
        }
        val direction = when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> AudioManager.ADJUST_LOWER
            else -> return
        }
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }
}
