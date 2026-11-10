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
 *  - PLAY/PAUSE short      -> tap the centre (pause/resume), right away
 *  - PLAY/PAUSE held       -> like / pocket lock / save, see [HoldAction]: the phone beeps
 *                             1, 2 or 3 times as each hold time is reached, and releasing
 *                             the button runs the last one reached.
 *    or, in Press count style: 2 presses like, 3 pocket lock, 4 save (1, 2, 3 beeps as
 *                             the presses are counted). A burst ends after a quiet gap.
 *
 * Next/Previous long presses fire as soon as the threshold is reached while the key is still
 * held, so the user gets feedback without having to let go. The matching ACTION_UP is ignored.
 *
 * Used by both input paths (MediaSession in [RemoteMediaService] and key filtering in
 * [GestureAccessibilityService]). Must be called on the main thread.
 *
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

        private val VOLUME_HOLD_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )

        val HANDLED_KEYS = VOLUME_HOLD_KEYS + PLAY_PAUSE_KEYS
    }

    private class PressState(val downTime: Long) {
        var longFired = false
        /** Next/Previous: fires the volume step. Play/Pause: one runnable per hold action. */
        val timers = mutableListOf<Runnable>()
        /** Play/Pause: last hold action whose time has been reached. */
        var reached: HoldAction? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val presses = HashMap<Int, PressState>()

    // Press count style
    private var pressCount = 0
    private val finishPresses = Runnable { finishPressCount() }

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
        if (keyCode in PLAY_PAUSE_KEYS && HoldAction.countStyle(context)) countPress() else onShortPress(keyCode)
    }

    fun release() {
        handler.removeCallbacks(finishPresses)
        pressCount = 0
        presses.values.forEach { state -> state.timers.forEach { handler.removeCallbacks(it) } }
        presses.clear()
    }

    private fun onDown(event: KeyEvent) {
        val code = event.keyCode
        // Auto-repeat DOWN events while the key is held: we time the hold ourselves.
        if (event.repeatCount > 0 || presses.containsKey(code)) return

        val state = PressState(eventTime(event))
        presses[code] = state

        if (code in PLAY_PAUSE_KEYS && HoldAction.countStyle(context)) {
            countPress()
            return
        }
        if (code in PLAY_PAUSE_KEYS) {
            // Beep as each hold time is passed, so the user knows when to let go.
            for ((action, ms) in HoldAction.timeline(context)) {
                val runnable = Runnable {
                    state.reached = action
                    FeedbackSound.beeps(action.beeps)
                    KeyLog.note("hold ${ms / 1000} s: ${context.getString(action.labelRes)} (${action.beeps} beep) - release now")
                }
                state.timers += runnable
                handler.postDelayed(runnable, ms)
            }
        } else {
            val runnable = Runnable {
                state.longFired = true
                onVolumeHold(code)
            }
            state.timers += runnable
            handler.postDelayed(runnable, LONG_PRESS_MS)
        }
    }

    private fun onUp(event: KeyEvent) {
        val code = event.keyCode
        val state = presses.remove(code)

        val counting = code in PLAY_PAUSE_KEYS && HoldAction.countStyle(context)
        if (state == null) {
            // UP without a DOWN (some remotes and stacks only deliver UP): treat as a press.
            if (counting) countPress() else onShortPress(code)
            return
        }
        state.timers.forEach { handler.removeCallbacks(it) }
        if (counting) {
            // The press was counted on DOWN. A late UP still restarts the quiet gap, so a
            // next press that the system holds back behind this UP joins the same burst.
            if (pressCount > 0) restartGap()
            return
        }

        // Events can arrive late and in a batch, so also use the event timestamps.
        val heldFor = eventTime(event) - state.downTime

        if (code in PLAY_PAUSE_KEYS) {
            val action = state.reached
                ?: HoldAction.timeline(context).lastOrNull { heldFor >= it.second }?.first
            if (action == null) onShortPress(code) else runHoldAction(action)
            return
        }

        if (state.longFired) return
        if (heldFor > LONG_PRESS_MS) onVolumeHold(code) else onShortPress(code)
    }

    /** Press count style: one more Play/Pause press in the current burst. */
    private fun countPress() {
        pressCount++
        val action = HoldAction.forPressCount(pressCount)
        if (action != null) {
            FeedbackSound.beeps(action.beeps)
            KeyLog.note("press $pressCount: ${context.getString(action.labelRes)} (${action.beeps} beep)")
        }
        if (action == HoldAction.SAVE) finishPressCount() else restartGap()
    }

    private fun restartGap() {
        handler.removeCallbacks(finishPresses)
        handler.postDelayed(finishPresses, HoldAction.gapMs(context))
    }

    private fun finishPressCount() {
        handler.removeCallbacks(finishPresses)
        val count = pressCount
        pressCount = 0
        if (count == 0) return
        val action = HoldAction.forPressCount(count)
        if (action == null) onShortPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else runHoldAction(action)
    }

    private fun eventTime(event: KeyEvent): Long =
        if (event.eventTime > 0) event.eventTime else SystemClock.uptimeMillis()

    private fun onShortPress(code: Int) {
        val label = when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> "=> next video"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "=> previous video"
            else -> "=> pause/resume"
        }
        if (KeyLog.testing) {
            KeyLog.note("$label  (test only)")
            return
        }
        val service = serviceOrWarn() ?: return
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> service.swipeUp()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> service.swipeDown()
            in PLAY_PAUSE_KEYS -> service.tapCenter()
        }
    }

    private fun runHoldAction(action: HoldAction) {
        KeyLog.note("=> ${context.getString(action.labelRes)}" + if (KeyLog.testing) "  (test only)" else "")
        if (KeyLog.testing) return
        val service = serviceOrWarn() ?: return
        when (action) {
            HoldAction.LIKE -> service.doubleTapCenter()
            HoldAction.LOCK -> service.toggleLock()
            HoldAction.SAVE -> if (!service.pressSaveButton()) {
                Toast.makeText(context, R.string.toast_save_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onVolumeHold(code: Int) {
        val up = code == KeyEvent.KEYCODE_MEDIA_NEXT
        if (KeyLog.testing) {
            KeyLog.note(if (up) "=> hold: volume up  (test only)" else "=> hold: volume down  (test only)")
            return
        }
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun serviceOrWarn(): GestureAccessibilityService? {
        val service = GestureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not connected; cannot dispatch gesture")
            Toast.makeText(context, R.string.toast_enable_accessibility, Toast.LENGTH_SHORT).show()
        }
        return service
    }
}
