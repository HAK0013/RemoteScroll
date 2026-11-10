package com.remotescroll.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Date

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

        setUpHoldTimes()
        setUpStyle()
        findViewById<CheckBox>(R.id.checkKeyFilter).apply {
            isChecked = KeyLog.useKeyFilter(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                KeyLog.setUseKeyFilter(this@MainActivity, on)
                // On that path releases arrive at a fixed delay, so holds can't be measured.
                if (on) HoldAction.setCountStyle(this@MainActivity, true)
                showStyle()
            }
        }
        textKeyLog = findViewById(R.id.textKeyLog)
        findViewById<Button>(R.id.buttonClearLog).setOnClickListener { KeyLog.clear() }

        setUpSleepTimer()
        findViewById<Button>(R.id.buttonChooseRemotes).setOnClickListener { chooseRemotes() }
    }

    // ------------------------------------------------------------ sleep timer

    private val sleepOptions by lazy {
        mapOf(R.id.sleepOff to 0, R.id.sleep30 to 30, R.id.sleep60 to 60, R.id.sleep90 to 90)
    }
    private var updatingSleepUi = false

    private fun setUpSleepTimer() {
        findViewById<RadioGroup>(R.id.radioSleep).setOnCheckedChangeListener { _, id ->
            if (updatingSleepUi) return@setOnCheckedChangeListener
            val minutes = sleepOptions[id] ?: 0
            if (minutes > 0 && !GestureAccessibilityService.isEnabled(this)) {
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
            }
            RemoteMediaService.setSleepTimer(this, minutes)
            // The service applies it asynchronously.
            buttonToggleService.postDelayed({ refreshSleepTimer(); refreshStatus() }, 400)
        }
    }

    private fun refreshSleepTimer() {
        val minutes = if (RemoteMediaService.sleepAtMillis > 0) RemoteMediaService.sleepMinutes else 0
        val id = sleepOptions.entries.firstOrNull { it.value == minutes }?.key ?: R.id.sleepOff
        updatingSleepUi = true
        findViewById<RadioGroup>(R.id.radioSleep).check(id)
        updatingSleepUi = false

        val status = findViewById<TextView>(R.id.textSleepStatus)
        status.text = if (RemoteMediaService.sleepAtMillis > 0) {
            val at = DateFormat.getTimeFormat(this).format(Date(RemoteMediaService.sleepAtMillis))
            getString(R.string.sleep_status_on, at)
        } else {
            getString(R.string.sleep_status_off)
        }
    }

    // ------------------------------------------------------------ remotes

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) chooseRemotes()
            else Toast.makeText(this, R.string.remotes_permission_denied, Toast.LENGTH_LONG).show()
        }

    private fun chooseRemotes() {
        if (!RemoteFilter.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            return
        }
        // Keep chosen names that are no longer paired, so they can still be unticked.
        val chosen = RemoteFilter.selected(this)
        val names = (RemoteFilter.pairedNames(this) + chosen).distinct().sortedBy { it.lowercase() }
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.remotes_none_paired, Toast.LENGTH_LONG).show()
            return
        }
        val checked = BooleanArray(names.size) { names[it] in chosen }
        AlertDialog.Builder(this)
            .setTitle(R.string.remotes_dialog_title)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.remotes_dialog_save) { _, _ ->
                RemoteFilter.setSelected(this, names.filterIndexed { i, _ -> checked[i] }.toSet())
                refreshRemotes()
            }
            .setNeutralButton(R.string.remotes_dialog_all) { _, _ ->
                RemoteFilter.setSelected(this, emptySet())
                refreshRemotes()
            }
            .show()
    }

    private fun refreshRemotes() {
        val chosen = RemoteFilter.selected(this)
        val summary = if (chosen.isEmpty()) {
            getString(R.string.remotes_all)
        } else {
            getString(R.string.remotes_some, chosen.sortedBy { it.lowercase() }.joinToString(", ")) +
                getString(R.string.remotes_note)
        }
        findViewById<TextView>(R.id.textRemotes).text = summary
    }

    private lateinit var textKeyLog: TextView

    /** Hold vs Press count, plus the Press count quiet-gap slider (100 ms steps). */
    private fun setUpStyle() {
        findViewById<RadioGroup>(R.id.radioStyle).setOnCheckedChangeListener { _, id ->
            HoldAction.setCountStyle(this, id == R.id.styleCount)
            showStyleGroups()
        }
        val label = findViewById<TextView>(R.id.textGap)
        val seek = findViewById<SeekBar>(R.id.seekGap)
        val step = 100
        fun show(ms: Int) { label.text = getString(R.string.gap_label, ms / 1000f) }
        seek.max = (HoldAction.MAX_GAP_MS - HoldAction.MIN_GAP_MS) / step
        val current = HoldAction.gapMs(this).toInt()
        seek.progress = (current - HoldAction.MIN_GAP_MS) / step
        show(current)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = HoldAction.MIN_GAP_MS + progress * step
                show(ms)
                if (fromUser) HoldAction.setGapMs(this@MainActivity, ms)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        setUpCountLockHold()
        showStyle()
    }

    /** Press count style: how long a Play/Pause hold must be for pocket lock (100 ms steps). */
    private fun setUpCountLockHold() {
        val label = findViewById<TextView>(R.id.textCountLockHold)
        val seek = findViewById<SeekBar>(R.id.seekCountLockHold)
        val step = 100
        fun show(ms: Int) { label.text = getString(R.string.count_lock_label, ms / 1000f) }
        seek.max = (HoldAction.MAX_COUNT_LOCK_HOLD_MS - HoldAction.MIN_COUNT_LOCK_HOLD_MS) / step
        val current = HoldAction.countLockHoldMs(this).toInt()
        seek.progress = (current - HoldAction.MIN_COUNT_LOCK_HOLD_MS) / step
        show(current)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = HoldAction.MIN_COUNT_LOCK_HOLD_MS + progress * step
                show(ms)
                if (fromUser) HoldAction.setCountLockHoldMs(this@MainActivity, ms)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun showStyle() {
        findViewById<RadioGroup>(R.id.radioStyle).check(
            if (HoldAction.countStyle(this)) R.id.styleCount else R.id.styleHold
        )
        showStyleGroups()
    }

    private fun showStyleGroups() {
        val count = HoldAction.countStyle(this)
        findViewById<android.view.View>(R.id.groupCount).visibility =
            if (count) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.groupHold).visibility =
            if (count) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.textCountLockHelp).setText(
            if (KeyLog.useKeyFilter(this)) R.string.count_lock_help_filter else R.string.count_lock_help_ok
        )
    }

    /** One slider per Play/Pause hold action, in whole seconds. */
    private fun setUpHoldTimes() {
        bindHoldSlider(HoldAction.LIKE, R.id.textHoldLike, R.id.seekHoldLike)
        bindHoldSlider(HoldAction.LOCK, R.id.textHoldLock, R.id.seekHoldLock)
        bindHoldSlider(HoldAction.SAVE, R.id.textHoldSave, R.id.seekHoldSave)
    }

    private fun bindHoldSlider(action: HoldAction, labelId: Int, seekId: Int) {
        val label = findViewById<TextView>(labelId)
        val seek = findViewById<SeekBar>(seekId)
        fun show(seconds: Int) {
            label.text = getString(
                if (action.beeps == 1) R.string.hold_row else R.string.hold_row_plural,
                getString(action.labelRes), seconds, action.beeps
            )
        }
        seek.max = HoldAction.MAX_SECONDS - HoldAction.MIN_SECONDS
        val current = action.seconds(this)
        seek.progress = current - HoldAction.MIN_SECONDS
        show(current)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = HoldAction.MIN_SECONDS + progress
                show(seconds)
                if (fromUser) action.setSeconds(this@MainActivity, seconds)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun showKeyLog() {
        val text = KeyLog.text()
        textKeyLog.text = text.ifEmpty { getString(R.string.remote_test_empty) }
    }

    override fun onPause() {
        // Back in TikTok (or anywhere else): remote presses act again.
        KeyLog.testing = false
        KeyLog.listener = null
        super.onPause()
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
        refreshSleepTimer()
        refreshRemotes()
        // Remote test: while this screen is open, presses are only logged.
        KeyLog.testing = true
        KeyLog.listener = { showKeyLog() }
        showKeyLog()
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
