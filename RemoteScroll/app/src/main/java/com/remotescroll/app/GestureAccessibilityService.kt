package com.remotescroll.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Performs swipes and taps with dispatchGesture().
 *
 * It also filters key events. Remotes that connect as a Bluetooth HID keyboard send media
 * keys through the input pipeline, and this catches them before any app does. Remotes
 * that use AVRCP (most headphones and media remotes) reach [RemoteMediaService]'s
 * MediaSession instead. A key consumed here never reaches the MediaSession, so a press
 * is never handled twice.
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GestureA11yService"
        private const val SWIPE_DURATION_MS = 200L
        private const val TAP_DURATION_MS = 50L
        /** Gap between the two taps of a double tap (TikTok's "like"). */
        private const val DOUBLE_TAP_GAP_MS = 100L

        private const val PREFS = "remote_scroll"
        private const val KEY_AUTO_LOCK_SCROLLS = "auto_lock_scrolls"
        private const val KEY_MINI_CONTROL = "mini_control_enabled"

        /** How many scrolls before pocket lock turns on automatically, or 0 = off. */
        fun autoLockScrolls(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_AUTO_LOCK_SCROLLS, 0)

        fun setAutoLockScrolls(context: Context, count: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_AUTO_LOCK_SCROLLS, count.coerceAtLeast(0)).apply()
        }

        /** Whether the floating on-screen mini controller is enabled on supported video apps. */
        fun isMiniControlEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MINI_CONTROL, true)

        fun setMiniControlEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MINI_CONTROL, enabled).apply()
        }

        private const val KEY_MINI_CONTROL_ALWAYS = "mini_control_always_on"

        /** If true, the mini controller stays on screen across all apps instead of video apps only. */
        fun isMiniControlAlwaysOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MINI_CONTROL_ALWAYS, false)

        fun setMiniControlAlwaysOn(context: Context, alwaysOn: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MINI_CONTROL_ALWAYS, alwaysOn).apply()
        }

        private const val KEY_AUTO_SKIP_ADS = "auto_skip_ads"
        private const val KEY_INACTIVITY_SLEEP_MINS = "inactivity_sleep_mins"

        /** Auto-skip sponsored ads and LIVE streams. */
        fun isAutoSkipAdsEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_SKIP_ADS, false)

        fun setAutoSkipAdsEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_SKIP_ADS, enabled).apply()
        }

        /** Inactivity sleep timeout in minutes, or 0 = disabled. */
        fun inactivitySleepMinutes(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_INACTIVITY_SLEEP_MINS, 0)

        fun setInactivitySleepMinutes(context: Context, minutes: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_INACTIVITY_SLEEP_MINS, minutes.coerceAtLeast(0)).apply()
        }

        private const val KEY_AUTO_PILOT_SECONDS = "auto_pilot_seconds"
        private const val KEY_AUTO_PILOT_RESET_ON_SCROLL = "auto_pilot_reset_on_scroll"

        fun autoPilotSeconds(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_AUTO_PILOT_SECONDS, 0)

        fun setAutoPilotSeconds(context: Context, seconds: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_AUTO_PILOT_SECONDS, seconds.coerceAtLeast(0)).apply()
        }

        fun isAutoPilotResetOnScroll(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_PILOT_RESET_ON_SCROLL, true)

        fun setAutoPilotResetOnScroll(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_PILOT_RESET_ON_SCROLL, enabled).apply()
        }

        /** Usual position of TikTok's bookmark (Save) icon, as a fraction of the screen. */
        // Measured from a 1152x2560 screenshot: bookmark icon centre at (1068, 1930).
        private const val SAVE_ICON_X = 0.927f
        private const val SAVE_ICON_Y = 0.754f

        /**
         * Words in the label of TikTok's Save / Favorites button, in the languages TikTok
         * is most used in around here. Lower case.
         */
        private val SAVE_WORDS = listOf(
            "favorite", "favourite", "bookmark", "save",  // English
            "yêu thích", "lưu",                           // Vietnamese
            "รายการโปรด", "บันทึก",                         // Thai
            "kegemaran", "simpan", "favorit",             // Malay / Indonesian
            "收藏",                                        // Chinese
            "お気に入り", "즐겨찾기"                         // Japanese / Korean
        )

        /** Time for the window manager to apply FLAG_NOT_TOUCHABLE before we inject. */
        private const val PASS_THROUGH_SETTLE_MS = 60L
        /** Keep the overlay see-through a moment longer so the gesture's last events land. */
        private const val PASS_THROUGH_RESTORE_MS = 80L
        /** After a remote connects, wait this long before starting (connection settles). */
        private const val AUTO_START_DELAY_MS = 1_500L
        private const val AUTO_SKIP_COOLDOWN_MS = 2_500L
        private const val AUTO_START_RETRY_MS = 10_000L

        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        /** True if the user turned this service on in system Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, GestureAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }

    private var keyHandler: MediaKeyHandler? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lockOverlay: LockOverlay? = null
    private var sleepBlackoutOverlay: SleepBlackoutOverlay? = null
    private var miniControlOverlay: MiniControlOverlay? = null
    private var calibrationOverlay: PointsCalibrationOverlay? = null
    private var floatingNavOverlay: FloatingNavOverlay? = null
    private var youTubeAdSkipper: YouTubeAdSkipper? = null
    private var facebookAdSkipper: FacebookAdSkipper? = null
    private var khmerSubtitleOverlay: KhmerSubtitleOverlay? = null
    private var khmerVoiceSpeaker: KhmerVoiceSpeaker? = null
    private val captionExecutor = Executors.newSingleThreadExecutor()
    private val latestPendingCaption = AtomicReference<String?>(null)
    private val captionWorkerBusy = AtomicBoolean(false)
    private var pendingCaptionTtsResult: TranslationResult? = null
    private var firstCaptionUnsettledUptimeMs = 0L
    private val captionResyncGen = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var lastSpokenCaptionSource = ""
    private var lastCaptionText = ""
    private var captionCheckPending = false
    private val captionCheckRunnable = Runnable {
        captionCheckPending = false
        checkAndTranslateLiveCaption()
    }
    private val captionTtsRunnable = Runnable {
        firstCaptionUnsettledUptimeMs = 0L
        val settled = pendingCaptionTtsResult ?: return@Runnable
        pendingCaptionTtsResult = null
        if (settled.sourceText == lastSpokenCaptionSource) return@Runnable
        if (TranslatorPrefs.isVoiceTtsEnabled(this) &&
            (YouTubeTranslatorService.isRunning || TranslatorPrefs.isSubtitleEnabled(this))
        ) {
            lastSpokenCaptionSource = settled.sourceText
            khmerVoiceSpeaker?.speak(settled.khmerText, settled.speakerGender)
        }
    }

    var onYouTubeSkipCountChanged: ((Int) -> Unit)? = null

    /** Counts scroll gestures (swipe up / swipe down) since the last unlock or service start. */
    @Volatile
    var scrollCount = 0

    val isLocked: Boolean get() = lockOverlay?.isShowing == true
    val isSleepBlackout: Boolean get() = sleepBlackoutOverlay?.isShowing == true

    /** Auto-Pilot / Timed Auto-Scroll interval in seconds (0 = off). */
    @Volatile
    var autoPilotSeconds = 0
        private set

    @Volatile
    var autoPilotRemaining = 0
        private set

    @Volatile
    var isAutoPilotPaused = false
        private set

    val isAutoPilotActive: Boolean get() = autoPilotSeconds > 0

    /** Package of the app window in front (from window-state events), or null. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    /**
     * The supported video app in front, or null if another app is.
     * Uses multi-level fallback (cached pkg -> rootInActiveWindow -> application windows)
     * so transient popups/keyboards never corrupt foreground detection.
     */
    val foregroundVideoApp: VideoApp?
        get() {
            // 1. Check cached foregroundPackage
            VideoApp.forPackage(foregroundPackage)?.let { return it }

            // 2. Check rootInActiveWindow
            try {
                val activePkg = rootInActiveWindow?.packageName?.toString()
                if (activePkg != null && !isTransientOrSystemPackage(activePkg)) {
                    VideoApp.forPackage(activePkg)?.let {
                        foregroundPackage = activePkg
                        return it
                    }
                }
            } catch (_: Exception) {}

            // 3. Check active application windows on screen
            try {
                for (win in windows) {
                    if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val winPkg = win.root?.packageName?.toString() ?: continue
                        if (!isTransientOrSystemPackage(winPkg)) {
                            VideoApp.forPackage(winPkg)?.let {
                                foregroundPackage = winPkg
                                return it
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            return null
        }

    /** True if package is a transient system component (keyboard, system dialog, autofill, etc.) */
    fun isTransientOrSystemPackage(pkg: String): Boolean {
        if (pkg == packageName) return true // our own overlay windows
        val lower = pkg.lowercase()
        if (lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("ime")) return true
        if (lower == "com.android.systemui" || lower.endsWith(".systemui")) return true
        if (lower == "android" || lower.contains("intentresolver") || lower.contains("permissioncontroller")) return true
        if (lower.contains("autofill") || lower == "com.google.android.gms" || lower == "com.google.android.as") return true
        return false
    }

    private var proximity: ProximityLock? = null
    /** Pocket lock was switched on by the proximity sensor, so it may switch it off too. */
    private var lockedByProximity = false

    @Volatile
    private var lastActivityUptimeMs = SystemClock.uptimeMillis()

    fun noteUserActivity() {
        lastActivityUptimeMs = SystemClock.uptimeMillis()
    }

    private val inactivityCheckRunnable = object : Runnable {
        override fun run() {
            checkInactivity()
            handler.postDelayed(this, 30_000L)
        }
    }

    private fun checkInactivity() {
        val timeoutMins = inactivitySleepMinutes(this)
        if (timeoutMins <= 0 || !RemoteMediaService.isRunning) return
        val inactiveMs = SystemClock.uptimeMillis() - lastActivityUptimeMs
        if (inactiveMs >= timeoutMins * 60_000L) {
            KeyLog.note("smart sleep: no activity for $timeoutMins min, putting phone to sleep")
            sleepNow()
            RemoteMediaService.stop(this)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        keyHandler = MediaKeyHandler(this, "key filter", swallowIgnored = false)
        lockOverlay = LockOverlay(this).apply {
            onUnlockedByUser = {
                lockedByProximity = false
                scrollCount = 0
                KeyLog.note("unlocked by user: reset scroll count to 0")
                FeedbackSound.lockOff()
                updateMiniControl()
                miniControlOverlay?.updateStatus(0, autoLockScrolls(this@GestureAccessibilityService))
                RemoteMediaService.refreshNotification(this@GestureAccessibilityService)
            }
        }
        sleepBlackoutOverlay = SleepBlackoutOverlay(this).apply {
            onDismissed = {
                updateMiniControl()
                updateFloatingNav()
                KeyLog.note("sleep blackout mode off")
            }
        }
        miniControlOverlay = MiniControlOverlay(this)
        calibrationOverlay = PointsCalibrationOverlay(this)
        floatingNavOverlay = FloatingNavOverlay(this)
        youTubeAdSkipper = YouTubeAdSkipper(this)
        facebookAdSkipper = FacebookAdSkipper(this)
        khmerSubtitleOverlay = KhmerSubtitleOverlay(this)
        khmerVoiceSpeaker = KhmerVoiceSpeaker(this)
        if (YouTubeTranslatorService.isRunning) {
            onTranslatorStarted()
        }
        proximity = ProximityLock(this, onCovered = ::onPhoneCovered, onUncovered = ::onPhoneUncovered)
        updateProximity()
        autoPilotSeconds = autoPilotSeconds(this)
        autoPilotRemaining = autoPilotSeconds
        updateMiniControl()
        updateFloatingNav()
        registerBluetoothReceiver()
        handler.postDelayed(inactivityCheckRunnable, 30_000L)
        handler.postDelayed(autoPilotTicker, 1_000L)
        scheduleAutoSkipCheck(800L, force = true)
        Log.i(TAG, "Accessibility service connected")
    }

    // ------------------------------------------------------------- proximity lock

    /** Listen to the proximity sensor only while it is wanted: setting on and remote running. */
    fun updateProximity() {
        proximity?.setActive(ProximityLock.isEnabled(this) && RemoteMediaService.isRunning)
    }

    private fun onPhoneCovered() {
        if (isLocked || foregroundVideoApp == null) return
        KeyLog.note("proximity: covered, pocket lock on")
        showLock()
        lockedByProximity = isLocked
    }

    private fun onPhoneUncovered() {
        if (!lockedByProximity || !isLocked) return
        KeyLog.note("proximity: uncovered, pocket lock off")
        hideLock()
    }

    // ------------------------------------------------------- bluetooth auto-start

    private var bluetoothReceiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!AutoStart.isEnabled(context)) return
            val device = IntentCompat.getParcelableExtra(
                intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
            ) ?: return
            val name = try {
                @Suppress("MissingPermission") // receiving this broadcast already needs it
                device.name
            } catch (e: SecurityException) {
                null
            } ?: return
            if (RemoteFilter.selected(context).none { it.equals(name, ignoreCase = true) }) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onRemoteConnected(name)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onRemoteDisconnected(name)
            }
        }
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // System broadcasts still arrive with NOT_EXPORTED.
        ContextCompat.registerReceiver(this, bluetoothReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        bluetoothReceiverRegistered = true
    }

    private fun onRemoteConnected(name: String) {
        KeyLog.note("auto-start: $name connected")
        // Give the connection a moment to settle before media keys start flowing.
        handler.postDelayed({
            if (!RemoteMediaService.isRunning) {
                try {
                    RemoteMediaService.start(this)
                } catch (e: Exception) {
                    // E.g. ForegroundServiceStartNotAllowedException on some phones.
                    Log.e(TAG, "Auto-start could not start the remote service", e)
                    KeyLog.note("auto-start: Android refused to start the remote service")
                    return@postDelayed
                }
            }
            if (!VideoApp.current(this).launch(this)) {
                KeyLog.note("auto-start: ${getString(VideoApp.current(this).labelRes)} is not installed")
            }
        }, AUTO_START_DELAY_MS)
    }

    // ------------------------------------------------ auto-start when TikTok opens

    private var lastAutoStartAttemptUptimeMs = 0L

    /** Starts the remote service when [app] (e.g. TikTok) comes to the front, if the setting is on. */
    private fun maybeAutoStartRemote(app: VideoApp) {
        if (!AutoStart.shouldAutoStart(this)) return
        val now = SystemClock.uptimeMillis()
        // Don't hammer startForegroundService if Android refused a moment ago.
        if (now - lastAutoStartAttemptUptimeMs < AUTO_START_RETRY_MS) return
        lastAutoStartAttemptUptimeMs = now
        try {
            RemoteMediaService.start(this, byUser = false)
            KeyLog.note("auto-start: ${getString(app.labelRes)} opened, remote started")
            handler.postDelayed({ updateProximity() }, 500L)
        } catch (e: Exception) {
            Log.e(TAG, "Auto-start on app open could not start the remote service", e)
            KeyLog.note("auto-start: Android refused to start the remote service")
        }
    }

    private fun onRemoteDisconnected(name: String) {
        if (!RemoteMediaService.isRunning) return
        KeyLog.note("auto-start: $name disconnected, stopping")
        hideLock()
        RemoteMediaService.stop(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        noteUserActivity()
        // Only take over media keys while the user has RemoteScroll switched on.
        // Otherwise music apps etc. keep working normally.
        if (!RemoteMediaService.isRunning) return false
        // By default let media keys go on to our MediaSession; the key filter path is an
        // option for remotes that only work this way (see "Catch keys early").
        if (!KeyLog.useKeyFilter(this)) return false
        return keyHandler?.onKeyEvent(event) ?: false
    }

    /** Time until which accessibility events are considered our own injected gestures. */
    private var injectingUntilUptimeMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore transient popups/keyboards so foregroundPackage is never corrupted
            if (isTransientOrSystemPackage(pkg)) return
            foregroundPackage = pkg
            val openedApp = VideoApp.forPackage(pkg)
            if (openedApp != null) maybeAutoStartRemote(openedApp)
            val isYouTube = openedApp == VideoApp.YOUTUBE
            val isFacebook = FacebookAdSkipper.isFacebookPackage(pkg)
            youTubeAdSkipper?.onForegroundChanged(isYouTube)
            if (!isFacebook) facebookAdSkipper?.reset()
            updateMiniControl()
            updateFloatingNav()
            updateKhmerSubtitleOverlay()
            scheduleAutoSkipCheck(if (isYouTube || isFacebook) 250L else 600L, force = true)
            return
        }

        // On content changes or view scrolls: ensure mini control & floating nav are restored if hidden
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (!isLocked && isMiniControlEnabled(this)) {
                if (shouldShowMiniControl() && miniControlOverlay?.isShowing == false) {
                    updateMiniControl()
                }
                if (shouldShowMiniControl() && GesturePoints.isFloatingNavEnabled(this) && floatingNavOverlay?.isShowing == false) {
                    updateFloatingNav()
                }
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                val eventPkg = event.packageName?.toString()
                if (shouldAutoDetectCaptions()) {
                    if (eventPkg == "com.google.android.as" ||
                        eventPkg in VideoApp.YOUTUBE.packages ||
                        foregroundVideoApp == VideoApp.YOUTUBE
                    ) {
                        scheduleCaptionCheck(60L)
                    }
                }
                if (eventPkg == null || !isTransientOrSystemPackage(eventPkg)) {
                    val isYouTube = foregroundVideoApp == VideoApp.YOUTUBE ||
                        (eventPkg != null && eventPkg in VideoApp.YOUTUBE.packages)
                    val isFacebook = FacebookAdSkipper.isFacebookPackage(eventPkg) ||
                        FacebookAdSkipper.isFacebookPackage(foregroundPackage)
                    if (isYouTube && YouTubeAdSkipper.isEnabled(this)) {
                        scheduleAutoSkipCheck(200L)
                    } else if (isFacebook && FacebookAdSkipper.isEnabled(this)) {
                        scheduleAutoSkipCheck(300L)
                    } else if (isAutoSkipAdsEnabled(this) && foregroundVideoApp != null) {
                        scheduleAutoSkipCheck(450L)
                    }
                }
            }
        }

        // If the user manually interacts with the screen (touch/click/scroll) outside our lock,
        // reset the auto-lock scroll counter so it starts back from 0 (or 1 on next remote scroll).
        if (!isLocked &&
            (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED)
        ) {
            noteUserActivity()
            if (SystemClock.uptimeMillis() > injectingUntilUptimeMs) {
                if (scrollCount > 0) {
                    KeyLog.note("manual touch detected: reset scroll count from $scrollCount to 0")
                    scrollCount = 0
                    miniControlOverlay?.updateStatus(0, autoLockScrolls(this))
                }
                if (isAutoPilotActive && isAutoPilotResetOnScroll(this)) {
                    autoPilotRemaining = autoPilotSeconds
                    miniControlOverlay?.updateAutoPilot(autoPilotRemaining, autoPilotSeconds, isAutoPilotPaused)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        proximity?.setActive(false)
        proximity = null
        if (bluetoothReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
            } catch (_: IllegalArgumentException) {
            }
            bluetoothReceiverRegistered = false
        }
        stopTurboSpeed()
        youTubeAdSkipper?.restoreMuteIfNeeded()
        youTubeAdSkipper = null
        autoSkipCheckPending = false
        lockOverlay?.hide()
        lockOverlay = null
        sleepBlackoutOverlay?.hide()
        sleepBlackoutOverlay = null
        miniControlOverlay?.destroy()
        miniControlOverlay = null
        calibrationOverlay?.hide()
        calibrationOverlay = null
        floatingNavOverlay?.hide()
        floatingNavOverlay = null
        khmerSubtitleOverlay?.hide()
        khmerSubtitleOverlay = null
        khmerVoiceSpeaker?.shutdown()
        khmerVoiceSpeaker = null
        captionCheckPending = false
        latestPendingCaption.set(null)
        pendingCaptionTtsResult = null
        captionExecutor.shutdownNow()
        keyHandler?.release()
        keyHandler = null
    }

    // ------------------------------------------------------------- pocket lock

    /** Black, touch-blocking overlay on top of TikTok. See [LockOverlay]. */
    fun showLock() {
        val overlay = lockOverlay ?: return
        if (overlay.isShowing) return
        overlay.show()
        if (overlay.isShowing) {
            scrollCount = 0
            FeedbackSound.lockOn()
            updateMiniControl()
            updateFloatingNav()
            updateKhmerSubtitleOverlay()
        }
        RemoteMediaService.refreshNotification(this)
    }

    fun hideLock() {
        val overlay = lockOverlay ?: return
        if (!overlay.isShowing) return
        overlay.hide()
        lockedByProximity = false
        scrollCount = 0   // reset so auto-lock can count again from zero
        FeedbackSound.lockOff()
        updateMiniControl()
        updateFloatingNav()
        updateKhmerSubtitleOverlay()
        RemoteMediaService.refreshNotification(this)
    }

    fun toggleLock() = if (isLocked) hideLock() else showLock()

    // ------------------------------------------------------------- audio-only sleep blackout

    /** Fullscreen AMOLED blackout with audio playing in foreground. See [SleepBlackoutOverlay]. */
    fun showSleepBlackout() {
        if (isLocked) hideLock()
        val overlay = sleepBlackoutOverlay ?: return
        if (overlay.isShowing) return
        overlay.show()
        if (overlay.isShowing) {
            updateMiniControl()
            updateFloatingNav()
            updateKhmerSubtitleOverlay()
            KeyLog.note("sleep blackout mode on")
        }
    }

    fun hideSleepBlackout() {
        val overlay = sleepBlackoutOverlay ?: return
        if (!overlay.isShowing) return
        overlay.hide()
        updateMiniControl()
        updateFloatingNav()
        updateKhmerSubtitleOverlay()
    }

    fun toggleSleepBlackout() = if (isSleepBlackout) hideSleepBlackout() else showSleepBlackout()

    fun shouldShowMiniControl(): Boolean {
        if (!isMiniControlEnabled(this)) return false
        if (isLocked || isSleepBlackout) return false
        if (isMiniControlAlwaysOn(this)) return true
        return foregroundVideoApp != null
    }

    /**
     * Updates visibility and content of the on-screen mini control widget.
     * Appears when mini controller is enabled, in a supported video app (e.g. TikTok) or always-on,
     * and screen is not pocket-locked or sleep blacked-out.
     */
    fun updateMiniControl() {
        if (shouldShowMiniControl()) {
            miniControlOverlay?.show()
            miniControlOverlay?.updateStatus(scrollCount, autoLockScrolls(this))
        } else {
            miniControlOverlay?.hide()
        }
    }

    fun updateFloatingNav() {
        val shouldShow = GesturePoints.isFloatingNavEnabled(this) &&
            !isLocked && !isSleepBlackout &&
            (isMiniControlAlwaysOn(this) || foregroundVideoApp != null)
        if (shouldShow) {
            floatingNavOverlay?.show()
        } else {
            floatingNavOverlay?.hide()
        }
    }

    fun showCalibration() {
        calibrationOverlay?.show()
    }

    fun hideCalibration() {
        calibrationOverlay?.hide()
    }

    /**
     * Sleep timer ran out: remove pocket lock & sleep blackout, and go to the home screen.
     * Leaving TikTok always pauses it. The screen then times out and locks as usual.
     */
    fun sleepNow() {
        hideLock()
        hideSleepBlackout()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---------------------------------------------------------------- gestures

    /** Finger moves between calibrated points: TikTok shows the next video. */
    fun swipeUp(fromUser: Boolean = true) {
        if (fromUser) noteUserActivity()
        val (startPt, endPt) = GesturePoints.getNextPoints(this)
        val duration = GesturePoints.getSwipeDuration(this)
        coordinateSwipe(startPt.x, startPt.y, endPt.x, endPt.y, duration)
        onScrolled()
        scheduleAutoSkipCheck(1_200L, force = true)
    }

    /** Finger moves between calibrated points: TikTok shows the previous video. */
    fun swipeDown(fromUser: Boolean = true) {
        if (fromUser) noteUserActivity()
        val (startPt, endPt) = GesturePoints.getBackPoints(this)
        val duration = GesturePoints.getSwipeDuration(this)
        coordinateSwipe(startPt.x, startPt.y, endPt.x, endPt.y, duration)
        onScrolled()
        scheduleAutoSkipCheck(1_200L, force = true)
    }

    /**
     * Increments the scroll counter and shows pocket lock automatically once the
     * user-configured threshold is reached. Also resets Auto-Pilot countdown if enabled.
     */
    private fun onScrolled() {
        if (isAutoPilotActive && isAutoPilotResetOnScroll(this)) {
            autoPilotRemaining = autoPilotSeconds
            miniControlOverlay?.updateAutoPilot(autoPilotRemaining, autoPilotSeconds, isAutoPilotPaused)
        }

        val threshold = autoLockScrolls(this)
        miniControlOverlay?.updateStatus(scrollCount + 1, threshold)
        if (isLocked) return                  // already locked, nothing to do
        if (threshold <= 0) return            // feature is off
        scrollCount++
        KeyLog.note("scroll $scrollCount / $threshold")
        miniControlOverlay?.updateStatus(scrollCount, threshold)
        if (scrollCount >= threshold) {
            KeyLog.note("auto-lock: $threshold scrolls reached, pocket lock on")
            scrollCount = 0
            showLock()
        }
    }

    // ------------------------------------------------------------- auto-pilot / timed auto-scroll

    private val autoPilotTicker = object : Runnable {
        override fun run() {
            tickAutoPilot()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun tickAutoPilot() {
        if (autoPilotSeconds <= 0 || isAutoPilotPaused || isLocked) {
            return
        }
        // Only tick when in video app or always-on
        if (!isMiniControlAlwaysOn(this) && foregroundVideoApp == null) {
            return
        }

        autoPilotRemaining--
        if (autoPilotRemaining <= 0) {
            autoPilotRemaining = autoPilotSeconds
            swipeUp()
            KeyLog.note("auto-pilot: auto-scrolled to next video")
        }
        miniControlOverlay?.updateAutoPilot(autoPilotRemaining, autoPilotSeconds, isAutoPilotPaused)
    }

    fun startAutoPilot(seconds: Int) {
        autoPilotSeconds = seconds
        autoPilotRemaining = seconds
        isAutoPilotPaused = false
        setAutoPilotSeconds(this, seconds)
        miniControlOverlay?.updateAutoPilot(autoPilotRemaining, autoPilotSeconds, isAutoPilotPaused)
        KeyLog.note("auto-pilot: started ($seconds s interval)")
    }

    fun stopAutoPilot() {
        autoPilotSeconds = 0
        autoPilotRemaining = 0
        isAutoPilotPaused = false
        setAutoPilotSeconds(this, 0)
        miniControlOverlay?.updateAutoPilot(0, 0, false)
        KeyLog.note("auto-pilot: stopped")
    }

    fun toggleAutoPilotPause() {
        if (!isAutoPilotActive) return
        isAutoPilotPaused = !isAutoPilotPaused
        miniControlOverlay?.updateAutoPilot(autoPilotRemaining, autoPilotSeconds, isAutoPilotPaused)
        KeyLog.note(if (isAutoPilotPaused) "auto-pilot: paused" else "auto-pilot: resumed")
        Toast.makeText(this, if (isAutoPilotPaused) "⏸️ Auto-Pilot Paused" else "▶️ Auto-Pilot Resumed", Toast.LENGTH_SHORT).show()
    }

    fun cycleAutoPilot() {
        val next = when (autoPilotSeconds) {
            0 -> 15
            15 -> 20
            20 -> 30
            30 -> 45
            else -> 0
        }
        if (next > 0) startAutoPilot(next) else stopAutoPilot()
        Toast.makeText(this, if (next > 0) "▶️ Auto-Pilot: ${next}s" else "⏹️ Auto-Pilot Off", Toast.LENGTH_SHORT).show()
    }

    /** Tap at calibrated tap point: TikTok pauses/resumes. */
    fun tapCenter() {
        val size = screenSize()
        val tapPt = GesturePoints.getTapPoint(this)
        val path = Path().apply { moveTo(clampX(size.x * tapPt.x, size.x), clampY(size.y * tapPt.y, size.y)) }
        dispatch(path, TAP_DURATION_MS, "tap")
    }

    /** Two quick taps at calibrated point: TikTok likes the video. */
    fun doubleTapCenter() {
        injectingUntilUptimeMs = SystemClock.uptimeMillis() + DOUBLE_TAP_GAP_MS + (TAP_DURATION_MS * 2) + 400L
        val size = screenSize()
        val tapPt = GesturePoints.getTapPoint(this)
        val x = clampX(size.x * tapPt.x, size.x)
        val y = clampY(size.y * tapPt.y, size.y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0L, TAP_DURATION_MS))
            .addStroke(
                GestureDescription.StrokeDescription(
                    tapPath(x, y), TAP_DURATION_MS + DOUBLE_TAP_GAP_MS, TAP_DURATION_MS
                )
            )
            .build()
        dispatch(gesture, "double tap")
    }

    /**
     * Presses TikTok's Save / Favorites button on the video that is on screen. The button
     * moves between phones and TikTok versions, so it is found by its accessibility label
     * rather than by position. Works under pocket lock too: the click goes straight to the
     * button instead of through the screen.
     *
     * @return false if no such button is visible.
     */
    fun pressSaveButton(): Boolean {
        val screen = screenSize()
        val visibleArea = Rect(0, 0, screen.x, screen.y)
        val node = appRoots().firstNotNullOfOrNull { findSaveNode(it, visibleArea) }
        if (node != null && clickSmallButton(node, screen)) {
            Log.i(TAG, "Save button clicked by label")
        } else if (foregroundVideoApp?.saveByPosition == false) {
            // YouTube / Instagram: something else sits where TikTok's bookmark is.
            return false
        } else {
            // No usable label: tap the bookmark icon where TikTok draws it (right column,
            // below like and comments). Not the label's bounds: they can be off.
            Log.i(TAG, "Tapping the bookmark icon position")
            dispatch(
                tapPath(screen.x * SAVE_ICON_X, screen.y * SAVE_ICON_Y),
                TAP_DURATION_MS,
                "save tap (position)"
            )
        }
        return true
    }

    /**
     * Clicks [node] or its nearest clickable parent, but only if that is a small button
     * in the right column. A large clickable parent (e.g. the whole video) would pause the
     * video instead of saving it.
     */
    private fun clickSmallButton(node: AccessibilityNodeInfo, screen: Point): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        target ?: return false
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        val small = bounds.width() < screen.x * 0.3f && bounds.height() < screen.y * 0.15f
        if (!small || bounds.centerX() < screen.x / 2) return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Root nodes of the app windows on screen, the focused one first, deduplicated by windowId. */
    private fun appRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val seenWindowIds = HashSet<Int>()
        try {
            rootInActiveWindow?.let { root ->
                roots += root
                seenWindowIds += root.windowId
            }
            for (win in windows) {
                if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION && win.id !in seenWindowIds) {
                    win.root?.let { root ->
                        roots += root
                        seenWindowIds += root.windowId
                    }
                }
            }
        } catch (_: Exception) {}
        return roots
    }

    /** Depth-first search for a visible node whose label matches [SAVE_WORDS]. */
    private fun findSaveNode(node: AccessibilityNodeInfo, visibleArea: Rect): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val label = (node.contentDescription ?: node.text)?.toString()?.lowercase()
        if (label != null && SAVE_WORDS.any { it in label }) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            // TikTok's side buttons are on the right half; skip off-screen videos.
            if (Rect.intersects(bounds, visibleArea) && bounds.centerX() > visibleArea.width() / 2) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSaveNode(child, visibleArea)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------- 2x turbo speed

    private var turboStroke: GestureDescription.StrokeDescription? = null
    @Volatile
    var isTurboActive = false
        private set

    /** Holds touch to trigger TikTok's 2x speed playback. */
    fun startTurboSpeed() {
        if (isTurboActive || isLocked) return
        isTurboActive = true
        noteUserActivity()
        val screen = screenSize()
        val turboPt = GesturePoints.getTurboPoint(this)
        val x = clampX(screen.x * turboPt.x, screen.x)
        val y = clampY(screen.y * turboPt.y, screen.y)
        val path = Path().apply { moveTo(x, y) }

        val stroke = GestureDescription.StrokeDescription(path, 0L, 120_000L, true)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Turbo gesture started")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Turbo gesture cancelled")
                isTurboActive = false
                turboStroke = null
            }
        }, handler)
        turboStroke = stroke
        KeyLog.note("turbo: 2x speed active")
        FeedbackSound.lockOff()
    }

    /** Releases touch to return to normal 1x speed. */
    fun stopTurboSpeed() {
        if (!isTurboActive) return
        isTurboActive = false
        noteUserActivity()
        val stroke = turboStroke
        val screen = screenSize()
        val turboPt = GesturePoints.getTurboPoint(this)
        val x = clampX(screen.x * turboPt.x, screen.x)
        val y = clampY(screen.y * turboPt.y, screen.y)
        val path = Path().apply { moveTo(x, y) }

        if (stroke != null) {
            try {
                val endStroke = stroke.continueStroke(path, 0L, 40L, false)
                val endGesture = GestureDescription.Builder().addStroke(endStroke).build()
                dispatchGesture(endGesture, null, handler)
            } catch (e: Exception) {
                Log.w(TAG, "Could not end turbo stroke", e)
            }
        }
        turboStroke = null
        KeyLog.note("turbo: 2x speed ended")
    }

    fun toggleTurboSpeed() {
        if (isTurboActive) stopTurboSpeed() else startTurboSpeed()
    }

    // ------------------------------------------------------------- auto-skip ads & LIVE

    private var lastAutoSkipUptimeMs = 0L
    private var autoSkipCheckPending = false
    private val autoSkipRunnable = Runnable {
        autoSkipCheckPending = false
        checkAndSkipAds()
    }

    fun suppressManualTouchTracking(durationMs: Long) {
        val target = SystemClock.uptimeMillis() + durationMs
        if (target > injectingUntilUptimeMs) {
            injectingUntilUptimeMs = target
        }
    }

    fun tapScreenPoint(x: Float, y: Float, label: String = "tap") {
        val size = screenSize()
        dispatch(tapPath(clampX(x, size.x), clampY(y, size.y)), TAP_DURATION_MS, label)
    }

    fun performAutoSkipSwipe(reason: String) {
        KeyLog.note("auto-skip: $reason detected, swiping")
        val (startPt, endPt) = GesturePoints.getNextPoints(this)
        val duration = GesturePoints.getSwipeDuration(this)
        coordinateSwipe(startPt.x, startPt.y, endPt.x, endPt.y, duration)
        if (!isLocked && !isSleepBlackout) {
            Toast.makeText(this, "Auto-skipped $reason", Toast.LENGTH_SHORT).show()
        }
        scheduleAutoSkipCheck(1_200L, force = true)
    }

    fun restoreYouTubeAdMute() {
        youTubeAdSkipper?.restoreMuteIfNeeded()
    }

    fun refreshYouTubeAdSettings() {
        if (!YouTubeAdSkipper.isEnabled(this) || !YouTubeAdSkipper.isMuteAdsEnabled(this)) {
            youTubeAdSkipper?.restoreMuteIfNeeded()
        }
        if (YouTubeAdSkipper.isEnabled(this)) {
            scheduleAutoSkipCheck(200L, force = true)
        }
        miniControlOverlay?.updateYouTubeAdSkipStatus()
    }

    fun notifyYouTubeAdSkipped(totalSkipped: Int) {
        miniControlOverlay?.updateYouTubeAdSkipStatus()
        onYouTubeSkipCountChanged?.invoke(totalSkipped)
        if (!isLocked && !isSleepBlackout) {
            Toast.makeText(this, getString(R.string.yt_auto_skip_toast, totalSkipped), Toast.LENGTH_SHORT).show()
        }
    }

    fun notifyFacebookAdSkipped(totalSkipped: Int) {
        miniControlOverlay?.updateYouTubeAdSkipStatus()
        onYouTubeSkipCountChanged?.invoke(totalSkipped)
        if (!isLocked && !isSleepBlackout) {
            Toast.makeText(this, getString(R.string.fb_auto_skip_toast, totalSkipped), Toast.LENGTH_SHORT).show()
        }
    }

    fun notifyYouTubeSkipCountUpdated() {
        val total = YouTubeAdSkipper.skippedCount(this)
        miniControlOverlay?.updateYouTubeAdSkipStatus()
        onYouTubeSkipCountChanged?.invoke(total)
    }

    // ------------------------------------------------------------- YouTube Khmer translator

    fun isYouTubeOrOwnForeground(): Boolean {
        val app = foregroundVideoApp
        if (app == VideoApp.YOUTUBE) return true
        val pkg = foregroundPackage
        if (pkg == packageName) return true
        return try {
            appRoots().any { it.packageName?.toString() in VideoApp.YOUTUBE.packages }
        } catch (_: Exception) {
            false
        }
    }

    fun onTranslatorStarted() {
        lastCaptionText = ""
        lastSpokenCaptionSource = ""
        firstCaptionUnsettledUptimeMs = 0L
        latestPendingCaption.set(null)
        pendingCaptionTtsResult = null
        YouTubeTranslatorService.clearExternalSubtitleSeen()
        handler.removeCallbacks(captionTtsRunnable)
        if (TranslatorPrefs.isVoiceTtsEnabled(this)) {
            khmerVoiceSpeaker?.ensureInitialized()
        }
        updateKhmerSubtitleOverlay()
        miniControlOverlay?.updateYouTubeAdSkipStatus()
    }

    fun onTranslatorStopped() {
        captionCheckPending = false
        firstCaptionUnsettledUptimeMs = 0L
        lastSpokenCaptionSource = ""
        latestPendingCaption.set(null)
        pendingCaptionTtsResult = null
        YouTubeTranslatorService.clearExternalSubtitleSeen()
        handler.removeCallbacks(captionCheckRunnable)
        handler.removeCallbacks(captionTtsRunnable)
        khmerSubtitleOverlay?.hide()
        khmerVoiceSpeaker?.stopSpeaking()
        miniControlOverlay?.updateYouTubeAdSkipStatus()
    }

    fun refreshTranslatorUiState() {
        if (TranslatorPrefs.isVoiceTtsEnabled(this)) {
            khmerVoiceSpeaker?.ensureInitialized()
            if (TranslatorPrefs.isAudioTrackModeEnabled(this) && isYouTubeOrOwnForeground()) {
                khmerVoiceSpeaker?.engageAudioTrackBedNow()
            } else if (!TranslatorPrefs.isAudioTrackModeEnabled(this)) {
                khmerVoiceSpeaker?.releaseAudioTrackBedNow()
            }
        } else {
            khmerVoiceSpeaker?.stopSpeaking()
        }
        updateKhmerSubtitleOverlay()
        miniControlOverlay?.updateYouTubeAdSkipStatus()
    }

    fun updateTranslatorStatus(status: String) {
        khmerSubtitleOverlay?.updateStatus(status)
    }

    fun deliverKhmerTranslation(result: TranslationResult) {
        if (result.skipped || result.khmerText.isBlank()) return
        if (result.updateSubtitle) {
            if (YouTubeTranslatorService.isRunning || TranslatorPrefs.isSubtitleEnabled(this)) {
                updateKhmerSubtitleOverlay()
            }
            khmerSubtitleOverlay?.updateSubtitle(
                result.detectedLang,
                result.sourceText,
                result.khmerText,
                result.engineLabel,
                result.speakerGender
            )
        }
        if (result.speakVoice && TranslatorPrefs.isVoiceTtsEnabled(this)) {
            khmerVoiceSpeaker?.speak(result.khmerText, result.speakerGender)
        } else if (TranslatorPrefs.isVoiceTtsEnabled(this) && TranslatorPrefs.isAudioTrackModeEnabled(this)) {
            khmerVoiceSpeaker?.refreshAudioTrackHoldIfActive()
        }
    }

    fun stopKhmerVoice() {
        khmerVoiceSpeaker?.stopSpeaking()
    }

    /**
     * Restart / resync button handler: immediately flushes stale TTS + caption/STT queues,
     * re-initializes the Khmer TTS engine, and scans for the latest live subtitle or audio.
     */
    fun restartTtsAndLiveDetection() {
        captionResyncGen.incrementAndGet()
        captionCheckPending = false
        firstCaptionUnsettledUptimeMs = 0L
        lastCaptionText = ""
        lastSpokenCaptionSource = ""
        latestPendingCaption.set(null)
        pendingCaptionTtsResult = null
        handler.removeCallbacks(captionCheckRunnable)
        handler.removeCallbacks(captionTtsRunnable)

        khmerVoiceSpeaker?.restartForRealtimeSync()
        YouTubeTranslatorService.restartRealtimeDetection()
        if (TranslatorPrefs.isSubtitleEnabled(this)) {
            updateKhmerSubtitleOverlay()
            khmerSubtitleOverlay?.updateStatus("🔄 Resynced • Live Real-Time...")
        }
        if (shouldAutoDetectCaptions()) {
            scheduleCaptionCheck(40L)
        }
    }

    fun updateKhmerTtsVolume() {
        khmerVoiceSpeaker?.applyTtsVolumeNow()
        khmerSubtitleOverlay?.refreshButtons()
    }

    fun updateKhmerTtsSpeed() {
        khmerVoiceSpeaker?.applyTtsSpeedNow()
    }

    fun updateKhmerSubtitleOverlay() {
        val activeForCaptions = YouTubeTranslatorService.isRunning || lastCaptionText.isNotBlank()
        val shouldShow = activeForCaptions &&
            TranslatorPrefs.isSubtitleEnabled(this) &&
            !isLocked && !isSleepBlackout &&
            (!TranslatorPrefs.isOnlyYouTube(this) || isYouTubeOrOwnForeground())
        if (shouldShow) {
            khmerSubtitleOverlay?.show()
            khmerSubtitleOverlay?.refreshButtons()
        } else {
            khmerSubtitleOverlay?.hide()
            if (!isYouTubeOrOwnForeground() || isLocked || isSleepBlackout) {
                khmerVoiceSpeaker?.releaseAudioTrackBedNow()
            }
        }
    }

    private fun shouldAutoDetectCaptions(): Boolean =
        TranslatorPrefs.isSubtitleEnabled(this) ||
            (YouTubeTranslatorService.isRunning && TranslatorPrefs.isVoiceTtsEnabled(this))

    private fun scheduleCaptionCheck(delayMs: Long) {
        if (captionCheckPending) return
        captionCheckPending = true
        handler.postDelayed(captionCheckRunnable, delayMs)
    }

    private fun checkAndTranslateLiveCaption() {
        if (!shouldAutoDetectCaptions()) return
        val extracted = try {
            val active = rootInActiveWindow
            val wins = try { windows } catch (_: Exception) { null }
            LiveCaptionExtractor.extractBestCaption(wins, active)?.trim()
        } catch (_: Exception) {
            null
        } ?: return

        if (extracted.length < 3) return

        // Refresh active-subtitle hold even when the same line stays on screen so audio STT
        // yields cleanly and does not speak over the currently displayed subtitle.
        if (YouTubeTranslatorService.isRunning) {
            YouTubeTranslatorService.notifyExternalSubtitleSeen()
        }

        if (extracted == lastCaptionText) return
        lastCaptionText = extracted
        latestPendingCaption.set(extracted)

        if (captionWorkerBusy.compareAndSet(false, true)) {
            captionExecutor.execute { processLatestCaptionsLoop() }
        }
    }

    private fun processLatestCaptionsLoop() {
        try {
            while (true) {
                val nextText = latestPendingCaption.getAndSet(null) ?: break
                val loopGen = captionResyncGen.get()
                try {
                    val captionEngineLabel = if (YouTubeTranslatorService.isRunning) "CC AUTO" else "CC"
                    val translated = KhmerTranslatorClient.translateTextToKhmer(nextText, engineLabel = captionEngineLabel)
                    if (translated != null && translated.khmerText.isNotBlank()) {
                        if (captionResyncGen.get() != loopGen || latestPendingCaption.get() != null) continue
                        val speakerGender = YouTubeTranslatorService.currentSpeakerGender
                        val subtitleResult = translated.copy(
                            isPartial = true,
                            speakVoice = false,
                            updateSubtitle = TranslatorPrefs.isSubtitleEnabled(this),
                            speakerGender = speakerGender
                        )
                        val prevSpoken = lastSpokenCaptionSource
                        val speakDelta = YouTubeTranslatorService.computeUnspokenDelta(nextText, prevSpoken)
                        val voiceTranslated = when {
                            speakDelta.length < 6 -> null
                            speakDelta == nextText -> translated
                            else -> KhmerTranslatorClient.translateTextToKhmer(speakDelta, engineLabel = captionEngineLabel)
                        }
                        if (captionResyncGen.get() != loopGen || latestPendingCaption.get() != null) continue
                        val voiceResult = voiceTranslated
                            ?.takeIf { it.khmerText.isNotBlank() }
                            ?.copy(
                                sourceText = nextText,
                                isPartial = false,
                                speakVoice = true,
                                updateSubtitle = false,
                                speakerGender = speakerGender
                            )
                        val endsClause = nextText.last() in charArrayOf('.', '?', '!', ',', ';', '។')
                        val speakNow = voiceResult != null &&
                            (speakDelta.length >= 24 || endsClause || (prevSpoken.isEmpty() && speakDelta.length >= 10))
                        handler.post {
                            // Always keep full caption box text on screen cleanly.
                            deliverKhmerTranslation(subtitleResult)
                            if (voiceResult == null) return@post
                            if (speakNow) {
                                firstCaptionUnsettledUptimeMs = 0L
                                pendingCaptionTtsResult = null
                                handler.removeCallbacks(captionTtsRunnable)
                                lastSpokenCaptionSource = nextText
                                deliverKhmerTranslation(voiceResult)
                            } else {
                                val now = SystemClock.uptimeMillis()
                                if (firstCaptionUnsettledUptimeMs == 0L) {
                                    firstCaptionUnsettledUptimeMs = now
                                }
                                pendingCaptionTtsResult = voiceResult
                                val targetUptime = minOf(now + 180L, firstCaptionUnsettledUptimeMs + 480L)
                                val delay = (targetUptime - now).coerceAtLeast(30L)
                                handler.removeCallbacks(captionTtsRunnable)
                                handler.postDelayed(captionTtsRunnable, delay)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Caption translate error", e)
                }
            }
        } finally {
            captionWorkerBusy.set(false)
            if (latestPendingCaption.get() != null && captionWorkerBusy.compareAndSet(false, true)) {
                captionExecutor.execute { processLatestCaptionsLoop() }
            }
        }
    }

    fun scheduleAutoSkipCheck(delayMs: Long = 350L, force: Boolean = false) {
        val app = foregroundVideoApp
        val ytNeeded = app == VideoApp.YOUTUBE && YouTubeAdSkipper.isEnabled(this)
        val feedNeeded = app != null && app != VideoApp.YOUTUBE && isAutoSkipAdsEnabled(this) && !isLocked
        val muteRecoveryNeeded = youTubeAdSkipper?.isMuteActive == true
        val fbNeeded = FacebookAdSkipper.isFacebookPackage(foregroundPackage) &&
            FacebookAdSkipper.isEnabled(this)

        if (!ytNeeded && !feedNeeded && !muteRecoveryNeeded && !fbNeeded) return
        if (autoSkipCheckPending && !force) return

        handler.removeCallbacks(autoSkipRunnable)
        autoSkipCheckPending = true
        handler.postDelayed(autoSkipRunnable, delayMs)
    }

    private fun checkAndSkipAds() {
        val app = foregroundVideoApp
        val roots = appRoots()
        val hasYouTubeWindow = app == VideoApp.YOUTUBE ||
            roots.any { it.packageName?.toString() in VideoApp.YOUTUBE.packages }

        if (hasYouTubeWindow) {
            val outcome = youTubeAdSkipper?.evaluate(
                roots = roots,
                screen = screenSize(),
                allowShortsLiveSkip = isAutoSkipAdsEnabled(this) && !isLocked
            ) ?: YouTubeAdSkipper.ScanOutcome.NONE

            when (outcome) {
                YouTubeAdSkipper.ScanOutcome.SKIPPED_BUTTON,
                YouTubeAdSkipper.ScanOutcome.WAITING_COUNTDOWN -> {
                    scheduleAutoSkipCheck(320L, force = true)
                }
                YouTubeAdSkipper.ScanOutcome.SKIPPED_SHORTS_AD -> Unit
                YouTubeAdSkipper.ScanOutcome.NONE -> {
                    if (youTubeAdSkipper?.isMuteActive == true) {
                        scheduleAutoSkipCheck(400L, force = true)
                    }
                }
            }
            if (app == VideoApp.YOUTUBE) return
        } else {
            youTubeAdSkipper?.restoreMuteIfNeeded()
        }

        if (app == null && FacebookAdSkipper.isFacebookPackage(foregroundPackage)) {
            val outcome = facebookAdSkipper?.evaluate(roots, screenSize())
                ?: FacebookAdSkipper.Outcome.NONE
            when (outcome) {
                // Keep polling while an in-video ad is on screen so Skip is clicked the moment it appears.
                FacebookAdSkipper.Outcome.CLICKED_SKIP,
                FacebookAdSkipper.Outcome.WAITING -> scheduleAutoSkipCheck(400L, force = true)
                FacebookAdSkipper.Outcome.SWIPED_SPONSORED,
                FacebookAdSkipper.Outcome.NONE -> Unit
            }
            return
        }

        if (!isAutoSkipAdsEnabled(this) || app == null || app == VideoApp.YOUTUBE || isLocked) return
        if (SystemClock.uptimeMillis() - lastAutoSkipUptimeMs < AUTO_SKIP_COOLDOWN_MS) return

        val detected = scanForAdOrLive(roots, app.packages) ?: return
        lastAutoSkipUptimeMs = SystemClock.uptimeMillis()
        KeyLog.note("auto-skip: $detected detected, swiping")
        handler.post {
            swipeUp(fromUser = false)
            if (!isSleepBlackout) {
                Toast.makeText(this, "Auto-skipped $detected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scanForAdOrLive(roots: List<AccessibilityNodeInfo>, allowedPackages: List<String>): String? {
        val adKeywords = listOf(
            "sponsored", "promoted", "install now", "shop now", "order now",
            "paid partnership", "learn more", "tải ngay", "quảng cáo", "ผู้สนับสนุน"
        )
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg !in allowedPackages) continue
            val result = scanNodeForAdOrLive(root, adKeywords)
            if (result != null) return result
        }
        return null
    }

    private fun scanNodeForAdOrLive(node: AccessibilityNodeInfo, adKeywords: List<String>): String? {
        if (!node.isVisibleToUser) return null
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = (text ?: desc)?.lowercase()

        if (label != null) {
            if (text.equals("LIVE", ignoreCase = true) ||
                text.equals("Tap to watch LIVE", ignoreCase = true) ||
                text.equals("Top LIVE", ignoreCase = true) ||
                desc?.startsWith("LIVE", ignoreCase = true) == true
            ) {
                return "LIVE stream"
            }

            for (kw in adKeywords) {
                if (label.contains(kw)) {
                    return "Sponsored Ad"
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanNodeForAdOrLive(child, adKeywords)
            if (found != null) return found
        }
        return null
    }

    private fun tapPath(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private fun coordinateSwipe(startXFrac: Float, startYFrac: Float, endXFrac: Float, endYFrac: Float, durationMs: Long) {
        val size = screenSize()
        val startX = clampX(size.x * startXFrac, size.x)
        val startY = clampY(size.y * startYFrac, size.y)
        val endX = clampX(size.x * endXFrac, size.x)
        val endY = clampY(size.y * endYFrac, size.y)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        dispatch(path, durationMs, "swipe")
    }

    private fun clampX(x: Float, width: Int) = x.coerceIn(1f, (width - 1).toFloat())
    private fun clampY(y: Float, height: Int) = y.coerceIn(1f, (height - 1).toFloat())

    private fun dispatch(path: Path, durationMs: Long, label: String) {
        injectingUntilUptimeMs = SystemClock.uptimeMillis() + durationMs + 400L
        dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(),
            label
        )
    }

    private fun dispatch(gesture: GestureDescription, label: String) {
        val overlay = lockOverlay
        val blackout = sleepBlackoutOverlay
        val needPassThroughLock = overlay != null && overlay.isShowing
        val needPassThroughBlackout = blackout != null && blackout.isShowing

        if (!needPassThroughLock && !needPassThroughBlackout) {
            dispatchNow(gesture, label) {}
            return
        }
        // Pocket lock or Sleep blackout is on: let this gesture fall through to TikTok.
        if (needPassThroughLock) overlay?.beginPassThrough()
        if (needPassThroughBlackout) blackout?.beginPassThrough()
        handler.postDelayed({
            dispatchNow(gesture, label) {
                handler.postDelayed({
                    if (needPassThroughLock) overlay?.endPassThrough()
                    if (needPassThroughBlackout) blackout?.endPassThrough()
                }, PASS_THROUGH_RESTORE_MS)
            }
        }, PASS_THROUGH_SETTLE_MS)
    }

    private fun dispatchNow(gesture: GestureDescription, label: String, onDone: () -> Unit) {
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "$label completed")
                onDone()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "$label cancelled")
                onDone()
            }
        }, handler)

        if (!accepted) {
            Log.w(TAG, "$label rejected by the system")
            onDone()
        }
    }

    /**
     * Current physical size of the default display, in pixels, for the current rotation.
     * Read again on every gesture, so it follows rotation and folding/unfolding.
     */
    private fun screenSize(): Point {
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) {
            val dm = resources.displayMetrics
            size.set(dm.widthPixels, dm.heightPixels)
        }
        return size
    }
}
