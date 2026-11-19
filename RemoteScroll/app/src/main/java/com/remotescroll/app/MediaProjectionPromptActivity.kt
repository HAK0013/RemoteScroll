package com.remotescroll.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Translucent helper activity used to obtain RECORD_AUDIO + MediaProjection consent
 * whether started from MainActivity or from the floating MiniControlOverlay over YouTube.
 */
class MediaProjectionPromptActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_OPEN_YOUTUBE = "open_youtube"

        fun launch(context: Context, openYouTubeAfterConsent: Boolean = false) {
            val intent = Intent(context, MediaProjectionPromptActivity::class.java).apply {
                putExtra(EXTRA_OPEN_YOUTUBE, openYouTubeAfterConsent)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }

    private var openYouTube = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!audioGranted) {
                Toast.makeText(this, R.string.yt_translate_audio_denied, Toast.LENGTH_LONG).show()
                finish()
                return@registerForActivityResult
            }
            requestProjectionConsent()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                YouTubeTranslatorService.start(this, result.resultCode, data)
                if (openYouTube) {
                    if (!RemoteMediaService.isRunning) {
                        RemoteMediaService.start(this)
                    }
                    VideoApp.YOUTUBE.launch(this, openFeed = false)
                }
            } else {
                Toast.makeText(this, R.string.yt_translate_capture_cancelled, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openYouTube = intent.getBooleanExtra(EXTRA_OPEN_YOUTUBE, false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.yt_translate_android10_required, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val needed = ArrayList<String>(2)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            requestProjectionConsent()
        }
    }

    private fun requestProjectionConsent() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        if (mgr == null) {
            Toast.makeText(this, R.string.yt_translate_capture_cancelled, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }
}
