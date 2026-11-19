package com.remotescroll.app

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Automatically skips ads in YouTube (`com.google.android.youtube`):
 *  - Clicks "Skip" / "Skip Ad" / "Skip Ads" buttons as soon as the countdown finishes
 *    (via AccessibilityNodeInfo.ACTION_CLICK, with touch-tap fallback if needed).
 *  - Closes in-player overlay ad banners ("Close ad").
 *  - Swipes past sponsored YouTube Shorts ads while inside the Shorts player.
 *  - Optionally mutes media audio while waiting out a countdown / unskippable ad,
 *    then restores volume automatically once the ad ends or skips.
 *
 * Designed to work even under Pocket Lock and Sleep Blackout Mode.
 */
class YouTubeAdSkipper(private val service: GestureAccessibilityService) {

    enum class ScanOutcome {
        NONE,
        SKIPPED_BUTTON,
        WAITING_COUNTDOWN,
        SKIPPED_SHORTS_AD
    }

    companion object {
        private const val TAG = "YouTubeAdSkipper"
        private const val PREFS = "remote_scroll"
        private const val KEY_YT_AUTO_SKIP = "youtube_auto_skip_ads"
        private const val KEY_YT_MUTE_ADS = "youtube_mute_ads"
        private const val KEY_YT_SKIP_COUNT = "youtube_ads_skipped_count"

        private const val MAX_TREE_DEPTH = 32
        private const val MAX_NODES_PER_ROOT = 500
        private const val CLICK_DEBOUNCE_MS = 850L
        private const val COUNT_COOLDOWN_MS = 2_000L
        private const val SHORTS_SWIPE_COOLDOWN_MS = 2_500L

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_YT_AUTO_SKIP, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_YT_AUTO_SKIP, enabled).apply()
        }

        fun isMuteAdsEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_YT_MUTE_ADS, false)

        fun setMuteAdsEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_YT_MUTE_ADS, enabled).apply()
        }

        fun skippedCount(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_YT_SKIP_COUNT, 0)

        fun resetSkippedCount(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_YT_SKIP_COUNT, 0).apply()
        }

        /** Shared "ads skipped" total, also counted by [FacebookAdSkipper]. */
        internal fun incrementSkippedCount(context: Context): Int {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = sp.getInt(KEY_YT_SKIP_COUNT, 0) + 1
            sp.edit().putInt(KEY_YT_SKIP_COUNT, next).apply()
            return next
        }

        private val SKIP_VIEW_ID_SUFFIXES = listOf(
            ":id/skip_ad_button",
            ":id/skip_ad_button_text",
            ":id/skip_ad_button_icon",
            ":id/modern_skip_ad_text",
            ":id/skip_button"
        )

        private val AD_CLOSE_VIEW_ID_SUFFIXES = listOf(
            ":id/ad_overlay_close_button",
            ":id/close_ad_button",
            ":id/dismiss_ad_button"
        )

        private val PLAYER_AD_VIEW_ID_SUBSTRINGS = listOf(
            "skip_ad",
            "ad_progress",
            "ad_countdown",
            "ad_badge",
            "modern_ad_badge",
            "ad_info_button",
            "player_ad_overlay",
            "ad_cta_button",
            "ad_learn_more",
            "visit_advertiser"
        )

        private val SHORTS_VIEW_ID_SUBSTRINGS = listOf(
            ":id/reel_",
            ":id/shorts_player",
            ":id/shorts_video",
            ":id/shorts_container"
        )

        private val EXACT_AD_SKIP_LABELS = setOf(
            "skip ad", "skip ads", "skip this ad",
            "bỏ qua quảng cáo", "bỏ qua qc",
            "រំលងការផ្សាយពាណិជ្ជកម្ម", "រំលងពាណិជ្ជកម្ម",
            "ข้ามโฆษณา",
            "lewati iklan", "langkau iklan",
            "跳过广告", "略過廣告",
            "広告をスキップ",
            "광고 건너뛰기",
            "saltar anuncio", "saltar anuncios", "omitir anuncio",
            "ignorer l'annonce", "ignorer les annonces",
            "anzeige überspringen",
            "pular anúncio", "pular anúncios",
            "تخطي الإعلان", "تخطي الإعلانات",
            "विज्ञापन छोड़ें",
            "пропустить рекламу"
        )

        private val BARE_SKIP_LABELS = setOf(
            "skip",
            "bỏ qua",
            "រំលង",
            "ข้าม",
            "lewati", "langkau",
            "跳过", "略過",
            "スキップ",
            "건너뛰기",
            "saltar", "omitir",
            "ignorer",
            "überspringen",
            "pular",
            "تخطي",
            "छोड़ें",
            "пропустить"
        )

        private val AD_CLOSE_LABELS = setOf(
            "close ad", "close ad panel", "dismiss ad", "close advertisement",
            "đóng quảng cáo", "បិទការផ្សាយពាណិជ្ជកម្ម", "ปิดโฆษณา",
            "tutup iklan", "关闭广告", "關閉廣告", "広告を閉じる", "광고 닫기"
        )

        private val COUNTDOWN_SKIP_WORDS = listOf(
            "skip", "bỏ qua", "រំលង", "ข้าม", "lewati", "langkau",
            "跳过", "略過", "スキップ", "건너뛰기", "saltar", "omitir",
            "ignorer", "überspringen", "pular", "تخطي", "छोड़ें", "пропустить"
        )

        private val COUNTDOWN_PREFIXES = listOf(
            "skip in", "skip ad in", "you can skip", "video will play",
            "ad will end", "bỏ qua sau", "រំលងក្នុង", "จะข้ามได้ใน", "lewati dalam"
        )

        private val PLAYER_AD_PREFIXES = listOf(
            "ad 1 of", "ad 2 of", "ad ·", "ad •", "quảng cáo 1", "quảng cáo 2"
        )

        private val SHORTS_AD_KEYWORDS = listOf(
            "sponsored", "promoted", "install now", "shop now", "order now",
            "paid partnership", "learn more", "tải ngay", "quảng cáo", "ผู้สนับสนุน"
        )

        internal fun normalizeLabel(raw: String): String =
            raw.trim()
                .lowercase()
                .replace(Regex("[\\s\\u00A0]+"), " ")
                .trimEnd('.', '!', '›', '>', '»', '·', ' ')

        internal fun isCountdownText(raw: String): Boolean {
            if (raw.isEmpty()) return false
            val lower = normalizeLabel(raw)
            if (COUNTDOWN_PREFIXES.any { lower.startsWith(it) }) return true
            if (lower.any { it.isDigit() } && COUNTDOWN_SKIP_WORDS.any { lower.contains(it) }) {
                return true
            }
            return false
        }

        internal fun isExactAdSkipLabel(normLabel: String): Boolean =
            normLabel in EXACT_AD_SKIP_LABELS

        internal fun isBareSkipLabel(normLabel: String): Boolean =
            normLabel in BARE_SKIP_LABELS

        internal fun isAdCloseLabel(normLabel: String): Boolean =
            normLabel in AD_CLOSE_LABELS

        internal fun isInStreamAdIndicator(
            normText: String,
            normDesc: String,
            boundsTop: Int,
            screenWidth: Int,
            screenHeight: Int
        ): Boolean {
            if (PLAYER_AD_PREFIXES.any { normText.startsWith(it) || normDesc.startsWith(it) }) {
                return true
            }
            // Only treat "sponsored" / "visit advertiser" as an in-stream ad if it sits inside the player zone
            // (top ~35% in portrait or anywhere in landscape) so feed ad cards below portrait videos don't trigger mute.
            val isLandscape = screenWidth > screenHeight
            val inPlayerZone = isLandscape || boundsTop < screenHeight * 0.36f
            if (!inPlayerZone) return false

            return normText == "sponsored" ||
                normDesc == "sponsored" ||
                normText.startsWith("sponsored ·") ||
                normText == "visit advertiser" ||
                normDesc == "visit advertiser" ||
                normText == "quảng cáo"
        }
    }

    private data class SkipCandidate(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val fromViewId: Boolean,
        val isExplicitAdLabel: Boolean,
        val label: String
    )

    private val audioManager by lazy {
        service.getSystemService(AudioManager::class.java)
    }

    private var lastClickUptimeMs = 0L
    private var lastCountedSkipUptimeMs = 0L
    private var lastShortsSwipeUptimeMs = 0L
    private var pendingClickVerifyUptimeMs = 0L
    private var lastClickedBounds: Rect? = null
    private var mutedBySkipper = false

    val isMuteActive: Boolean get() = mutedBySkipper

    fun onForegroundChanged(isYouTubeInForeground: Boolean) {
        if (!isYouTubeInForeground) {
            pendingClickVerifyUptimeMs = 0L
            lastClickedBounds = null
            restoreMuteIfNeeded()
        }
    }

    fun restoreMuteIfNeeded() {
        if (!mutedBySkipper) return
        try {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            Log.i(TAG, "Restored media audio after YouTube ad")
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore media audio", e)
        } finally {
            mutedBySkipper = false
        }
    }

    private fun muteForAdIfNeeded() {
        if (!isMuteAdsEnabled(service) || mutedBySkipper) return
        val am = audioManager ?: return
        try {
            if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                mutedBySkipper = true
                Log.i(TAG, "Muted media audio during YouTube ad")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute media audio during ad", e)
        }
    }

    fun evaluate(
        roots: List<AccessibilityNodeInfo>,
        screen: Point,
        allowShortsLiveSkip: Boolean
    ): ScanOutcome {
        if (!isEnabled(service)) {
            restoreMuteIfNeeded()
            return ScanOutcome.NONE
        }

        val ytRoots = roots.filter { it.packageName?.toString() in VideoApp.YOUTUBE.packages }
        if (ytRoots.isEmpty()) {
            restoreMuteIfNeeded()
            return ScanOutcome.NONE
        }

        val screenRect = Rect(0, 0, screen.x, screen.y)
        val allowOccludedLookup = service.isLocked || service.isSleepBlackout
        val skipCandidates = mutableListOf<SkipCandidate>()
        var adCloseCandidate: AccessibilityNodeInfo? = null
        var hasPlayerAdContext = false
        var hasCountdown = false
        var isShortsPlayer = false
        var shortsAdDetected = false
        var shortsLiveDetected = false

        for (root in ytRoots) {
            val visited = intArrayOf(0)
            scanTree(
                node = root,
                depth = 0,
                visited = visited,
                screen = screen,
                screenRect = screenRect,
                allowOccludedLookup = allowOccludedLookup,
                skipCandidates = skipCandidates,
                onAdCloseFound = { if (adCloseCandidate == null) adCloseCandidate = it },
                onPlayerAdContext = { hasPlayerAdContext = true },
                onCountdownFound = { hasCountdown = true; hasPlayerAdContext = true },
                onShortsPlayerFound = { isShortsPlayer = true },
                onShortsAdFound = { shortsAdDetected = true },
                onShortsLiveFound = { shortsLiveDetected = true }
            )
        }

        // 1. Choose the best verified Skip Ad button (viewId or explicit ad label first, then bare "Skip" with context/position)
        val bestSkip = skipCandidates.firstOrNull { it.fromViewId || it.isExplicitAdLabel }
            ?: skipCandidates.firstOrNull { candidate ->
                val b = candidate.bounds
                val reasonableSize = b.width() < screen.x * 0.45f && b.height() < screen.y * 0.14f
                val bottomRightZone = b.centerX() > screen.x * 0.50f && b.centerY() > screen.y * 0.10f
                reasonableSize && (hasPlayerAdContext || bottomRightZone)
            }

        val now = SystemClock.uptimeMillis()

        if (bestSkip != null) {
            if (isMuteAdsEnabled(service)) {
                muteForAdIfNeeded()
            } else {
                restoreMuteIfNeeded()
            }

            if (now - lastClickUptimeMs < CLICK_DEBOUNCE_MS) {
                return ScanOutcome.WAITING_COUNTDOWN
            }

            // If our previous ACTION_CLICK left the same Skip button still on screen, fall back to a real screen tap.
            val shouldUseTapFallback = pendingClickVerifyUptimeMs > 0L &&
                now - pendingClickVerifyUptimeMs >= CLICK_DEBOUNCE_MS &&
                lastClickedBounds != null &&
                Rect.intersects(bestSkip.bounds, lastClickedBounds!!)

            service.suppressManualTouchTracking(900L)
            var acted = false
            if (!shouldUseTapFallback) {
                val clickableTarget = findClickableTarget(bestSkip.node, screen)
                if (clickableTarget != null && clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    acted = true
                    pendingClickVerifyUptimeMs = now
                    lastClickedBounds = Rect(bestSkip.bounds)
                }
            }

            if (!acted) {
                service.tapScreenPoint(
                    bestSkip.bounds.centerX().toFloat(),
                    bestSkip.bounds.centerY().toFloat(),
                    "youtube skip ad"
                )
                pendingClickVerifyUptimeMs = 0L
                lastClickedBounds = null
            }

            lastClickUptimeMs = now
            recordSkipIfNeeded(now, "Skip button (${bestSkip.label.ifEmpty { "id" }})")
            return ScanOutcome.SKIPPED_BUTTON
        }

        pendingClickVerifyUptimeMs = 0L
        lastClickedBounds = null

        // 2. Close in-player overlay ad banners if visible
        val closeNode = adCloseCandidate
        if (closeNode != null && now - lastClickUptimeMs >= CLICK_DEBOUNCE_MS) {
            val target = findClickableTarget(closeNode, screen)
            if (target != null) {
                service.suppressManualTouchTracking(700L)
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastClickUptimeMs = now
                    recordSkipIfNeeded(now, "Ad banner close")
                    return ScanOutcome.SKIPPED_BUTTON
                }
            }
        }

        // 3. Handle countdown / unskippable in-stream ad state (mute if enabled & keep polling)
        if (hasPlayerAdContext || hasCountdown) {
            if (isMuteAdsEnabled(service)) {
                muteForAdIfNeeded()
            } else {
                restoreMuteIfNeeded()
            }
            return ScanOutcome.WAITING_COUNTDOWN
        }

        // No in-stream player ad active: restore audio immediately if we muted it
        restoreMuteIfNeeded()

        // 4. YouTube Shorts feed ads (only when actually inside the Shorts player, never on Home/Watch!)
        if (isShortsPlayer && (shortsAdDetected || (allowShortsLiveSkip && shortsLiveDetected))) {
            if (now - lastShortsSwipeUptimeMs >= SHORTS_SWIPE_COOLDOWN_MS) {
                lastShortsSwipeUptimeMs = now
                val reason = if (shortsAdDetected) "YouTube Shorts Ad" else "YouTube Shorts LIVE"
                service.performAutoSkipSwipe(reason)
                if (shortsAdDetected) {
                    incrementSkippedCount(service)
                    service.notifyYouTubeSkipCountUpdated()
                }
                return ScanOutcome.SKIPPED_SHORTS_AD
            }
        }

        return ScanOutcome.NONE
    }

    private fun recordSkipIfNeeded(now: Long, reason: String) {
        if (now - lastCountedSkipUptimeMs >= COUNT_COOLDOWN_MS) {
            lastCountedSkipUptimeMs = now
            val total = incrementSkippedCount(service)
            KeyLog.note("youtube auto-skip: $reason (total $total)")
            service.notifyYouTubeAdSkipped(total)
        } else {
            KeyLog.note("youtube auto-skip: retry $reason")
        }
    }

    private fun scanTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IntArray,
        screen: Point,
        screenRect: Rect,
        allowOccludedLookup: Boolean,
        skipCandidates: MutableList<SkipCandidate>,
        onAdCloseFound: (AccessibilityNodeInfo) -> Unit,
        onPlayerAdContext: () -> Unit,
        onCountdownFound: () -> Unit,
        onShortsPlayerFound: () -> Unit,
        onShortsAdFound: () -> Unit,
        onShortsLiveFound: () -> Unit
    ) {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_NODES_PER_ROOT) return
        visited[0]++

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val onScreen = bounds.width() > 0 && bounds.height() > 0 && Rect.intersects(bounds, screenRect)
        if (!onScreen) return
        if (!allowOccludedLookup && !node.isVisibleToUser) return

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val rawText = node.text?.toString()?.trim().orEmpty()
        val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
        val normText = normalizeLabel(rawText)
        val normDesc = normalizeLabel(rawDesc)

        if (viewId.isNotEmpty()) {
            if (SHORTS_VIEW_ID_SUBSTRINGS.any { viewId.contains(it) }) {
                onShortsPlayerFound()
            }
            if (PLAYER_AD_VIEW_ID_SUBSTRINGS.any { viewId.contains(it) }) {
                onPlayerAdContext()
            }
            if (AD_CLOSE_VIEW_ID_SUFFIXES.any { viewId.endsWith(it) }) {
                onAdCloseFound(node)
            }
        }

        // Check countdown / player ad labels
        if (isCountdownText(rawText) || isCountdownText(rawDesc)) {
            onCountdownFound()
        }
        if (hasInStreamAdIndicator(normText, normDesc, bounds, screen)) {
            onPlayerAdContext()
        }

        // Check Shorts ads / LIVE
        if (SHORTS_AD_KEYWORDS.any { normText.contains(it) || normDesc.contains(it) }) {
            onShortsAdFound()
        }
        if (rawText.equals("LIVE", ignoreCase = true) ||
            rawText.equals("Tap to watch LIVE", ignoreCase = true) ||
            rawDesc.startsWith("LIVE", ignoreCase = true)
        ) {
            onShortsLiveFound()
        }

        // Check Skip Ad button by View ID
        val matchesSkipId = SKIP_VIEW_ID_SUFFIXES.any { viewId.endsWith(it) }
        if (matchesSkipId) {
            if (!subtreeHasCountdown(node, 0)) {
                skipCandidates += SkipCandidate(
                    node = node,
                    bounds = bounds,
                    fromViewId = true,
                    isExplicitAdLabel = true,
                    label = normText.ifEmpty { normDesc }
                )
            } else {
                onCountdownFound()
            }
        } else {
            // Check Skip Ad button by exact text / contentDescription (Litho / Elements UI)
            val matchedExplicit = when {
                isExactAdSkipLabel(normText) -> normText
                isExactAdSkipLabel(normDesc) -> normDesc
                else -> null
            }
            if (matchedExplicit != null) {
                skipCandidates += SkipCandidate(
                    node = node,
                    bounds = bounds,
                    fromViewId = false,
                    isExplicitAdLabel = true,
                    label = matchedExplicit
                )
            } else {
                val matchedBare = when {
                    isBareSkipLabel(normText) -> normText
                    isBareSkipLabel(normDesc) -> normDesc
                    else -> null
                }
                if (matchedBare != null && !isCountdownText(rawText) && !isCountdownText(rawDesc)) {
                    skipCandidates += SkipCandidate(
                        node = node,
                        bounds = bounds,
                        fromViewId = false,
                        isExplicitAdLabel = false,
                        label = matchedBare
                    )
                }
            }
        }

        if (isAdCloseLabel(normText) || isAdCloseLabel(normDesc)) {
            onAdCloseFound(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scanTree(
                node = child,
                depth = depth + 1,
                visited = visited,
                screen = screen,
                screenRect = screenRect,
                allowOccludedLookup = allowOccludedLookup,
                skipCandidates = skipCandidates,
                onAdCloseFound = onAdCloseFound,
                onPlayerAdContext = onPlayerAdContext,
                onCountdownFound = onCountdownFound,
                onShortsPlayerFound = onShortsPlayerFound,
                onShortsAdFound = onShortsAdFound,
                onShortsLiveFound = onShortsLiveFound
            )
        }
    }

    private fun subtreeHasCountdown(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 4) return false
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (isCountdownText(t) || isCountdownText(d)) return true
        // Pure number inside skip_ad_button (e.g. "5") means countdown is still running
        if ((t.isNotEmpty() && t.length <= 3 && t.all { it.isDigit() }) ||
            (d.isNotEmpty() && d.length <= 3 && d.all { it.isDigit() })
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (subtreeHasCountdown(child, depth + 1)) return true
        }
        return false
    }

    private fun findClickableTarget(node: AccessibilityNodeInfo, screen: Point): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled) {
                val b = Rect().also { candidate.getBoundsInScreen(it) }
                // Guard against clicking the full-screen video player container
                if (b.width() < screen.x * 0.65f && b.height() < screen.y * 0.25f) {
                    return candidate
                }
            }
            current = candidate.parent
        }
        return null
    }

    private fun hasInStreamAdIndicator(
        normText: String,
        normDesc: String,
        bounds: Rect,
        screen: Point
    ): Boolean = isInStreamAdIndicator(
        normText = normText,
        normDesc = normDesc,
        boundsTop = bounds.top,
        screenWidth = screen.x,
        screenHeight = screen.y
    )
}
