package com.remotescroll.app

import android.content.Context
import android.graphics.PointF

/**
 * Manages user-configured screen coordinate points for gestures (Next swipe, Back swipe,
 * Tap/Like, 2x Turbo, and Save button), as well as floating navigation buttons positions.
 * Coordinates are stored as screen fractions (0.0f .. 1.0f) so they adapt to screen rotation
 * and display resolution.
 */
object GesturePoints {

    private const val PREFS = "remote_scroll"

    const val DEFAULT_SWIPE_X = 0.50f
    const val DEFAULT_NEXT_START_Y = 0.70f
    const val DEFAULT_NEXT_END_Y = 0.30f
    const val DEFAULT_BACK_START_Y = 0.30f
    const val DEFAULT_BACK_END_Y = 0.70f
    const val DEFAULT_TAP_X = 0.50f
    const val DEFAULT_TAP_Y = 0.50f
    const val DEFAULT_TURBO_X = 0.25f
    const val DEFAULT_TURBO_Y = 0.50f
    const val DEFAULT_SWIPE_DURATION_MS = 200L

    private const val KEY_SWIPE_X = "gesture_swipe_x"
    private const val KEY_NEXT_START_Y = "gesture_next_start_y"
    private const val KEY_NEXT_END_Y = "gesture_next_end_y"
    private const val KEY_BACK_START_Y = "gesture_back_start_y"
    private const val KEY_BACK_END_Y = "gesture_back_end_y"
    private const val KEY_TAP_X = "gesture_tap_x"
    private const val KEY_TAP_Y = "gesture_tap_y"
    private const val KEY_TURBO_X = "gesture_turbo_x"
    private const val KEY_TURBO_Y = "gesture_turbo_y"
    private const val KEY_SWIPE_DURATION_MS = "gesture_swipe_duration_ms"

    private const val KEY_FLOATING_NAV = "floating_nav_buttons_enabled"
    private const val KEY_NAV_NEXT_X = "nav_next_x"
    private const val KEY_NAV_NEXT_Y = "nav_next_y"
    private const val KEY_NAV_BACK_X = "nav_back_x"
    private const val KEY_NAV_BACK_Y = "nav_back_y"

    fun getSwipeX(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SWIPE_X, DEFAULT_SWIPE_X)

    fun setSwipeX(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SWIPE_X, value.coerceIn(0.05f, 0.95f)).apply()
    }

    fun getNextPoints(context: Context): Pair<PointF, PointF> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = sp.getFloat(KEY_SWIPE_X, DEFAULT_SWIPE_X)
        val startY = sp.getFloat(KEY_NEXT_START_Y, DEFAULT_NEXT_START_Y)
        val endY = sp.getFloat(KEY_NEXT_END_Y, DEFAULT_NEXT_END_Y)
        return PointF(x, startY) to PointF(x, endY)
    }

    fun setNextPoints(context: Context, startY: Float, endY: Float, x: Float = getSwipeX(context)) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SWIPE_X, x.coerceIn(0.05f, 0.95f))
            .putFloat(KEY_NEXT_START_Y, startY.coerceIn(0.05f, 0.95f))
            .putFloat(KEY_NEXT_END_Y, endY.coerceIn(0.05f, 0.95f))
            .apply()
    }

    fun getBackPoints(context: Context): Pair<PointF, PointF> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = sp.getFloat(KEY_SWIPE_X, DEFAULT_SWIPE_X)
        val startY = sp.getFloat(KEY_BACK_START_Y, DEFAULT_BACK_START_Y)
        val endY = sp.getFloat(KEY_BACK_END_Y, DEFAULT_BACK_END_Y)
        return PointF(x, startY) to PointF(x, endY)
    }

    fun setBackPoints(context: Context, startY: Float, endY: Float, x: Float = getSwipeX(context)) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SWIPE_X, x.coerceIn(0.05f, 0.95f))
            .putFloat(KEY_BACK_START_Y, startY.coerceIn(0.05f, 0.95f))
            .putFloat(KEY_BACK_END_Y, endY.coerceIn(0.05f, 0.95f))
            .apply()
    }

    fun getTapPoint(context: Context): PointF {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PointF(
            sp.getFloat(KEY_TAP_X, DEFAULT_TAP_X),
            sp.getFloat(KEY_TAP_Y, DEFAULT_TAP_Y)
        )
    }

    fun setTapPoint(context: Context, x: Float, y: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TAP_X, x.coerceIn(0.05f, 0.95f))
            .putFloat(KEY_TAP_Y, y.coerceIn(0.05f, 0.95f))
            .apply()
    }

    fun getTurboPoint(context: Context): PointF {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PointF(
            sp.getFloat(KEY_TURBO_X, DEFAULT_TURBO_X),
            sp.getFloat(KEY_TURBO_Y, DEFAULT_TURBO_Y)
        )
    }

    fun setTurboPoint(context: Context, x: Float, y: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TURBO_X, x.coerceIn(0.05f, 0.95f))
            .putFloat(KEY_TURBO_Y, y.coerceIn(0.05f, 0.95f))
            .apply()
    }

    fun getSwipeDuration(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SWIPE_DURATION_MS, DEFAULT_SWIPE_DURATION_MS)

    fun setSwipeDuration(context: Context, durationMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SWIPE_DURATION_MS, durationMs.coerceIn(100L, 500L))
            .apply()
    }

    fun isFloatingNavEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLOATING_NAV, false)

    fun setFloatingNavEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FLOATING_NAV, enabled)
            .apply()
    }

    fun getNavNextPos(context: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getInt(KEY_NAV_NEXT_X, defaultX) to sp.getInt(KEY_NAV_NEXT_Y, defaultY)
    }

    fun setNavNextPos(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAV_NEXT_X, x)
            .putInt(KEY_NAV_NEXT_Y, y)
            .apply()
    }

    fun getNavBackPos(context: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getInt(KEY_NAV_BACK_X, defaultX) to sp.getInt(KEY_NAV_BACK_Y, defaultY)
    }

    fun setNavBackPos(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAV_BACK_X, x)
            .putInt(KEY_NAV_BACK_Y, y)
            .apply()
    }

    fun resetToDefaults(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SWIPE_X)
            .remove(KEY_NEXT_START_Y)
            .remove(KEY_NEXT_END_Y)
            .remove(KEY_BACK_START_Y)
            .remove(KEY_BACK_END_Y)
            .remove(KEY_TAP_X)
            .remove(KEY_TAP_Y)
            .remove(KEY_TURBO_X)
            .remove(KEY_TURBO_Y)
            .remove(KEY_SWIPE_DURATION_MS)
            .apply()
    }
}
