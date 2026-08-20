package com.verba.interpretation.protocol

import org.json.JSONObject

data class StartMessage(val sessionId: String, val sourceLanguage: String, val targetLanguage: String) {
    fun toJson(): String = JSONObject()
        .put("type", "start").put("sessionId", sessionId).put("mode", "s2s")
        .put("sourceLanguage", sourceLanguage).put("targetLanguage", targetLanguage)
        .put("targetAudioFormat", "pcm").put("targetAudioRate", 16_000).toString()
}

sealed interface AgentEvent {
    data object Ready : AgentEvent
    data object Finished : AgentEvent
    data class Subtitle(val kind: Kind, val text: String) : AgentEvent {
        enum class Kind { SOURCE_PARTIAL, SOURCE_FINAL, TRANSLATION_PARTIAL, TRANSLATION_FINAL }
    }
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
            "source_partial" -> subtitle(json, AgentEvent.Subtitle.Kind.SOURCE_PARTIAL)
            "source_final" -> subtitle(json, AgentEvent.Subtitle.Kind.SOURCE_FINAL)
            "translation_partial" -> subtitle(json, AgentEvent.Subtitle.Kind.TRANSLATION_PARTIAL)
            "translation_final" -> subtitle(json, AgentEvent.Subtitle.Kind.TRANSLATION_FINAL)
            "error" -> AgentEvent.Error(json.optString("code", "UNKNOWN"), json.optString("message", "翻译服务错误。"))
            else -> throw ProtocolException("不支持的 Agent 事件：$type")
        }
    }

    private fun subtitle(json: JSONObject, kind: AgentEvent.Subtitle.Kind): AgentEvent.Subtitle {
        val message = json.optString("message").trim()
        if (message.isEmpty()) throw ProtocolException("字幕事件缺少 message。")
        return AgentEvent.Subtitle(kind, message)
    }
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
