package com.remotescroll.app

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Extracts live spoken caption text from YouTube captions or Android System Live Caption
 * (`com.google.android.as`) windows as a zero-API-key fallback for subtitle translation.
 */
object LiveCaptionExtractor {
    private const val LIVE_CAPTION_PKG = "com.google.android.as"
    private const val MAX_DEPTH = 18
    private const val MAX_NODES = 220

    private val UI_NOISE_TEXTS = setOf(
        "subscribe", "subscribed", "share", "remix", "download", "save",
        "like", "dislike", "comments", "comment", "skip", "skip ad",
        "skip ads", "sponsored", "live", "shorts", "home", "subscriptions",
        "you", "more", "description", "captions", "live caption"
    )

    private val DURATION_REGEX = Regex("""^\d{1,2}:\d{2}(?::\d{2})?$""")
    private val COUNT_REGEX = Regex("""^[\d.,]+[KMBkmb]?$""")

    fun extractBestCaption(windows: List<AccessibilityWindowInfo>?, activeYouTubeRoot: AccessibilityNodeInfo?): String? {
        // 1) System Live Caption overlay (`com.google.android.as`) is clean and reliable when enabled
        if (windows != null) {
            for (win in windows) {
                val root = win.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg == LIVE_CAPTION_PKG) {
                    val text = findCaptionTextInTree(root, fromLiveCaption = true)
                    if (!text.isNullOrBlank()) return text
                }
            }
            for (win in windows) {
                val root = win.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg in VideoApp.YOUTUBE.packages) {
                    val text = findCaptionTextInTree(root, fromLiveCaption = false)
                    if (!text.isNullOrBlank()) return text
                }
            }
        }

        if (activeYouTubeRoot != null && activeYouTubeRoot.packageName?.toString() in VideoApp.YOUTUBE.packages) {
            return findCaptionTextInTree(activeYouTubeRoot, fromLiveCaption = false)
        }
        return null
    }

    private fun findCaptionTextInTree(root: AccessibilityNodeInfo, fromLiveCaption: Boolean): String? {
        val candidates = ArrayList<String>(4)
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || visited >= MAX_NODES) return
            visited++
            if (!node.isVisibleToUser) return

            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()

            if (text.isNotEmpty() && isValidCaptionCandidate(text, viewId, fromLiveCaption)) {
                candidates.add(text)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)
        return candidates.maxByOrNull { it.length }
    }

    private fun isValidCaptionCandidate(text: String, viewId: String, fromLiveCaption: Boolean): Boolean {
        if (text.length < 3 || text.length > 260) return false
        val lower = text.lowercase()
        if (lower in UI_NOISE_TEXTS) return false
        if (DURATION_REGEX.matches(text) || COUNT_REGEX.matches(text)) return false

        if (fromLiveCaption) {
            // Live Caption window typically contains only caption text lines and header labels
            return !lower.startsWith("detecting ") && !lower.contains("turn off")
        }

        // Inside YouTube app, restrict strictly to subtitle/caption view IDs so video titles
        // and comments aren't mistaken for live spoken dialogue.
        return viewId.contains("caption") || viewId.contains("subtitle")
    }
}
