package com.remotescroll.app

import android.content.Context

/**
 * Play/Pause hold actions. Holding the button passes each action's time in turn; at each
 * one the phone beeps that action's number of times, and letting go runs the last action
 * reached. A short press (released before the first time) pauses/resumes.
 *
 * Times are user settings (seconds). Defaults: like 3 s, pocket lock 5 s, save 7 s.
 */
enum class HoldAction(
    val beeps: Int,
    private val prefKey: String,
    val defaultSeconds: Int,
    val labelRes: Int
) {
    LIKE(1, "hold_like_s", 3, R.string.hold_like),
    LOCK(2, "hold_lock_s", 5, R.string.hold_lock),
    SAVE(3, "hold_save_s", 7, R.string.hold_save);

    fun seconds(context: Context): Int =
        prefs(context).getInt(prefKey, defaultSeconds).coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun setSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(prefKey, seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)).apply()
    }

    companion object {
        const val MIN_SECONDS = 1
        private const val KEY_STYLE = "play_style"
        private const val KEY_GAP = "press_gap_ms"
        private const val KEY_COUNT_LOCK_HOLD = "count_lock_hold_ms"
        const val DEFAULT_COUNT_LOCK_HOLD_MS = 1000
        const val MIN_COUNT_LOCK_HOLD_MS = 500
        const val MAX_COUNT_LOCK_HOLD_MS = 5000

        private const val KEY_COUNT_HOLD_ACTION = "count_hold_action"

        /** Press count style: what a Play/Pause hold does (default pocket lock). */
        fun countHoldAction(context: Context): HoldAction {
            val name = prefs(context).getString(KEY_COUNT_HOLD_ACTION, LOCK.name)
            return values().firstOrNull { it.name == name } ?: LOCK
        }

        fun setCountHoldAction(context: Context, action: HoldAction) {
            prefs(context).edit().putString(KEY_COUNT_HOLD_ACTION, action.name).apply()
        }

        /** Press count style: how long to hold Play/Pause for [countHoldAction]. */
        fun countLockHoldMs(context: Context): Long =
            prefs(context).getInt(KEY_COUNT_LOCK_HOLD, DEFAULT_COUNT_LOCK_HOLD_MS)
                .coerceIn(MIN_COUNT_LOCK_HOLD_MS, MAX_COUNT_LOCK_HOLD_MS).toLong()

        fun setCountLockHoldMs(context: Context, ms: Int) {
            prefs(context).edit().putInt(
                KEY_COUNT_LOCK_HOLD, ms.coerceIn(MIN_COUNT_LOCK_HOLD_MS, MAX_COUNT_LOCK_HOLD_MS)
            ).apply()
        }
        const val DEFAULT_GAP_MS = 1000
        const val MIN_GAP_MS = 400
        const val MAX_GAP_MS = 3000

        /**
         * true = Press count style (2 presses like, 3 lock, 4 save, hold = lock);
         * false = Hold style. Press count suits remotes that end every hold early
         * (e.g. after about 1.5 s), where long holds can't be reached.
         */
        fun countStyle(context: Context): Boolean =
            // Until the user picks, keep what earlier versions chose with the key filter on.
            prefs(context).getBoolean(KEY_STYLE, KeyLog.useKeyFilter(context))

        fun setCountStyle(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean(KEY_STYLE, on).apply()
        }

        /** Press count style: quiet time after the last signal that ends a burst of presses. */
        fun gapMs(context: Context): Long =
            prefs(context).getInt(KEY_GAP, DEFAULT_GAP_MS).coerceIn(MIN_GAP_MS, MAX_GAP_MS).toLong()

        fun setGapMs(context: Context, ms: Int) {
            prefs(context).edit().putInt(KEY_GAP, ms.coerceIn(MIN_GAP_MS, MAX_GAP_MS)).apply()
        }

        /** Press count style: action for [presses] presses (1 = pause, so null). */
        fun forPressCount(presses: Int): HoldAction? = when {
            presses <= 1 -> null
            presses == 2 -> LIKE
            presses == 3 -> LOCK
            else -> SAVE
        }

        const val MAX_SECONDS = 15

        private fun prefs(context: Context) =
            context.getSharedPreferences("remote_scroll", Context.MODE_PRIVATE)

        /** All actions with their hold time in ms, shortest first. */
        fun timeline(context: Context): List<Pair<HoldAction, Long>> =
            values().map { it to it.seconds(context) * 1000L }.sortedBy { it.second }
    }
}
