package com.remotescroll.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Foreground service that owns an active MediaSessionCompat in the PLAYING state and plays
 * silent audio. This lets it receive Bluetooth media button events while another app
 * (TikTok) is in the foreground.
 */
class RemoteMediaService : Service() {

    companion object {
        private const val TAG = "RemoteMediaService"
        private const val CHANNEL_ID = "remote_scroll_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.remotescroll.app.action.STOP"
        private const val ACTION_TOGGLE_LOCK = "com.remotescroll.app.action.TOGGLE_LOCK"
        private const val ACTION_SLEEP = "com.remotescroll.app.action.SLEEP"
        private const val EXTRA_MINUTES = "minutes"

        /** When the sleep timer ends (System.currentTimeMillis), or 0 if it is off. */
        @Volatile
        var sleepAtMillis = 0L
            private set

        /** Minutes the running sleep timer was set to, or 0. */
        @Volatile
        var sleepMinutes = 0
            private set

        /**
         * Sets the sleep timer: after [minutes] the remote stops, pocket lock goes away and
         * TikTok is paused (sent to the background). 0 turns the timer off. Starts the
         * service if it is not running.
         */
        fun setSleepTimer(context: Context, minutes: Int) {
            if (minutes <= 0 && !isRunning) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteMediaService::class.java)
                    .setAction(ACTION_SLEEP)
                    .putExtra(EXTRA_MINUTES, minutes)
            )
        }

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: RemoteMediaService? = null

        /** Redraw the notification, e.g. after pocket lock was switched on or off. */
        fun refreshNotification(@Suppress("UNUSED_PARAMETER") context: Context) {
            instance?.updateNotification()
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteMediaService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteMediaService::class.java))
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private lateinit var keyHandler: MediaKeyHandler
    private val silentPlayer = SilentAudioPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val sleepRunnable = Runnable {
        Log.i(TAG, "Sleep timer finished")
        GestureAccessibilityService.instance?.sleepNow()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        keyHandler = MediaKeyHandler(this, "media session", swallowIgnored = true)
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_LOCK) {
            GestureAccessibilityService.instance?.toggleLock()
            // Close the notification shade so the overlay (or TikTok) is visible right away.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }
            if (isRunning) return START_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException when restarted from the background.
            Log.e(TAG, "Could not enter foreground state", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (mediaSession == null) mediaSession = createMediaSession()
        silentPlayer.start()
        isRunning = true
        if (intent?.action == ACTION_SLEEP) {
            scheduleSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
            updateNotification()
        }
        return START_STICKY
    }

    private fun scheduleSleep(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepAtMillis = 0L
            sleepMinutes = 0
            return
        }
        val delay = minutes * 60_000L
        sleepAtMillis = System.currentTimeMillis() + delay
        sleepMinutes = minutes
        handler.postDelayed(sleepRunnable, delay)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(sleepRunnable)
        sleepAtMillis = 0L
        sleepMinutes = 0
        if (instance === this) instance = null
        // Never leave the screen blacked out once the remote stops working.
        GestureAccessibilityService.instance?.hideLock()
        silentPlayer.stop()
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        keyHandler.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ media session

    private fun createMediaSession(): MediaSessionCompat =
        MediaSessionCompat(this, "RemoteScroll").apply {
            setCallback(sessionCallback)
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.app_name))
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.notification_text))
                    .build()
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    // Always PLAYING: the system prefers playing sessions for media buttons.
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            isActive = true
        }

    private val sessionCallback = object : MediaSessionCompat.Callback() {

        /** Gets the raw KeyEvents (DOWN and UP) so long presses can be detected. */
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(
                mediaButtonEvent,
                Intent.EXTRA_KEY_EVENT,
                KeyEvent::class.java
            )
            if (event != null && keyHandler.onKeyEvent(event)) return true
            return super.onMediaButtonEvent(mediaButtonEvent)
        }

        // Fallbacks for controllers that call transport controls directly instead of
        // sending key events (e.g. some car kits / smartwatches). No long press here.
        override fun onSkipToNext() = keyHandler.performShortPress(KeyEvent.KEYCODE_MEDIA_NEXT)
        override fun onSkipToPrevious() = keyHandler.performShortPress(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        override fun onPlay() = keyHandler.performShortPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        override fun onPause() = keyHandler.performShortPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    // ------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (sleepAtMillis > 0) {
                    // Live countdown to the sleep timer.
                    setSubText(getString(R.string.sleep_notification))
                    setShowWhen(true)
                    setWhen(sleepAtMillis)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                } else {
                    setShowWhen(false)
                }
            }
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0,
                getString(
                    if (GestureAccessibilityService.instance?.isLocked == true) {
                        R.string.notification_unlock
                    } else {
                        R.string.notification_lock
                    }
                ),
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, RemoteMediaService::class.java).setAction(ACTION_TOGGLE_LOCK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                0,
                getString(R.string.notification_stop),
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, RemoteMediaService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
}
