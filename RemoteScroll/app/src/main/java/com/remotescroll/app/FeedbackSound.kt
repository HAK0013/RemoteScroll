package com.remotescroll.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Short beeps so the user knows what is happening without looking at the screen
 * (e.g. phone in pocket under pocket lock). Played on the media stream, so the media
 * volume controls it. No audio focus is taken, so TikTok keeps playing.
 */
object FeedbackSound {

    private const val TAG = "FeedbackSound"
    private const val VOLUME = 80 // percent of the stream volume
    private const val BEEP_MS = 120
    private const val BEEP_GAP_MS = 220L

    private val handler = Handler(Looper.getMainLooper())
    private var tones: ToneGenerator? = null

    fun lockOn() = play(ToneGenerator.TONE_PROP_ACK, 250)
    fun lockOff() = play(ToneGenerator.TONE_PROP_PROMPT, 250)

    /** [count] short beeps in a row: 1 = like, 2 = pocket lock, 3 = save. */
    fun beeps(count: Int) {
        for (i in 0 until count) {
            handler.postDelayed({ play(ToneGenerator.TONE_PROP_BEEP, BEEP_MS) }, i * BEEP_GAP_MS)
        }
    }

    private fun play(tone: Int, durationMs: Int) {
        try {
            val gen = tones ?: ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also { tones = it }
            gen.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            // ToneGenerator can fail to initialise on some devices; feedback is optional.
            Log.w(TAG, "Could not play feedback tone", e)
            tones = null
        }
    }
}
