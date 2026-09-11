package com.verba.interpretation.protocol

import org.json.JSONObject

data class StartMessage(
    val sessionId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val userId: String? = null,
    val installId: String? = null,
    val settings: TranslationSettings = TranslationSettings(),
    val candidateLanguages: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("type", "start").put("sessionId", sessionId).put("mode", "s2s")
        .put("sourceLanguage", sourceLanguage).put("targetLanguage", targetLanguage)
        .put("targetAudioFormat", "pcm").put("targetAudioRate", 16_000)
        .apply {
            userId?.let { put("userId", it) }
            installId?.let { put("installId", it) }
            if (settings.provider == TranslationProvider.AZURE) put("provider", settings.provider.storedValue)
            if (candidateLanguages.isNotEmpty()) put("candidateLanguages", candidateLanguages)
            settings.voiceFor(targetLanguage).takeIf { it.isNotEmpty() }?.let { put("voice", it) }
        }.toString()
}

enum class TranslationSessionEndReason {
    REPLACED,
    ENDED,
}

sealed interface AgentEvent {
    data object Ready : AgentEvent
    data object Finished : AgentEvent
    data class DetectedLanguage(
        val language: String,
        val segmentId: Long? = null,
        val targetLanguage: String? = null,
    ) : AgentEvent
    data class TtsSegment(val segmentId: Long, val targetLanguage: String) : AgentEvent
    data class Subtitle(
        val kind: Kind,
        val text: String,
        val segmentId: Long? = null,
        val targetLanguage: String? = null,
    ) : AgentEvent {
        enum class Kind { SOURCE_PARTIAL, SOURCE_FINAL, TRANSLATION_PARTIAL, TRANSLATION_FINAL }
    }
    data class SessionTerminated(val reason: TranslationSessionEndReason) : AgentEvent
    data class Error(val code: String, val message: String) : AgentEvent
}

object AgentProtocol {
    const val FINISH = "{\"type\":\"finish\"}"

    fun parse(text: String): AgentEvent {
        val json = try { JSONObject(text) } catch (error: Exception) {
            throw ProtocolException("消息不是有效 JSON。", error)
        }
        return when (val type = json.optString("type")) {
            "ready" -> AgentEvent.Ready
            "finished" -> AgentEvent.Finished
            "detected_language" -> detectedLanguage(json)
            "tts" -> ttsSegment(json)
            "source_partial" -> subtitle(json, AgentEvent.Subtitle.Kind.SOURCE_PARTIAL)
            "source_final" -> subtitle(json, AgentEvent.Subtitle.Kind.SOURCE_FINAL)
            "translation_partial" -> subtitle(json, AgentEvent.Subtitle.Kind.TRANSLATION_PARTIAL)
            "translation_final" -> subtitle(json, AgentEvent.Subtitle.Kind.TRANSLATION_FINAL)
            "error" -> error(json)
            else -> throw ProtocolException("不支持的 Agent 事件：$type")
        }
    }

    private fun detectedLanguage(json: JSONObject): AgentEvent.DetectedLanguage {
        val language = json.optString("language").trim()
        if (language.isEmpty()) throw ProtocolException("检测语言事件缺少 language。")
        val binding = optionalSegmentBinding(json)
        return AgentEvent.DetectedLanguage(language, binding?.first, binding?.second)
    }

    private fun ttsSegment(json: JSONObject): AgentEvent.TtsSegment {
        val segmentId = requiredSegmentId(json)
        val targetLanguage = optionalTargetLanguage(json) ?: throw ProtocolException("TTS 片段缺少 targetLanguage。")
        return AgentEvent.TtsSegment(segmentId, targetLanguage)
    }

    private fun subtitle(json: JSONObject, kind: AgentEvent.Subtitle.Kind): AgentEvent.Subtitle {
        val message = json.optString("message").trim()
        if (message.isEmpty()) throw ProtocolException("字幕事件缺少 message。")
        val binding = optionalSegmentBinding(json)
        return AgentEvent.Subtitle(kind, message, binding?.first, binding?.second)
    }

    /** Segment metadata is all-or-nothing so a binary TTS frame cannot be misrouted. */
    private fun optionalSegmentBinding(json: JSONObject): Pair<Long, String>? {
        val hasSegmentId = json.has("segmentId")
        val targetLanguage = optionalTargetLanguage(json)
        if (!hasSegmentId && targetLanguage == null) return null
        if (!hasSegmentId || targetLanguage == null) throw ProtocolException("片段事件必须同时包含 segmentId 和 targetLanguage。")
        return requiredSegmentId(json) to targetLanguage
    }

    private fun requiredSegmentId(json: JSONObject): Long {
        val value = json.optLong("segmentId", 0L)
        if (value <= 0L) throw ProtocolException("片段事件缺少有效 segmentId。")
        return value
    }

    private fun optionalTargetLanguage(json: JSONObject): String? =
        json.optString("targetLanguage").trim().takeIf { it.isNotEmpty() }

    private fun error(json: JSONObject): AgentEvent {
        // Terminal UX is selected only from an exact, typed code. Agent-provided message text is
        // deliberately ignored for these states so it cannot impersonate trusted product copy.
        val code = json.opt("code") as? String ?: "UNKNOWN"
        return when (code) {
            "TRANSLATION_SESSION_REPLACED" -> AgentEvent.SessionTerminated(TranslationSessionEndReason.REPLACED)
            "TRANSLATION_SESSION_ENDED" -> AgentEvent.SessionTerminated(TranslationSessionEndReason.ENDED)
            else -> AgentEvent.Error(
                code = code,
                message = (json.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: "翻译服务错误。",
            )
        }
    }
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
