package com.remotescroll.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Speaks translated Khmer text aloud using Google/system TTS (`km-KH`) routed
 * over `USAGE_ALARM` / `STREAM_ALARM` so lowering original YouTube media audio
 * (`STREAM_MUSIC`) never attenuates the translated Khmer voice (on Android/Samsung,
 * `STREAM_ACCESSIBILITY` and `STREAM_TTS` are volume-aliased to `STREAM_MUSIC`),
 * and AudioPlaybackCapture (`USAGE_MEDIA`) never loops back our own speech.
 */
class KhmerVoiceSpeaker(context: Context) {

    companion object {
        private const val TAG = "KhmerVoiceSpeaker"
        private const val GOOGLE_TTS_PKG = "com.google.android.tts"
        private const val REPEAT_DEDUP_MS = 3_500L
        private const val DUCK_RESTORE_HOLD_MS = 1_800L
        private const val AUDIO_TRACK_HOLD_MS = 8_500L
        private const val DUCK_WATCHDOG_MS = 12_000L
        private const val NETWORK_VOICE_COOLDOWN_MS = 30_000L
        private const val COALESCE_HOLD_MS = 220L
        private const val COALESCE_FLUSH_LEN = 22
        private val KHMER_LOCALE = Locale("km", "KH")

        private val KHMER_QUESTION_PREFIXES = listOf(
            "តើ", "ហេតុអ្វី", "ម៉េច", "ម្ដេច", "ដូចម្ដេច", "ឯណា", "ប៉ុន្មាន", "ពេលណា", "នរណា", "អ្វី"
        )
        private val EXCITEMENT_HINTS = listOf("អូ", "វ៉ាវ", "អស្ចារ្យ", "ហាហា", "wow", "oh!")

        data class HumanProsody(
            val pitch: Float,
            val rate: Float
        )

        internal fun sanitizeForSpeech(rawText: String): String =
            rawText
                .replace(Regex("https?://\\S+"), " ")
                .replace(Regex("\\[[^\\]]{1,16}\\]"), " ")
                .replace(Regex("[*_`#~|<>]+"), " ")
                // Preserve natural human breath pauses across ellipses, dashes, and semicolons.
                .replace(Regex("\\.{2,}|…+|—|–|;"), ", ")
                .replace(Regex("\\s*([,!?;។])\\s*"), "$1 ")
                .replace(Regex(",\\s*,+"), ", ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim(',')
                .trim()

        internal fun computeDuckedMusicVolume(currentVol: Int, maxVol: Int): Int {
            if (currentVol <= 1 || maxVol <= 1) return currentVol
            val scaled = (currentVol * 0.15f).toInt().coerceAtLeast(1)
            return scaled.coerceAtMost((currentVol - 1).coerceAtLeast(1))
        }

        internal fun computeAudioTrackDuckVolume(
            currentVol: Int,
            maxVol: Int,
            audioTrackMode: Boolean,
            muteOriginal: Boolean = false
        ): Int {
            if (currentVol <= 0 || maxVol <= 1) return currentVol
            return if (audioTrackMode) {
                if (muteOriginal) 0 else 1
            } else {
                computeDuckedMusicVolume(currentVol, maxVol)
            }
        }

        internal fun computeDuckRestoreHoldMs(audioTrackMode: Boolean): Long =
            if (audioTrackMode) AUDIO_TRACK_HOLD_MS else DUCK_RESTORE_HOLD_MS

        internal fun computeTtsAlarmVolume(
            maxAlarmVol: Int,
            ttsVolumePercent: Int
        ): Int {
            if (maxAlarmVol <= 1) return maxAlarmVol
            val ratio = ttsVolumePercent.coerceIn(10, 100) / 100f
            return (maxAlarmVol * ratio).roundToInt().coerceIn(1, maxAlarmVol)
        }

        internal fun computeTtsParamVolume(ttsVolumePercent: Int): Float =
            if (ttsVolumePercent.coerceIn(10, 100) < 15) 0.70f else 1.0f

        internal fun computeVoicePitch(
            speakerGender: SpeakerGender,
            hasDedicatedGenderVoice: Boolean
        ): Float {
            if (hasDedicatedGenderVoice) return 1.0f
            return when (speakerGender) {
                SpeakerGender.MALE -> 0.89f
                SpeakerGender.FEMALE -> 1.02f
                SpeakerGender.UNKNOWN -> 0.98f
            }
        }

        internal fun computeHumanProsody(
            spokenText: String,
            speakerGender: SpeakerGender,
            hasDedicatedGenderVoice: Boolean,
            baseSpeed: Float,
            toneMode: String,
            utteranceIndex: Int = 0,
            activeQueueCount: Int = 0
        ): HumanProsody {
            val basePitch = computeVoicePitch(speakerGender, hasDedicatedGenderVoice)
            if (toneMode == TranslatorPrefs.TONE_MODE_FLAT) {
                return HumanProsody(
                    pitch = basePitch.coerceIn(0.78f, 1.18f),
                    rate = baseSpeed.coerceIn(0.85f, 2.25f)
                )
            }

            val trimmed = spokenText.trim()
            val isQuestion = trimmed.endsWith("?") || trimmed.endsWith("？") ||
                KHMER_QUESTION_PREFIXES.any { trimmed.startsWith(it) }
            val isExclamation = trimmed.endsWith("!") || trimmed.endsWith("！") ||
                EXCITEMENT_HINTS.any { trimmed.contains(it, ignoreCase = true) }
            val isContinuation = trimmed.endsWith(",") || trimmed.endsWith("៖")
            val isReflectiveLong = trimmed.length >= 42 && !isQuestion && !isExclamation

            val tonePitchBias = when (toneMode) {
                TranslatorPrefs.TONE_MODE_WARM -> -0.03f
                TranslatorPrefs.TONE_MODE_LIVELY -> +0.03f
                else -> 0.0f
            }
            val toneRateScale = when (toneMode) {
                TranslatorPrefs.TONE_MODE_WARM -> 0.94f
                TranslatorPrefs.TONE_MODE_LIVELY -> 1.05f
                else -> 1.00f
            }

            // Subtle human cadence wave prevents back-to-back sentences from sounding robotic.
            val cadenceWave = when (Math.floorMod(utteranceIndex, 4)) {
                0 -> 0.0f
                1 -> +0.02f
                2 -> -0.02f
                else -> +0.01f
            }

            val intentPitchDelta = when {
                isExclamation -> +0.06f
                isQuestion -> +0.05f
                isContinuation -> -0.015f
                isReflectiveLong -> -0.025f
                else -> 0.0f
            }

            val intentRateScale = when {
                isExclamation -> 1.04f
                isQuestion -> 0.96f
                isContinuation -> 0.96f
                trimmed.length >= 55 -> 1.06f
                else -> 1.0f
            }

            val queueCatchUpScale = when {
                activeQueueCount >= 3 -> 1.32f
                activeQueueCount == 2 -> 1.18f
                activeQueueCount == 1 -> 1.07f
                else -> 1.0f
            }
            val finalPitch = (basePitch + tonePitchBias + intentPitchDelta + cadenceWave)
                .coerceIn(0.78f, 1.18f)
            val finalRate = (baseSpeed * toneRateScale * intentRateScale * queueCatchUpScale)
                .coerceIn(0.88f, 2.25f)

            return HumanProsody(pitch = finalPitch, rate = finalRate)
        }

        internal fun chooseQueueMode(
            activeCount: Int,
            elapsedSinceStartMs: Long,
            textLength: Int
        ): Int {
            if (activeCount <= 0) return TextToSpeech.QUEUE_ADD
            // Keep smooth continuous dubbing (QUEUE_ADD) unless backlog grows deep or stalls > 5.0s.
            if (activeCount < 5 && elapsedSinceStartMs < 5_000L) {
                return TextToSpeech.QUEUE_ADD
            }
            return TextToSpeech.QUEUE_FLUSH
        }

        internal fun splitIntoNaturalClauses(text: String, maxClauseChars: Int = 90): List<String> {
            val cleaned = text.trim()
            if (cleaned.length <= maxClauseChars) {
                return if (cleaned.isEmpty()) emptyList() else listOf(cleaned)
            }
            val result = ArrayList<String>()
            var remaining = cleaned
            while (remaining.length > maxClauseChars) {
                var cut = -1
                for (stopCh in charArrayOf('។', '?', '!', '？', '！')) {
                    val idx = remaining.lastIndexOf(stopCh, maxClauseChars - 1)
                    if (idx >= maxClauseChars / 3) {
                        cut = maxOf(cut, idx + 1)
                    }
                }
                if (cut < 0) {
                    for (softCh in charArrayOf(',', ';', ' ')) {
                        val idx = remaining.lastIndexOf(softCh, maxClauseChars - 1)
                        if (idx >= maxClauseChars / 2) {
                            cut = maxOf(cut, idx + 1)
                        }
                    }
                }
                if (cut <= 0 || cut > remaining.length) {
                    cut = maxClauseChars
                }
                val part = remaining.substring(0, cut).trim()
                if (part.isNotEmpty()) result.add(part)
                remaining = remaining.substring(cut).trim()
            }
            if (remaining.isNotEmpty()) result.add(remaining)
            return result
        }

        internal fun mergePendingPhrase(pending: String, incoming: String): String {
            val a = pending.trim()
            val b = incoming.trim()
            if (a.isEmpty()) return b
            if (b.isEmpty() || a == b || a.endsWith(b)) return a
            return "$a $b"
        }

        internal fun shouldFlushCoalescedNow(mergedText: String, activeQueueCount: Int): Boolean {
            if (activeQueueCount <= 0) return true
            val t = mergedText.trim()
            if (t.isEmpty()) return false
            val endsStrong = t.last() in charArrayOf('.', '?', '!', '។', '？', '！')
            if (endsStrong) return true
            if (t.length < 9) return false
            return t.last() == ',' || t.length >= COALESCE_FLUSH_LEN
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val duckLock = Any()

    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    @Volatile
    private var triedGoogleFallback = false
    @Volatile
    private var lastSpoken = ""
    @Volatile
    private var lastSpokenAtMs = 0L
    @Volatile
    private var lastSpeakDispatchUptimeMs = 0L
    @Volatile
    private var pendingAfterInit: String? = null
    @Volatile
    private var pendingGenderAfterInit: SpeakerGender = SpeakerGender.UNKNOWN
    @Volatile
    private var activeCount = 0
    @Volatile
    private var activeUtteranceId: String? = null
    @Volatile
    private var activeUtteranceText: String = ""
    @Volatile
    private var activeUtteranceGender: SpeakerGender = SpeakerGender.UNKNOWN
    @Volatile
    private var activeUtteranceUsedNetworkVoice = false
    @Volatile
    private var activeUtteranceRetriedOffline = false
    @Volatile
    private var networkVoiceCooldownUntilMs = 0L
    @Volatile
    private var utteranceSequence = 0
    private var maleKhmerVoice: Voice? = null
    private var femaleKhmerVoice: Voice? = null
    private var hdNetworkKhmerVoice: Voice? = null
    private var offlineKhmerVoice: Voice? = null
    private var defaultKhmerVoice: Voice? = null

    private var preDuckMusicVolume: Int? = null
    private var appliedDuckMusicVolume: Int? = null
    private var preDuckAlarmVolume: Int? = null
    private var appliedBoostAlarmVolume: Int? = null
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var hasDuckFocus = false
    @Volatile
    private var currentUtteranceStartMs = 0L

    private val restoreMediaRunnable = Runnable {
        restoreMediaVolumeIfNeeded()
    }
    @Volatile
    private var pendingCoalesceText: String = ""
    @Volatile
    private var pendingCoalesceGender: SpeakerGender = SpeakerGender.UNKNOWN
    private val coalesceFlushRunnable = Runnable {
        val text = pendingCoalesceText
        val gender = pendingCoalesceGender
        pendingCoalesceText = ""
        pendingCoalesceGender = SpeakerGender.UNKNOWN
        if (text.isNotBlank()) {
            dispatchSpeakNow(text, gender)
        }
    }

    fun ensureInitialized() {
        if (tts != null) return
        initEngine(enginePackage = null)
    }

    private fun initEngine(enginePackage: String?) {
        ready = false
        val listener = TextToSpeech.OnInitListener { status ->
            val engine = tts ?: return@OnInitListener
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed (engine=${enginePackage ?: "default"}, status=$status)")
                if (enginePackage == null && !triedGoogleFallback) {
                    triedGoogleFallback = true
                    releaseEngineOnly()
                    initEngine(GOOGLE_TTS_PKG)
                }
                return@OnInitListener
            }

            val avail = engine.isLanguageAvailable(KHMER_LOCALE)
            if (avail < TextToSpeech.LANG_AVAILABLE && enginePackage == null && !triedGoogleFallback) {
                Log.i(TAG, "Default TTS lacks km-KH ($avail), switching to $GOOGLE_TTS_PKG")
                triedGoogleFallback = true
                releaseEngineOnly()
                initEngine(GOOGLE_TTS_PKG)
                return@OnInitListener
            }

            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = KHMER_LOCALE
            } else {
                Log.w(TAG, "Khmer TTS voice availability=$avail; falling back to default locale")
            }

            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine.setSpeechRate(TranslatorPrefs.getTtsSpeed(appContext))
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    currentUtteranceStartMs = SystemClock.uptimeMillis()
                    if (utteranceId == null || utteranceId == activeUtteranceId) {
                        if (activeCount <= 0) activeCount = 1
                    }
                }

                override fun onDone(utteranceId: String?) {
                    finishUtterance(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finishUtterance(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    finishUtterance(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS utterance error=$errorCode id=$utteranceId")
                    if (activeUtteranceUsedNetworkVoice) {
                        networkVoiceCooldownUntilMs = SystemClock.uptimeMillis() + NETWORK_VOICE_COOLDOWN_MS
                        val retryText = activeUtteranceText
                        val retryGender = activeUtteranceGender
                        val canRetry = !activeUtteranceRetriedOffline &&
                            utteranceId == activeUtteranceId &&
                            retryText.isNotBlank() &&
                            offlineKhmerVoice != null
                        if (canRetry) {
                            activeUtteranceRetriedOffline = true
                            activeCount = 0
                            currentUtteranceStartMs = 0L
                            mainHandler.post {
                                lastSpokenAtMs = 0L
                                speak(retryText, retryGender)
                            }
                            return
                        }
                    }
                    finishUtterance(utteranceId)
                }
            })
            ready = true
            discoverKhmerVoices(engine)
            val prewarmVoice = if (
                TranslatorPrefs.getVoiceToneMode(appContext) != TranslatorPrefs.TONE_MODE_FLAT &&
                hdNetworkKhmerVoice != null &&
                isNetworkAvailableNow()
            ) {
                hdNetworkKhmerVoice
            } else {
                offlineKhmerVoice ?: defaultKhmerVoice
            }
            if (prewarmVoice != null) {
                try {
                    engine.voice = prewarmVoice
                } catch (_: Exception) {}
            }
            val vol = audioManager?.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY) ?: -1
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY) ?: -1
            Log.i(
                TAG,
                "KhmerVoiceSpeaker ready (engine=${enginePackage ?: "default"}, avail=$avail, a11yVol=$vol/$maxVol, maleVoice=${maleKhmerVoice?.name}, femaleVoice=${femaleKhmerVoice?.name})"
            )
            val queued = pendingAfterInit
            val queuedGender = pendingGenderAfterInit
            pendingAfterInit = null
            pendingGenderAfterInit = SpeakerGender.UNKNOWN
            if (!queued.isNullOrBlank()) {
                speak(queued, queuedGender)
            }
        }

        tts = if (enginePackage != null) {
            TextToSpeech(appContext, listener, enginePackage)
        } else {
            TextToSpeech(appContext, listener)
        }
    }

    private fun discoverKhmerVoices(engine: TextToSpeech) {
        maleKhmerVoice = null
        femaleKhmerVoice = null
        hdNetworkKhmerVoice = null
        offlineKhmerVoice = null
        defaultKhmerVoice = null
        try {
            val khmerVoices = engine.voices
                ?.filter { it.locale.language.equals("km", ignoreCase = true) }
                ?.sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenBy { it.latency }
                )
                .orEmpty()
            if (khmerVoices.isNotEmpty()) {
                Log.i(TAG, "Khmer voices: ${khmerVoices.joinToString { "${it.name}(q=${it.quality},net=${it.isNetworkConnectionRequired})" }}")
                hdNetworkKhmerVoice = khmerVoices.firstOrNull {
                    it.isNetworkConnectionRequired || it.name.contains("network", ignoreCase = true)
                }
                offlineKhmerVoice = khmerVoices.firstOrNull { !it.isNetworkConnectionRequired }
                    ?: khmerVoices.firstOrNull()
                defaultKhmerVoice = offlineKhmerVoice ?: khmerVoices.firstOrNull()
                for (voice in khmerVoices) {
                    val lower = voice.name.lowercase(Locale.ROOT)
                    val features = voice.features?.joinToString(",")?.lowercase(Locale.ROOT).orEmpty()
                    val isFemaleHint = lower.contains("female") || features.contains("female")
                    val isMaleHint = (!isFemaleHint && (lower.contains("male") || features.contains("male")))
                    if (isMaleHint && maleKhmerVoice == null) maleKhmerVoice = voice
                    if (isFemaleHint && femaleKhmerVoice == null) femaleKhmerVoice = voice
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice enumeration unavailable", e)
        }
    }

    private fun isNetworkAvailableNow(): Boolean {
        val cm = connectivityManager ?: return false
        return try {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    fun speak(khmerText: String, speakerGender: SpeakerGender = SpeakerGender.UNKNOWN) {
        val text = sanitizeForSpeech(khmerText)
        if (text.length < 4) return

        val effectiveGender = when (TranslatorPrefs.getVoiceGenderMode(appContext)) {
            TranslatorPrefs.GENDER_MODE_FEMALE -> SpeakerGender.FEMALE
            TranslatorPrefs.GENDER_MODE_MALE -> SpeakerGender.MALE
            else -> speakerGender
        }

        if (!ready) {
            pendingAfterInit = text
            pendingGenderAfterInit = effectiveGender
            ensureInitialized()
            return
        }

        // Coalesce short mid-sentence fragments while TTS is actively speaking so multiple short
        // words merge into one smooth human clause instead of separate 1-word utterances.
        val merged = mergePendingPhrase(pendingCoalesceText, text)
        if (!shouldFlushCoalescedNow(merged, activeCount)) {
            pendingCoalesceText = merged
            pendingCoalesceGender = effectiveGender
            mainHandler.removeCallbacks(coalesceFlushRunnable)
            mainHandler.postDelayed(coalesceFlushRunnable, COALESCE_HOLD_MS)
            return
        }

        mainHandler.removeCallbacks(coalesceFlushRunnable)
        pendingCoalesceText = ""
        pendingCoalesceGender = SpeakerGender.UNKNOWN
        val clauses = splitIntoNaturalClauses(merged)
        for (clause in clauses) {
            dispatchSpeakNow(clause, effectiveGender)
        }
    }

    private fun dispatchSpeakNow(text: String, effectiveGender: SpeakerGender) {
        val toneMode = TranslatorPrefs.getVoiceToneMode(appContext)
        val now = SystemClock.uptimeMillis()
        if (text == lastSpoken && now - lastSpokenAtMs < REPEAT_DEDUP_MS) return
        lastSpoken = text
        lastSpokenAtMs = now

        val engine = tts ?: return
        val canUseHdNetwork = toneMode != TranslatorPrefs.TONE_MODE_FLAT &&
            hdNetworkKhmerVoice != null &&
            now >= networkVoiceCooldownUntilMs &&
            isNetworkAvailableNow()

        val selectedVoice = when (effectiveGender) {
            SpeakerGender.MALE -> maleKhmerVoice
            SpeakerGender.FEMALE -> femaleKhmerVoice
            SpeakerGender.UNKNOWN -> null
        }
        val hasDedicatedGenderVoice = when (effectiveGender) {
            SpeakerGender.MALE -> maleKhmerVoice != null
            SpeakerGender.FEMALE -> femaleKhmerVoice != null
            SpeakerGender.UNKNOWN -> false
        }
        val targetVoice = selectedVoice
            ?: (if (canUseHdNetwork) hdNetworkKhmerVoice else offlineKhmerVoice)
            ?: defaultKhmerVoice
        if (targetVoice != null) {
            try {
                if (engine.voice?.name != targetVoice.name) {
                    engine.voice = targetVoice
                }
            } catch (_: Exception) {}
        }

        val prosody = computeHumanProsody(
            spokenText = text,
            speakerGender = effectiveGender,
            hasDedicatedGenderVoice = hasDedicatedGenderVoice,
            baseSpeed = TranslatorPrefs.getTtsSpeed(appContext),
            toneMode = toneMode,
            utteranceIndex = utteranceSequence++,
            activeQueueCount = activeCount
        )
        engine.setPitch(prosody.pitch)
        engine.setSpeechRate(prosody.rate)

        // Avoid chopping off an utterance that only just started while flushing stale backlog.
        val volumePercent = TranslatorPrefs.getTtsVolumePercent(appContext)
        duckMediaVolume(volumePercent)
        val playingSince = if (currentUtteranceStartMs > 0L) currentUtteranceStartMs else lastSpeakDispatchUptimeMs
        val elapsedSinceStart = if (playingSince > 0L) now - playingSince else 0L
        val queueMode = chooseQueueMode(activeCount, elapsedSinceStart, text.length)
        val utteranceId = UUID.randomUUID().toString()
        activeUtteranceId = utteranceId
        activeUtteranceText = text
        activeUtteranceGender = effectiveGender
        activeUtteranceUsedNetworkVoice = targetVoice?.isNetworkConnectionRequired == true
        activeUtteranceRetriedOffline = false
        activeCount = if (queueMode == TextToSpeech.QUEUE_FLUSH) 1 else (activeCount + 1).coerceAtMost(6)
        if (queueMode == TextToSpeech.QUEUE_FLUSH || currentUtteranceStartMs == 0L) {
            currentUtteranceStartMs = now
        }
        lastSpeakDispatchUptimeMs = now
        val paramVolume = computeTtsParamVolume(volumePercent)
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, paramVolume)
        }
        Log.i(
            TAG,
            "speak() len=${text.length} voice=${targetVoice?.name} tone=$toneMode gender=$effectiveGender pitch=${"%.2f".format(Locale.US, prosody.pitch)} rate=${"%.2f".format(Locale.US, prosody.rate)} vol=$volumePercent% mode=${if (queueMode == TextToSpeech.QUEUE_FLUSH) "FLUSH" else "ADD"} q=$activeCount"
        )
        engine.speak(text, queueMode, params, utteranceId)
    }

    fun applyTtsSpeedNow() {
        val engine = tts ?: return
        if (!ready) return
        try {
            engine.setSpeechRate(TranslatorPrefs.getTtsSpeed(appContext))
        } catch (_: Exception) {}
    }

    fun applyTtsVolumeNow() {
        val am = audioManager ?: return
        val volumePercent = TranslatorPrefs.getTtsVolumePercent(appContext)
        synchronized(duckLock) {
            if (activeCount <= 0 && preDuckMusicVolume == null && preDuckAlarmVolume == null) return
            applyDesiredAlarmVolumeLocked(am, volumePercent)
        }
    }

    fun stopSpeaking() {
        pendingAfterInit = null
        pendingGenderAfterInit = SpeakerGender.UNKNOWN
        pendingCoalesceText = ""
        pendingCoalesceGender = SpeakerGender.UNKNOWN
        mainHandler.removeCallbacks(coalesceFlushRunnable)
        activeUtteranceId = null
        activeUtteranceText = ""
        try {
            tts?.stop()
        } catch (_: Exception) {}
        activeCount = 0
        currentUtteranceStartMs = 0L
        mainHandler.removeCallbacks(restoreMediaRunnable)
        restoreMediaVolumeIfNeeded()
        abandonDuckFocus()
    }

    /**
     * Immediately flushes any queued/stale Khmer speech, resets dedup and network-voice backoff,
     * and re-initializes Android TTS voice discovery so detection resumes cleanly at the latest
     * real-time video position.
     */
    fun restartForRealtimeSync() {
        Log.i(TAG, "restartForRealtimeSync(): resetting TTS queue and re-detecting Khmer voice")
        stopSpeaking()
        lastSpoken = ""
        lastSpokenAtMs = 0L
        lastSpeakDispatchUptimeMs = 0L
        networkVoiceCooldownUntilMs = 0L
        utteranceSequence = 0
        releaseEngineOnly()
        triedGoogleFallback = false
        initEngine(enginePackage = null)
    }

    fun shutdown() {
        stopSpeaking()
        releaseEngineOnly()
        triedGoogleFallback = false
        lastSpoken = ""
        lastSpokenAtMs = 0L
    }

    private fun releaseEngineOnly() {
        ready = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }

    private fun finishUtterance(utteranceId: String?) {
        if (utteranceId != null && activeUtteranceId != null && utteranceId != activeUtteranceId) {
            // An earlier queued utterance finished while a newer one is still queued/playing.
            activeCount = (activeCount - 1).coerceAtLeast(1)
            return
        }
        activeCount = 0
        currentUtteranceStartMs = 0L
        scheduleMediaRestore()
    }

    fun engageAudioTrackBedNow() {
        if (!TranslatorPrefs.isVoiceTtsEnabled(appContext) ||
            !TranslatorPrefs.isAudioTrackModeEnabled(appContext)
        ) return
        duckMediaVolume(TranslatorPrefs.getTtsVolumePercent(appContext))
        if (activeCount <= 0) {
            scheduleMediaRestore()
        }
    }

    fun refreshAudioTrackHoldIfActive() {
        if (!TranslatorPrefs.isVoiceTtsEnabled(appContext) ||
            !TranslatorPrefs.isAudioTrackModeEnabled(appContext)
        ) return
        if (activeCount <= 0) {
            engageAudioTrackBedNow()
        }
    }

    fun releaseAudioTrackBedNow() {
        if (activeCount > 0) return
        mainHandler.removeCallbacks(restoreMediaRunnable)
        restoreMediaVolumeIfNeeded()
    }

    private fun duckMediaVolume(ttsVolumePercent: Int) {
        val am = audioManager ?: return
        val audioTrackMode = TranslatorPrefs.isAudioTrackModeEnabled(appContext)
        val muteOrig = audioTrackMode && TranslatorPrefs.isAudioTrackMuteOrigEnabled(appContext)
        mainHandler.removeCallbacks(restoreMediaRunnable)
        if (!hasDuckFocus) {
            hasDuckFocus = true
            requestDuckFocus()
        }
        synchronized(duckLock) {
            try {
                if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val desiredTarget = computeAudioTrackDuckVolume(cur, max, audioTrackMode, muteOrig)
                    if (preDuckMusicVolume == null) {
                        if (desiredTarget < cur) {
                            preDuckMusicVolume = cur
                            appliedDuckMusicVolume = desiredTarget
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, desiredTarget, 0)
                            Log.i(TAG, "Ducked original media volume (track=$audioTrackMode, mute=$muteOrig): $cur -> $desiredTarget")
                        }
                    } else if (audioTrackMode && cur == appliedDuckMusicVolume) {
                        val adjusted = if (muteOrig) 0 else 1
                        if (cur != adjusted && preDuckMusicVolume != null) {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, adjusted, 0)
                            appliedDuckMusicVolume = adjusted
                        }
                    }
                }
                applyDesiredAlarmVolumeLocked(am, ttsVolumePercent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not duck media volume", e)
            }
        }
        // Watchdog ensures media volume always restores if TTS engine fails to notify onDone.
        mainHandler.postDelayed(restoreMediaRunnable, DUCK_WATCHDOG_MS)
    }

    private fun applyDesiredAlarmVolumeLocked(am: AudioManager, ttsVolumePercent: Int) {
        val alarmCur = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val alarmTarget = computeTtsAlarmVolume(alarmMax, ttsVolumePercent)
        if (preDuckAlarmVolume == null) {
            preDuckAlarmVolume = alarmCur
        }
        if (alarmTarget != alarmCur) {
            appliedBoostAlarmVolume = alarmTarget
            am.setStreamVolume(AudioManager.STREAM_ALARM, alarmTarget, 0)
            Log.i(TAG, "Set TTS alarm volume ($ttsVolumePercent%): $alarmCur -> $alarmTarget")
        } else {
            appliedBoostAlarmVolume = alarmTarget
        }
    }

    private fun scheduleMediaRestore() {
        mainHandler.removeCallbacks(restoreMediaRunnable)
        synchronized(duckLock) {
            if (preDuckMusicVolume == null && preDuckAlarmVolume == null) return
        }
        val holdMs = computeDuckRestoreHoldMs(TranslatorPrefs.isAudioTrackModeEnabled(appContext))
        mainHandler.postDelayed(restoreMediaRunnable, holdMs)
    }

    private fun restoreMediaVolumeIfNeeded() {
        val am = audioManager ?: return
        synchronized(duckLock) {
            val savedMusic = preDuckMusicVolume
            val appliedMusic = appliedDuckMusicVolume
            preDuckMusicVolume = null
            appliedDuckMusicVolume = null
            if (savedMusic != null) {
                try {
                    if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                        val curNow = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        // Only restore if the user didn't manually raise/lower volume during ducking.
                        if (appliedMusic == null || curNow == appliedMusic) {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0)
                            Log.i(TAG, "Restored original media volume: $curNow -> $savedMusic")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not restore media volume", e)
                }
            }

            val savedAlarm = preDuckAlarmVolume
            val appliedAlarm = appliedBoostAlarmVolume
            preDuckAlarmVolume = null
            appliedBoostAlarmVolume = null
            if (savedAlarm != null) {
                try {
                    val curAlarmNow = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    if (appliedAlarm == null || curAlarmNow == appliedAlarm) {
                        am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0)
                        Log.i(TAG, "Restored TTS alarm volume: $curAlarmNow -> $savedAlarm")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not restore alarm volume", e)
                }
            }
        }
        if (hasDuckFocus) {
            hasDuckFocus = false
            abandonDuckFocus()
        }
    }

    private fun requestDuckFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                    .also { focusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) {}
    }

    private fun abandonDuckFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }
}
