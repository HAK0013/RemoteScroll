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
 *  - PLAY/PAUSE 3 times    -> press TikTok's Save / Favorites button
 *  - PLAY/PAUSE held       -> pocket lock on/off (see [LockOverlay])
 *
 * A long press fires as soon as the threshold is reached while the key is still held,
 * so the user gets feedback without having to let go. The matching ACTION_UP is then ignored.
 *
 * Play/Pause clicks are counted on ACTION_DOWN, not ACTION_UP: Bluetooth often delivers the
 * UP late, which made fast double/triple clicks fall apart. A burst ends when no new press
 * starts within the click window; if a key is still held then, we wait for its release
 * (or for the hold to become a long press).
 *
 * Used by both input paths (MediaSession in [RemoteMediaService] and key filtering in
 * [GestureAccessibilityService]). Must be called on the main thread.
 */
/**
 * @param via name of the input path, shown in the Remote test log.
 * @param swallowIgnored what to do with a press from a remote the user did not choose:
 *   true = consume it silently (MediaSession path: otherwise the session would still
 *   act on it through its transport callbacks); false = pass it on (key filter path).
 */
class MediaKeyHandler(
    private val context: Context,
    private val via: String,
    private val swallowIgnored: Boolean
) {

    companion object {
        private const val TAG = "MediaKeyHandler"
        const val LONG_PRESS_MS = 500L

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
        /** Play/Pause only: this press was already counted as a click on DOWN. */
        var counted = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val presses = HashMap<Int, PressState>()

    /** Play/Pause clicks so far in the current burst, acted on when the burst ends. */
    private var clickCount = 0
    /** The click window ran out while Play/Pause was still held: act on its release. */
    private var finishOnRelease = false
    private val finishClicks = Runnable {
        if (presses.keys.any { it in PLAY_PAUSE_KEYS }) finishOnRelease = true else fireClicks()
    }

    fun handles(keyCode: Int): Boolean = keyCode in HANDLED_KEYS

    /** @return true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!handles(event.keyCode)) return false
        KeyLog.key(event, via)
        if (!RemoteFilter.isAllowed(context, event)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                KeyLog.note("=> ignored: ${RemoteFilter.deviceName(event)} is not a chosen remote")
            }
            return swallowIgnored
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onDown(event)
            KeyEvent.ACTION_UP -> onUp(event)
        }
        return true
    }

    /** Short-press entry point for transport-control callbacks that have no DOWN/UP pair. */
    fun performShortPress(keyCode: Int) {
        KeyLog.note("${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")} (transport control)  [$via]")
        onShortPress(keyCode)
    }

    fun release() {
        resetClicks()
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

        if (code in PLAY_PAUSE_KEYS) {
            state.counted = true
            onPlayPauseClick()
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

        if (state.counted) {
            // Click already counted on DOWN. Only finish a burst that was waiting for this.
            if (finishOnRelease) fireClicks()
            return
        }

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
        if (code in PLAY_PAUSE_KEYS) {
            onPlayPauseClick()
            return
        }
        if (KeyLog.testing) {
            KeyLog.note(if (code == KeyEvent.KEYCODE_MEDIA_NEXT) "=> next video" else "=> previous video")
            return
        }
        val service = GestureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not connected; cannot dispatch gesture")
            Toast.makeText(context, R.string.toast_enable_accessibility, Toast.LENGTH_SHORT).show()
            return
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> service.swipeUp()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> service.swipeDown()
        }
    }

    /**
     * Counts clicks that come within the click window (a setting, see [KeyLog]) of each other:
     * 1 = pause/resume, 2 = like, 3 = save. One and two clicks wait out that window,
     * because another click might still follow; the third click acts at once.
     */
    private fun onPlayPauseClick() {
        handler.removeCallbacks(finishClicks)
        clickCount++
        if (clickCount >= 3) {
            // Third click: act now, even if the key is still down.
            fireClicks()
        } else {
            handler.postDelayed(finishClicks, KeyLog.clickWindowMs(context))
        }
    }

    private fun fireClicks() {
        val count = clickCount
        resetClicks()
        if (count == 0) return
        KeyLog.note(
            when (count) {
                1 -> "=> 1 click: pause/resume"
                2 -> "=> 2 clicks: like"
                else -> "=> 3 clicks: save"
            } + if (KeyLog.testing) "  (test only)" else ""
        )
        if (KeyLog.testing) return
        val service = GestureAccessibilityService.instance
        if (service == null) {
            Toast.makeText(context, R.string.toast_enable_accessibility, Toast.LENGTH_SHORT).show()
            return
        }
        when (count) {
            1 -> service.tapCenter()
            2 -> service.doubleTapCenter()
            else -> if (!service.pressSaveButton()) {
                Toast.makeText(context, R.string.toast_save_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetClicks() {
        handler.removeCallbacks(finishClicks)
        clickCount = 0
        finishOnRelease = false
    }

    private fun onLongPress(code: Int) {
        if (code in PLAY_PAUSE_KEYS) {
            // A hold: its own DOWN was counted as a click, so drop the burst.
            resetClicks()
            KeyLog.note("=> hold: pocket lock on/off" + if (KeyLog.testing) "  (test only)" else "")
            if (KeyLog.testing) return
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
        if (KeyLog.testing) {
            KeyLog.note(if (direction == AudioManager.ADJUST_RAISE) "=> hold: volume up" else "=> hold: volume down")
            return
        }
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }
}
