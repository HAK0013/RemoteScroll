package com.remotescroll.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Short beeps so the user knows a remote action worked without looking at the screen
 * (e.g. phone in pocket under pocket lock). Played on the media stream, so the media
 * volume controls it. No audio focus is taken, so TikTok keeps playing.
 */
object FeedbackSound {

    private const val TAG = "FeedbackSound"
    private const val VOLUME = 70 // percent of the stream volume

    private var tones: ToneGenerator? = null

    fun lockOn() = play(ToneGenerator.TONE_PROP_ACK, 250)
    fun lockOff() = play(ToneGenerator.TONE_PROP_PROMPT, 250)
    fun like() = play(ToneGenerator.TONE_PROP_BEEP, 120)
    fun save() = play(ToneGenerator.TONE_PROP_BEEP2, 250)

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
