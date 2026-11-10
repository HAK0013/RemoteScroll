package com.remotescroll.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        /** Give TikTok time to come to the front before blacking out the screen. */
        private const val LOCK_DELAY_MS = 1_500L
    }

    private lateinit var textAccessibilityStatus: TextView
    private lateinit var textServiceStatus: TextView
    private lateinit var buttonToggleService: Button

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.toast_notifications_denied, Toast.LENGTH_LONG).show()
            }
            startRemoteService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textAccessibilityStatus = findViewById(R.id.textAccessibilityStatus)
        textServiceStatus = findViewById(R.id.textServiceStatus)
        buttonToggleService = findViewById(R.id.buttonToggleService)

        findViewById<Button>(R.id.buttonOpenAccessibility).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        buttonToggleService.setOnClickListener {
            if (RemoteMediaService.isRunning) {
                RemoteMediaService.stop(this)
                refreshStatusSoon()
            } else {
                requestNotificationPermissionThenStart()
            }
        }

        findViewById<Button>(R.id.buttonOpenTikTok).setOnClickListener { openTikTok(locked = false) }
        findViewById<Button>(R.id.buttonOpenTikTokLocked).setOnClickListener { openTikTok(locked = true) }
    }

    /**
     * Starts the remote service (if needed) and opens TikTok. With [locked], pocket lock
     * switches on once TikTok is in front.
     */
    private fun openTikTok(locked: Boolean) {
        if (!GestureAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        if (!RemoteMediaService.isRunning) RemoteMediaService.start(this)
        if (!TikTokLauncher.launch(this)) {
            Toast.makeText(this, R.string.toast_tiktok_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (locked) {
            // The accessibility service outlives this activity, so post on its side.
            Handler(Looper.getMainLooper()).postDelayed({
                GestureAccessibilityService.instance?.showLock()
            }, LOCK_DELAY_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startRemoteService()
        }
    }

    private fun startRemoteService() {
        if (!GestureAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
        }
        RemoteMediaService.start(this)
        refreshStatusSoon()
    }

    /** Service start/stop is asynchronous, so check again shortly afterwards. */
    private fun refreshStatusSoon() {
        refreshStatus()
        buttonToggleService.postDelayed({ refreshStatus() }, 400)
    }

    private fun refreshStatus() {
        val a11yOn = GestureAccessibilityService.isEnabled(this)
        textAccessibilityStatus.setText(
            if (a11yOn) R.string.accessibility_enabled else R.string.accessibility_disabled
        )
        textAccessibilityStatus.setTextColor(statusColor(a11yOn))

        val running = RemoteMediaService.isRunning
        textServiceStatus.setText(if (running) R.string.service_running else R.string.service_stopped)
        textServiceStatus.setTextColor(statusColor(running))
        buttonToggleService.setText(if (running) R.string.stop_service else R.string.start_service)
    }

    private fun statusColor(ok: Boolean) =
        ContextCompat.getColor(this, if (ok) R.color.status_ok else R.color.status_off)
}
