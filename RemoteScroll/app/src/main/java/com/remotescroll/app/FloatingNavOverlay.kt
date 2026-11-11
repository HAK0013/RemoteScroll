package com.remotescroll.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Floating on-screen navigation buttons (Next & Back) that can be positioned anywhere
 * on the screen. Gives the user direct thumb buttons right where they want them.
 */
class FloatingNavOverlay(private val service: GestureAccessibilityService) {

    companion object {
        private const val TAG = "FloatingNavOverlay"
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = rootView != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return

        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val (savedX, savedY) = GesturePoints.getNavNextPos(service, screenW - 140, screenH / 2)

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Back / Previous Button (▲)
        val btnBack = TextView(service).apply {
            text = "▲"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_mini_btn)
            setPadding(28, 22, 28, 22)
            setOnClickListener {
                service.swipeDown()
            }
        }

        // Next Button (▼)
        val btnNext = TextView(service).apply {
            text = "▼"
            setTextColor(Color.parseColor("#00D2FF"))
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_mini_btn)
            setPadding(28, 22, 28, 22)
            setOnClickListener {
                service.swipeUp()
            }
        }

        val spacer = View(service).apply {
            layoutParams = LinearLayout.LayoutParams(1, 16)
        }

        container.addView(btnBack)
        container.addView(spacer)
        container.addView(btnNext)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        attachDragger(container, lp, screenW, screenH)

        try {
            windowManager.addView(container, lp)
            rootView = container
            windowParams = lp
            Log.i(TAG, "Floating nav buttons overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating nav overlay", e)
        }
    }

    fun hide() {
        val root = rootView ?: return
        try {
            windowManager.removeView(root)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove floating nav overlay", e)
        }
        rootView = null
        windowParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragger(targetView: View, lp: WindowManager.LayoutParams, screenW: Int, screenH: Int) {
        var startX = 0
        var startY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var hasDragged = false
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

        targetView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    hasDragged = false
                    false // allow children to receive click if not dragged
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (!hasDragged && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        hasDragged = true
                    }
                    if (hasDragged) {
                        val newX = (startX + dx).coerceIn(0, maxOf(0, screenW - targetView.width))
                        val newY = (startY + dy).coerceIn(0, maxOf(0, screenH - targetView.height))
                        lp.x = newX
                        lp.y = newY
                        GesturePoints.setNavNextPos(service, newX, newY)
                        try {
                            windowManager.updateViewLayout(targetView, lp)
                        } catch (_: Exception) {}
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = hasDragged
                    hasDragged = false
                    wasDragging
                }
                else -> false
            }
        }
    }
}
