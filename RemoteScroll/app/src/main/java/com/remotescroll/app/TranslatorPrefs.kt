package com.remotescroll.app

import android.content.Context

/**
 * Stores settings for real-time YouTube voice/caption translation into Khmer.
 */
object TranslatorPrefs {
    private const val PREFS = "remote_scroll"

    private const val KEY_SUBTITLE_ENABLED = "yt_translator_subtitle_enabled"
    private const val KEY_VOICE_TTS_ENABLED = "yt_translator_voice_tts_enabled"
    private const val KEY_AUDIO_TRACK_MODE = "yt_translator_audio_track_mode"
    private const val KEY_AUDIO_TRACK_MUTE_ORIG = "yt_translator_audio_track_mute_orig"
    private const val KEY_SHOW_ORIGINAL = "yt_translator_show_original"
    private const val KEY_ONLY_YOUTUBE = "yt_translator_only_youtube"
    private const val KEY_GEMINI_API_KEY = "yt_translator_gemini_key"
    private const val KEY_FREE_SOURCE_LANG = "yt_translator_free_source_lang"
    private const val KEY_TTS_SPEED = "yt_translator_tts_speed"
    private const val KEY_TTS_VOLUME = "yt_translator_tts_volume"
    private const val KEY_TTS_GENDER_MODE = "yt_translator_tts_gender_mode"
    private const val KEY_TTS_TONE_MODE = "yt_translator_tts_tone_mode"
    private const val KEY_SUBTITLE_X = "yt_subtitle_x"

    const val GENDER_MODE_AUTO = "auto"
    const val GENDER_MODE_FEMALE = "female"
    const val GENDER_MODE_MALE = "male"

    const val TONE_MODE_NATURAL = "natural"
    const val TONE_MODE_WARM = "warm"
    const val TONE_MODE_LIVELY = "lively"
    const val TONE_MODE_FLAT = "flat"
    private const val KEY_SUBTITLE_Y = "yt_subtitle_y"

    /** Free SpeechRecognizer source hint when no Gemini API key is configured. */
    const val LANG_AUTO = "auto"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSubtitleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SUBTITLE_ENABLED, true)

    fun setSubtitleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SUBTITLE_ENABLED, enabled).apply()
    }

    fun isVoiceTtsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_TTS_ENABLED, false)

    fun setVoiceTtsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_TTS_ENABLED, enabled).apply()
    }

    fun isAudioTrackModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_TRACK_MODE, true)

    fun setAudioTrackModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_TRACK_MODE, enabled).apply()
    }

    fun isAudioTrackMuteOrigEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_TRACK_MUTE_ORIG, false)

    fun setAudioTrackMuteOrigEnabled(context: Context, mute: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_TRACK_MUTE_ORIG, mute).apply()
    }

    fun cycleTtsSpeedPreset(currentSpeed: Float): Float {
        val presets = floatArrayOf(1.0f, 1.25f, 1.50f, 1.75f)
        return presets.firstOrNull { it > currentSpeed + 0.04f } ?: presets.first()
    }

    /**
     * Cycles overlay audio selector like YouTube's Audio Track menu:
     * Original (🔇 Orig) -> Khmer Audio Track (🎧 ខ្មែរ) -> Hybrid Mix (🔊 Mix) -> Original.
     */
    fun cycleOverlayAudioMode(
        voiceEnabled: Boolean,
        audioTrackEnabled: Boolean
    ): Pair<Boolean, Boolean> = when {
        !voiceEnabled -> true to true
        audioTrackEnabled -> true to false
        else -> false to true
    }

    fun isShowOriginalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_ORIGINAL, true)

    fun setShowOriginalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_ORIGINAL, enabled).apply()
    }

    fun isOnlyYouTube(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONLY_YOUTUBE, true)

    fun setOnlyYouTube(context: Context, onlyYt: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONLY_YOUTUBE, onlyYt).apply()
    }

    fun getGeminiApiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI_API_KEY, "")?.trim().orEmpty()

    fun setGeminiApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun hasGeminiApiKey(context: Context): Boolean =
        getGeminiApiKey(context).isNotBlank()

    fun getFreeSourceLang(context: Context): String =
        prefs(context).getString(KEY_FREE_SOURCE_LANG, LANG_AUTO) ?: LANG_AUTO

    fun setFreeSourceLang(context: Context, langTag: String) {
        prefs(context).edit().putString(KEY_FREE_SOURCE_LANG, langTag).apply()
    }

    fun getTtsSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_TTS_SPEED, 1.26f).coerceIn(1.0f, 2.2f)

    fun setTtsSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(KEY_TTS_SPEED, speed.coerceIn(1.0f, 2.2f)).apply()
    }

    fun getVoiceGenderMode(context: Context): String =
        prefs(context).getString(KEY_TTS_GENDER_MODE, GENDER_MODE_AUTO) ?: GENDER_MODE_AUTO

    fun setVoiceGenderMode(context: Context, mode: String) {
        val valid = when (mode) {
            GENDER_MODE_FEMALE, GENDER_MODE_MALE -> mode
            else -> GENDER_MODE_AUTO
        }
        prefs(context).edit().putString(KEY_TTS_GENDER_MODE, valid).apply()
    }

    fun getVoiceToneMode(context: Context): String =
        prefs(context).getString(KEY_TTS_TONE_MODE, TONE_MODE_NATURAL) ?: TONE_MODE_NATURAL

    fun setVoiceToneMode(context: Context, mode: String) {
        val valid = when (mode) {
            TONE_MODE_WARM, TONE_MODE_LIVELY, TONE_MODE_FLAT -> mode
            else -> TONE_MODE_NATURAL
        }
        prefs(context).edit().putString(KEY_TTS_TONE_MODE, valid).apply()
    }

    fun cycleVoiceToneMode(currentMode: String): String = when (currentMode) {
        TONE_MODE_NATURAL -> TONE_MODE_WARM
        TONE_MODE_WARM -> TONE_MODE_LIVELY
        TONE_MODE_LIVELY -> TONE_MODE_FLAT
        else -> TONE_MODE_NATURAL
    }

    fun voiceToneShortLabel(mode: String): String = when (mode) {
        TONE_MODE_WARM -> "☕"
        TONE_MODE_LIVELY -> "⚡"
        TONE_MODE_FLAT -> "📏"
        else -> "🎭"
    }

    fun voiceToneDisplayName(mode: String): String = when (mode) {
        TONE_MODE_WARM -> "☕ Warm Calm"
        TONE_MODE_LIVELY -> "⚡ Lively"
        TONE_MODE_FLAT -> "📏 Flat"
        else -> "🎭 Human Auto"
    }

    fun getTtsVolumePercent(context: Context): Int =
        prefs(context).getInt(KEY_TTS_VOLUME, 85).coerceIn(10, 100)

    fun setTtsVolumePercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_TTS_VOLUME, percent.coerceIn(10, 100)).apply()
    }

    fun cycleTtsVolumePreset(currentPercent: Int): Int {
        val presets = intArrayOf(25, 50, 75, 90, 100)
        return presets.firstOrNull { it > currentPercent } ?: presets.first()
    }

    fun getSubtitlePos(context: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val sp = prefs(context)
        return sp.getInt(KEY_SUBTITLE_X, defaultX) to sp.getInt(KEY_SUBTITLE_Y, defaultY)
    }

    fun setSubtitlePos(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_SUBTITLE_X, x)
            .putInt(KEY_SUBTITLE_Y, y)
            .apply()
    }
}
