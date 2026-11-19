package com.remotescroll.app

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class SpeakerGender {
    UNKNOWN,
    MALE,
    FEMALE
}

/**
 * Fast zero-dependency voice gender estimator over 16 kHz mono PCM using
 * Normalized Square Difference Function (NSDF / McLeod pitch detection)
 * across voiced speech frames (`82 Hz .. 290 Hz`) with hysteresis so
 * alternating male and female speakers stay distinguishable without flicker.
 */
object VoiceGenderDetector {
    private const val MIN_VOICE_RMS = 0.015
    private const val MIN_FREQ_HZ = 82.0
    private const val MAX_FREQ_HZ = 290.0
    private const val NSDF_PEAK_MIN = 0.52
    private const val FIRST_PEAK_REL_THRESHOLD = 0.85
    private const val MALE_MAX_HZ = 158.0
    private const val FEMALE_MIN_HZ = 168.0
    private const val MID_SPLIT_HZ = 163.0

    fun estimatePitchHz(pcm16Le: ByteArray, sampleRateHz: Int = 16_000): Double {
        val totalSamples = pcm16Le.size / 2
        val windowSamples = (sampleRateHz * 20) / 1000 // 20 ms window (320 samples @ 16kHz)
        if (totalSamples < windowSamples || sampleRateHz <= 0) return 0.0

        val minLag = (sampleRateHz / MAX_FREQ_HZ).toInt().coerceAtLeast(8)
        val maxLag = (sampleRateHz / MIN_FREQ_HZ).toInt().coerceAtMost(windowSamples - 8)
        if (maxLag <= minLag) return 0.0

        val samples = DoubleArray(totalSamples)
        var sIdx = 0
        var bIdx = 0
        while (bIdx + 1 < pcm16Le.size) {
            val lo = pcm16Le[bIdx].toInt() and 0xFF
            val hi = pcm16Le[bIdx + 1].toInt()
            samples[sIdx++] = ((hi shl 8) or lo) / 32768.0
            bIdx += 2
        }

        val step = windowSamples / 2
        val windowPitches = ArrayList<Double>()
        var start = 0
        while (start + windowSamples <= totalSamples) {
            var sumSq = 0.0
            for (i in start until start + windowSamples) {
                val v = samples[i]
                sumSq += v * v
            }
            val rms = sqrt(sumSq / windowSamples)
            if (rms >= MIN_VOICE_RMS) {
                val f0 = estimateWindowPitchNsdf(samples, start, windowSamples, minLag, maxLag, sampleRateHz)
                if (f0 in MIN_FREQ_HZ..MAX_FREQ_HZ) {
                    windowPitches.add(f0)
                }
            }
            start += step
        }

        if (windowPitches.isEmpty()) return 0.0
        windowPitches.sort()
        return windowPitches[windowPitches.size / 2]
    }

    private fun estimateWindowPitchNsdf(
        samples: DoubleArray,
        offset: Int,
        windowLen: Int,
        minLag: Int,
        maxLag: Int,
        sampleRateHz: Int
    ): Double {
        val nsdf = DoubleArray(maxLag + 1)
        var maxPeak = 0.0

        for (tau in minLag..maxLag) {
            val count = windowLen - tau
            if (count <= 16) break
            var acf = 0.0
            var energy = 0.0
            for (j in 0 until count) {
                val a = samples[offset + j]
                val b = samples[offset + j + tau]
                acf += a * b
                energy += a * a + b * b
            }
            val valNsdf = if (energy > 1e-9) (2.0 * acf) / energy else 0.0
            nsdf[tau] = valNsdf
            if (valNsdf > maxPeak) maxPeak = valNsdf
        }

        if (maxPeak < NSDF_PEAK_MIN) return 0.0
        val acceptThreshold = max(NSDF_PEAK_MIN, maxPeak * FIRST_PEAK_REL_THRESHOLD)

        var bestTau = -1
        for (tau in (minLag + 1) until maxLag) {
            val cur = nsdf[tau]
            if (cur >= acceptThreshold && cur >= nsdf[tau - 1] && cur >= nsdf[tau + 1]) {
                bestTau = tau
                break
            }
        }
        if (bestTau < 0) {
            // Fallback to strongest peak if edge-aligned
            for (tau in minLag..maxLag) {
                if (abs(nsdf[tau] - maxPeak) < 1e-6) {
                    bestTau = tau
                    break
                }
            }
        }
        if (bestTau <= 0) return 0.0

        var refinedTau = bestTau.toDouble()
        if (bestTau in (minLag + 1) until maxLag) {
            val y1 = nsdf[bestTau - 1]
            val y2 = nsdf[bestTau]
            val y3 = nsdf[bestTau + 1]
            val denom = 2.0 * (2.0 * y2 - y1 - y3)
            if (abs(denom) > 1e-6) {
                refinedTau += ((y3 - y1) / denom).coerceIn(-0.5, 0.5)
            }
        }
        return if (refinedTau > 0.0) sampleRateHz / refinedTau else 0.0
    }

    fun classifyPitchHz(pitchHz: Double, current: SpeakerGender = SpeakerGender.UNKNOWN): SpeakerGender {
        if (pitchHz <= 0.0) return current
        return when {
            pitchHz < MALE_MAX_HZ -> SpeakerGender.MALE
            pitchHz > FEMALE_MIN_HZ -> SpeakerGender.FEMALE
            current == SpeakerGender.MALE || current == SpeakerGender.FEMALE -> current
            pitchHz < MID_SPLIT_HZ -> SpeakerGender.MALE
            else -> SpeakerGender.FEMALE
        }
    }

    fun detectSegmentGender(
        pcm16Le: ByteArray,
        sampleRateHz: Int = 16_000,
        fallback: SpeakerGender = SpeakerGender.UNKNOWN
    ): SpeakerGender {
        val pitchHz = estimatePitchHz(pcm16Le, sampleRateHz)
        return classifyPitchHz(pitchHz, fallback)
    }
}

/**
 * Rolling real-time speaker gender tracker fed continuously by captured audio frames
 * so Free STT and Live CC modes always know whether the active speaker is Male or Female.
 */
class RollingGenderTracker(
    private val sampleRateHz: Int = 16_000,
    private val windowSize: Int = 21,
    private val staleAfterMs: Long = 5_000L,
    private val minFramesToFlip: Int = 7,
    private val flipCooldownMs: Long = 3_000L
) {
    private val lock = Any()
    private val recentPitches = ArrayDeque<Pair<Long, Double>>()
    private var opposingRunCount = 0
    private var lastFlipMs = 0L

    @Volatile
    var currentGender: SpeakerGender = SpeakerGender.UNKNOWN
        private set

    fun feedFrame(pcm16Le: ByteArray, nowMs: Long = System.currentTimeMillis()): SpeakerGender {
        val pitchHz = VoiceGenderDetector.estimatePitchHz(pcm16Le, sampleRateHz)
        synchronized(lock) {
            while (recentPitches.isNotEmpty() && nowMs - recentPitches.first().first > staleAfterMs) {
                recentPitches.removeFirst()
            }
            if (pitchHz > 0.0) {
                recentPitches.addLast(nowMs to pitchHz)
                while (recentPitches.size > windowSize) {
                    recentPitches.removeFirst()
                }
                val sorted = recentPitches.map { it.second }.sorted()
                val median = sorted[sorted.size / 2]
                val candidate = VoiceGenderDetector.classifyPitchHz(median, currentGender)
                if (currentGender == SpeakerGender.UNKNOWN) {
                    currentGender = candidate
                    lastFlipMs = nowMs
                    opposingRunCount = 0
                } else if (candidate != currentGender && candidate != SpeakerGender.UNKNOWN) {
                    opposingRunCount++
                    val cooledDown = lastFlipMs == 0L || (nowMs - lastFlipMs) >= flipCooldownMs
                    if (opposingRunCount >= minFramesToFlip && cooledDown) {
                        currentGender = candidate
                        lastFlipMs = nowMs
                        opposingRunCount = 0
                    }
                } else {
                    opposingRunCount = 0
                }
            }
            return currentGender
        }
    }

    fun updateFromSegmentGender(gender: SpeakerGender, nowMs: Long = System.currentTimeMillis()) {
        if (gender == SpeakerGender.UNKNOWN) return
        synchronized(lock) {
            if (currentGender == SpeakerGender.UNKNOWN) {
                currentGender = gender
                lastFlipMs = nowMs
            } else if (gender != currentGender) {
                opposingRunCount += 2
                val cooledDown = lastFlipMs == 0L || (nowMs - lastFlipMs) >= flipCooldownMs
                if (opposingRunCount >= minFramesToFlip && cooledDown) {
                    currentGender = gender
                    lastFlipMs = nowMs
                    opposingRunCount = 0
                }
            } else {
                opposingRunCount = 0
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            recentPitches.clear()
            opposingRunCount = 0
            lastFlipMs = 0L
            currentGender = SpeakerGender.UNKNOWN
        }
    }
}

/**
 * Encodes raw 16-bit signed little-endian PCM audio into standard RIFF/WAVE bytes.
 */
object WavEncoder {
    fun pcm16MonoToWav(pcmData: ByteArray, sampleRateHz: Int = 16_000): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36L
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRateHz.toLong() * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        val out = ByteArray(44 + pcmData.size)
        // RIFF header
        out[0] = 'R'.code.toByte()
        out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte()
        out[3] = 'F'.code.toByte()
        writeIntLE(out, 4, totalDataLen.toInt())
        out[8] = 'W'.code.toByte()
        out[9] = 'A'.code.toByte()
        out[10] = 'V'.code.toByte()
        out[11] = 'E'.code.toByte()
        // fmt chunk
        out[12] = 'f'.code.toByte()
        out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte()
        out[15] = ' '.code.toByte()
        writeIntLE(out, 16, 16)
        writeShortLE(out, 20, 1) // PCM
        writeShortLE(out, 22, channels)
        writeIntLE(out, 24, sampleRateHz)
        writeIntLE(out, 28, byteRate.toInt())
        writeShortLE(out, 32, blockAlign)
        writeShortLE(out, 34, bitsPerSample)
        // data chunk
        out[36] = 'd'.code.toByte()
        out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte()
        out[39] = 'a'.code.toByte()
        writeIntLE(out, 40, pcmData.size)
        System.arraycopy(pcmData, 0, out, 44, pcmData.size)
        return out
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}

/**
 * Splits a continuous 16 kHz mono PCM stream into spoken segments using lightweight
 * adaptive RMS voice-activity detection so silent gaps don't trigger translations
 * while uninterrupted speech streams in ~4s overlapping chunks.
 */
class VoiceSpeechSegmenter(
    private val frameDurationMs: Int = 100,
    private val preRollFrames: Int = 2,
    private val minSilenceBreakMs: Int = 220,
    private val minPhraseMs: Int = 550,
    private val minShortBurstMs: Int = 400,
    private val maxContinuousMs: Int = 1_600,
    private val overlapFramesOnCut: Int = 2,
    private val onSegmentReady: (ByteArray) -> Unit
) {
    private val preRoll = ArrayDeque<ByteArray>()
    private val activeFrames = ArrayList<ByteArray>()

    private var inSpeech = false
    private var silenceMs = 0
    private var speechMs = 0
    private var voicedMs = 0
    private var noiseFloor = 0.008

    fun feedFrame(pcm16Le: ByteArray) {
        if (pcm16Le.isEmpty()) return
        val copy = pcm16Le.copyOf()
        val rms = computeRms(copy)

        if (!inSpeech) {
            updatePreRoll(copy)
            val trigger = max(0.014, noiseFloor * 2.3)
            if (rms >= trigger) {
                inSpeech = true
                silenceMs = 0
                speechMs = preRoll.size * frameDurationMs
                activeFrames.addAll(preRoll)
                preRoll.clear()
                activeFrames.add(copy)
                speechMs += frameDurationMs
                voicedMs = frameDurationMs
            } else {
                noiseFloor = (noiseFloor * 0.92 + rms * 0.08).coerceIn(0.003, 0.030)
            }
            return
        }

        activeFrames.add(copy)
        speechMs += frameDurationMs
        val keepThreshold = max(0.010, noiseFloor * 1.7)
        if (rms < keepThreshold) {
            silenceMs += frameDurationMs
        } else {
            silenceMs = 0
            voicedMs += frameDurationMs
        }

        // Natural pause after enough speech
        if (voicedMs >= minPhraseMs && silenceMs >= minSilenceBreakMs) {
            emitActiveAndReset(keepOverlap = false)
            return
        }

        // Long pause after short utterance: either emit short utterance or discard blip noise
        if (silenceMs >= 450) {
            if (voicedMs >= minShortBurstMs) {
                emitActiveAndReset(keepOverlap = false)
            } else {
                resetState()
            }
            return
        }

        // Uninterrupted rapid speech cap: slice cleanly while preserving short tail overlap
        if (speechMs >= maxContinuousMs) {
            emitActiveAndReset(keepOverlap = true)
        }
    }

    fun flush() {
        if (inSpeech && voicedMs >= minShortBurstMs && activeFrames.isNotEmpty()) {
            emitActiveAndReset(keepOverlap = false)
        } else {
            resetState()
        }
    }

    fun reset() {
        resetState()
        noiseFloor = 0.008
    }

    private fun updatePreRoll(frame: ByteArray) {
        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) {
            preRoll.removeFirst()
        }
    }

    private fun emitActiveAndReset(keepOverlap: Boolean) {
        val out = ByteArrayOutputStream(activeFrames.sumOf { it.size })
        for (frame in activeFrames) {
            out.write(frame)
        }
        val overlap = if (keepOverlap && overlapFramesOnCut > 0 && activeFrames.size > overlapFramesOnCut) {
            activeFrames.takeLast(overlapFramesOnCut)
        } else {
            emptyList()
        }
        resetState()
        if (overlap.isNotEmpty()) {
            inSpeech = true
            activeFrames.addAll(overlap)
            speechMs = overlap.size * frameDurationMs
            voicedMs = speechMs
        }
        onSegmentReady(out.toByteArray())
    }

    private fun resetState() {
        inSpeech = false
        silenceMs = 0
        speechMs = 0
        voicedMs = 0
        activeFrames.clear()
        preRoll.clear()
    }

    companion object {
        fun computeRms(pcm16Le: ByteArray): Double {
            val sampleCount = pcm16Le.size / 2
            if (sampleCount == 0) return 0.0
            var sumSq = 0.0
            var i = 0
            while (i + 1 < pcm16Le.size) {
                val lo = pcm16Le[i].toInt() and 0xFF
                val hi = pcm16Le[i + 1].toInt()
                val sample = (hi shl 8) or lo
                val norm = sample / 32768.0
                sumSq += norm * norm
                i += 2
            }
            return sqrt(sumSq / sampleCount)
        }
    }
}

