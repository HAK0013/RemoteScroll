package com.remotescroll.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The short-video apps RemoteScroll can drive. Swipes work the same in all of them; the
 * differences are noted per app. Every package here must also be in the manifest's
 * <queries>, or Android 11+ hides it from us.
 *
 * @param packages installed builds to try, first match wins.
 * @param feedUri opens straight into the short-video feed, or null for the app's start screen.
 * @param saveByPosition TikTok only: if the Save button can't be found by its label, tap
 *   where TikTok draws it. Other apps put something else there, so they never do this.
 */
enum class VideoApp(
    val labelRes: Int,
    val packages: List<String>,
    private val feedUri: String?,
    val saveByPosition: Boolean
) {
    TIKTOK(
        R.string.app_tiktok,
        listOf(
            "com.zhiliaoapp.musically",      // TikTok (global / US / EU)
            "com.ss.android.ugc.trill",      // TikTok (Asia)
            "com.zhiliaoapp.musically.go",   // TikTok Lite
            "com.ss.android.ugc.tiktok.lite",
            "com.ss.android.ugc.aweme"       // Douyin
        ),
        feedUri = null,
        saveByPosition = true
    ),
    YOUTUBE(
        R.string.app_youtube,
        listOf("com.google.android.youtube"),
        feedUri = "https://www.youtube.com/shorts",
        saveByPosition = false
    ),
    INSTAGRAM(
        R.string.app_instagram,
        listOf("com.instagram.android"),
        feedUri = "https://www.instagram.com/reels/",
        saveByPosition = false
    );

    /** @return true if the app was found and started. */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in packages) {
            if (pm.getLaunchIntentForPackage(pkg) == null) continue // not installed
            val feed = feedUri?.let {
                Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                if (feed != null) {
                    context.startActivity(feed)
                    return true
                }
            } catch (_: ActivityNotFoundException) {
                // Fall back to the app's normal start screen.
            }
            val start = pm.getLaunchIntentForPackage(pkg) ?: continue
            context.startActivity(start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        return false
    }

    companion object {
        private const val PREFS = "remote_scroll"
        private const val KEY_APP = "video_app"

        /** The app the Open buttons and auto-start use. */
        fun current(context: Context): VideoApp {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APP, TIKTOK.name)
            return values().firstOrNull { it.name == name } ?: TIKTOK
        }

        fun setCurrent(context: Context, app: VideoApp) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_APP, app.name).apply()
        }

        /** Which supported app [packageName] belongs to, or null. */
        fun forPackage(packageName: CharSequence?): VideoApp? {
            val pkg = packageName?.toString() ?: return null
            return values().firstOrNull { pkg in it.packages }
        }
    }
}
