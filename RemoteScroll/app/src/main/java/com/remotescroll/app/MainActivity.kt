package com.remotescroll.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.Locale

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
                AutoStart.markStoppedByUser(this)
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
            }
        }
        textKeyLog = findViewById(R.id.textKeyLog)
        findViewById<Button>(R.id.buttonClearLog).setOnClickListener { KeyLog.clear() }

        setUpSleepTimer()
        setUpInactivitySleep()
        findViewById<Button>(R.id.buttonChooseRemotes).setOnClickListener { chooseRemotes() }
        setUpVideoApp()
        setUpYouTubeAutoSkip()
        setUpYouTubeTranslator()
        setUpAutomatic()
        setUpAutoLock()
        setUpAutoPilot()
        setUpMiniControl()
        setUpGesturePoints()
        setUpSleepBlackout()
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
        refreshAutomatic()
    }

    // ------------------------------------------------------------ video app

    private val appIds by lazy {
        mapOf(
            R.id.appTikTok to VideoApp.TIKTOK,
            R.id.appYouTube to VideoApp.YOUTUBE,
            R.id.appInstagram to VideoApp.INSTAGRAM
        )
    }

    private fun setUpVideoApp() {
        val radio = findViewById<RadioGroup>(R.id.radioApp)
        val current = VideoApp.current(this)
        radio.check(appIds.entries.first { it.value == current }.key)
        radio.setOnCheckedChangeListener { _, id ->
            appIds[id]?.let { VideoApp.setCurrent(this, it) }
            showVideoApp()
        }
        showVideoApp()
    }

    private fun showVideoApp() {
        val app = VideoApp.current(this)
        val name = getString(app.labelRes)
        findViewById<Button>(R.id.buttonOpenTikTok).text = getString(R.string.open_tiktok, name)
        findViewById<Button>(R.id.buttonOpenTikTokLocked).text = getString(R.string.open_tiktok_locked, name)
        findViewById<TextView>(R.id.textAppNote).setText(
            when (app) {
                VideoApp.TIKTOK -> R.string.app_note_tiktok
                VideoApp.YOUTUBE -> R.string.app_note_youtube
                VideoApp.INSTAGRAM -> R.string.app_note_instagram
            }
        )
    }

    // ------------------------------------------------------------ automatic

    private fun setUpAutomatic() {
        findViewById<CheckBox>(R.id.checkAutoStartOnOpen).apply {
            isChecked = AutoStart.isAlwaysOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                AutoStart.setAlwaysOn(this@MainActivity, on)
                if (on && !GestureAccessibilityService.isEnabled(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
                }
            }
        }
        findViewById<CheckBox>(R.id.checkAutoStart).apply {
            isChecked = AutoStart.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                AutoStart.setEnabled(this@MainActivity, on)
                // Bluetooth names need the Nearby devices permission.
                if (on && !RemoteFilter.hasPermission(this@MainActivity)) chooseRemotes()
                refreshAutomatic()
            }
        }
        findViewById<CheckBox>(R.id.checkProximity).apply {
            isChecked = ProximityLock.isEnabled(this@MainActivity)
            isEnabled = ProximityLock.hasSensor(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                ProximityLock.setEnabled(this@MainActivity, on)
                GestureAccessibilityService.instance?.updateProximity()
            }
        }
        if (!ProximityLock.hasSensor(this)) {
            findViewById<TextView>(R.id.textProximityHelp).setText(R.string.proximity_missing)
        }
    }

    private fun refreshAutomatic() {
        val chosen = RemoteFilter.selected(this)
        findViewById<TextView>(R.id.textAutoStartHelp).text = if (chosen.isEmpty()) {
            getString(R.string.auto_start_help_none)
        } else {
            getString(R.string.auto_start_help_ok, chosen.sortedBy { it.lowercase() }.joinToString(", "))
        }
    }

    // ------------------------------------------------------------ auto-lock after scrolls

    private fun setUpAutoLock() {
        val radio = findViewById<RadioGroup>(R.id.radioAutoLock)
        val edit = findViewById<EditText>(R.id.editAutoLockCount)
        var updating = false

        val current = GestureAccessibilityService.autoLockScrolls(this)
        edit.setText(if (current > 0) current.toString() else "")

        fun updateRadio(count: Int) {
            updating = true
            when (count) {
                0 -> radio.check(R.id.autoLockOff)
                5 -> radio.check(R.id.autoLock5)
                10 -> radio.check(R.id.autoLock10)
                else -> radio.check(R.id.autoLockCustom)
            }
            updating = false
        }

        updateRadio(current)

        radio.setOnCheckedChangeListener { _, checkedId ->
            if (updating) return@setOnCheckedChangeListener
            val newCount = when (checkedId) {
                R.id.autoLockOff -> 0
                R.id.autoLock5 -> 5
                R.id.autoLock10 -> 10
                R.id.autoLockCustom -> edit.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 5
                else -> 0
            }
            if (checkedId == R.id.autoLockCustom) {
                edit.requestFocus()
            }
            updating = true
            edit.setText(if (newCount > 0) newCount.toString() else "")
            updating = false
            GestureAccessibilityService.setAutoLockScrolls(this, newCount)
            GestureAccessibilityService.instance?.let {
                it.scrollCount = 0
                it.updateMiniControl()
            }
        }

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val count = s?.toString()?.trim()?.toIntOrNull() ?: 0
                updateRadio(count)
                GestureAccessibilityService.setAutoLockScrolls(this@MainActivity, count)
                GestureAccessibilityService.instance?.let {
                    it.scrollCount = 0
                    it.updateMiniControl()
                }
            }
        })
    }

    // ------------------------------------------------------------ auto-pilot / timed auto-scroll

    private fun setUpAutoPilot() {
        val radio = findViewById<RadioGroup>(R.id.radioAutoPilot) ?: return
        val edit = findViewById<EditText>(R.id.editAutoPilotSeconds)
        val customLayout = findViewById<android.view.View>(R.id.layoutAutoPilotCustom)
        val resetCheck = findViewById<CheckBox>(R.id.checkAutoPilotReset)
        val startBtn = findViewById<Button>(R.id.buttonStartAutoPilot)
        var updating = false

        val current = GestureAccessibilityService.autoPilotSeconds(this)
        edit?.setText(if (current > 0) current.toString() else "20")

        fun updateRadio(seconds: Int) {
            updating = true
            when (seconds) {
                0 -> {
                    radio.check(R.id.autoPilotOff)
                    customLayout?.visibility = android.view.View.GONE
                }
                15 -> {
                    radio.check(R.id.autoPilot15)
                    customLayout?.visibility = android.view.View.GONE
                }
                20 -> {
                    radio.check(R.id.autoPilot20)
                    customLayout?.visibility = android.view.View.GONE
                }
                30 -> {
                    radio.check(R.id.autoPilot30)
                    customLayout?.visibility = android.view.View.GONE
                }
                else -> {
                    radio.check(R.id.autoPilotCustom)
                    customLayout?.visibility = android.view.View.VISIBLE
                }
            }
            updating = false
        }

        updateRadio(current)

        radio.setOnCheckedChangeListener { _, checkedId ->
            if (updating) return@setOnCheckedChangeListener
            val newSeconds = when (checkedId) {
                R.id.autoPilotOff -> 0
                R.id.autoPilot15 -> 15
                R.id.autoPilot20 -> 20
                R.id.autoPilot30 -> 30
                R.id.autoPilotCustom -> edit?.text?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: 20
                else -> 0
            }
            customLayout?.visibility = if (checkedId == R.id.autoPilotCustom) android.view.View.VISIBLE else android.view.View.GONE
            if (checkedId == R.id.autoPilotCustom) {
                edit?.requestFocus()
            }
            updating = true
            if (newSeconds > 0) edit?.setText(newSeconds.toString())
            updating = false

            GestureAccessibilityService.setAutoPilotSeconds(this, newSeconds)
            GestureAccessibilityService.instance?.let { service ->
                if (newSeconds > 0) {
                    service.startAutoPilot(newSeconds)
                } else {
                    service.stopAutoPilot()
                }
            }
        }

        edit?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val seconds = s?.toString()?.trim()?.toIntOrNull() ?: 0
                if (radio.checkedRadioButtonId == R.id.autoPilotCustom) {
                    GestureAccessibilityService.setAutoPilotSeconds(this@MainActivity, seconds)
                    GestureAccessibilityService.instance?.let { service ->
                        if (seconds > 0) service.startAutoPilot(seconds) else service.stopAutoPilot()
                    }
                }
            }
        })

        resetCheck?.apply {
            isChecked = GestureAccessibilityService.isAutoPilotResetOnScroll(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                GestureAccessibilityService.setAutoPilotResetOnScroll(this@MainActivity, isChecked)
            }
        }

        startBtn?.setOnClickListener {
            val seconds = GestureAccessibilityService.autoPilotSeconds(this@MainActivity)
            val effectiveSeconds = if (seconds > 0) seconds else 20
            GestureAccessibilityService.setAutoPilotSeconds(this@MainActivity, effectiveSeconds)
            GestureAccessibilityService.instance?.startAutoPilot(effectiveSeconds)
            openTikTok(locked = false)
        }
    }

    // ------------------------------------------------------------ floating mini control

    private fun setUpMiniControl() {
        findViewById<CheckBox>(R.id.checkMiniControl).apply {
            isChecked = GestureAccessibilityService.isMiniControlEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                GestureAccessibilityService.setMiniControlEnabled(this@MainActivity, isChecked)
                GestureAccessibilityService.instance?.updateMiniControl()
            }
        }

        findViewById<CheckBox>(R.id.checkMiniControlAlways)?.apply {
            isChecked = GestureAccessibilityService.isMiniControlAlwaysOn(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                GestureAccessibilityService.setMiniControlAlwaysOn(this@MainActivity, isChecked)
                GestureAccessibilityService.instance?.updateMiniControl()
                GestureAccessibilityService.instance?.updateFloatingNav()
            }
        }

        findViewById<CheckBox>(R.id.checkAutoSkipAds)?.apply {
            isChecked = GestureAccessibilityService.isAutoSkipAdsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                GestureAccessibilityService.setAutoSkipAdsEnabled(this@MainActivity, isChecked)
                if (isChecked) {
                    GestureAccessibilityService.instance?.scheduleAutoSkipCheck(300L, force = true)
                }
            }
        }
    }

    // ------------------------------------------------------------ YouTube auto-skip ads

    private var updatingYouTubeUi = false

    private fun setUpYouTubeAutoSkip() {
        findViewById<CheckBox>(R.id.checkYtAutoSkip)?.setOnCheckedChangeListener { _, isChecked ->
            if (updatingYouTubeUi) return@setOnCheckedChangeListener
            YouTubeAdSkipper.setEnabled(this, isChecked)
            if (isChecked && !GestureAccessibilityService.isEnabled(this)) {
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
            }
            GestureAccessibilityService.instance?.refreshYouTubeAdSettings()
        }

        findViewById<CheckBox>(R.id.checkYtMuteAds)?.setOnCheckedChangeListener { _, isChecked ->
            if (updatingYouTubeUi) return@setOnCheckedChangeListener
            YouTubeAdSkipper.setMuteAdsEnabled(this, isChecked)
            GestureAccessibilityService.instance?.refreshYouTubeAdSettings()
        }

        findViewById<Button>(R.id.buttonOpenYouTubeRegular)?.setOnClickListener {
            if (!GestureAccessibilityService.isEnabled(this)) {
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!RemoteMediaService.isRunning) RemoteMediaService.start(this)
            if (!VideoApp.YOUTUBE.launch(this, openFeed = false)) {
                Toast.makeText(this, R.string.yt_missing, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<CheckBox>(R.id.checkFbAutoSkip)?.setOnCheckedChangeListener { _, isChecked ->
            if (updatingYouTubeUi) return@setOnCheckedChangeListener
            FacebookAdSkipper.setEnabled(this, isChecked)
            if (isChecked && !GestureAccessibilityService.isEnabled(this)) {
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.buttonResetYtSkipCount)?.setOnClickListener {
            YouTubeAdSkipper.resetSkippedCount(this)
            refreshYouTubeAutoSkip()
            GestureAccessibilityService.instance?.refreshYouTubeAdSettings()
        }

        refreshYouTubeAutoSkip()
    }

    private fun refreshYouTubeAutoSkip() {
        updatingYouTubeUi = true
        findViewById<CheckBox>(R.id.checkYtAutoSkip)?.isChecked =
            YouTubeAdSkipper.isEnabled(this)
        findViewById<CheckBox>(R.id.checkYtMuteAds)?.isChecked =
            YouTubeAdSkipper.isMuteAdsEnabled(this)
        findViewById<CheckBox>(R.id.checkFbAutoSkip)?.isChecked =
            FacebookAdSkipper.isEnabled(this)
        updatingYouTubeUi = false

        val count = YouTubeAdSkipper.skippedCount(this)
        findViewById<TextView>(R.id.textYtSkippedBadge)?.text =
            getString(R.string.yt_auto_skip_badge, count)
    }

    private fun setUpInactivitySleep() {
        val radio = findViewById<RadioGroup>(R.id.radioInactivitySleep) ?: return
        val current = GestureAccessibilityService.inactivitySleepMinutes(this)
        when (current) {
            10 -> radio.check(R.id.inactivity10)
            15 -> radio.check(R.id.inactivity15)
            30 -> radio.check(R.id.inactivity30)
            else -> radio.check(R.id.inactivityOff)
        }
        radio.setOnCheckedChangeListener { _, checkedId ->
            val mins = when (checkedId) {
                R.id.inactivity10 -> 10
                R.id.inactivity15 -> 15
                R.id.inactivity30 -> 30
                else -> 0
            }
            GestureAccessibilityService.setInactivitySleepMinutes(this, mins)
            GestureAccessibilityService.instance?.noteUserActivity()
        }
    }

    private fun setUpGesturePoints() {
        findViewById<Button>(R.id.buttonCalibratePoints)?.setOnClickListener {
            if (!GestureAccessibilityService.isEnabled(this)) {
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            GestureAccessibilityService.instance?.showCalibration()
        }

        val radio = findViewById<RadioGroup>(R.id.radioSwipeColumn) ?: return
        val currentX = GesturePoints.getSwipeX(this)
        when {
            currentX <= 0.35f -> radio.check(R.id.colLeft)
            currentX >= 0.65f -> radio.check(R.id.colRight)
            else -> radio.check(R.id.colCenter)
        }
        radio.setOnCheckedChangeListener { _, checkedId ->
            val newX = when (checkedId) {
                R.id.colLeft -> 0.25f
                R.id.colRight -> 0.75f
                else -> 0.50f
            }
            GesturePoints.setSwipeX(this, newX)
        }

        findViewById<CheckBox>(R.id.checkFloatingNav)?.apply {
            isChecked = GesturePoints.isFloatingNavEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                GesturePoints.setFloatingNavEnabled(this@MainActivity, isChecked)
                GestureAccessibilityService.instance?.updateFloatingNav()
            }
        }
    }

    private fun setUpSleepBlackout() {
        findViewById<Button>(R.id.buttonStartSleepBlackout)?.setOnClickListener {
            if (!GestureAccessibilityService.isEnabled(this)) {
                Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!RemoteMediaService.isRunning) RemoteMediaService.start(this)
            GestureAccessibilityService.instance?.showSleepBlackout()
        }

        findViewById<Button>(R.id.buttonOpenTikTokSleep)?.setOnClickListener {
            openTikTok(locked = false, sleepBlackout = true)
        }

        findViewById<CheckBox>(R.id.checkSleepGestures)?.apply {
            isChecked = SleepBlackoutOverlay.isSleepGesturesEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                SleepBlackoutOverlay.setSleepGesturesEnabled(this@MainActivity, isChecked)
            }
        }
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

    /**
     * Press count style: what a Play/Pause hold does (pocket lock, like or save) and how
     * long it must be (100 ms steps).
     */
    private fun setUpCountLockHold() {
        val label = findViewById<TextView>(R.id.textCountLockHold)
        val seek = findViewById<SeekBar>(R.id.seekCountLockHold)
        val step = 100
        fun show(ms: Int) {
            val beeps = HoldAction.countHoldAction(this).beeps
            label.text = getString(
                if (beeps == 1) R.string.count_lock_label else R.string.count_lock_label_plural,
                ms / 1000f, beeps
            )
        }

        val actionIds = mapOf(
            R.id.countHoldLock to HoldAction.LOCK,
            R.id.countHoldLike to HoldAction.LIKE,
            R.id.countHoldSave to HoldAction.SAVE
        )
        val radio = findViewById<RadioGroup>(R.id.radioCountHold)
        val chosen = HoldAction.countHoldAction(this)
        radio.check(actionIds.entries.first { it.value == chosen }.key)
        radio.setOnCheckedChangeListener { _, id ->
            actionIds[id]?.let { HoldAction.setCountHoldAction(this, it) }
            show(HoldAction.countLockHoldMs(this).toInt()) // beep count follows the action
        }

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
        findViewById<TextView>(R.id.textCountLockHelp).setText(R.string.count_lock_help_ok)
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

    private var syncingTranslatorUi = false

    private val freeSourceLangs = listOf(
        TranslatorPrefs.LANG_AUTO to "Auto-Detect (Android 13+ / Captions)",
        "en-US" to "English (en-US)",
        "zh-CN" to "Chinese Mandarin (zh-CN)",
        "th-TH" to "Thai (th-TH)",
        "ja-JP" to "Japanese (ja-JP)",
        "ko-KR" to "Korean (ko-KR)",
        "vi-VN" to "Vietnamese (vi-VN)",
        "fr-FR" to "French (fr-FR)",
        "es-ES" to "Spanish (es-ES)",
        "id-ID" to "Indonesian (id-ID)",
        "hi-IN" to "Hindi (hi-IN)",
        "ar-SA" to "Arabic (ar-SA)",
        "ru-RU" to "Russian (ru-RU)"
    )

    private fun setUpYouTubeTranslator() {
        val checkSubtitles = findViewById<CheckBox>(R.id.checkYtTranslateSubtitles)
        val checkVoice = findViewById<CheckBox>(R.id.checkYtTranslateVoice)
        val checkAudioTrack = findViewById<CheckBox>(R.id.checkYtAudioTrackMode)
        val checkMuteOrig = findViewById<CheckBox>(R.id.checkYtAudioTrackMuteOrig)
        val textTtsVolume = findViewById<TextView>(R.id.textYtTtsVolumeLabel)
        val seekTtsVolume = findViewById<SeekBar>(R.id.seekYtTtsVolume)
        val textTtsSpeed = findViewById<TextView>(R.id.textYtTtsSpeedLabel)
        val seekTtsSpeed = findViewById<SeekBar>(R.id.seekYtTtsSpeed)
        val radioVoiceGender = findViewById<RadioGroup>(R.id.radioYtVoiceGender)
        val radioVoiceTone = findViewById<RadioGroup>(R.id.radioYtVoiceTone)
        val checkShowOriginal = findViewById<CheckBox>(R.id.checkYtTranslateShowOriginal)
        val checkOnlyYt = findViewById<CheckBox>(R.id.checkYtTranslateOnlyYt)
        val editApiKey = findViewById<EditText>(R.id.editGeminiApiKey)
        val btnSaveKey = findViewById<Button>(R.id.buttonSaveGeminiKey)
        val spinnerLang = findViewById<Spinner>(R.id.spinnerYtSourceLang)
        val btnToggleLive = findViewById<Button>(R.id.buttonToggleYtLiveTranslate)
        val btnRestartTts = findViewById<Button>(R.id.buttonRestartTtsDetect)

        val initVol = TranslatorPrefs.getTtsVolumePercent(this)
        textTtsVolume.text = "Khmer Voice Volume: $initVol%"
        seekTtsVolume.max = 90
        seekTtsVolume.progress = (initVol - 10).coerceIn(0, 90)
        seekTtsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val vol = (progress + 10).coerceIn(10, 100)
                textTtsVolume.text = "Khmer Voice Volume: $vol%"
                if (!fromUser || syncingTranslatorUi) return
                TranslatorPrefs.setTtsVolumePercent(this@MainActivity, vol)
                GestureAccessibilityService.instance?.updateKhmerTtsVolume()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val initSpeed = TranslatorPrefs.getTtsSpeed(this)
        textTtsSpeed.text = String.format(Locale.US, "Khmer Voice Speed: %.2fx", initSpeed)
        seekTtsSpeed.max = 24 // 1.00x .. 2.20x in 0.05x steps
        seekTtsSpeed.progress = ((initSpeed - 1.0f) / 0.05f).toInt().coerceIn(0, 24)
        seekTtsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val speed = (1.0f + progress * 0.05f).coerceIn(1.0f, 2.2f)
                textTtsSpeed.text = String.format(Locale.US, "Khmer Voice Speed: %.2fx", speed)
                if (!fromUser || syncingTranslatorUi) return
                TranslatorPrefs.setTtsSpeed(this@MainActivity, speed)
                GestureAccessibilityService.instance?.updateKhmerTtsSpeed()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        radioVoiceGender.setOnCheckedChangeListener { _, checkedId ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.radioVoiceFemale -> TranslatorPrefs.GENDER_MODE_FEMALE
                R.id.radioVoiceMale -> TranslatorPrefs.GENDER_MODE_MALE
                else -> TranslatorPrefs.GENDER_MODE_AUTO
            }
            TranslatorPrefs.setVoiceGenderMode(this, mode)
        }

        radioVoiceTone.setOnCheckedChangeListener { _, checkedId ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.radioToneWarm -> TranslatorPrefs.TONE_MODE_WARM
                R.id.radioToneLively -> TranslatorPrefs.TONE_MODE_LIVELY
                R.id.radioToneFlat -> TranslatorPrefs.TONE_MODE_FLAT
                else -> TranslatorPrefs.TONE_MODE_NATURAL
            }
            TranslatorPrefs.setVoiceToneMode(this, mode)
        }

        editApiKey.setText(TranslatorPrefs.getGeminiApiKey(this))

        val labels = freeSourceLangs.map { it.second }
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#F8FAFC"))
                tv.textSize = 13f
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setBackgroundColor(Color.parseColor("#151C2C"))
                tv.setTextColor(Color.parseColor("#F8FAFC"))
                tv.textSize = 13f
                val pad = (12 * resources.displayMetrics.density).toInt()
                tv.setPadding(pad, pad, pad, pad)
                return tv
            }
        }
        spinnerLang.adapter = adapter

        checkSubtitles.setOnCheckedChangeListener { _, checked ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            TranslatorPrefs.setSubtitleEnabled(this, checked)
            GestureAccessibilityService.instance?.updateKhmerSubtitleOverlay()
            GestureAccessibilityService.instance?.refreshTranslatorUiState()
            refreshYouTubeTranslator()
        }

        checkVoice.setOnCheckedChangeListener { _, checked ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            TranslatorPrefs.setVoiceTtsEnabled(this, checked)
            if (!checked) {
                GestureAccessibilityService.instance?.stopKhmerVoice()
            }
            GestureAccessibilityService.instance?.refreshTranslatorUiState()
            refreshYouTubeTranslator()
        }

        checkAudioTrack.setOnCheckedChangeListener { _, checked ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            TranslatorPrefs.setAudioTrackModeEnabled(this, checked)
            GestureAccessibilityService.instance?.refreshTranslatorUiState()
            refreshYouTubeTranslator()
        }

        checkMuteOrig.setOnCheckedChangeListener { _, checked ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            TranslatorPrefs.setAudioTrackMuteOrigEnabled(this, checked)
            GestureAccessibilityService.instance?.refreshTranslatorUiState()
            refreshYouTubeTranslator()
        }

        checkShowOriginal.setOnCheckedChangeListener { _, checked ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            TranslatorPrefs.setShowOriginalEnabled(this, checked)
            GestureAccessibilityService.instance?.updateKhmerSubtitleOverlay()
        }

        checkOnlyYt.setOnCheckedChangeListener { _, checked ->
            if (syncingTranslatorUi) return@setOnCheckedChangeListener
            TranslatorPrefs.setOnlyYouTube(this, checked)
            GestureAccessibilityService.instance?.updateKhmerSubtitleOverlay()
        }

        btnSaveKey.setOnClickListener {
            val raw = editApiKey.text?.toString()?.trim().orEmpty()
            TranslatorPrefs.setGeminiApiKey(this, raw)
            KhmerTranslatorClient.resetCooldowns()
            YouTubeTranslatorService.clearQuotaCooldown()
            editApiKey.clearFocus()
            Toast.makeText(
                this,
                if (raw.isNotEmpty()) getString(R.string.yt_translate_key_saved)
                else getString(R.string.yt_translate_key_cleared),
                Toast.LENGTH_SHORT
            ).show()
            refreshYouTubeTranslator()
        }

        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (syncingTranslatorUi) return
                val code = freeSourceLangs.getOrNull(position)?.first ?: TranslatorPrefs.LANG_AUTO
                TranslatorPrefs.setFreeSourceLang(this@MainActivity, code)
                refreshYouTubeTranslator()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        btnToggleLive.setOnClickListener {
            if (YouTubeTranslatorService.isRunning) {
                YouTubeTranslatorService.stop(this)
                refreshYouTubeTranslator()
                return@setOnClickListener
            }

            val typedKey = editApiKey.text?.toString()?.trim().orEmpty()
            if (typedKey != TranslatorPrefs.getGeminiApiKey(this)) {
                TranslatorPrefs.setGeminiApiKey(this, typedKey)
            }
            KhmerTranslatorClient.resetCooldowns()
            YouTubeTranslatorService.clearQuotaCooldown()
            if (!TranslatorPrefs.isSubtitleEnabled(this) && !TranslatorPrefs.isVoiceTtsEnabled(this)) {
                TranslatorPrefs.setSubtitleEnabled(this, true)
            }
            if (GestureAccessibilityService.instance == null) {
                Toast.makeText(
                    this,
                    "Tip: Enable RemoteScroll Accessibility Service to show floating subtitles over YouTube.",
                    Toast.LENGTH_LONG
                ).show()
            }
            GestureAccessibilityService.instance?.updateKhmerSubtitleOverlay()
            MediaProjectionPromptActivity.launch(this, openYouTubeAfterConsent = true)
        }

        btnRestartTts.setOnClickListener {
            val svc = GestureAccessibilityService.instance
            if (svc != null) {
                svc.restartTtsAndLiveDetection()
            } else {
                YouTubeTranslatorService.restartRealtimeDetection()
            }
            Toast.makeText(this, "🔄 Restarted TTS Detection & Live Real-Time Sync", Toast.LENGTH_SHORT).show()
            refreshYouTubeTranslator()
        }

        refreshYouTubeTranslator()
    }

    private fun refreshYouTubeTranslator() {
        val badge = findViewById<TextView>(R.id.textYtTranslateStatusBadge) ?: return
        val checkSubtitles = findViewById<CheckBox>(R.id.checkYtTranslateSubtitles) ?: return
        val checkVoice = findViewById<CheckBox>(R.id.checkYtTranslateVoice) ?: return
        val checkAudioTrack = findViewById<CheckBox>(R.id.checkYtAudioTrackMode)
        val checkMuteOrig = findViewById<CheckBox>(R.id.checkYtAudioTrackMuteOrig)
        val textTtsVolume = findViewById<TextView>(R.id.textYtTtsVolumeLabel)
        val seekTtsVolume = findViewById<SeekBar>(R.id.seekYtTtsVolume)
        val textTtsSpeed = findViewById<TextView>(R.id.textYtTtsSpeedLabel)
        val seekTtsSpeed = findViewById<SeekBar>(R.id.seekYtTtsSpeed)
        val radioVoiceGender = findViewById<RadioGroup>(R.id.radioYtVoiceGender)
        val radioVoiceTone = findViewById<RadioGroup>(R.id.radioYtVoiceTone)
        val checkShowOriginal = findViewById<CheckBox>(R.id.checkYtTranslateShowOriginal) ?: return
        val checkOnlyYt = findViewById<CheckBox>(R.id.checkYtTranslateOnlyYt) ?: return
        val spinnerLang = findViewById<Spinner>(R.id.spinnerYtSourceLang) ?: return
        val btnToggleLive = findViewById<Button>(R.id.buttonToggleYtLiveTranslate) ?: return
        val statusText = findViewById<TextView>(R.id.textYtTranslateLiveStatus) ?: return

        val live = YouTubeTranslatorService.isRunning
        val subtitleOn = TranslatorPrefs.isSubtitleEnabled(this)
        val hasKey = TranslatorPrefs.hasGeminiApiKey(this)
        val currentLang = TranslatorPrefs.getFreeSourceLang(this)
        val langIdx = freeSourceLangs.indexOfFirst { it.first == currentLang }.coerceAtLeast(0)
        val ttsVol = TranslatorPrefs.getTtsVolumePercent(this)
        val ttsSpeed = TranslatorPrefs.getTtsSpeed(this)

        syncingTranslatorUi = true
        try {
            checkSubtitles.isChecked = subtitleOn
            checkVoice.isChecked = TranslatorPrefs.isVoiceTtsEnabled(this)
            checkAudioTrack?.isChecked = TranslatorPrefs.isAudioTrackModeEnabled(this)
            checkMuteOrig?.isChecked = TranslatorPrefs.isAudioTrackMuteOrigEnabled(this)
            textTtsVolume?.text = "Khmer Voice Volume: $ttsVol%"
            if (seekTtsVolume != null && seekTtsVolume.progress != ttsVol - 10) {
                seekTtsVolume.progress = (ttsVol - 10).coerceIn(0, 90)
            }
            textTtsSpeed?.text = String.format(Locale.US, "Khmer Voice Speed: %.2fx", ttsSpeed)
            val speedProgress = ((ttsSpeed - 1.0f) / 0.05f).toInt().coerceIn(0, 24)
            if (seekTtsSpeed != null && seekTtsSpeed.progress != speedProgress) {
                seekTtsSpeed.progress = speedProgress
            }
            val targetGenderBtn = when (TranslatorPrefs.getVoiceGenderMode(this)) {
                TranslatorPrefs.GENDER_MODE_FEMALE -> R.id.radioVoiceFemale
                TranslatorPrefs.GENDER_MODE_MALE -> R.id.radioVoiceMale
                else -> R.id.radioVoiceAuto
            }
            if (radioVoiceGender != null && radioVoiceGender.checkedRadioButtonId != targetGenderBtn) {
                radioVoiceGender.check(targetGenderBtn)
            }
            val targetToneBtn = when (TranslatorPrefs.getVoiceToneMode(this)) {
                TranslatorPrefs.TONE_MODE_WARM -> R.id.radioToneWarm
                TranslatorPrefs.TONE_MODE_LIVELY -> R.id.radioToneLively
                TranslatorPrefs.TONE_MODE_FLAT -> R.id.radioToneFlat
                else -> R.id.radioToneNatural
            }
            if (radioVoiceTone != null && radioVoiceTone.checkedRadioButtonId != targetToneBtn) {
                radioVoiceTone.check(targetToneBtn)
            }
            checkShowOriginal.isChecked = TranslatorPrefs.isShowOriginalEnabled(this)
            checkOnlyYt.isChecked = TranslatorPrefs.isOnlyYouTube(this)
            if (spinnerLang.selectedItemPosition != langIdx) {
                spinnerLang.setSelection(langIdx, false)
            }
        } finally {
            syncingTranslatorUi = false
        }

        when {
            live -> {
                badge.text = getString(R.string.yt_translate_badge_live)
                badge.setTextColor(Color.parseColor("#10B981"))
            }
            subtitleOn -> {
                badge.text = getString(R.string.yt_translate_badge_caption)
                badge.setTextColor(Color.parseColor("#00D2FF"))
            }
            else -> {
                badge.text = getString(R.string.yt_translate_badge_off)
                badge.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        btnToggleLive.text = if (live) {
            getString(R.string.yt_translate_stop_btn)
        } else {
            getString(R.string.yt_translate_start_btn)
        }

        statusText.text = when {
            live && hasKey && YouTubeTranslatorService.shouldUseFreeFallback(this) ->
                "🟡 Gemini quota limit -> Hybrid Live Free Speech + CC → Khmer (auto-resumes AI)"
            live && hasKey ->
                "🟢 Streaming real-time audio → Multi-Model Gemini SSE + Auto Hybrid → Khmer"
            live ->
                "🟡 Streaming live YouTube audio ($currentLang) + YouTube CC/Live Caption → Khmer"
            hasKey && subtitleOn ->
                "🔵 Gemini SSE key ready + Real-Time Caption mode active. Tap Start Live Voice Translate."
            hasKey ->
                "✨ Gemini SSE key saved. Tap Start Live Voice Translate for real-time audio AI."
            subtitleOn ->
                "🔵 Real-Time Caption Mode active (turn on CC or Live Caption in YouTube), or tap Start Live."
            else ->
                getString(R.string.yt_translate_hint_no_key)
        }
    }

    override fun onPause() {
        // Back in TikTok (or anywhere else): remote presses act again.
        KeyLog.testing = false
        KeyLog.listener = null
        GestureAccessibilityService.instance?.onYouTubeSkipCountChanged = null
        YouTubeTranslatorService.onStateChanged = null
        super.onPause()
    }

    /**
     * Starts the remote service (if needed) and opens the chosen video app. With [locked],
     * pocket lock switches on once the app is in front; with [sleepBlackout], Audio-Only
     * sleep blackout switches on.
     */
    private fun openTikTok(locked: Boolean, sleepBlackout: Boolean = false) {
        if (!GestureAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        if (!RemoteMediaService.isRunning) RemoteMediaService.start(this)
        val app = VideoApp.current(this)
        if (!app.launch(this)) {
            Toast.makeText(this, getString(R.string.toast_tiktok_missing, getString(app.labelRes)), Toast.LENGTH_LONG).show()
            return
        }
        if (locked) {
            // The accessibility service outlives this activity, so post on its side.
            Handler(Looper.getMainLooper()).postDelayed({
                GestureAccessibilityService.instance?.showLock()
            }, LOCK_DELAY_MS)
        } else if (sleepBlackout) {
            Handler(Looper.getMainLooper()).postDelayed({
                GestureAccessibilityService.instance?.showSleepBlackout()
            }, LOCK_DELAY_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshSleepTimer()
        refreshRemotes()
        refreshAutomatic()
        refreshYouTubeAutoSkip()
        refreshYouTubeTranslator()
        YouTubeTranslatorService.onStateChanged = {
            runOnUiThread { refreshYouTubeTranslator() }
        }
        GestureAccessibilityService.instance?.onYouTubeSkipCountChanged = { count ->
            runOnUiThread {
                findViewById<TextView>(R.id.textYtSkippedBadge)?.text =
                    getString(R.string.yt_auto_skip_badge, count)
            }
        }
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
