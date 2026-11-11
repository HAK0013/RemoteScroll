package com.remotescroll.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Interactive full-screen calibration overlay that allows the user to visually drag and position
 * the gesture points for Next (Swipe Up start/end), Back (Swipe Down), and Tap/Like on screen.
 */
class PointsCalibrationOverlay(private val service: GestureAccessibilityService) {

    companion object {
        private const val TAG = "PointsCalibration"
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var rootView: FrameLayout? = null
    private var guideView: GuideCanvasView? = null

    // Normalized screen coordinates (0.0f .. 1.0f)
    private var swipeX = 0.50f
    private var nextStartY = 0.70f
    private var nextEndY = 0.30f
    private var tapX = 0.50f
    private var tapY = 0.50f

    val isShowing: Boolean get() = rootView != null

    fun show() {
        if (rootView != null) return

        swipeX = GesturePoints.getSwipeX(service)
        val (nextStart, nextEnd) = GesturePoints.getNextPoints(service)
        nextStartY = nextStart.y
        nextEndY = nextEnd.y
        val tap = GesturePoints.getTapPoint(service)
        tapX = tap.x
        tapY = tap.y

        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val root = FrameLayout(service).apply {
            setBackgroundColor(Color.argb(160, 11, 15, 25)) // semi-transparent dark cyber background
        }

        guideView = GuideCanvasView(service).also {
            root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        // Top Control Header
        val header = createHeader(screenW, screenH)
        root.addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            topMargin = 40
            leftMargin = 20
            rightMargin = 20
        })

        // Draggable Target Markers
        val markerNextStart = createTargetMarker("🔽 Next Start", Color.parseColor("#00D2FF")) { x, y ->
            swipeX = (x / screenW).coerceIn(0.05f, 0.95f)
            nextStartY = (y / screenH).coerceIn(0.05f, 0.95f)
            guideView?.invalidate()
        }
        val markerNextEnd = createTargetMarker("▲ Next End", Color.parseColor("#38BDF8")) { x, y ->
            swipeX = (x / screenW).coerceIn(0.05f, 0.95f)
            nextEndY = (y / screenH).coerceIn(0.05f, 0.95f)
            guideView?.invalidate()
        }
        val markerTap = createTargetMarker("🎯 Tap / Like", Color.parseColor("#F59E0B")) { x, y ->
            tapX = (x / screenW).coerceIn(0.05f, 0.95f)
            tapY = (y / screenH).coerceIn(0.05f, 0.95f)
            guideView?.invalidate()
        }

        root.addView(markerNextStart)
        root.addView(markerNextEnd)
        root.addView(markerTap)

        // Position markers initially
        positionMarkerView(markerNextStart, (swipeX * screenW).toInt(), (nextStartY * screenH).toInt())
        positionMarkerView(markerNextEnd, (swipeX * screenW).toInt(), (nextEndY * screenH).toInt())
        positionMarkerView(markerTap, (tapX * screenW).toInt(), (tapY * screenH).toInt())

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(root, lp)
            rootView = root
            Log.i(TAG, "Calibration overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show calibration overlay", e)
        }
    }

    fun hide() {
        val root = rootView ?: return
        try {
            windowManager.removeView(root)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove calibration overlay", e)
        }
        rootView = null
        guideView = null
    }

    private fun createHeader(screenW: Int, screenH: Int): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6151C2C"))
            setPadding(30, 24, 30, 24)

            val title = TextView(service).apply {
                text = "🎯 GESTURE POSITION CALIBRATION"
                setTextColor(Color.parseColor("#00D2FF"))
                textSize = 15f
                paint.isFakeBoldText = true
            }
            val subtitle = TextView(service).apply {
                text = "Drag the markers below to set exact screen points for Next swipe & Tap."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 4, 0, 16)
            }
            addView(title)
            addView(subtitle)

            val btnRow = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val btnTest = Button(service).apply {
                text = "Test Next"
                setTextColor(Color.parseColor("#0B0F19"))
                setBackgroundColor(Color.parseColor("#00D2FF"))
                setOnClickListener {
                    savePoints()
                    hide()
                    handler.postDelayed({
                        service.swipeUp()
                        Toast.makeText(service, "Next swipe triggered", Toast.LENGTH_SHORT).show()
                        handler.postDelayed({ show() }, 1_500L)
                    }, 300L)
                }
            }

            val btnReset = Button(service).apply {
                text = "Reset"
                setTextColor(Color.parseColor("#F8FAFC"))
                setBackgroundColor(Color.parseColor("#334155"))
                setOnClickListener {
                    GesturePoints.resetToDefaults(service)
                    hide()
                    show()
                    Toast.makeText(service, "Reset to defaults", Toast.LENGTH_SHORT).show()
                }
            }

            val btnDone = Button(service).apply {
                text = "Save & Done"
                setTextColor(Color.parseColor("#0B0F19"))
                setBackgroundColor(Color.parseColor("#10B981"))
                setOnClickListener {
                    savePoints()
                    hide()
                    Toast.makeText(service, "Gesture points saved!", Toast.LENGTH_SHORT).show()
                }
            }

            val lpBtn = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10
            }
            btnRow.addView(btnTest, lpBtn)
            btnRow.addView(btnReset, lpBtn)
            btnRow.addView(btnDone, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))

            addView(btnRow)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTargetMarker(label: String, color: Int, onMoved: (Float, Float) -> Unit): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val pill = TextView(service).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                paint.isFakeBoldText = true
                setBackgroundColor(color)
                setPadding(24, 12, 24, 12)
            }
            addView(pill)

            var dX = 0f
            var dY = 0f

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newX = (event.rawX + dX).coerceIn(0f, (service.resources.displayMetrics.widthPixels - view.width).toFloat())
                        val newY = (event.rawY + dY).coerceIn(150f, (service.resources.displayMetrics.heightPixels - view.height).toFloat())
                        view.x = newX
                        view.y = newY
                        val centerX = newX + view.width / 2f
                        val centerY = newY + view.height / 2f
                        onMoved(centerX, centerY)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun positionMarkerView(view: View, centerX: Int, centerY: Int) {
        view.post {
            view.x = (centerX - view.width / 2).toFloat()
            view.y = (centerY - view.height / 2).toFloat()
            guideView?.invalidate()
        }
    }

    private fun savePoints() {
        GesturePoints.setNextPoints(service, nextStartY, nextEndY, swipeX)
        // Set Back points symmetrically or with current swipeX
        GesturePoints.setBackPoints(service, nextEndY, nextStartY, swipeX)
        GesturePoints.setTapPoint(service, tapX, tapY)
    }

    /** Canvas view to draw gesture paths and directional guide lines between markers. */
    inner class GuideCanvasView(context: Context) : View(context) {
        private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00D2FF")
            strokeWidth = 6f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }

        private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00D2FF")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val startX = swipeX * w
            val startY = nextStartY * h
            val endX = swipeX * w
            val endY = nextEndY * h

            // Draw directional dashed swipe line for Next
            canvas.drawLine(startX, startY, endX, endY, paintLine)
            canvas.drawCircle(startX, startY, 12f, paintDot)
            canvas.drawCircle(endX, endY, 12f, paintDot)
        }
    }
}
