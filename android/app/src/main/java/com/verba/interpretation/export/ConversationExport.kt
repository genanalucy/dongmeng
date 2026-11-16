package com.verba.interpretation.export

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale

/** Text only: no account, transport, database or turn identifiers belong in an export. */
data class ExportEntry(
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceText: String,
    val translatedText: String,
    val completed: Boolean,
    val side: String? = null,
    val completedAtMillis: Long? = null,
)

enum class ExportFormat(val mimeType: String, val extension: String) {
    TXT("text/plain", "txt"),
    JSON("application/json", "json"),
}

/**
 * Capture before opening the document picker. The caller determines completion using a
 * verified bilingual boundary, not just transport termination. Unknown turn times stay null.
 *
 * Entries are copied and made unmodifiable; later mutations of the input list cannot change
 * either rendering. Text is never trimmed, normalized, paired or deduplicated here.
 */
class ExportSnapshot(
    val exportedAtMillis: Long,
    val mode: String,
    entries: List<ExportEntry>,
) {
    val entries: List<ExportEntry> = Collections.unmodifiableList(
        entries.filterNot { it.sourceText.isBlank() && it.translatedText.isBlank() },
    )

    /** Unicode text; the document writer must encode this String with Charsets.UTF_8. */
    fun render(format: ExportFormat): String {
        require(entries.isNotEmpty()) { "Cannot export an empty conversation." }
        return when (format) {
            ExportFormat.TXT -> renderText()
            ExportFormat.JSON -> renderJson()
        }
    }

    /** A bounded, path-free suggestion; the picker still lets the user choose the name. */
    fun fileName(format: ExportFormat): String {
        val safeMode = mode.map { character ->
            if (character in 'a'..'z' || character in 'A'..'Z' ||
                character in '0'..'9' || character == '-' || character == '_' ||
                character in '\u4e00'..'\u9fff'
            ) character else '_'
        }.joinToString("").take(40).trim('_', '-').ifEmpty { "conversation" }
        val time = FILE_TIME.format(Instant.ofEpochMilli(exportedAtMillis))
        return "译路随行_${safeMode}_$time.${format.extension}"
    }

    private fun renderText(): String = buildString {
        append("译路随行 · 翻译会话\n")
        append("导出时间（UTC）：").append(isoTime(exportedAtMillis)).append('\n')
        append("模式：").append(mode).append('\n')
        append("提示：本文件为明文；标记未完成的内容不代表已完成翻译。\n")
        entries.forEachIndexed { index, entry ->
            append('\n').append(index + 1).append(". [")
            append(if (entry.completed) "已完成" else "部分/未完成").append("]\n")
            entry.side?.let { append("发言侧：").append(it).append('\n') }
            append("语言：").append(entry.sourceLanguage).append(" → ")
                .append(entry.targetLanguage).append('\n')
            append("完成时间（UTC）：")
                .append(entry.completedAtMillis?.let(::isoTime) ?: "未知").append('\n')
            append("原文：\n").append(entry.sourceText).append('\n')
            append("译文：\n").append(entry.translatedText).append('\n')
        }
    }

    private fun renderJson(): String = buildString {
        append("{\n  \"schema_version\": 1,\n  \"exported_at\": ")
        appendJsonString(isoTime(exportedAtMillis))
        append(",\n  \"mode\": ").appendJsonString(mode)
        append(",\n  \"entries\": [\n")
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(",\n")
            append("    {\"source_language\": ").appendJsonString(entry.sourceLanguage)
            append(", \"target_language\": ").appendJsonString(entry.targetLanguage)
            append(", \"source_text\": ").appendJsonString(entry.sourceText)
            append(", \"translated_text\": ").appendJsonString(entry.translatedText)
            append(", \"completed\": ").append(entry.completed)
            append(", \"side\": ").appendJsonNullable(entry.side)
            append(", \"completed_at\": ")
                .appendJsonNullable(entry.completedAtMillis?.let(::isoTime))
            append('}')
        }
        append("\n  ]\n}\n")
    }
}

private val FILE_TIME: DateTimeFormatter = DateTimeFormatter
    .ofPattern("uuuuMMdd'T'HHmmssSSS'Z'", Locale.ROOT)
    .withZone(ZoneOffset.UTC)

private fun isoTime(millis: Long): String = Instant.ofEpochMilli(millis).toString()

private fun StringBuilder.appendJsonNullable(value: String?): StringBuilder =
    if (value == null) append("null") else appendJsonString(value)

/** JSON string escaping, without Android APIs or a runtime serialization dependency. */
private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ' || character.isSurrogate()) {
                // Escaping UTF-16 units also preserves emoji pairs and avoids replacing
                // isolated surrogates when the resulting JSON is encoded as UTF-8.
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    return append('"')
}
