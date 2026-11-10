package com.remotescroll.app

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Remote test: a short log of the raw button events the app receives, with the time since
 * the previous one. Shown in [MainActivity] so the user can see what their remote really
 * sends (which key codes, DOWN/UP pairs, and how much Bluetooth delays each press).
 *
 * While [testing] is true (RemoteScroll's own screen is open), keys are only logged:
 * no taps or swipes are made, so the test can't press buttons in this app.
 * Main thread only.
 */
object KeyLog {

    private const val MAX_LINES = 30
    private const val PREFS = "remote_scroll"
    private const val KEY_CLICK_WINDOW = "click_window_ms"
    const val DEFAULT_CLICK_WINDOW_MS = 500
    const val MIN_CLICK_WINDOW_MS = 250
    const val MAX_CLICK_WINDOW_MS = 1500

    var testing = false

    var listener: (() -> Unit)? = null

    private val lines = ArrayDeque<String>()
    private var lastTime = 0L

    fun text(): String = lines.joinToString("\n")

    fun clear() {
        lines.clear()
        lastTime = 0L
        listener?.invoke()
    }

    fun key(event: KeyEvent, via: String) {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) "DOWN(repeat)" else "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "action ${event.action}"
        }
        add("${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")} $action  [$via]")
    }

    fun note(message: String) = add(message)

    private fun add(message: String) {
        val now = SystemClock.uptimeMillis()
        val gap = if (lastTime == 0L) "" else "+${now - lastTime} ms  "
        lastTime = now
        lines.addFirst(gap + message)
        while (lines.size > MAX_LINES) lines.removeLast()
        listener?.invoke()
    }

    // ------------------------------------------------------------ click window setting

    /** How long to wait for another Play/Pause click before acting (1, 2 or 3 clicks). */
    fun clickWindowMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLICK_WINDOW, DEFAULT_CLICK_WINDOW_MS).toLong()

    fun setClickWindowMs(context: Context, ms: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLICK_WINDOW, ms.coerceIn(MIN_CLICK_WINDOW_MS, MAX_CLICK_WINDOW_MS))
            .apply()
    }
}
