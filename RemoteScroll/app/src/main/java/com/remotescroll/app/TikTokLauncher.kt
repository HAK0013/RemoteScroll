package com.remotescroll.app

import android.content.Context
import android.content.Intent

/** Finds and opens whichever TikTok build is installed. */
object TikTokLauncher {

    /** Regional builds use different package names. Each must also be in the manifest's <queries>. */
    private val PACKAGES = listOf(
        "com.zhiliaoapp.musically",      // TikTok (global / US / EU)
        "com.ss.android.ugc.trill",      // TikTok (Asia)
        "com.zhiliaoapp.musically.go",   // TikTok Lite
        "com.ss.android.ugc.tiktok.lite",
        "com.ss.android.ugc.aweme"       // Douyin
    )

    /** @return true if TikTok was found and started. */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        return false
    }
}
