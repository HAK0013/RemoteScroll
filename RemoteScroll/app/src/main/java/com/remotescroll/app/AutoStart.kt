package com.remotescroll.app

import android.content.Context

/**
 * Auto-start setting: when one of the chosen remotes ([RemoteFilter]) connects over
 * Bluetooth, start the remote service and open the video app; when it disconnects, stop.
 * The Bluetooth events are received by [GestureAccessibilityService], which is always
 * running, so RemoteScroll doesn't need to be opened first.
 */
object AutoStart {
    private const val PREFS = "remote_scroll"
    private const val KEY_ENABLED = "auto_start"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
    }
}
