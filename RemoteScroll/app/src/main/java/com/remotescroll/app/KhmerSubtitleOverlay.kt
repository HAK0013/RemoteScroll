package com.remotescroll.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
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
 * Floating draggable Khmer live subtitle overlay drawn via TYPE_ACCESSIBILITY_OVERLAY
 * over YouTube videos (portrait, landscape fullscreen, and Shorts).
 */
class KhmerSubtitleOverlay(private val service: GestureAccessibilityService) {

    companion object {
        private const val TAG = "KhmerSubtitleOverlay"
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var rootView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var textStatus: TextView? = null
    private var textOriginal: TextView? = null
    private var textKhmer: TextView? = null
    private var btnVoice: TextView? = null
    private var btnTone: TextView? = null
    private var btnVol: TextView? = null
    private var btnSpeed: TextView? = null
    private var btnOrig: TextView? = null
    private var btnRestart: TextView? = null

    private var lastStatus = "🇰🇭 YT → ខ្មែរ • Ready"
    private var lastOriginal = ""
    private var lastKhmer = ""

    val isShowing: Boolean get() = rootView != null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (rootView != null) {
            refreshButtons()
            renderLatest()
            return
        }

        val view = try {
            LayoutInflater.from(service).inflate(R.layout.overlay_khmer_subtitle, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate overlay_khmer_subtitle", e)
            return
        }

        textStatus = view.findViewById(R.id.textSubStatus)
        textOriginal = view.findViewById(R.id.textSubOriginal)
        textKhmer = view.findViewById(R.id.textSubKhmer)
        btnVoice = view.findViewById(R.id.btnSubVoice)
        btnTone = view.findViewById(R.id.btnSubTone)
        btnVol = view.findViewById(R.id.btnSubVol)
        btnSpeed = view.findViewById(R.id.btnSubSpeed)
        btnOrig = view.findViewById(R.id.btnSubOrig)
        btnRestart = view.findViewById(R.id.btnSubRestart)

        val btnClose = view.findViewById<View>(R.id.btnSubClose)

        btnVoice?.setOnClickListener {
            val (nextVoice, nextTrack) = TranslatorPrefs.cycleOverlayAudioMode(
                voiceEnabled = TranslatorPrefs.isVoiceTtsEnabled(service),
                audioTrackEnabled = TranslatorPrefs.isAudioTrackModeEnabled(service)
            )
            TranslatorPrefs.setVoiceTtsEnabled(service, nextVoice)
            TranslatorPrefs.setAudioTrackModeEnabled(service, nextTrack)
            if (!nextVoice) {
                service.stopKhmerVoice()
            } else {
                service.refreshTranslatorUiState()
            }
            refreshButtons()
            val msg = when {
                !nextVoice -> "🔇 Audio Track: Original Video Audio"
                nextTrack -> "🎧 Audio Track: Khmer Dub (Continuous)"
                else -> "🔊 Audio Track: Hybrid TTS + Original"
            }
            Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
        }

        btnTone?.setOnClickListener {
            val cur = TranslatorPrefs.getVoiceToneMode(service)
            val next = TranslatorPrefs.cycleVoiceToneMode(cur)
            TranslatorPrefs.setVoiceToneMode(service, next)
            refreshButtons()
            Toast.makeText(
                service,
                "Voice Tone: ${TranslatorPrefs.voiceToneDisplayName(next)}",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnVol?.setOnClickListener {
            val cur = TranslatorPrefs.getTtsVolumePercent(service)
            val next = TranslatorPrefs.cycleTtsVolumePreset(cur)
            TranslatorPrefs.setTtsVolumePercent(service, next)
            service.updateKhmerTtsVolume()
            Toast.makeText(service, "🔊 TTS Volume: $next%", Toast.LENGTH_SHORT).show()
        }

        btnSpeed?.setOnClickListener {
            val cur = TranslatorPrefs.getTtsSpeed(service)
            val next = TranslatorPrefs.cycleTtsSpeedPreset(cur)
            TranslatorPrefs.setTtsSpeed(service, next)
            service.updateKhmerTtsSpeed()
            refreshButtons()
            Toast.makeText(
                service,
                String.format(java.util.Locale.US, "⚡ Voice Speed: %.2fx", next),
                Toast.LENGTH_SHORT
            ).show()
        }

        btnOrig?.setOnClickListener {
            val next = !TranslatorPrefs.isShowOriginalEnabled(service)
            TranslatorPrefs.setShowOriginalEnabled(service, next)
            refreshButtons()
            renderLatest()
        }

        btnRestart?.setOnClickListener {
            service.restartTtsAndLiveDetection()
            refreshButtons()
            Toast.makeText(service, "🔄 Restarted TTS & Live Real-Time Sync", Toast.LENGTH_SHORT).show()
        }

        btnClose?.setOnClickListener {
            YouTubeTranslatorService.stop(service)
            hide()
            service.stopKhmerVoice()
            service.refreshTranslatorUiState()
        }

        val metrics = service.resources.displayMetrics
        val defaultW = (metrics.widthPixels * 0.88f).toInt().coerceIn(600, 980)
        val defaultX = ((metrics.widthPixels - defaultW) / 2).coerceAtLeast(16)
        val defaultY = (metrics.heightPixels * 0.66f).toInt().coerceAtLeast(100)
        val (savedX, savedY) = TranslatorPrefs.getSubtitlePos(service, defaultX, defaultY)

        val lp = WindowManager.LayoutParams(
            defaultW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, (metrics.widthPixels - 240).coerceAtLeast(0))
            y = savedY.coerceIn(0, (metrics.heightPixels - 180).coerceAtLeast(0))
        }

        attachDragger(view)

        try {
            windowManager.addView(view, lp)
            rootView = view
            windowParams = lp
            refreshButtons()
            renderLatest()
            Log.i(TAG, "Khmer subtitle overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Khmer subtitle overlay", e)
        }
    }

    fun hide() {
        val root = rootView ?: return
        try {
            windowManager.removeView(root)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove subtitle overlay", e)
        }
        rootView = null
        windowParams = null
        textStatus = null
        textOriginal = null
        textKhmer = null
        btnVoice = null
        btnTone = null
        btnVol = null
        btnSpeed = null
        btnOrig = null
        btnRestart = null
    }

    fun updateStatus(statusText: String) {
        lastStatus = statusText
        textStatus?.text = statusText
    }

    fun updateSubtitle(
        lang: String,
        sourceText: String,
        khmerText: String,
        engineLabel: String = "LIVE",
        speakerGender: SpeakerGender = SpeakerGender.UNKNOWN
    ) {
        val badge = if (lang.isNotEmpty() && lang != "AUTO") "$lang → ខ្មែរ" else "AUTO → ខ្មែរ"
        val genderBadge = when (speakerGender) {
            SpeakerGender.MALE -> " • 👨 Male"
            SpeakerGender.FEMALE -> " • 👩 Female"
            SpeakerGender.UNKNOWN -> ""
        }
        val leadIcon = if (TranslatorPrefs.isVoiceTtsEnabled(service) &&
            TranslatorPrefs.isAudioTrackModeEnabled(service)
        ) {
            "🎧 ខ្មែរ Track"
        } else {
            "🇰🇭 $engineLabel"
        }
        lastStatus = "$leadIcon • $badge$genderBadge"
        lastOriginal = if (sourceText.isNotBlank()) {
            if (lang.isNotEmpty()) "[$lang] $sourceText" else sourceText
        } else {
            ""
        }
        lastKhmer = khmerText
        renderLatest()
    }

    fun refreshButtons() {
        val ttsOn = TranslatorPrefs.isVoiceTtsEnabled(service)
        val audioTrackOn = TranslatorPrefs.isAudioTrackModeEnabled(service)
        btnVoice?.apply {
            text = when {
                !ttsOn -> "🔇 Orig"
                audioTrackOn -> "🎧 ខ្មែរ"
                else -> "🔊 Mix"
            }
            setTextColor(
                when {
                    !ttsOn -> Color.parseColor("#94A3B8")
                    audioTrackOn -> Color.parseColor("#10B981")
                    else -> Color.parseColor("#00D2FF")
                }
            )
        }
        val toneMode = TranslatorPrefs.getVoiceToneMode(service)
        btnTone?.apply {
            text = TranslatorPrefs.voiceToneShortLabel(toneMode)
            setTextColor(if (ttsOn) Color.parseColor("#C084FC") else Color.parseColor("#94A3B8"))
        }
        val volPercent = TranslatorPrefs.getTtsVolumePercent(service)
        btnVol?.apply {
            text = if (volPercent >= 70) "🔊 $volPercent%" else "🔉 $volPercent%"
            setTextColor(if (ttsOn) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
        }
        val speed = TranslatorPrefs.getTtsSpeed(service)
        btnSpeed?.apply {
            text = String.format(java.util.Locale.US, "%.1fx", speed)
            setTextColor(if (ttsOn) Color.parseColor("#FBBF24") else Color.parseColor("#94A3B8"))
        }
        val origOn = TranslatorPrefs.isShowOriginalEnabled(service)
        btnOrig?.apply {
            setTextColor(if (origOn) Color.parseColor("#00D2FF") else Color.parseColor("#64748B"))
        }
    }

    private fun renderLatest() {
        textStatus?.text = lastStatus
        val showOrig = TranslatorPrefs.isShowOriginalEnabled(service) && lastOriginal.isNotBlank()
        textOriginal?.apply {
            visibility = if (showOrig) View.VISIBLE else View.GONE
            text = lastOriginal
        }
        textKhmer?.text = if (lastKhmer.isNotBlank()) {
            lastKhmer
        } else {
            service.getString(R.string.yt_translate_waiting)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragger(target: View) {
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        var startRawX = 0f
        var startRawY = 0f
        var startWinX = 0
        var startWinY = 0
        var dragging = false

        target.setOnTouchListener { _, event ->
            val lp = windowParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startWinX = lp.x
                    startWinY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        val metrics = service.resources.displayMetrics
                        lp.x = (startWinX + dx.toInt()).coerceIn(0, (metrics.widthPixels - 200).coerceAtLeast(0))
                        lp.y = (startWinY + dy.toInt()).coerceIn(0, (metrics.heightPixels - 140).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(rootView ?: return@setOnTouchListener false, lp)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        TranslatorPrefs.setSubtitlePos(service, lp.x, lp.y)
                    }
                    true
                }
                else -> false
            }
        }
    }
}
