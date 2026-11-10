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
        const val MAX_SECONDS = 15

        private fun prefs(context: Context) =
            context.getSharedPreferences("remote_scroll", Context.MODE_PRIVATE)

        /** All actions with their hold time in ms, shortest first. */
        fun timeline(context: Context): List<Pair<HoldAction, Long>> =
            values().map { it to it.seconds(context) * 1000L }.sortedBy { it.second }
    }
}
