package com.remotescroll.app

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TranslationResult(
    val detectedLang: String,
    val sourceText: String,
    val khmerText: String,
    val skipped: Boolean = false,
    val isPartial: Boolean = false,
    val engineLabel: String = "LIVE",
    val speakVoice: Boolean = !isPartial,
    val updateSubtitle: Boolean = true,
    val speakerGender: SpeakerGender = SpeakerGender.UNKNOWN
)

class QuotaExhaustedException(
    message: String,
    val retryAfterMs: Long
) : IllegalStateException(message)

/**
 * Pure-Kotlin / OkHttp translation client supporting:
 * 1) Real-time Gemini SSE audio streaming (`streamGenerateContent?alt=sse`) across multiple flash models
 *    with automatic 429/404 model cascade & cooldown fallback.
 * 2) Persistent HTTP/2 connection pooling for free Google Translate (`sl=auto&tl=km`) on STT/captions.
 */
object KhmerTranslatorClient {
    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val FREE_TRANSLATE_BASE =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=km&dt=t&q="

    internal val GEMINI_AUDIO_MODELS = listOf(
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash"
    )

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 3, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val modelCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val translationCache = object : LinkedHashMap<String, TranslationResult>(80, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResult>?): Boolean =
            size > 64
    }

    fun resetCooldowns() {
        modelCooldownUntilMs.clear()
    }

    fun isAnyModelReady(nowMs: Long = System.currentTimeMillis()): Boolean =
        GEMINI_AUDIO_MODELS.any { (modelCooldownUntilMs[it] ?: 0L) <= nowMs }

    fun nextCooldownRemainingMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (isAnyModelReady(nowMs)) return 0L
        val earliest = GEMINI_AUDIO_MODELS.minOf { modelCooldownUntilMs[it] ?: 0L }
        return (earliest - nowMs).coerceAtLeast(1_000L)
    }

    fun translateAudioWithGemini(
        wavBytes: ByteArray,
        apiKey: String,
        recentContext: List<String> = emptyList()
    ): TranslationResult? =
        translateAudioWithGeminiStream(wavBytes, apiKey, recentContext, onPartial = {})

    fun translateAudioWithGeminiStream(
        wavBytes: ByteArray,
        apiKey: String,
        recentContext: List<String> = emptyList(),
        onPartial: (TranslationResult) -> Unit
    ): TranslationResult? {
        if (apiKey.isBlank() || wavBytes.size <= 44) return null

        val now = System.currentTimeMillis()
        val readyModels = GEMINI_AUDIO_MODELS.filter { (modelCooldownUntilMs[it] ?: 0L) <= now }
        if (readyModels.isEmpty()) {
            throw QuotaExhaustedException(
                "All Gemini models are in rate-limit cooldown",
                nextCooldownRemainingMs(now)
            )
        }

        val contextBlock = if (recentContext.isNotEmpty()) {
            "Prev context (do not repeat): ${recentContext.takeLast(2).joinToString(" / ")}. "
        } else {
            ""
        }

        val prompt = buildString {
            append("You are a real-time YouTube speech translator (any spoken language -> natural conversational spoken Khmer ភាសាខ្មែរនិយាយធម្មជាតិ ដូចមនុស្សនិយាយពិត). ")
            append("If silence, music, or SFX only: output exactly SKIP. ")
            append(contextBlock)
            append("Preserve natural punctuation (?, !, ,) for spoken tone. Otherwise output STRICTLY ONE LINE with NO extra text: LANG|original spoken words|Khmer translation")
        }

        val base64Audio = Base64.getEncoder().encodeToString(wavBytes)
        var lastError: Exception? = null

        for (model in readyModels) {
            val thinkingSection = if (model.startsWith("gemini-2.5")) {
                """, "thinkingConfig": { "thinkingBudget": 0 }"""
            } else {
                ""
            }
            val payload = """
                {
                  "contents": [
                    {
                      "parts": [
                        { "text": "${jsonEscape(prompt)}" },
                        {
                          "inline_data": {
                            "mime_type": "audio/wav",
                            "data": "$base64Audio"
                          }
                        }
                      ]
                    }
                  ],
                  "generationConfig": {
                    "temperature": 0.1,
                    "maxOutputTokens": 160$thinkingSection
                  }
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("${GEMINI_BASE}${model}:streamGenerateContent?alt=sse&key=${urlEncode(apiKey)}")
                .post(payload.toRequestBody(JSON_MEDIA))
                .header("Accept", "text/event-stream")
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty()
                        val msg = extractGeminiError(errBody)
                        when (response.code) {
                            429 -> {
                                val cooldown = computeCooldownMs(errBody)
                                modelCooldownUntilMs[model] = System.currentTimeMillis() + cooldown
                                lastError = QuotaExhaustedException("Gemini 429 ($model): $msg", cooldown)
                                return@use
                            }
                            404 -> {
                                modelCooldownUntilMs[model] = System.currentTimeMillis() + 24 * 60 * 60_000L
                                lastError = IllegalStateException("Gemini 404 ($model): $msg")
                                return@use
                            }
                            else -> {
                                if (response.code in listOf(400, 401, 403) &&
                                    (msg.contains("API key", true) || msg.contains("API_KEY", true))
                                ) {
                                    throw IllegalStateException("Gemini HTTP ${response.code}: $msg")
                                }
                                lastError = IllegalStateException("Gemini HTTP ${response.code} ($model): $msg")
                                return@use
                            }
                        }
                    }

                    val bodyStream = response.body?.byteStream() ?: return null
                    val engine = shortModelBadge(model)
                    val accumulated = StringBuilder()
                    var lastEmittedKm = ""

                    BufferedReader(InputStreamReader(bodyStream, Charsets.UTF_8)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val trimmedLine = line.trim()
                            if (!trimmedLine.startsWith("data:")) continue
                            val dataJson = trimmedLine.removePrefix("data:").trim()
                            if (dataJson.isEmpty() || dataJson == "[DONE]") continue

                            val delta = extractDeltaText(dataJson) ?: continue
                            accumulated.append(delta)
                            val partialParsed = parseCompactStreamLine(
                                accumulated.toString(),
                                isPartial = true,
                                engineLabel = engine
                            )
                            if (partialParsed != null && !partialParsed.skipped &&
                                partialParsed.khmerText.length >= 3 &&
                                partialParsed.khmerText != lastEmittedKm &&
                                partialParsed.khmerText.length >= lastEmittedKm.length + 2
                            ) {
                                lastEmittedKm = partialParsed.khmerText
                                onPartial(partialParsed)
                            }
                        }
                    }

                    return parseCompactStreamLine(
                        accumulated.toString(),
                        isPartial = false,
                        engineLabel = engine
                    )
                }
            } catch (e: QuotaExhaustedException) {
                lastError = e
            } catch (e: IllegalStateException) {
                if (e.message?.contains("API key", true) == true || e.message?.contains("API_KEY", true) == true) {
                    throw e
                }
                lastError = e
            }
        }

        if (!isAnyModelReady()) {
            throw QuotaExhaustedException(
                lastError?.message ?: "All Gemini models exhausted quota",
                nextCooldownRemainingMs()
            )
        }
        lastError?.let { throw it }
        return null
    }

    fun translateTextToKhmer(text: String, engineLabel: String = "FREE"): TranslationResult? {
        val cleaned = text.trim()
        if (cleaned.length < 2) return null

        val cacheKey = cleaned.lowercase(java.util.Locale.ROOT)
        val cached = synchronized(translationCache) { translationCache[cacheKey] }
        if (cached != null) {
            return cached.copy(
                sourceText = cleaned,
                engineLabel = engineLabel
            )
        }

        val request = Request.Builder()
            .url(FREE_TRANSLATE_BASE + urlEncode(cleaned))
            .get()
            .header("User-Agent", "Mozilla/5.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val parsed = parseGoogleTranslateResponse(body, cleaned, engineLabel) ?: return null
            if (!parsed.skipped && parsed.khmerText.isNotBlank()) {
                synchronized(translationCache) {
                    translationCache[cacheKey] = parsed
                }
            }
            return parsed
        }
    }

    internal fun computeCooldownMs(errBody: String, nowMs: Long = System.currentTimeMillis()): Long {
        if (errBody.contains("PerDay", true) ||
            errBody.contains("free_tier_requests", true) ||
            Regex("""retry in \d+h""", RegexOption.IGNORE_CASE).containsMatchIn(errBody)
        ) {
            return 20 * 60_000L
        }
        val secMatch = Regex("""retry in (\d+(?:\.\d+)?)s""", RegexOption.IGNORE_CASE).find(errBody)
        if (secMatch != null) {
            val sec = secMatch.groupValues[1].toDoubleOrNull() ?: 60.0
            return ((sec * 1_000.0).toLong() + 2_000L).coerceIn(15_000L, 120_000L)
        }
        return 65_000L
    }

    internal fun shortModelBadge(model: String): String = when {
        model.contains("flash-lite") -> "AI-LITE"
        model.contains("2.5-flash") -> "AI-2.5"
        model.contains("2.0-flash") -> "AI-2.0"
        else -> "GEMINI"
    }

    private fun extractDeltaText(dataJson: String): String? {
        val partsIdx = dataJson.indexOf("\"parts\"")
        val slice = if (partsIdx >= 0) dataJson.substring(partsIdx) else dataJson
        return extractJsonStringField(slice, "text")
    }

    internal fun parseCompactStreamLine(
        raw: String,
        isPartial: Boolean,
        engineLabel: String = "LIVE"
    ): TranslationResult? {
        val cleaned = raw
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.isEmpty()) return null

        val firstLine = cleaned.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        if (firstLine.equals("SKIP", ignoreCase = true) || firstLine.startsWith("SKIP|", ignoreCase = true)) {
            return TranslationResult("", "", "", skipped = true, isPartial = isPartial, engineLabel = engineLabel)
        }

        // If Gemini responded in JSON fallback format
        if (cleaned.startsWith("{")) {
            if (extractJsonBooleanField(cleaned, "skip") == true) {
                return TranslationResult("", "", "", skipped = true, isPartial = isPartial, engineLabel = engineLabel)
            }
            val km = extractJsonStringField(cleaned, "km")?.trim().orEmpty()
            if (km.isEmpty()) return null
            val src = extractJsonStringField(cleaned, "src")?.trim().orEmpty()
            val lang = extractJsonStringField(cleaned, "lang")?.trim()?.uppercase().orEmpty()
            return TranslationResult(
                detectedLang = lang.ifEmpty { "AUTO" },
                sourceText = src,
                khmerText = km,
                skipped = false,
                isPartial = isPartial,
                engineLabel = engineLabel
            )
        }

        val firstPipe = firstLine.indexOf('|')
        if (firstPipe < 0) {
            if (!isPartial && containsKhmerScript(firstLine)) {
                return TranslationResult(
                    detectedLang = "AUTO",
                    sourceText = "",
                    khmerText = firstLine,
                    skipped = false,
                    isPartial = false,
                    engineLabel = engineLabel
                )
            }
            return null
        }

        val secondPipe = firstLine.indexOf('|', firstPipe + 1)
        if (secondPipe < 0) {
            // Two parts only: wait during partial in case LANG|src is still streaming, accept on final if Khmer exists
            if (isPartial) return null
            val left = firstLine.substring(0, firstPipe).trim()
            val right = firstLine.substring(firstPipe + 1).trim()
            if (right.isEmpty()) return null
            val looksLikeCode = left.length in 2..6 && left.all { it.isLetter() || it == '-' }
            return TranslationResult(
                detectedLang = if (looksLikeCode) left.uppercase() else "AUTO",
                sourceText = if (looksLikeCode) "" else left,
                khmerText = right,
                skipped = false,
                isPartial = false,
                engineLabel = engineLabel
            )
        }

        val lang = firstLine.substring(0, firstPipe).trim().uppercase().ifEmpty { "AUTO" }
        val src = firstLine.substring(firstPipe + 1, secondPipe).trim()
        val km = firstLine.substring(secondPipe + 1).trim()
        if (km.isEmpty()) return null

        return TranslationResult(
            detectedLang = lang,
            sourceText = src,
            khmerText = km,
            skipped = false,
            isPartial = isPartial,
            engineLabel = engineLabel
        )
    }

    internal fun parseGeminiGenerateContentResponse(responseJson: String): TranslationResult? {
        val partsIdx = responseJson.indexOf("\"parts\"")
        val searchSlice = if (partsIdx >= 0) responseJson.substring(partsIdx) else responseJson
        val innerText = extractJsonStringField(searchSlice, "text") ?: return null
        return parseCompactStreamLine(innerText, isPartial = false, engineLabel = "GEMINI")
    }

    internal fun parseGoogleTranslateResponse(
        rawJson: String,
        originalText: String,
        engineLabel: String = "FREE"
    ): TranslationResult? {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("[")) return null

        var chunksArrayStart = 1
        while (chunksArrayStart < trimmed.length && trimmed[chunksArrayStart].isWhitespace()) {
            chunksArrayStart++
        }
        if (chunksArrayStart >= trimmed.length || trimmed[chunksArrayStart] != '[') return null

        val translatedBuilder = StringBuilder()
        var idx = chunksArrayStart
        var depth = 0
        while (idx < trimmed.length) {
            when (trimmed[idx]) {
                '[' -> {
                    depth++
                    if (depth == 2) {
                        var p = idx + 1
                        while (p < trimmed.length && trimmed[p].isWhitespace()) p++
                        if (p < trimmed.length && trimmed[p] == '"') {
                            val parsed = readJsonString(trimmed, p)
                            if (parsed != null) {
                                translatedBuilder.append(parsed.first)
                                idx = parsed.second
                                continue
                            }
                        }
                    }
                }
                ']' -> {
                    depth--
                    if (depth <= 0) {
                        idx++
                        break
                    }
                }
                '"' -> {
                    val skipped = readJsonString(trimmed, idx)
                    if (skipped != null) {
                        idx = skipped.second
                        continue
                    }
                }
            }
            idx++
        }

        val khmer = translatedBuilder.toString().trim()
        if (khmer.isEmpty()) return null

        var detected = "AUTO"
        if (idx < trimmed.length) {
            val tail = trimmed.substring(idx)
            val langMatch = Regex(""",\s*(?:null|"[^"]*")\s*,\s*"([a-zA-Z-]+)"""").find(tail)
                ?: Regex(""""([a-zA-Z]{2,8}(?:-[a-zA-Z]{2,4})?)"""").find(tail)
            if (langMatch != null) {
                detected = langMatch.groupValues[1].uppercase()
            }
        }

        return TranslationResult(
            detectedLang = detected,
            sourceText = originalText,
            khmerText = khmer,
            skipped = false,
            isPartial = false,
            engineLabel = engineLabel
        )
    }

    private fun containsKhmerScript(text: String): Boolean =
        text.any { ch -> ch in '\u1780'..'\u17FF' || ch in '\u19E0'..'\u19FF' }

    private fun extractGeminiError(body: String): String =
        extractJsonStringField(body, "message") ?: body.take(180)

    internal fun extractJsonStringField(json: String, fieldName: String): String? {
        val pattern = Regex(""""${Regex.escape(fieldName)}"\s*:\s*"""")
        val match = pattern.find(json) ?: return null
        val quoteStart = match.range.last
        return readJsonString(json, quoteStart)?.first
    }

    internal fun extractJsonBooleanField(json: String, fieldName: String): Boolean? {
        val pattern = Regex(""""${Regex.escape(fieldName)}"\s*:\s*(true|false)""")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1].toBoolean()
    }

    private fun readJsonString(src: String, openQuoteIndex: Int): Pair<String, Int>? {
        if (openQuoteIndex >= src.length || src[openQuoteIndex] != '"') return null
        val sb = StringBuilder()
        var i = openQuoteIndex + 1
        while (i < src.length) {
            val ch = src[i]
            if (ch == '"') {
                return sb.toString() to (i + 1)
            }
            if (ch == '\\' && i + 1 < src.length) {
                val next = src[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < src.length) {
                            val hex = src.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append('u')
                        i += 2
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return null
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
