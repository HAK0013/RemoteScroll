package com.remotescroll.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Loops one second of digital silence (all-zero PCM) forever.
 *
 * Android routes media buttons to the MediaSession of the app that most recently played
 * audio. If that app has no session (TikTok usually has none), the system moves on to the
 * next playing app that does. Keeping a silent track "playing" keeps us next in line.
 *
 * Audio focus is deliberately NOT requested. Requesting AUDIOFOCUS_GAIN would make TikTok
 * pause or duck its own sound, which defeats the purpose.
 */
class SilentAudioPlayer {

    companion object {
        private const val TAG = "SilentAudioPlayer"
        private const val SAMPLE_RATE = 8_000
    }

    private var track: AudioTrack? = null

    val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun start() {
        if (track != null) return
        try {
            val frames = SAMPLE_RATE // 1 second, mono, 16-bit
            val silence = ShortArray(frames)

            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()

            newTrack.write(silence, 0, silence.size)
            newTrack.setLoopPoints(0, frames, -1) // loop forever
            newTrack.play()
            track = newTrack
            Log.i(TAG, "Silent audio started")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start silent audio", e)
            stop()
        }
    }

    fun stop() {
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
    }
}
