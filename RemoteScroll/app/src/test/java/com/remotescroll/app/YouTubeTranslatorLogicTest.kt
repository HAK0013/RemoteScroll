package com.remotescroll.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class YouTubeTranslatorLogicTest {

    private fun makePcm16Frame(durationMs: Int = 100, amplitude: Int = 0, sampleRateHz: Int = 16_000): ByteArray {
        val sampleCount = (sampleRateHz * durationMs) / 1_000
        val bytes = ByteArray(sampleCount * 2)
        if (amplitude <= 0) return bytes
        for (i in 0 until sampleCount) {
            val sample = (amplitude * sin(2.0 * PI * 440.0 * i / sampleRateHz)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    @Test
    fun wavEncoderWritesValidRiffHeaderAndPayload() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val wav = WavEncoder.pcm16MonoToWav(pcm, sampleRateHz = 16_000)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, readIntLE(wav, 4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(16_000, readIntLE(wav, 24))
        assertEquals(32_000, readIntLE(wav, 28))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, readIntLE(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun voiceSegmenterIgnoresSilenceAndEmitsSpokenPhraseWithPreRoll() {
        val emitted = mutableListOf<ByteArray>()
        val segmenter = VoiceSpeechSegmenter { emitted.add(it) }

        val silentFrame = makePcm16Frame(amplitude = 0)
        val loudFrame = makePcm16Frame(amplitude = 5_000)

        repeat(6) { segmenter.feedFrame(silentFrame) }
        assertTrue(emitted.isEmpty())

        // 6 loud frames = 600ms (>= minPhraseMs 550ms), then 3 silent frames = 300ms (>= minSilenceBreakMs 220ms)
        repeat(6) { segmenter.feedFrame(loudFrame) }
        assertTrue(emitted.isEmpty())
        repeat(3) { segmenter.feedFrame(silentFrame) }

        assertEquals(1, emitted.size)
        // Includes 2 preRoll + 6 loud + 3 trailing silent = 11 frames (each 3200 bytes)
        assertEquals(11 * 3_200, emitted[0].size)
    }

    @Test
    fun voiceSegmenterSlicesUninterruptedSpeechAtMaxContinuousCap() {
        val emitted = mutableListOf<ByteArray>()
        val segmenter = VoiceSpeechSegmenter { emitted.add(it) }
        val loudFrame = makePcm16Frame(amplitude = 6_000)

        repeat(18) { segmenter.feedFrame(loudFrame) }

        assertEquals(1, emitted.size)
        assertTrue(emitted[0].size >= 16 * 3_200)
    }

    @Test
    fun voiceSegmenterDiscardsShortBlipAndFlushesShortBurst() {
        val emitted = mutableListOf<ByteArray>()
        val segmenter = VoiceSpeechSegmenter { emitted.add(it) }
        val silentFrame = makePcm16Frame(amplitude = 0)
        val loudFrame = makePcm16Frame(amplitude = 5_000)

        // Short 200ms blip followed by 600ms silence -> discarded (< 400ms minShortBurstMs)
        repeat(3) { segmenter.feedFrame(silentFrame) }
        repeat(2) { segmenter.feedFrame(loudFrame) }
        repeat(6) { segmenter.feedFrame(silentFrame) }
        assertTrue(emitted.isEmpty())

        // 500ms short utterance flushed cleanly -> emitted (>= 400ms minShortBurstMs)
        repeat(5) { segmenter.feedFrame(loudFrame) }
        segmenter.flush()
        assertEquals(1, emitted.size)
    }

    @Test
    fun parseGeminiResponseExtractsJsonAndSkipAndFallback() {
        val normalJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"skip\":false,\"lang\":\"en\",\"src\":\"Hello everyone\",\"km\":\"សួស្តីអ្នកទាំងអស់គ្នា\"}"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val res = KhmerTranslatorClient.parseGeminiGenerateContentResponse(normalJson)
        assertNotNull(res)
        assertFalse(res!!.skipped)
        assertEquals("EN", res.detectedLang)
        assertEquals("Hello everyone", res.sourceText)
        assertEquals("សួស្តីអ្នកទាំងអស់គ្នា", res.khmerText)

        val fencedSkipJson = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "```json\n{\"skip\":true}\n```"
                  }]
                }
              }]
            }
        """.trimIndent()
        val skipRes = KhmerTranslatorClient.parseGeminiGenerateContentResponse(fencedSkipJson)
        assertNotNull(skipRes)
        assertTrue(skipRes!!.skipped)

        val plainFallbackJson = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "សួស្តី"
                  }]
                }
              }]
            }
        """.trimIndent()
        val fallbackRes = KhmerTranslatorClient.parseGeminiGenerateContentResponse(plainFallbackJson)
        assertNotNull(fallbackRes)
        assertEquals("AUTO", fallbackRes!!.detectedLang)
        assertEquals("សួស្តី", fallbackRes.khmerText)
    }

    @Test
    fun parseGoogleTranslateResponseHandlesMultiChunkAndDetectedLanguage() {
        val rawEn = """
            [[["សួស្តី ","Hello ",null,null,10],["ពិភពលោក","world",null,null,10]],null,"en",null,null,null,null,[]]
        """.trimIndent()
        val resEn = KhmerTranslatorClient.parseGoogleTranslateResponse(rawEn, "Hello world")
        assertNotNull(resEn)
        assertEquals("EN", resEn!!.detectedLang)
        assertEquals("Hello world", resEn.sourceText)
        assertEquals("សួស្តី ពិភពលោក", resEn.khmerText)

        val rawZh = """
            [[["អរគុណច្រើន","非常感谢",null,null,1]],null,"zh-CN"]
        """.trimIndent()
        val resZh = KhmerTranslatorClient.parseGoogleTranslateResponse(rawZh, "非常感谢")
        assertNotNull(resZh)
        assertEquals("ZH-CN", resZh!!.detectedLang)
        assertEquals("អរគុណច្រើន", resZh.khmerText)

        assertNull(KhmerTranslatorClient.parseGoogleTranslateResponse("not json", "hi"))
    }

    @Test
    fun parseCompactStreamLineHandlesPartialFinalSkipAndJson() {
        // Streaming partial with all 3 pipe segments renders immediately as partial
        val partial = KhmerTranslatorClient.parseCompactStreamLine(
            "en|Hello my friends|សួស្តីមិត្តភក្តិ",
            isPartial = true,
            engineLabel = "AI-LITE"
        )
        assertNotNull(partial)
        assertTrue(partial!!.isPartial)
        assertFalse(partial.skipped)
        assertEquals("EN", partial.detectedLang)
        assertEquals("Hello my friends", partial.sourceText)
        assertEquals("សួស្តីមិត្តភក្តិ", partial.khmerText)
        assertEquals("AI-LITE", partial.engineLabel)

        // Incomplete partial before second pipe waits quietly
        assertNull(KhmerTranslatorClient.parseCompactStreamLine("en|Hello", isPartial = true))

        // Final 3-part pipe line marks isPartial = false
        val finalRes = KhmerTranslatorClient.parseCompactStreamLine(
            "ja|おはようございます|អរុណសួស្តី",
            isPartial = false,
            engineLabel = "AI-2.5"
        )
        assertNotNull(finalRes)
        assertFalse(finalRes!!.isPartial)
        assertEquals("JA", finalRes.detectedLang)
        assertEquals("អរុណសួស្តី", finalRes.khmerText)

        // SKIP instruction
        val skipped = KhmerTranslatorClient.parseCompactStreamLine("SKIP", isPartial = false)
        assertNotNull(skipped)
        assertTrue(skipped!!.skipped)

        // JSON fallback still supported
        val jsonFallback = KhmerTranslatorClient.parseCompactStreamLine(
            """{"skip":false,"lang":"ko","src":"안녕하세요","km":"សួស្តី"}""",
            isPartial = false,
            engineLabel = "AI-2.0"
        )
        assertNotNull(jsonFallback)
        assertEquals("KO", jsonFallback!!.detectedLang)
        assertEquals("សួស្តី", jsonFallback.khmerText)
        assertEquals("AI-2.0", jsonFallback.engineLabel)
    }

    @Test
    fun computeCooldownMsDistinguishesDailyQuotaVsPerMinuteRetry() {
        val dailyErr = """Quota exceeded for quota metric 'Generate Requests', quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier', free_tier_requests limit: 20."""
        assertEquals(20 * 60_000L, KhmerTranslatorClient.computeCooldownMs(dailyErr))

        val rpmErr = """Rate limit exceeded. Please retry in 18.5s."""
        assertEquals(20_500L, KhmerTranslatorClient.computeCooldownMs(rpmErr))

        val genericErr = """Resource has been exhausted."""
        assertEquals(65_000L, KhmerTranslatorClient.computeCooldownMs(genericErr))
    }

    @Test
    fun computeUnspokenDeltaExtractsRemainingSuffixWithoutRepeatingSpokenWords() {
        assertEquals("hello everyone", YouTubeTranslatorService.computeUnspokenDelta("hello everyone", ""))
        assertEquals(
            "welcome back today",
            YouTubeTranslatorService.computeUnspokenDelta(
                "hello everyone, welcome back today",
                "hello everyone"
            )
        )
        assertEquals("", YouTubeTranslatorService.computeUnspokenDelta("hello everyone", "Hello Everyone"))
        assertEquals(
            "world today",
            YouTubeTranslatorService.computeUnspokenDelta(
                "hello world today",
                "hello worl"
            )
        )
        assertEquals(
            "brand new sentence",
            YouTubeTranslatorService.computeUnspokenDelta(
                "brand new sentence",
                "old previous sentence"
            )
        )
        assertEquals(
            "today I want",
            YouTubeTranslatorService.computeUnspokenDelta(
                "to the channel today I want",
                "Welcome back to the channel"
            )
        )
    }

    @Test
    fun computeDuckedMusicVolumeLowersMediaSafely() {
        assertEquals(0, KhmerVoiceSpeaker.computeDuckedMusicVolume(0, 15))
        assertEquals(1, KhmerVoiceSpeaker.computeDuckedMusicVolume(1, 15))
        assertEquals(1, KhmerVoiceSpeaker.computeDuckedMusicVolume(4, 15))
        assertEquals(1, KhmerVoiceSpeaker.computeDuckedMusicVolume(10, 15))
        assertEquals(2, KhmerVoiceSpeaker.computeDuckedMusicVolume(15, 15))
    }

    @Test
    fun computeTtsAlarmVolumeScalesWithUserPercentAndCyclesPresets() {
        assertEquals(2, KhmerVoiceSpeaker.computeTtsAlarmVolume(15, 10))
        assertEquals(8, KhmerVoiceSpeaker.computeTtsAlarmVolume(15, 50))
        assertEquals(13, KhmerVoiceSpeaker.computeTtsAlarmVolume(15, 85))
        assertEquals(15, KhmerVoiceSpeaker.computeTtsAlarmVolume(15, 100))
        assertEquals(0.70f, KhmerVoiceSpeaker.computeTtsParamVolume(10), 0.001f)
        assertEquals(1.0f, KhmerVoiceSpeaker.computeTtsParamVolume(85), 0.001f)
        assertEquals(50, TranslatorPrefs.cycleTtsVolumePreset(25))
        assertEquals(75, TranslatorPrefs.cycleTtsVolumePreset(60))
        assertEquals(25, TranslatorPrefs.cycleTtsVolumePreset(100))
    }

    private fun makeHarmonicVoicePcm(
        freqHz: Double,
        durationMs: Int = 200,
        amplitude: Double = 8000.0,
        sampleRateHz: Int = 16_000
    ): ByteArray {
        val sampleCount = (sampleRateHz * durationMs) / 1_000
        val bytes = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val t = i.toDouble() / sampleRateHz
            val wave = (
                0.60 * sin(2.0 * PI * freqHz * t) +
                    0.30 * sin(2.0 * PI * (2.0 * freqHz) * t) +
                    0.15 * sin(2.0 * PI * (3.0 * freqHz) * t)
                )
            val s = (amplitude * wave).toInt().coerceIn(-32767, 32767)
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun detectsMaleAndFemaleHarmonicVoicesByPitch() {
        val maleVoice = makeHarmonicVoicePcm(freqHz = 115.0)
        val femaleVoice = makeHarmonicVoicePcm(freqHz = 215.0)
        val silence = makePcm16Frame(durationMs = 200, amplitude = 0)

        val malePitch = VoiceGenderDetector.estimatePitchHz(maleVoice)
        val femalePitch = VoiceGenderDetector.estimatePitchHz(femaleVoice)

        assertTrue("Expected ~115Hz male pitch, got $malePitch", malePitch in 108.0..122.0)
        assertTrue("Expected ~215Hz female pitch, got $femalePitch", femalePitch in 205.0..225.0)
        assertEquals(SpeakerGender.MALE, VoiceGenderDetector.detectSegmentGender(maleVoice))
        assertEquals(SpeakerGender.FEMALE, VoiceGenderDetector.detectSegmentGender(femaleVoice))
        assertEquals(SpeakerGender.MALE, VoiceGenderDetector.detectSegmentGender(silence, fallback = SpeakerGender.MALE))
    }

    @Test
    fun rollingGenderTrackerSwitchesSmoothlyWithHysteresis() {
        val tracker = RollingGenderTracker()
        val maleFrame = makeHarmonicVoicePcm(freqHz = 120.0, durationMs = 100)
        val femaleFrame = makeHarmonicVoicePcm(freqHz = 210.0, durationMs = 100)

        assertEquals(SpeakerGender.MALE, tracker.feedFrame(maleFrame, nowMs = 1_000L))
        // Deadband pitch (163 Hz) keeps existing MALE classification
        assertEquals(SpeakerGender.MALE, VoiceGenderDetector.classifyPitchHz(163.0, SpeakerGender.MALE))
        assertEquals(SpeakerGender.FEMALE, VoiceGenderDetector.classifyPitchHz(163.0, SpeakerGender.FEMALE))

        // Sustained female frames switch cleanly after both minFramesToFlip and flipCooldownMs (3s)
        var t = 1_100L
        repeat(10) {
            tracker.feedFrame(femaleFrame, nowMs = t)
            t += 100L
        }
        assertEquals(SpeakerGender.MALE, tracker.currentGender)
        // Once 3s cooldown passes with sustained female audio, switch completes smoothly
        tracker.feedFrame(femaleFrame, nowMs = 4_200L)
        assertEquals(SpeakerGender.FEMALE, tracker.currentGender)
    }

    @Test
    fun computeVoicePitchAdjustsPitchUnlessDedicatedVoicePresent() {
        assertEquals(0.89f, KhmerVoiceSpeaker.computeVoicePitch(SpeakerGender.MALE, false), 0.001f)
        assertEquals(1.02f, KhmerVoiceSpeaker.computeVoicePitch(SpeakerGender.FEMALE, false), 0.001f)
        assertEquals(0.98f, KhmerVoiceSpeaker.computeVoicePitch(SpeakerGender.UNKNOWN, false), 0.001f)
        assertEquals(1.0f, KhmerVoiceSpeaker.computeVoicePitch(SpeakerGender.MALE, true), 0.001f)
    }

    @Test
    fun sanitizeForSpeechStripsNoiseAndPreservesBreathPauses() {
        assertEquals(
            "សួស្តី អ្នកទាំងអស់គ្នា",
            KhmerVoiceSpeaker.sanitizeForSpeech("[EN] **សួស្តី** https://example.com អ្នកទាំងអស់គ្នា...")
        )
        assertEquals(
            "អូ, ពិតមែនឬ?",
            KhmerVoiceSpeaker.sanitizeForSpeech("អូ... ពិតមែនឬ?")
        )
    }

    @Test
    fun humanProsodyAddsExpressivePitchAndRateVariationAcrossTones() {
        val statement = KhmerVoiceSpeaker.computeHumanProsody(
            spokenText = "ថ្ងៃនេះអាកាសធាតុល្អណាស់។",
            speakerGender = SpeakerGender.FEMALE,
            hasDedicatedGenderVoice = false,
            baseSpeed = 1.25f,
            toneMode = TranslatorPrefs.TONE_MODE_NATURAL,
            utteranceIndex = 0
        )
        val question = KhmerVoiceSpeaker.computeHumanProsody(
            spokenText = "តើអ្នកសុខសប្បាយជាទេ?",
            speakerGender = SpeakerGender.FEMALE,
            hasDedicatedGenderVoice = false,
            baseSpeed = 1.25f,
            toneMode = TranslatorPrefs.TONE_MODE_NATURAL,
            utteranceIndex = 0
        )
        val warm = KhmerVoiceSpeaker.computeHumanProsody(
            spokenText = "ថ្ងៃនេះអាកាសធាតុល្អណាស់។",
            speakerGender = SpeakerGender.MALE,
            hasDedicatedGenderVoice = false,
            baseSpeed = 1.25f,
            toneMode = TranslatorPrefs.TONE_MODE_WARM,
            utteranceIndex = 0
        )
        val lively = KhmerVoiceSpeaker.computeHumanProsody(
            spokenText = "វ៉ាវ អស្ចារ្យណាស់!",
            speakerGender = SpeakerGender.MALE,
            hasDedicatedGenderVoice = false,
            baseSpeed = 1.25f,
            toneMode = TranslatorPrefs.TONE_MODE_LIVELY,
            utteranceIndex = 1
        )
        assertTrue("Question should rise in pitch over calm statement", question.pitch > statement.pitch)
        assertTrue("Warm tone should speak slightly deeper/slower than lively tone", warm.pitch < lively.pitch && warm.rate < lively.rate)
    }

    @Test
    fun chooseQueueModeAvoidsShortChoppingButFlushesStaleBacklog() {
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_ADD, KhmerVoiceSpeaker.chooseQueueMode(0, 0L, 20))
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_ADD, KhmerVoiceSpeaker.chooseQueueMode(1, 500L, 16))
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_ADD, KhmerVoiceSpeaker.chooseQueueMode(2, 1200L, 24))
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_ADD, KhmerVoiceSpeaker.chooseQueueMode(4, 120L, 16))
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_FLUSH, KhmerVoiceSpeaker.chooseQueueMode(5, 120L, 16))
        assertEquals(android.speech.tts.TextToSpeech.QUEUE_FLUSH, KhmerVoiceSpeaker.chooseQueueMode(1, 5_100L, 16))
    }

    @Test
    fun subtitleSourceActiveWithinHoldWindowAndExpiresAfterTimeout() {
        val hold = YouTubeTranslatorService.SUBTITLE_ACTIVE_HOLD_MS
        assertFalse(YouTubeTranslatorService.isSubtitleSourceActive(0L, 10_000L, hold))
        assertTrue(YouTubeTranslatorService.isSubtitleSourceActive(10_000L, 10_000L, hold))
        assertTrue(YouTubeTranslatorService.isSubtitleSourceActive(10_000L, 10_000L + hold, hold))
        assertFalse(YouTubeTranslatorService.isSubtitleSourceActive(10_000L, 10_000L + hold + 1L, hold))
    }

    @Test
    fun phraseCoalescingMergesShortFragmentsWhileBusyAndFlushesImmediatelyWhenIdle() {
        assertTrue(KhmerVoiceSpeaker.shouldFlushCoalescedNow("សួស្តី", activeQueueCount = 0))
        assertFalse(KhmerVoiceSpeaker.shouldFlushCoalescedNow("សួស្តី", activeQueueCount = 1))
        assertTrue(KhmerVoiceSpeaker.shouldFlushCoalescedNow("សួស្តី?", activeQueueCount = 1))
        assertTrue(
            KhmerVoiceSpeaker.shouldFlushCoalescedNow(
                "ថ្ងៃនេះអាកាសធាតុល្អណាស់",
                activeQueueCount = 1
            )
        )
        assertEquals(
            "ថ្ងៃនេះ យើងទៅ",
            KhmerVoiceSpeaker.mergePendingPhrase("ថ្ងៃនេះ", "យើងទៅ")
        )
    }

    @Test
    fun toneCycleAndQueueCatchUpScaleWorkCleanly() {
        assertEquals(
            TranslatorPrefs.TONE_MODE_WARM,
            TranslatorPrefs.cycleVoiceToneMode(TranslatorPrefs.TONE_MODE_NATURAL)
        )
        assertEquals(
            TranslatorPrefs.TONE_MODE_LIVELY,
            TranslatorPrefs.cycleVoiceToneMode(TranslatorPrefs.TONE_MODE_WARM)
        )
        assertEquals(
            TranslatorPrefs.TONE_MODE_FLAT,
            TranslatorPrefs.cycleVoiceToneMode(TranslatorPrefs.TONE_MODE_LIVELY)
        )
        assertEquals(
            TranslatorPrefs.TONE_MODE_NATURAL,
            TranslatorPrefs.cycleVoiceToneMode(TranslatorPrefs.TONE_MODE_FLAT)
        )

        val single = KhmerVoiceSpeaker.computeHumanProsody(
            spokenText = "នេះជាការសាកល្បង។",
            speakerGender = SpeakerGender.FEMALE,
            hasDedicatedGenderVoice = false,
            baseSpeed = 1.25f,
            toneMode = TranslatorPrefs.TONE_MODE_NATURAL,
            utteranceIndex = 0,
            activeQueueCount = 0
        )
        val queued3 = KhmerVoiceSpeaker.computeHumanProsody(
            spokenText = "នេះជាការសាកល្បង។",
            speakerGender = SpeakerGender.FEMALE,
            hasDedicatedGenderVoice = false,
            baseSpeed = 1.25f,
            toneMode = TranslatorPrefs.TONE_MODE_NATURAL,
            utteranceIndex = 0,
            activeQueueCount = 3
        )
        assertTrue("Queued backlog should speed up smoothly", queued3.rate > single.rate)
    }

    @Test
    fun restartSpeechPipeOnlyWhenRunningFreeWithoutSubtitles() {
        assertTrue(YouTubeTranslatorService.shouldRestartSpeechPipeOnResync(true, true, false))
        assertFalse(YouTubeTranslatorService.shouldRestartSpeechPipeOnResync(false, true, false))
        assertFalse(YouTubeTranslatorService.shouldRestartSpeechPipeOnResync(true, false, false))
        assertFalse(YouTubeTranslatorService.shouldRestartSpeechPipeOnResync(true, true, true))
    }

    @Test
    fun audioTrackModeCyclesAndHoldsContinuousBedWithoutPumping() {
        assertEquals(true to true, TranslatorPrefs.cycleOverlayAudioMode(voiceEnabled = false, audioTrackEnabled = true))
        assertEquals(true to false, TranslatorPrefs.cycleOverlayAudioMode(voiceEnabled = true, audioTrackEnabled = true))
        assertEquals(false to true, TranslatorPrefs.cycleOverlayAudioMode(voiceEnabled = true, audioTrackEnabled = false))

        assertEquals(1, KhmerVoiceSpeaker.computeAudioTrackDuckVolume(currentVol = 10, maxVol = 15, audioTrackMode = true))
        assertEquals(0, KhmerVoiceSpeaker.computeAudioTrackDuckVolume(currentVol = 10, maxVol = 15, audioTrackMode = true, muteOriginal = true))
        assertEquals(
            KhmerVoiceSpeaker.computeDuckedMusicVolume(10, 15),
            KhmerVoiceSpeaker.computeAudioTrackDuckVolume(currentVol = 10, maxVol = 15, audioTrackMode = false)
        )
        assertTrue(
            KhmerVoiceSpeaker.computeDuckRestoreHoldMs(audioTrackMode = true) >
                KhmerVoiceSpeaker.computeDuckRestoreHoldMs(audioTrackMode = false)
        )
    }

    @Test
    fun longParagraphSplitsIntoNaturalClausesAndSpeedPresetCycles() {
        val longText = "សួស្តីអ្នកទាំងអស់គ្នាថ្ងៃនេះយើងមករៀនអំពីការបង្កើតកម្មវិធីដ៏អស្ចារ្យមួយ។ វាពិតជាងាយស្រួល និងមានប្រយោជន៍ខ្លាំងណាស់សម្រាប់ការងារប្រចាំថ្ងៃ។"
        val clauses = KhmerVoiceSpeaker.splitIntoNaturalClauses(longText, maxClauseChars = 80)
        assertTrue("Expected >= 2 clauses, got ${clauses.size}", clauses.size >= 2)
        assertTrue(clauses.all { it.length <= 80 })

        assertEquals(1.25f, TranslatorPrefs.cycleTtsSpeedPreset(1.0f), 0.001f)
        assertEquals(1.50f, TranslatorPrefs.cycleTtsSpeedPreset(1.25f), 0.001f)
        assertEquals(1.75f, TranslatorPrefs.cycleTtsSpeedPreset(1.50f), 0.001f)
        assertEquals(1.00f, TranslatorPrefs.cycleTtsSpeedPreset(1.75f), 0.001f)
    }
}
