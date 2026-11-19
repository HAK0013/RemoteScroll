package com.remotescroll.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAdSkipperTest {

    @Test
    fun normalizeLabel_stripsWhitespaceAndTrailingMarkers() {
        assertEquals("skip ad", YouTubeAdSkipper.normalizeLabel("  Skip\u00A0Ad  › "))
        assertEquals("skip", YouTubeAdSkipper.normalizeLabel("Skip!"))
        assertEquals("bỏ qua quảng cáo", YouTubeAdSkipper.normalizeLabel(" Bỏ  qua quảng cáo » "))
    }

    @Test
    fun countdownText_detectsCountdownsAndRejectsReadyButtons() {
        assertTrue(YouTubeAdSkipper.isCountdownText("Skip in 5"))
        assertTrue(YouTubeAdSkipper.isCountdownText("Skip ad in 3 s"))
        assertTrue(YouTubeAdSkipper.isCountdownText("You can skip to video in 4"))
        assertTrue(YouTubeAdSkipper.isCountdownText("Bỏ qua sau 5 giây"))
        assertTrue(YouTubeAdSkipper.isCountdownText("រំលងក្នុង 5"))
        assertTrue(YouTubeAdSkipper.isCountdownText("5秒后可跳过"))

        assertFalse(YouTubeAdSkipper.isCountdownText("Skip"))
        assertFalse(YouTubeAdSkipper.isCountdownText("Skip Ad"))
        assertFalse(YouTubeAdSkipper.isCountdownText("Skip Ads"))
        assertFalse(YouTubeAdSkipper.isCountdownText("Bỏ qua"))
        assertFalse(YouTubeAdSkipper.isCountdownText("រំលង"))
    }

    @Test
    fun skipLabels_matchExactAndRejectFalsePositives() {
        assertTrue(YouTubeAdSkipper.isExactAdSkipLabel("skip ad"))
        assertTrue(YouTubeAdSkipper.isExactAdSkipLabel("skip ads"))
        assertTrue(YouTubeAdSkipper.isExactAdSkipLabel("bỏ qua quảng cáo"))
        assertTrue(YouTubeAdSkipper.isExactAdSkipLabel("រំលងការផ្សាយពាណិជ្ជកម្ម"))

        assertTrue(YouTubeAdSkipper.isBareSkipLabel("skip"))
        assertTrue(YouTubeAdSkipper.isBareSkipLabel("bỏ qua"))
        assertTrue(YouTubeAdSkipper.isBareSkipLabel("រំលង"))

        // Must never match generic titles or unrelated prompts
        assertFalse(YouTubeAdSkipper.isExactAdSkipLabel("how to skip ads on youtube"))
        assertFalse(YouTubeAdSkipper.isBareSkipLabel("how to skip ads"))
        assertFalse(YouTubeAdSkipper.isBareSkipLabel("skip trial"))
        assertFalse(YouTubeAdSkipper.isExactAdSkipLabel("skip trial"))
        assertFalse(YouTubeAdSkipper.isBareSkipLabel("skip to next video"))
    }

    @Test
    fun adCloseLabels_onlyMatchExplicitAdOverlays() {
        assertTrue(YouTubeAdSkipper.isAdCloseLabel("close ad"))
        assertTrue(YouTubeAdSkipper.isAdCloseLabel("close ad panel"))
        assertTrue(YouTubeAdSkipper.isAdCloseLabel("dismiss ad"))

        assertFalse(YouTubeAdSkipper.isAdCloseLabel("close"))
        assertFalse(YouTubeAdSkipper.isAdCloseLabel("dismiss"))
    }

    @Test
    fun inStreamAdIndicator_ignoresPortraitFeedSponsoredCards() {
        // Portrait 1080x2400: feed Sponsored card at y=1200 must NOT trigger player mute
        assertFalse(
            YouTubeAdSkipper.isInStreamAdIndicator(
                normText = "sponsored",
                normDesc = "",
                boundsTop = 1200,
                screenWidth = 1080,
                screenHeight = 2400
            )
        )

        // Portrait 1080x2400: Sponsored label inside top video player (y=320) DOES trigger
        assertTrue(
            YouTubeAdSkipper.isInStreamAdIndicator(
                normText = "sponsored",
                normDesc = "",
                boundsTop = 320,
                screenWidth = 1080,
                screenHeight = 2400
            )
        )

        // Landscape 2400x1080: Sponsored label in bottom-left of fullscreen player (y=900) DOES trigger
        assertTrue(
            YouTubeAdSkipper.isInStreamAdIndicator(
                normText = "sponsored · my ad center",
                normDesc = "",
                boundsTop = 900,
                screenWidth = 2400,
                screenHeight = 1080
            )
        )

        // Explicit "Ad 1 of 2" always triggers
        assertTrue(
            YouTubeAdSkipper.isInStreamAdIndicator(
                normText = "ad 1 of 2",
                normDesc = "",
                boundsTop = 700,
                screenWidth = 1080,
                screenHeight = 2400
            )
        )
    }
}
