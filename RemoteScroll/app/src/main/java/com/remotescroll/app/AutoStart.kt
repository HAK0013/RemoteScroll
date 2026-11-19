package com.remotescroll.app

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

/**
 * Auto-start settings.
 *
 * - [isEnabled]: when one of the chosen remotes ([RemoteFilter]) connects over Bluetooth,
 *   start the remote service and open the video app; when it disconnects, stop.
 * - [isAlwaysOn]: start the remote service by itself, without pressing Start, as soon as
 *   TikTok (or another supported [VideoApp]) is opened.
 *
 * Both are driven by [GestureAccessibilityService], which is always running, so
 * RemoteScroll doesn't need to be opened first.
 *
 * If the user stops the remote by hand, [isAlwaysOn] leaves it off until they start it
 * again or the phone restarts, so Stop always means stop.
 */
object AutoStart {
    private const val PREFS = "remote_scroll"
    private const val KEY_ENABLED = "auto_start"
    private const val KEY_ALWAYS_ON = "auto_start_always"
    private const val KEY_STOPPED_BY_USER_BOOT = "auto_start_stopped_boot"

    /** Two boot-time estimates closer than this are the same boot (clock drift / adjustments). */
    private const val SAME_BOOT_TOLERANCE_MS = 60_000L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
    }

    fun isAlwaysOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALWAYS_ON, true)

    fun setAlwaysOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ALWAYS_ON, on).apply()
    }

    /** Wall-clock time the phone booted, approximately. */
    private fun bootTimeMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** The user pressed Stop: keep auto-start away until they start it or the phone restarts. */
    fun markStoppedByUser(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STOPPED_BY_USER_BOOT, bootTimeMillis()).apply()
    }

    fun clearStoppedByUser(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_STOPPED_BY_USER_BOOT).apply()
    }

    /** True if the user stopped the remote by hand since the phone last started. */
    fun wasStoppedByUser(context: Context): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_STOPPED_BY_USER_BOOT, 0L)
        return at != 0L && abs(at - bootTimeMillis()) < SAME_BOOT_TOLERANCE_MS
    }

    /** Whether [isAlwaysOn] should start the remote right now. */
    fun shouldAutoStart(context: Context): Boolean =
        isAlwaysOn(context) && !RemoteMediaService.isRunning && !wasStoppedByUser(context)
}
