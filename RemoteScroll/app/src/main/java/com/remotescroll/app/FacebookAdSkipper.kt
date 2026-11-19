package com.remotescroll.app

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Automatically skips ads in Facebook (`com.facebook.katana`, `com.facebook.lite`):
 *  - Clicks "Skip ad" in in-stream video ads as soon as Facebook offers it.
 *  - Swipes past "Sponsored" reels while the Reels viewer is open.
 *
 * Sponsored posts in the normal News Feed are left alone: auto-scrolling the feed while
 * the user is reading would be more annoying than the ad itself.
 *
 * Facebook's view IDs are obfuscated, so everything here works from visible text and
 * content descriptions, plus a few position checks to avoid false positives.
 */
class FacebookAdSkipper(private val service: GestureAccessibilityService) {

    enum class Outcome { NONE, CLICKED_SKIP, WAITING, SWIPED_SPONSORED }

    companion object {
        private const val PREFS = "remote_scroll"
        private const val KEY_ENABLED = "facebook_auto_skip_ads"

        private const val MAX_TREE_DEPTH = 40
        private const val MAX_NODES_PER_ROOT = 1_500
        private const val CLICK_DEBOUNCE_MS = 900L
        private const val SWIPE_COOLDOWN_MS = 3_000L

        val PACKAGES = listOf("com.facebook.katana", "com.facebook.lite")

        fun isFacebookPackage(pkg: CharSequence?): Boolean = pkg?.toString() in PACKAGES

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply()
        }

        /** Facebook's "Sponsored" tag in the languages used most around here. Lower case. */
        private val SPONSORED_LABELS = setOf(
            "sponsored",
            "được tài trợ",
            "បានឧបត្ថម្ភ", "ឧបត្ថម្ភ",
            "ได้รับการสนับสนุน",
            "bersponsor", "ditaja",
            "赞助内容", "贊助",
            "広告", "광고",
            "publicidad", "patrocinado",
            "sponsorisé", "gesponsert",
            "реклама", "ممول", "प्रायोजित"
        )

        /** Title of the Reels viewer. */
        private val REELS_LABELS = setOf(
            "reels", "reel", "thước phim", "វីដេអូខ្លី", "រីល", "รีล", "video pendek"
        )

        /** Buttons of the Reels action rail on the right edge (Like / Comment / Share). */
        private val RAIL_WORDS = listOf(
            "like", "comment", "share", "thích", "bình luận", "chia sẻ",
            "ចូលចិត្ត", "មតិ", "ចែករំលែក", "ถูกใจ", "แสดงความคิดเห็น", "แชร์",
            "suka", "komentar", "bagikan", "kongsi"
        )

        /** In-stream video ad markers ("Ad · 0:12", "Video will resume after ad"). */
        private val IN_STREAM_AD_PREFIXES = listOf(
            "ad ·", "ad •", "ad 0:", "ad 1 of", "ad 2 of",
            "video will resume", "your video will resume", "your video will play",
            "quảng cáo ·", "video sẽ tiếp tục"
        )

        internal fun isSponsoredLabel(norm: String): Boolean {
            if (norm.isEmpty()) return false
            if (norm in SPONSORED_LABELS) return true
            return SPONSORED_LABELS.any { norm.startsWith("$it ·") || norm.startsWith("$it •") }
        }

        internal fun isReelsLabel(norm: String): Boolean = norm in REELS_LABELS

        internal fun isRailButtonLabel(norm: String): Boolean =
            norm.isNotEmpty() && norm.length <= 40 && RAIL_WORDS.any { norm.startsWith(it) }

        internal fun isInStreamAdLabel(norm: String): Boolean =
            norm.isNotEmpty() && IN_STREAM_AD_PREFIXES.any { norm.startsWith(it) }
    }

    private class ScanState {
        var explicitSkip: Pair<AccessibilityNodeInfo, Rect>? = null
        var bareSkip: Pair<AccessibilityNodeInfo, Rect>? = null
        var hasInStreamAd = false
        var hasReelsTitle = false
        var railButtons = 0
        var sponsoredInLowerHalf = false
    }

    private var lastClickUptimeMs = 0L
    private var lastSwipeUptimeMs = 0L

    fun reset() {
        lastClickUptimeMs = 0L
    }

    fun evaluate(roots: List<AccessibilityNodeInfo>, screen: Point): Outcome {
        if (!isEnabled(service)) return Outcome.NONE
        val fbRoots = roots.filter { isFacebookPackage(it.packageName) }
        if (fbRoots.isEmpty()) return Outcome.NONE

        val state = ScanState()
        val screenRect = Rect(0, 0, screen.x, screen.y)
        val allowOccluded = service.isLocked || service.isSleepBlackout
        for (root in fbRoots) {
            scanTree(root, 0, intArrayOf(0), screen, screenRect, allowOccluded, state)
        }

        val now = SystemClock.uptimeMillis()

        // 1. In-stream video ad with a Skip button.
        val skip = state.explicitSkip ?: state.bareSkip?.takeIf { state.hasInStreamAd }
        if (skip != null) {
            if (now - lastClickUptimeMs < CLICK_DEBOUNCE_MS) return Outcome.WAITING
            lastClickUptimeMs = now
            val (node, bounds) = skip
            service.suppressManualTouchTracking(900L)
            val target = findClickableTarget(node, screen)
            if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                service.tapScreenPoint(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "facebook skip ad")
            }
            val total = YouTubeAdSkipper.incrementSkippedCount(service)
            KeyLog.note("facebook auto-skip: Skip ad clicked (total $total)")
            service.notifyFacebookAdSkipped(total)
            return Outcome.CLICKED_SKIP
        }

        // 2. Unskippable in-stream ad still playing: keep watching for the Skip button.
        if (state.hasInStreamAd) return Outcome.WAITING

        // 3. Sponsored reel inside the Reels viewer: swipe to the next reel.
        val inReels = state.hasReelsTitle || state.railButtons >= 2
        if (inReels && state.sponsoredInLowerHalf && now - lastSwipeUptimeMs >= SWIPE_COOLDOWN_MS) {
            lastSwipeUptimeMs = now
            service.performAutoSkipSwipe("Facebook sponsored reel")
            YouTubeAdSkipper.incrementSkippedCount(service)
            service.notifyYouTubeSkipCountUpdated()
            return Outcome.SWIPED_SPONSORED
        }

        return Outcome.NONE
    }

    private fun scanTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IntArray,
        screen: Point,
        screenRect: Rect,
        allowOccluded: Boolean,
        state: ScanState
    ) {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_NODES_PER_ROOT) return
        visited[0]++

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0 || !Rect.intersects(bounds, screenRect)) return
        if (!allowOccluded && !node.isVisibleToUser) return

        val normText = YouTubeAdSkipper.normalizeLabel(node.text?.toString().orEmpty())
        val normDesc = YouTubeAdSkipper.normalizeLabel(node.contentDescription?.toString().orEmpty())

        if (isInStreamAdLabel(normText) || isInStreamAdLabel(normDesc)) state.hasInStreamAd = true

        val smallButton = bounds.width() < screen.x * 0.5f && bounds.height() < screen.y * 0.15f
        if (smallButton) {
            if (state.explicitSkip == null &&
                (YouTubeAdSkipper.isExactAdSkipLabel(normText) || YouTubeAdSkipper.isExactAdSkipLabel(normDesc))
            ) {
                state.explicitSkip = node to bounds
            } else if (state.bareSkip == null &&
                (YouTubeAdSkipper.isBareSkipLabel(normText) || YouTubeAdSkipper.isBareSkipLabel(normDesc))
            ) {
                state.bareSkip = node to bounds
            }
        }

        if (bounds.top < screen.y * 0.2f && (isReelsLabel(normText) || isReelsLabel(normDesc))) {
            state.hasReelsTitle = true
        }
        if (bounds.centerX() > screen.x * 0.78f && smallButton &&
            (isRailButtonLabel(normText) || isRailButtonLabel(normDesc))
        ) {
            state.railButtons++
        }
        // Reels put "Sponsored" under the author name at the bottom; feed posts put it near the top of each card.
        if (bounds.top > screen.y * 0.55f && (isSponsoredLabel(normText) || isSponsoredLabel(normDesc))) {
            state.sponsoredInLowerHalf = true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scanTree(child, depth + 1, visited, screen, screenRect, allowOccluded, state)
        }
    }

    private fun findClickableTarget(node: AccessibilityNodeInfo, screen: Point): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return null
            if (candidate.isClickable && candidate.isEnabled) {
                val b = Rect().also { candidate.getBoundsInScreen(it) }
                // Never click the whole video player.
                if (b.width() < screen.x * 0.65f && b.height() < screen.y * 0.25f) return candidate
            }
            current = candidate.parent
        }
        return null
    }
}
