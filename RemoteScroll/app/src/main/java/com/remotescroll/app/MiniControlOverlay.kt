package com.remotescroll.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * Floating mini controller shown on top of TikTok (and other supported video apps).
 * Uses TYPE_ACCESSIBILITY_OVERLAY from GestureAccessibilityService, so no extra
 * "Draw over other apps" permission is needed.
 *
 * Provides quick actions:
 *  - Pocket Lock (turns off display/blocks touch while keeping TikTok playing)
 *  - Auto-lock threshold toggle (cycles Off -> 5 -> 10 -> Off)
 *  - Scroll up / down buttons
 *  - Quick shortcut to RemoteScroll settings
 *  - Draggable anywhere on screen, collapsible into a small sleek pill
 */
class MiniControlOverlay(private val service: GestureAccessibilityService) {

    companion object {
        private const val TAG = "MiniControlOverlay"
        private const val PREFS = "remote_scroll"
        private const val KEY_POS_X = "mini_ctrl_pos_x"
        private const val KEY_POS_Y = "mini_ctrl_pos_y"
        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 400
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var layoutCollapsed: View? = null
    private var layoutExpanded: View? = null
    private var textBubbleStatus: TextView? = null
    private var btnAutoLock: TextView? = null
    private var btnAutoPilot: TextView? = null
    private var btnYtSkipAds: TextView? = null
    private var btnYtMuteAds: TextView? = null
    private var textYtSkipCounter: TextView? = null
    private var btnYtTranslate: TextView? = null
    private var btnYtTranslateVoice: TextView? = null

    private var isExpanded = false
    private var lastX = DEFAULT_X
    private var lastY = DEFAULT_Y

    val isShowing: Boolean get() = rootView != null

    init {
        val sp = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lastX = sp.getInt(KEY_POS_X, DEFAULT_X)
        lastY = sp.getInt(KEY_POS_Y, DEFAULT_Y)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (rootView != null) return

        val inflater = LayoutInflater.from(service)
        val view = try {
            inflater.inflate(R.layout.overlay_mini_control, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate overlay_mini_control", e)
            return
        }

        val collapsed = view.findViewById<View>(R.id.layoutCollapsed)
        val expanded = view.findViewById<View>(R.id.layoutExpanded)
        val bubbleStatus = view.findViewById<TextView>(R.id.textBubbleStatus)
        val autoPilotBtn = view.findViewById<TextView>(R.id.btnAutoPilot)
        val autoBtn = view.findViewById<TextView>(R.id.btnAutoLock)
        val turboBtn = view.findViewById<TextView>(R.id.btnTurbo2x)
        val sleepBtn = view.findViewById<View>(R.id.btnSleep)
        val pocketLockBtn = view.findViewById<View>(R.id.btnPocketLock)
        val prevBtn = view.findViewById<View>(R.id.btnPrev)
        val nextBtn = view.findViewById<View>(R.id.btnNext)
        val pointsBtn = view.findViewById<View>(R.id.btnPoints)
        val settingsBtn = view.findViewById<View>(R.id.btnSettings)
        val minimizeBtn = view.findViewById<View>(R.id.btnMinimize)
        val closeBtn = view.findViewById<View>(R.id.btnClose)
        val dragHandle = view.findViewById<View>(R.id.dragHandle)
        val ytSkipBtn = view.findViewById<TextView>(R.id.btnYtSkipAds)
        val ytMuteBtn = view.findViewById<TextView>(R.id.btnYtMuteAds)
        val ytCounterText = view.findViewById<TextView>(R.id.textYtSkipCounter)
        val ytTranslateBtn = view.findViewById<TextView>(R.id.btnYtTranslate)
        val ytTranslateVoiceBtn = view.findViewById<TextView>(R.id.btnYtTranslateVoice)

        layoutCollapsed = collapsed
        layoutExpanded = expanded
        textBubbleStatus = bubbleStatus
        btnAutoPilot = autoPilotBtn
        btnAutoLock = autoBtn
        btnYtSkipAds = ytSkipBtn
        btnYtMuteAds = ytMuteBtn
        textYtSkipCounter = ytCounterText
        btnYtTranslate = ytTranslateBtn
        btnYtTranslateVoice = ytTranslateVoiceBtn

        // Buttons
        autoPilotBtn?.setOnClickListener {
            service.cycleAutoPilot()
        }

        autoPilotBtn?.setOnLongClickListener {
            service.toggleAutoPilotPause()
            true
        }

        // Buttons
        sleepBtn?.setOnClickListener {
            setExpanded(false)
            service.showSleepBlackout()
        }

        pocketLockBtn?.setOnClickListener {
            service.showLock()
        }

        pointsBtn?.setOnClickListener {
            service.showCalibration()
        }

        turboBtn?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    service.startTurboSpeed()
                    turboBtn.setTextColor(android.graphics.Color.WHITE)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    service.stopTurboSpeed()
                    turboBtn.setTextColor(android.graphics.Color.parseColor("#00D2FF"))
                    true
                }
                else -> false
            }
        }

        autoBtn?.setOnClickListener {
            cycleAutoLock()
        }

        prevBtn?.setOnClickListener {
            service.swipeDown()
        }

        nextBtn?.setOnClickListener {
            service.swipeUp()
        }

        settingsBtn?.setOnClickListener {
            openSettings()
        }

        minimizeBtn?.setOnClickListener {
            setExpanded(false)
        }

        closeBtn?.setOnClickListener {
            setExpanded(false)
        }

        ytSkipBtn?.setOnClickListener {
            val next = !YouTubeAdSkipper.isEnabled(service)
            YouTubeAdSkipper.setEnabled(service, next)
            if (next) {
                service.scheduleAutoSkipCheck(200L, force = true)
            } else {
                service.restoreYouTubeAdMute()
            }
            updateYouTubeAdSkipStatus()
            Toast.makeText(
                service,
                if (next) "⏭️ YouTube Auto-Skip Ads: ON" else "⏭️ YouTube Auto-Skip Ads: OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

        ytMuteBtn?.setOnClickListener {
            val next = !YouTubeAdSkipper.isMuteAdsEnabled(service)
            YouTubeAdSkipper.setMuteAdsEnabled(service, next)
            if (!next) {
                service.restoreYouTubeAdMute()
            } else if (YouTubeAdSkipper.isEnabled(service)) {
                service.scheduleAutoSkipCheck(200L, force = true)
            }
            updateYouTubeAdSkipStatus()
            Toast.makeText(
                service,
                if (next) "🔇 Mute YouTube Ads: ON" else "🔊 Mute YouTube Ads: OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

        ytTranslateBtn?.setOnClickListener {
            val active = YouTubeTranslatorService.isRunning || TranslatorPrefs.isSubtitleEnabled(service)
            if (active) {
                YouTubeTranslatorService.stop(service)
                TranslatorPrefs.setSubtitleEnabled(service, false)
                service.updateKhmerSubtitleOverlay()
                updateYouTubeAdSkipStatus()
                Toast.makeText(service, "🇰🇭 YT Translate: OFF", Toast.LENGTH_SHORT).show()
            } else {
                TranslatorPrefs.setSubtitleEnabled(service, true)
                service.updateKhmerSubtitleOverlay()
                MediaProjectionPromptActivity.launch(service, openYouTubeAfterConsent = false)
                updateYouTubeAdSkipStatus()
            }
        }

        ytTranslateVoiceBtn?.setOnClickListener {
            val next = !TranslatorPrefs.isVoiceTtsEnabled(service)
            TranslatorPrefs.setVoiceTtsEnabled(service, next)
            if (!next) {
                service.stopKhmerVoice()
            }
            updateYouTubeAdSkipStatus()
            Toast.makeText(
                service,
                if (next) "🔊 Khmer Voice: ON" else "🔇 Khmer Voice: OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Draggers
        attachDragger(collapsed) {
            setExpanded(true)
        }
        attachDragger(dragHandle, null)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }

        try {
            windowManager.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Could not add mini control overlay", e)
            return
        }

        rootView = view
        windowParams = lp

        setExpanded(isExpanded)
        updateStatus(service.scrollCount, GestureAccessibilityService.autoLockScrolls(service))
        updateYouTubeAdSkipStatus()
        Log.i(TAG, "Mini control overlay shown")
    }

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove mini control overlay", e)
        }
        rootView = null
        windowParams = null
        layoutCollapsed = null
        layoutExpanded = null
        textBubbleStatus = null
        btnAutoLock = null
        btnAutoPilot = null
        btnYtSkipAds = null
        btnYtMuteAds = null
        textYtSkipCounter = null
        btnYtTranslate = null
        btnYtTranslateVoice = null
        Log.i(TAG, "Mini control overlay hidden")
    }

    fun destroy() {
        hide()
    }

    fun updateYouTubeAdSkipStatus() {
        handler.post {
            val ytSkip = YouTubeAdSkipper.isEnabled(service)
            val ytMute = YouTubeAdSkipper.isMuteAdsEnabled(service)
            val count = YouTubeAdSkipper.skippedCount(service)
            val liveCapture = YouTubeTranslatorService.isRunning
            val subtitleOn = TranslatorPrefs.isSubtitleEnabled(service)
            val voiceOn = TranslatorPrefs.isVoiceTtsEnabled(service)

            btnYtSkipAds?.apply {
                text = if (ytSkip) "⏭️ YT Ads: ON" else "⏭️ YT Ads: OFF"
                setTextColor(
                    android.graphics.Color.parseColor(if (ytSkip) "#00D2FF" else "#94A3B8")
                )
            }
            btnYtMuteAds?.apply {
                text = if (ytMute) "🔇 Mute: ON" else "🔇 Mute: OFF"
                setTextColor(
                    android.graphics.Color.parseColor(if (ytMute) "#00D2FF" else "#94A3B8")
                )
            }
            textYtSkipCounter?.text = "$count ads"

            btnYtTranslate?.apply {
                text = when {
                    liveCapture -> "🇰🇭 Translate: LIVE"
                    subtitleOn -> "🇰🇭 Caption: ON"
                    else -> "🇰🇭 Translate: OFF"
                }
                setTextColor(
                    android.graphics.Color.parseColor(
                        when {
                            liveCapture -> "#10B981"
                            subtitleOn -> "#00D2FF"
                            else -> "#94A3B8"
                        }
                    )
                )
            }
            btnYtTranslateVoice?.apply {
                text = if (voiceOn) "🔊 Voice" else "🔇 Voice"
                setTextColor(
                    android.graphics.Color.parseColor(if (voiceOn) "#00D2FF" else "#94A3B8")
                )
            }
        }
    }

    fun updateAutoPilot(remainingSeconds: Int, totalSeconds: Int, isPaused: Boolean) {
        handler.post {
            val bubble = textBubbleStatus
            val autoPilotBtn = btnAutoPilot
            if (totalSeconds > 0) {
                val status = if (isPaused) "⏸️ ${remainingSeconds}s" else "▶ ${remainingSeconds}s"
                val color = if (isPaused) android.graphics.Color.parseColor("#F59E0B") else android.graphics.Color.parseColor("#00D2FF")
                bubble?.text = status
                bubble?.setTextColor(color)
                autoPilotBtn?.text = if (isPaused) "⏸️ ${remainingSeconds}s" else "▶ ${remainingSeconds}s"
                autoPilotBtn?.setTextColor(color)
            } else {
                autoPilotBtn?.text = "▶ Auto: Off"
                autoPilotBtn?.setTextColor(android.graphics.Color.parseColor("#00D2FF"))
                updateStatus(service.scrollCount, GestureAccessibilityService.autoLockScrolls(service))
            }
        }
    }

    fun updateStatus(scrollCount: Int, autoLockThreshold: Int) {
        handler.post {
            val bubble = textBubbleStatus ?: return@post
            val autoBtn = btnAutoLock ?: return@post

            if (service.isAutoPilotActive) {
                val rem = service.autoPilotRemaining
                val paused = service.isAutoPilotPaused
                bubble.text = if (paused) "⏸️ ${rem}s" else "▶ ${rem}s"
                bubble.setTextColor(if (paused) android.graphics.Color.parseColor("#F59E0B") else android.graphics.Color.parseColor("#00D2FF"))
            } else {
                if (autoLockThreshold > 0) {
                    bubble.text = "⚡ $scrollCount/$autoLockThreshold"
                } else {
                    bubble.text = "⚡ RS"
                }
                bubble.setTextColor(android.graphics.Color.parseColor("#00D2FF"))
            }

            if (autoLockThreshold > 0) {
                autoBtn.text = "🎯 Lock: $scrollCount/$autoLockThreshold"
            } else {
                autoBtn.text = "🎯 Lock: Off"
            }
        }
    }

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        layoutCollapsed?.visibility = if (expanded) View.GONE else View.VISIBLE
        layoutExpanded?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun cycleAutoLock() {
        val current = GestureAccessibilityService.autoLockScrolls(service)
        val next = when (current) {
            0 -> 5
            5 -> 10
            10 -> 0
            else -> 0
        }
        GestureAccessibilityService.setAutoLockScrolls(service, next)
        service.scrollCount = 0
        updateStatus(0, next)
        val toastText = if (next > 0) {
            service.getString(R.string.auto_lock_toast_on, next)
        } else {
            service.getString(R.string.auto_lock_toast_off)
        }
        Toast.makeText(service, toastText, Toast.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        try {
            val intent = Intent(service, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            service.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity from overlay", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragger(targetView: View?, onSingleTap: (() -> Unit)?) {
        val view = targetView ?: return
        var startX = 0
        var startY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var hasDragged = false
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

        view.setOnTouchListener { v, event ->
            val lp = windowParams ?: return@setOnTouchListener false
            val metrics = service.resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    hasDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (!hasDragged && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        hasDragged = true
                    }
                    if (hasDragged) {
                        val newX = (startX + dx).coerceIn(0, maxOf(0, screenW - (rootView?.width ?: 100)))
                        val newY = (startY + dy).coerceIn(0, maxOf(0, screenH - (rootView?.height ?: 100)))
                        lp.x = newX
                        lp.y = newY
                        lastX = newX
                        lastY = newY
                        savePosition(newX, newY)
                        try {
                            rootView?.let { windowManager.updateViewLayout(it, lp) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update layout during drag", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasDragged) {
                        if (onSingleTap != null) {
                            onSingleTap()
                        } else {
                            v.performClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun savePosition(x: Int, y: Int) {
        service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_POS_X, x)
            .putInt(KEY_POS_Y, y)
            .apply()
    }
}
