package com.remotescroll.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (`mediaProjection`) that captures internal YouTube audio via
 * AudioPlaybackCapture (excluding RemoteScroll's own UID so Khmer TTS never loops back)
 * and translates spoken dialogue from all languages into real-time Khmer subtitles/voice.
 */
class YouTubeTranslatorService : Service() {

    companion object {
        private const val TAG = "YouTubeTranslatorSvc"
        private const val CHANNEL_ID = "yt_translator_channel"
        private const val NOTIFICATION_ID = 1002

        private const val ACTION_START = "com.remotescroll.app.action.TRANSLATE_START"
        private const val ACTION_STOP = "com.remotescroll.app.action.TRANSLATE_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = 3_200 // 100 ms of 16 kHz mono 16-bit PCM
        private const val FREE_PARTIAL_THROTTLE_MS = 220L
        private const val FREE_PARTIAL_TTS_MAX_WAIT_MS = 420L
        private const val FREE_PARTIAL_TTS_CHUNK_CHARS = 28

        internal fun computeUnspokenDelta(current: String, committed: String): String {
            val cur = current.trim()
            val prev = committed.trim()
            if (cur.isEmpty()) return ""
            if (prev.isEmpty()) return cur
            if (cur.equals(prev, ignoreCase = true)) return ""
            if (cur.startsWith(prev, ignoreCase = true) &&
                (cur.length == prev.length || !cur[prev.length].isLetterOrDigit())
            ) {
                return cur.substring(prev.length).trimStart(' ', ',', '.', ';', ':', '-', '!', '?')
            }
            if (prev.contains(cur, ignoreCase = true)) return ""
            val curWords = cur.split(Regex("\\s+"))
            val prevWords = prev.split(Regex("\\s+"))
            var matched = 0
            val limit = minOf(curWords.size, prevWords.size)
            while (matched < limit && curWords[matched].equals(prevWords[matched], ignoreCase = true)) {
                matched++
            }
            if (matched > 0) {
                return curWords.drop(matched).joinToString(" ")
            }
            // Handle 2-line scrolling captions where the top words scroll off the left edge.
            val maxOverlap = minOf(prevWords.size, curWords.size)
            for (k in maxOverlap downTo 2) {
                var allMatch = true
                val prevStart = prevWords.size - k
                for (i in 0 until k) {
                    if (!prevWords[prevStart + i].equals(curWords[i], ignoreCase = true)) {
                        allMatch = false
                        break
                    }
                }
                if (allMatch) {
                    return curWords.drop(k).joinToString(" ")
                }
            }
            return cur
        }

        const val SUBTITLE_ACTIVE_HOLD_MS = 5_000L

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentEngineStatus = "Idle"
            private set

        @Volatile
        var currentSpeakerGender: SpeakerGender = SpeakerGender.UNKNOWN
            private set

        @Volatile
        var isQuotaCooldownActive = false
            private set

        @Volatile
        var lastSubtitleSeenUptimeMs: Long = 0L
            private set

        @Volatile
        private var instance: YouTubeTranslatorService? = null

        var onStateChanged: (() -> Unit)? = null

        fun isSubtitleSourceActive(
            lastSeenUptimeMs: Long,
            nowUptimeMs: Long,
            holdMs: Long = SUBTITLE_ACTIVE_HOLD_MS
        ): Boolean {
            if (lastSeenUptimeMs <= 0L) return false
            val elapsed = nowUptimeMs - lastSeenUptimeMs
            return elapsed in 0L..holdMs
        }

        fun isExternalSubtitleActiveNow(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean =
            isSubtitleSourceActive(lastSubtitleSeenUptimeMs, nowUptimeMs)

        fun notifyExternalSubtitleSeen(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean {
            val wasActive = isSubtitleSourceActive(lastSubtitleSeenUptimeMs, nowUptimeMs)
            lastSubtitleSeenUptimeMs = nowUptimeMs
            if (!wasActive) {
                instance?.onExternalSubtitleActivated()
            }
            return !wasActive
        }

        fun clearExternalSubtitleSeen() {
            lastSubtitleSeenUptimeMs = 0L
        }

        internal fun shouldRestartSpeechPipeOnResync(
            serviceRunning: Boolean,
            useFreeFallback: Boolean,
            subtitleActive: Boolean
        ): Boolean = serviceRunning && useFreeFallback && !subtitleActive

        fun restartRealtimeDetection() {
            KhmerTranslatorClient.resetCooldowns()
            clearQuotaCooldown()
            clearExternalSubtitleSeen()
            instance?.restartRealtimeDetectionInternal()
        }

        fun shouldUseFreeFallback(context: Context): Boolean =
            !TranslatorPrefs.hasGeminiApiKey(context) || isQuotaCooldownActive || !KhmerTranslatorClient.isAnyModelReady()

        fun clearQuotaCooldown() {
            isQuotaCooldownActive = false
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, YouTubeTranslatorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, YouTubeTranslatorService::class.java))
        }
    }

    private data class GeminiAudioChunk(
        val wav: ByteArray,
        val speakerGender: SpeakerGender
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runningFlag = AtomicBoolean(false)
    private val resyncGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val queueLock = Object()
    private val pendingWavQueue = ArrayDeque<GeminiAudioChunk>(2)
    private val recentContext = ArrayDeque<String>(4)
    private val rollingGenderTracker = RollingGenderTracker(sampleRateHz = SAMPLE_RATE)

    private data class FreeRequest(
        val text: String,
        val engineLabel: String,
        val speakVoice: Boolean,
        val updateSubtitle: Boolean,
        val speakerGender: SpeakerGender
    )

    private val freeTextLock = Object()
    private val pendingFinalQueue = ArrayDeque<FreeRequest>(4)
    private var pendingPartialReq: FreeRequest? = null
    private var freeWorkerThread: Thread? = null
    private var lastCachedFreeSource = ""
    private var lastCachedFreeResult: TranslationResult? = null

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var geminiWorkerThread: Thread? = null

    private var speechPipeOut: OutputStream? = null
    private var speechReadFd: ParcelFileDescriptor? = null
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var speechPipeActive = false
    @Volatile
    private var speechRestartPending = false
    private var speechPipeUnavailable = false
    private var retrySpeechWithoutAutoDetect = false
    private var speechQuickFailCount = 0
    private var speechSessionStartMs = 0L
    private var lastPartialTranslateMs = 0L
    private var lastRequestedFreeText = ""
    @Volatile
    private var lastCommittedFreeSource = ""
    @Volatile
    private var latestPendingFreeVoiceSource = ""
    @Volatile
    private var freeCommitDeadlineMs = 0L
    private val freePartialCommitRunnable = Runnable {
        freeCommitDeadlineMs = 0L
        val latest = latestPendingFreeVoiceSource
        if (latest.isNotEmpty() && runningFlag.get()) {
            commitPendingFreeVoice(latest)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.yt_translate_notif_text)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter mediaProjection foreground state", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startCaptureSession(resultCode, resultData)
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun startCaptureSession(resultCode: Int, resultData: Intent) {
        stopCaptureResources()

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            mgr?.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (projection == null) {
            notifyOverlayStatus("⚠️ Could not start YouTube audio capture")
            stopSelf()
            return
        }

        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system/user")
                mainHandler.post { stopSelf() }
            }
        }
        projection.registerCallback(cb, mainHandler)
        projectionCallback = cb
        mediaProjection = projection

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .excludeUid(Process.myUid())
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_BYTES * 2)

        val recorder = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 4))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord AudioPlaybackCapture build failed", e)
            null
        }

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            notifyOverlayStatus("⚠️ Audio capture unavailable (check Mic permission)")
            stopSelf()
            return
        }

        audioRecord = recorder
        runningFlag.set(true)
        isRunning = true

        synchronized(recentContext) { recentContext.clear() }
        rollingGenderTracker.reset()
        currentSpeakerGender = SpeakerGender.UNKNOWN
        lastCachedFreeSource = ""
        lastCachedFreeResult = null
        lastRequestedFreeText = ""
        lastPartialTranslateMs = 0L
        lastCommittedFreeSource = ""
        latestPendingFreeVoiceSource = ""
        freeCommitDeadlineMs = 0L
        lastSubtitleSeenUptimeMs = 0L
        currentSpeakerGender = SpeakerGender.UNKNOWN
        rollingGenderTracker.reset()
        mainHandler.removeCallbacks(freePartialCommitRunnable)
        synchronized(freeTextLock) {
            pendingFinalQueue.clear()
            pendingPartialReq = null
        }
        speechPipeUnavailable = false
        speechRestartPending = false
        retrySpeechWithoutAutoDetect = false

        val hasGeminiKey = TranslatorPrefs.hasGeminiApiKey(this)
        val aiReady = hasGeminiKey && KhmerTranslatorClient.isAnyModelReady()
        isQuotaCooldownActive = hasGeminiKey && !aiReady

        currentEngineStatus = when {
            aiReady -> "Gemini SSE AI (All → ខ្មែរ)"
            hasGeminiKey -> "Hybrid Free + CC (AI cooldown)"
            else -> "Free Live (STT / CC → ខ្មែរ)"
        }
        GestureAccessibilityService.instance?.onTranslatorStarted()
        notifyOverlayStatus(
            when {
                aiReady -> "🇰🇭 LIVE • Gemini SSE listening..."
                hasGeminiKey -> "⚡ AI cooldown -> Hybrid Live Free + CC..."
                else -> "🇰🇭 LIVE • Free streaming listening..."
            }
        )
        mainHandler.post { onStateChanged?.invoke() }

        startFreeTranslateWorker()
        if (hasGeminiKey) {
            startGeminiWorker()
        }
        if (shouldUseFreeFallback(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                speechQuickFailCount = 0
                mainHandler.post { startAndroidSpeechPipeSession() }
            } else {
                notifyOverlayStatus("🇰🇭 Turn ON YouTube CC / Live Caption (or add Gemini key)")
            }
        }

        recorder.startRecording()
        startCaptureLoop(recorder)
        Log.i(TAG, "YouTubeTranslatorService started (gemini=${hasGeminiKey}, aiReady=${aiReady})")
    }

    private fun startCaptureLoop(recorder: AudioRecord) {
        val segmenter = VoiceSpeechSegmenter { pcmSegment ->
            if (!runningFlag.get()) return@VoiceSpeechSegmenter
            val detectedGender = VoiceGenderDetector.detectSegmentGender(
                pcmSegment,
                SAMPLE_RATE,
                rollingGenderTracker.currentGender
            )
            if (detectedGender != SpeakerGender.UNKNOWN) {
                rollingGenderTracker.updateFromSegmentGender(detectedGender)
                currentSpeakerGender = detectedGender
            }
            val apiKey = TranslatorPrefs.getGeminiApiKey(this)
            if (apiKey.isNotBlank() && KhmerTranslatorClient.isAnyModelReady()) {
                val wav = WavEncoder.pcm16MonoToWav(pcmSegment, SAMPLE_RATE)
                enqueueWavForGemini(wav, detectedGender)
            }
        }

        captureThread = Thread({
            val buf = ByteArray(FRAME_BYTES)
            var pausedForOtherApp = false
            var pausedForSubtitles = false
            var wasUsingGemini = TranslatorPrefs.hasGeminiApiKey(this) && KhmerTranslatorClient.isAnyModelReady()

            while (runningFlag.get()) {
                val read = try {
                    recorder.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord read ended", e)
                    break
                }
                if (read <= 0) continue

                val onlyYt = TranslatorPrefs.isOnlyYouTube(this)
                val ytActive = GestureAccessibilityService.instance?.isYouTubeOrOwnForeground() ?: true
                if (onlyYt && !ytActive) {
                    if (!pausedForOtherApp) {
                        pausedForOtherApp = true
                        segmenter.reset()
                        rollingGenderTracker.reset()
                        currentSpeakerGender = SpeakerGender.UNKNOWN
                    }
                    continue
                }
                if (pausedForOtherApp) {
                    pausedForOtherApp = false
                    notifyOverlayStatus("🇰🇭 LIVE • Listening YouTube...")
                }

                val slice = if (read == buf.size) buf else buf.copyOf(read)
                val liveGender = rollingGenderTracker.feedFrame(slice)
                if (liveGender != SpeakerGender.UNKNOWN) {
                    currentSpeakerGender = liveGender
                }

                // Auto-subtitle priority: yield competing audio STT/Gemini while on-screen CC is active,
                // while keeping PCM gender detection alive so Male/Female pitch adapts in real time.
                if (isExternalSubtitleActiveNow()) {
                    if (!pausedForSubtitles) {
                        pausedForSubtitles = true
                        segmenter.reset()
                    }
                    continue
                }
                if (pausedForSubtitles) {
                    pausedForSubtitles = false
                    lastCommittedFreeSource = ""
                    latestPendingFreeVoiceSource = ""
                    val hasGemini = TranslatorPrefs.hasGeminiApiKey(this)
                    val aiReady = hasGemini && KhmerTranslatorClient.isAnyModelReady()
                    currentEngineStatus = when {
                        aiReady -> "Gemini SSE AI (All → ខ្មែរ)"
                        hasGemini -> "Hybrid Free + CC (AI cooldown)"
                        else -> "Free Live (STT / CC → ខ្មែរ)"
                    }
                    mainHandler.post { onStateChanged?.invoke() }
                    notifyOverlayStatus(
                        if (aiReady) "🇰🇭 LIVE • Gemini SSE listening..."
                        else "🇰🇭 LIVE • Free listening (no CC)..."
                    )
                }

                val useGeminiNow = TranslatorPrefs.hasGeminiApiKey(this) && KhmerTranslatorClient.isAnyModelReady()
                if (useGeminiNow) {
                    if (!wasUsingGemini || isQuotaCooldownActive) {
                        isQuotaCooldownActive = false
                        speechPipeActive = false
                        speechRestartPending = false
                        mainHandler.post {
                            tearDownSpeechPipeOnly()
                            onStateChanged?.invoke()
                        }
                        notifyOverlayStatus("🇰🇭 LIVE • Gemini SSE resumed")
                    }
                    wasUsingGemini = true
                    segmenter.feedFrame(slice)
                } else {
                    if (wasUsingGemini) {
                        segmenter.reset()
                    }
                    wasUsingGemini = false
                    if (speechPipeActive) {
                        writeToSpeechPipe(slice)
                    } else if (!speechPipeUnavailable && !speechRestartPending &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        speechRestartPending = true
                        mainHandler.post {
                            if (runningFlag.get() && shouldUseFreeFallback(this) && !speechPipeActive && !speechPipeUnavailable) {
                                speechQuickFailCount = 0
                                startAndroidSpeechPipeSession()
                            } else {
                                speechRestartPending = false
                            }
                        }
                    }
                }
            }
            segmenter.flush()
        }, "YTTranslatorCapture").apply { start() }
    }

    private fun enqueueWavForGemini(wav: ByteArray, speakerGender: SpeakerGender) {
        if (isExternalSubtitleActiveNow()) return
        synchronized(queueLock) {
            // Keep only the latest chunk so voice translation stays real-time behind live video
            pendingWavQueue.clear()
            pendingWavQueue.addLast(GeminiAudioChunk(wav, speakerGender))
            queueLock.notifyAll()
        }
    }

    private fun startGeminiWorker() {
        geminiWorkerThread = Thread({
            while (runningFlag.get()) {
                val chunk = synchronized(queueLock) {
                    while (runningFlag.get() && pendingWavQueue.isEmpty()) {
                        try {
                            queueLock.wait(500L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (!runningFlag.get() || pendingWavQueue.isEmpty()) null else pendingWavQueue.removeFirst()
                } ?: continue

                val apiKey = TranslatorPrefs.getGeminiApiKey(this)
                if (apiKey.isBlank()) continue
                val chunkGen = resyncGeneration.get()

                val contextSnapshot = synchronized(recentContext) { recentContext.toList() }
                try {
                    val result = KhmerTranslatorClient.translateAudioWithGeminiStream(
                        wavBytes = chunk.wav,
                        apiKey = apiKey,
                        recentContext = contextSnapshot,
                        onPartial = { partial ->
                            if (runningFlag.get() && resyncGeneration.get() == chunkGen && !isExternalSubtitleActiveNow()) {
                                val tagged = partial.copy(speakerGender = chunk.speakerGender)
                                mainHandler.post {
                                    if (resyncGeneration.get() == chunkGen && !isExternalSubtitleActiveNow()) {
                                        GestureAccessibilityService.instance?.deliverKhmerTranslation(tagged)
                                    }
                                }
                            }
                        }
                    )
                    if (resyncGeneration.get() != chunkGen) continue
                    if (isQuotaCooldownActive) {
                        isQuotaCooldownActive = false
                        mainHandler.post { onStateChanged?.invoke() }
                    }
                    if (result != null && !result.skipped && result.khmerText.isNotBlank()) {
                        synchronized(recentContext) {
                            recentContext.addLast(result.khmerText)
                            while (recentContext.size > 3) recentContext.removeFirst()
                        }
                        if (runningFlag.get() && resyncGeneration.get() == chunkGen && !isExternalSubtitleActiveNow()) {
                            val tagged = result.copy(speakerGender = chunk.speakerGender)
                            mainHandler.post {
                                if (resyncGeneration.get() == chunkGen && !isExternalSubtitleActiveNow()) {
                                    GestureAccessibilityService.instance?.deliverKhmerTranslation(tagged)
                                }
                            }
                        }
                    }
                } catch (e: QuotaExhaustedException) {
                    Log.w(TAG, "Gemini quota cooldown (${e.retryAfterMs}ms): ${e.message}")
                    isQuotaCooldownActive = true
                    synchronized(queueLock) { pendingWavQueue.clear() }
                    notifyOverlayStatus("⚡ AI quota limit -> Hybrid Live Free + CC active")
                    mainHandler.post {
                        onStateChanged?.invoke()
                        if (runningFlag.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !speechPipeActive && !speechPipeUnavailable && !speechRestartPending
                        ) {
                            speechQuickFailCount = 0
                            startAndroidSpeechPipeSession()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini translate failed", e)
                    val msg = e.message.orEmpty()
                    val statusHint = when {
                        msg.contains("400") || msg.contains("401") || msg.contains("403") || msg.contains("API_KEY", true) ->
                            "⚠️ Gemini API key invalid"
                        else -> "⚠️ Network retrying..."
                    }
                    notifyOverlayStatus(statusHint)
                    try {
                        Thread.sleep(1_000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "YTGeminiWorker").apply { start() }
    }

    private fun writeToSpeechPipe(pcmSlice: ByteArray) {
        val out = speechPipeOut ?: return
        try {
            out.write(pcmSlice)
        } catch (_: Exception) {
            speechPipeOut = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startAndroidSpeechPipeSession() {
        speechRestartPending = false
        if (!runningFlag.get() || !shouldUseFreeFallback(this)) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speechPipeUnavailable = true
            notifyOverlayStatus("🇰🇭 Live CC active (turn ON YT CC or Live Caption)")
            return
        }

        tearDownSpeechPipeOnly()

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]
            speechReadFd = readFd
            speechPipeOut = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)

            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            speechPipeActive = true
            speechSessionStartMs = SystemClock.uptimeMillis()
            val selectedLang = TranslatorPrefs.getFreeSourceLang(this)

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    if (isExternalSubtitleActiveNow()) return
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.length < 3) return
                    val now = SystemClock.uptimeMillis()

                    val delta = computeUnspokenDelta(text, lastCommittedFreeSource)
                    val endsClause = text.last() in charArrayOf('.', '?', '!', ',', ';', '។')
                    if (delta.length >= FREE_PARTIAL_TTS_CHUNK_CHARS || (delta.length >= 6 && endsClause)) {
                        // Commit subtitle & voice from a single translation request so voice never lags behind text.
                        mainHandler.removeCallbacks(freePartialCommitRunnable)
                        freeCommitDeadlineMs = 0L
                        lastPartialTranslateMs = now
                        commitPendingFreeVoice(text)
                        return
                    }

                    if (text != lastRequestedFreeText &&
                        now - lastPartialTranslateMs >= FREE_PARTIAL_THROTTLE_MS
                    ) {
                        lastPartialTranslateMs = now
                        requestFreeTranslation(
                            text = text,
                            speakVoice = false,
                            updateSubtitle = true,
                            engineLabel = "FREE"
                        )
                    }

                    if (delta.length >= 6) {
                        latestPendingFreeVoiceSource = text
                        if (freeCommitDeadlineMs == 0L) {
                            freeCommitDeadlineMs = now + FREE_PARTIAL_TTS_MAX_WAIT_MS
                            mainHandler.postDelayed(freePartialCommitRunnable, FREE_PARTIAL_TTS_MAX_WAIT_MS)
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    speechPipeActive = false
                    try {
                        readFd.close()
                    } catch (_: Exception) {}
                    lastPartialTranslateMs = 0L
                    mainHandler.removeCallbacks(freePartialCommitRunnable)
                    freeCommitDeadlineMs = 0L
                    latestPendingFreeVoiceSource = ""
                    if (isExternalSubtitleActiveNow()) {
                        lastCommittedFreeSource = ""
                        return
                    }

                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isNotEmpty()) {
                        speechQuickFailCount = 0
                        val prevCommitted = lastCommittedFreeSource
                        val delta = computeUnspokenDelta(text, prevCommitted)
                        if (delta.length >= 2) {
                            requestFreeTranslation(
                                text = delta,
                                speakVoice = true,
                                updateSubtitle = (prevCommitted.isEmpty() || delta == text),
                                engineLabel = "FREE"
                            )
                        }
                        if (prevCommitted.isNotEmpty() && delta != text && text != lastRequestedFreeText) {
                            requestFreeTranslation(
                                text = text,
                                speakVoice = false,
                                updateSubtitle = true,
                                engineLabel = "FREE"
                            )
                        }
                    }
                    lastCommittedFreeSource = ""
                    restartSpeechPipeDelayed(80L)
                }

                override fun onError(error: Int) {
                    val elapsed = SystemClock.uptimeMillis() - speechSessionStartMs
                    Log.w(TAG, "SpeechRecognizer audio pipe error=$error elapsed=${elapsed}ms")
                    speechPipeActive = false
                    try {
                        readFd.close()
                    } catch (_: Exception) {}

                    mainHandler.removeCallbacks(freePartialCommitRunnable)
                    freeCommitDeadlineMs = 0L
                    val leftover = latestPendingFreeVoiceSource
                    if (leftover.isNotEmpty()) {
                        commitPendingFreeVoice(leftover)
                    }
                    lastCommittedFreeSource = ""
                    latestPendingFreeVoiceSource = ""

                    if (elapsed < 1_500L && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        if (selectedLang == TranslatorPrefs.LANG_AUTO && !retrySpeechWithoutAutoDetect) {
                            retrySpeechWithoutAutoDetect = true
                        } else {
                            speechQuickFailCount++
                        }
                    }

                    if (speechQuickFailCount >= 3) {
                        speechPipeUnavailable = true
                        tearDownSpeechPipeOnly()
                        notifyOverlayStatus("🇰🇭 Live CC active (turn ON YT CC or Live Caption)")
                        return
                    }
                    restartSpeechPipeDelayed(if (elapsed < 1_500L) 200L else 100L)
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                if (selectedLang == TranslatorPrefs.LANG_AUTO && !retrySpeechWithoutAutoDetect) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                } else if (selectedLang != TranslatorPrefs.LANG_AUTO) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLang)
                } else {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                }
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            speechPipeActive = false
            speechPipeUnavailable = true
            Log.w(TAG, "Failed to attach SpeechRecognizer audio pipe", e)
            notifyOverlayStatus("🇰🇭 Live CC active (turn ON YT CC or Live Caption)")
        }
    }

    private fun commitPendingFreeVoice(fullText: String) {
        if (isExternalSubtitleActiveNow()) {
            lastCommittedFreeSource = ""
            latestPendingFreeVoiceSource = ""
            return
        }
        val delta = computeUnspokenDelta(fullText, lastCommittedFreeSource)
        if (delta.length < 2) {
            lastCommittedFreeSource = fullText
            latestPendingFreeVoiceSource = ""
            return
        }
        val updateSub = lastCommittedFreeSource.isEmpty()
        lastCommittedFreeSource = fullText
        latestPendingFreeVoiceSource = ""
        requestFreeTranslation(
            text = delta,
            speakVoice = true,
            updateSubtitle = updateSub,
            engineLabel = "FREE"
        )
    }

    private fun restartSpeechPipeDelayed(delayMs: Long) {
        if (!runningFlag.get() || !shouldUseFreeFallback(this) || speechPipeUnavailable ||
            speechRestartPending || isExternalSubtitleActiveNow()
        ) return
        speechRestartPending = true
        mainHandler.postDelayed({
            speechRestartPending = false
            if (runningFlag.get() && shouldUseFreeFallback(this) &&
                !isExternalSubtitleActiveNow() &&
                !speechPipeUnavailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                startAndroidSpeechPipeSession()
            }
        }, delayMs)
    }

    private fun onExternalSubtitleActivated() {
        lastCommittedFreeSource = ""
        latestPendingFreeVoiceSource = ""
        freeCommitDeadlineMs = 0L
        mainHandler.removeCallbacks(freePartialCommitRunnable)
        synchronized(queueLock) { pendingWavQueue.clear() }
        synchronized(freeTextLock) {
            pendingFinalQueue.clear()
            pendingPartialReq = null
        }
        speechRestartPending = false
        speechPipeActive = false
        mainHandler.post {
            tearDownSpeechPipeOnly()
            onStateChanged?.invoke()
        }
        currentEngineStatus = "Auto Subtitle (CC On-Time → ខ្មែរ)"
        notifyOverlayStatus("🇰🇭 CC AUTO • On-Time Subtitle Voice")
    }

    private fun restartRealtimeDetectionInternal() {
        if (!runningFlag.get()) return
        val gen = resyncGeneration.incrementAndGet()
        Log.i(TAG, "restartRealtimeDetectionInternal(gen=$gen): flushing stale audio/STT buffers")

        synchronized(recentContext) { recentContext.clear() }
        rollingGenderTracker.reset()
        currentSpeakerGender = SpeakerGender.UNKNOWN
        lastCachedFreeSource = ""
        lastCachedFreeResult = null
        lastRequestedFreeText = ""
        lastPartialTranslateMs = 0L
        lastCommittedFreeSource = ""
        latestPendingFreeVoiceSource = ""
        freeCommitDeadlineMs = 0L
        speechPipeUnavailable = false
        speechQuickFailCount = 0
        speechRestartPending = false

        synchronized(queueLock) { pendingWavQueue.clear() }
        synchronized(freeTextLock) {
            pendingFinalQueue.clear()
            pendingPartialReq = null
        }

        mainHandler.removeCallbacks(freePartialCommitRunnable)
        val hasGeminiKey = TranslatorPrefs.hasGeminiApiKey(this)
        val aiReady = hasGeminiKey && KhmerTranslatorClient.isAnyModelReady()
        isQuotaCooldownActive = hasGeminiKey && !aiReady
        currentEngineStatus = when {
            aiReady -> "Gemini SSE AI (All → ខ្មែរ)"
            hasGeminiKey -> "Hybrid Free + CC (AI cooldown)"
            else -> "Free Live (STT / CC → ខ្មែរ)"
        }
        notifyOverlayStatus("🔄 Live Resynced • Real-Time...")

        mainHandler.post {
            if (!runningFlag.get() || resyncGeneration.get() != gen) return@post
            speechPipeActive = false
            tearDownSpeechPipeOnly()
            onStateChanged?.invoke()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                shouldRestartSpeechPipeOnResync(
                    serviceRunning = runningFlag.get(),
                    useFreeFallback = shouldUseFreeFallback(this),
                    subtitleActive = isExternalSubtitleActiveNow()
                )
            ) {
                startAndroidSpeechPipeSession()
            }
        }
    }

    private fun startFreeTranslateWorker() {
        freeWorkerThread?.interrupt()
        freeWorkerThread = Thread({
            while (runningFlag.get()) {
                val req = synchronized(freeTextLock) {
                    while (runningFlag.get() && pendingFinalQueue.isEmpty() && pendingPartialReq == null) {
                        try {
                            freeTextLock.wait(500L)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                    }
                    when {
                        !runningFlag.get() -> null
                        pendingFinalQueue.isNotEmpty() -> pendingFinalQueue.removeFirst()
                        else -> {
                            val p = pendingPartialReq
                            pendingPartialReq = null
                            p
                        }
                    }
                } ?: continue

                if (req.text == lastCachedFreeSource && lastCachedFreeResult != null) {
                    val cached = lastCachedFreeResult!!.copy(
                        isPartial = !req.speakVoice,
                        speakVoice = req.speakVoice,
                        updateSubtitle = req.updateSubtitle,
                        speakerGender = req.speakerGender
                    )
                    val reqGen = resyncGeneration.get()
                    mainHandler.post {
                        if (runningFlag.get() && resyncGeneration.get() == reqGen && !isExternalSubtitleActiveNow()) {
                            GestureAccessibilityService.instance?.deliverKhmerTranslation(cached)
                        }
                    }
                    continue
                }

                val reqGen = resyncGeneration.get()
                try {
                    val res = KhmerTranslatorClient.translateTextToKhmer(req.text, engineLabel = req.engineLabel)
                    if (res != null && res.khmerText.isNotBlank() && resyncGeneration.get() == reqGen) {
                        lastCachedFreeSource = req.text
                        lastCachedFreeResult = res
                        val delivered = res.copy(
                            isPartial = !req.speakVoice,
                            speakVoice = req.speakVoice,
                            updateSubtitle = req.updateSubtitle,
                            speakerGender = req.speakerGender
                        )
                        if (runningFlag.get() && !isExternalSubtitleActiveNow()) {
                            mainHandler.post {
                                if (resyncGeneration.get() == reqGen && !isExternalSubtitleActiveNow()) {
                                    GestureAccessibilityService.instance?.deliverKhmerTranslation(delivered)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Free text translate error", e)
                }
            }
        }, "YTFreeTranslateWorker").apply { start() }
    }

    private fun requestFreeTranslation(
        text: String,
        speakVoice: Boolean,
        updateSubtitle: Boolean,
        engineLabel: String
    ) {
        if (!speakVoice && text == lastRequestedFreeText) return
        lastRequestedFreeText = text
        val detectedGender = currentSpeakerGender
        if (text == lastCachedFreeSource && lastCachedFreeResult != null) {
            val cached = lastCachedFreeResult!!.copy(
                isPartial = !speakVoice,
                speakVoice = speakVoice,
                updateSubtitle = updateSubtitle,
                speakerGender = detectedGender
            )
            mainHandler.post {
                if (runningFlag.get()) {
                    GestureAccessibilityService.instance?.deliverKhmerTranslation(cached)
                }
            }
            return
        }
        val req = FreeRequest(
            text = text,
            engineLabel = engineLabel,
            speakVoice = speakVoice,
            updateSubtitle = updateSubtitle,
            speakerGender = detectedGender
        )
        synchronized(freeTextLock) {
            if (speakVoice) {
                while (pendingFinalQueue.size >= 3) {
                    pendingFinalQueue.removeFirst()
                }
                pendingFinalQueue.addLast(req)
            } else {
                pendingPartialReq = req
            }
            freeTextLock.notifyAll()
        }
    }

    private fun notifyOverlayStatus(status: String) {
        currentEngineStatus = status
        mainHandler.post {
            GestureAccessibilityService.instance?.updateTranslatorStatus(status)
        }
    }

    private fun tearDownSpeechPipeOnly() {
        try {
            speechPipeOut?.close()
        } catch (_: Exception) {}
        speechPipeOut = null
        try {
            speechReadFd?.close()
        } catch (_: Exception) {}
        speechReadFd = null
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun stopCaptureResources() {
        runningFlag.set(false)
        isRunning = false
        isQuotaCooldownActive = false
        lastSubtitleSeenUptimeMs = 0L
        speechRestartPending = false
        lastCommittedFreeSource = ""
        latestPendingFreeVoiceSource = ""
        freeCommitDeadlineMs = 0L
        currentSpeakerGender = SpeakerGender.UNKNOWN
        rollingGenderTracker.reset()
        mainHandler.removeCallbacks(freePartialCommitRunnable)
        synchronized(queueLock) {
            pendingWavQueue.clear()
            queueLock.notifyAll()
        }
        synchronized(freeTextLock) {
            pendingFinalQueue.clear()
            pendingPartialReq = null
            freeTextLock.notifyAll()
        }
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        captureThread?.interrupt()
        captureThread = null
        geminiWorkerThread?.interrupt()
        geminiWorkerThread = null
        freeWorkerThread?.interrupt()
        freeWorkerThread = null

        speechPipeActive = false
        mainHandler.post { tearDownSpeechPipeOnly() }

        val proj = mediaProjection
        val cb = projectionCallback
        if (proj != null && cb != null) {
            try {
                proj.unregisterCallback(cb)
            } catch (_: Exception) {}
        }
        try {
            proj?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        projectionCallback = null
    }

    override fun onDestroy() {
        stopCaptureResources()
        currentEngineStatus = "Idle"
        if (instance === this) instance = null
        GestureAccessibilityService.instance?.onTranslatorStopped()
        mainHandler.post { onStateChanged?.invoke() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.yt_translate_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.yt_translate_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0,
                getString(R.string.yt_translate_notif_stop),
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, YouTubeTranslatorService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
}
