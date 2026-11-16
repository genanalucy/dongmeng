package com.verba.interpretation.export

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportTest {
    private fun entry(
        source: String = "你好",
        translated: String = "Hello",
        completed: Boolean = true,
        side: String? = null,
        completedAtMillis: Long? = null,
    ) = ExportEntry("zh", "en", source, translated, completed, side, completedAtMillis)

    @Test fun formatsAdvertiseDocumentMimeTypesAndExtensions() {
        assertEquals("text/plain", ExportFormat.TXT.mimeType)
        assertEquals("txt", ExportFormat.TXT.extension)
        assertEquals("application/json", ExportFormat.JSON.mimeType)
        assertEquals("json", ExportFormat.JSON.extension)
    }

    @Test fun jsonContainsVersionModeOrderedBilingualEntriesAndOnlyPublicMetadata() {
        val repeated = entry(side = "LEFT", completedAtMillis = 1_234L)
        val snapshot = ExportSnapshot(0L, "face_to_face", listOf(
            repeated,
            entry("Xin chào", "你好", completed = false, side = "RIGHT"),
            repeated,
        ))
        val json = JSONObject(snapshot.render(ExportFormat.JSON))
        assertEquals(setOf("schema_version", "exported_at", "mode", "entries"), json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("1970-01-01T00:00:00Z", json.getString("exported_at"))
        assertEquals("face_to_face", json.getString("mode"))
        val entries = json.getJSONArray("entries")
        assertEquals(3, entries.length())
        val first = entries.getJSONObject(0)
        assertEquals(setOf(
            "source_language", "target_language", "source_text", "translated_text",
            "completed", "side", "completed_at",
        ), first.keys().asSequence().toSet())
        assertEquals("zh", first.getString("source_language"))
        assertEquals("en", first.getString("target_language"))
        assertEquals("你好", first.getString("source_text"))
        assertEquals("Hello", first.getString("translated_text"))
        assertTrue(first.getBoolean("completed"))
        assertEquals("LEFT", first.getString("side"))
        assertEquals("1970-01-01T00:00:01.234Z", first.getString("completed_at"))
        assertEquals("Xin chào", entries.getJSONObject(1).getString("source_text"))
        assertEquals("RIGHT", entries.getJSONObject(1).getString("side"))
        assertFalse(entries.getJSONObject(1).getBoolean("completed"))
        assertTrue(entries.getJSONObject(1).isNull("completed_at"))
        assertEquals(first.toString(), entries.getJSONObject(2).toString())
    }

    @Test fun unknownTimesAndSidesStayNullEvenOnCompletedEntries() {
        val json = JSONObject(ExportSnapshot(99_999L, "solo", listOf(entry()))
            .render(ExportFormat.JSON))
        val exported = json.getJSONArray("entries").getJSONObject(0)
        assertTrue(exported.has("completed_at"))
        assertTrue(exported.isNull("completed_at"))
        assertTrue(exported.isNull("side"))
        assertTrue(exported.getBoolean("completed"))
        assertEquals("1970-01-01T00:01:39.999Z", json.getString("exported_at"))
    }

    @Test fun jsonEscapesQuotesBackslashesAllControlCharactersAndPreservesUnicode() {
        val controls = (0..31).map(Int::toChar).joinToString("")
        val source = "  引号\" \\ 换行\nTiếng Việt 😀\r\n" + controls
        val translated = "\tEnglish\n中文 🌏  "
        val mode = "face\"\\\n模式😀"
        val original = ExportEntry("zh\"", "en\\", source, translated, false, "RIGHT\n\t")
        val rendered = ExportSnapshot(0L, mode, listOf(original)).render(ExportFormat.JSON)
        assertTrue(rendered.contains("\\\""))
        assertTrue(rendered.contains("\\\\"))
        assertTrue(rendered.contains("\\u0000"))
        val json = JSONObject(String(rendered.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertEquals(mode, json.getString("mode"))
        val exported = json.getJSONArray("entries").getJSONObject(0)
        assertEquals(original.sourceLanguage, exported.getString("source_language"))
        assertEquals(original.targetLanguage, exported.getString("target_language"))
        assertEquals(source, exported.getString("source_text"))
        assertEquals(translated, exported.getString("translated_text"))
        assertEquals(original.side, exported.getString("side"))
    }

    @Test fun textIsReadableAndPreservesWhitespaceNewlinesEmojiAndRepeats() {
        val source = "  你好\n第二行 😀  "
        val translated = "Hello\r\nXin chào 🌏"
        val snapshot = ExportSnapshot(0L, "conversation", listOf(
            entry(source, translated, side = "LEFT", completedAtMillis = 1_234L),
            entry(source, translated, completed = false),
        ))
        val text = snapshot.render(ExportFormat.TXT)
        assertEquals("""
            译路随行 · 翻译会话
            导出时间（UTC）：1970-01-01T00:00:00Z
            模式：conversation
            提示：本文件为明文；标记未完成的内容不代表已完成翻译。

            1. [已完成]
            发言侧：LEFT
            语言：zh → en
            完成时间（UTC）：1970-01-01T00:00:01.234Z
        """.trimIndent() + "\n原文：\n$source\n译文：\n$translated\n" +
            "\n2. [部分/未完成]\n语言：zh → en\n完成时间（UTC）：未知\n" +
            "原文：\n$source\n译文：\n$translated\n", text)
        assertEquals(text, String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test fun filtersOnlyEntirelyBlankEntriesAndKeepsOneSidedPartialTextInBothFormats() {
        val snapshot = ExportSnapshot(0L, "solo", listOf(
            entry(" \t\r\n", "\u2003\u00a0"),
            entry("", "Translation only", completed = false),
            entry("Source only", "", completed = false),
            entry("  keep spaces  ", "\t", completed = false),
        ))
        assertEquals(3, snapshot.entries.size)
        val exported = JSONObject(snapshot.render(ExportFormat.JSON)).getJSONArray("entries")
        assertEquals(3, exported.length())
        assertEquals("", exported.getJSONObject(0).getString("source_text"))
        assertEquals("Translation only", exported.getJSONObject(0).getString("translated_text"))
        assertEquals("Source only", exported.getJSONObject(1).getString("source_text"))
        assertEquals("  keep spaces  ", exported.getJSONObject(2).getString("source_text"))
        assertEquals("\t", exported.getJSONObject(2).getString("translated_text"))
        val text = snapshot.render(ExportFormat.TXT)
        assertTrue(text.contains("Translation only"))
        assertTrue(text.contains("Source only"))
        assertTrue(text.contains("  keep spaces  "))
        assertFalse(text.contains("[已完成]"))
    }

    @Test fun identicalUtterancesRemainSeparateOrderedEntriesInBothFormats() {
        val repeated = entry("Again", "再一次")
        val snapshot = ExportSnapshot(0L, "solo", listOf(repeated, repeated))
        assertEquals(2, snapshot.entries.size)
        val exported = JSONObject(snapshot.render(ExportFormat.JSON)).getJSONArray("entries")
        assertEquals(2, exported.length())
        assertEquals("Again", exported.getJSONObject(0).getString("source_text"))
        assertEquals("Again", exported.getJSONObject(1).getString("source_text"))
        val text = snapshot.render(ExportFormat.TXT)
        assertTrue(text.contains("1. [已完成]"))
        assertTrue(text.contains("2. [已完成]"))
        assertEquals(2, Regex("原文：\nAgain\n").findAll(text).count())
    }

    @Test fun inputListMutationsCannotChangeEitherFrozenRendering() {
        val input = mutableListOf(entry(), entry("Pending", "", completed = false))
        val snapshot = ExportSnapshot(0L, "solo", input)
        val text = snapshot.render(ExportFormat.TXT)
        val json = snapshot.render(ExportFormat.JSON)
        input[0] = entry("Changed", "Changed")
        input.removeAt(1)
        input.add(entry("New", "New"))
        input.clear()
        assertEquals(2, snapshot.entries.size)
        assertEquals(text, snapshot.render(ExportFormat.TXT))
        assertEquals(json, snapshot.render(ExportFormat.JSON))
    }

    @Test fun exposedEntriesCannotBeMutatedThroughAMutableListCast() {
        val snapshot = ExportSnapshot(0L, "solo", listOf(entry()))
        val entries = snapshot.entries as MutableList<ExportEntry>
        assertThrows(UnsupportedOperationException::class.java) { entries.clear() }
        assertEquals(1, snapshot.entries.size)
    }

    @Test fun emptyAndBlankSnapshotsCannotRenderAnEmptyFile() {
        listOf(emptyList(), listOf(entry("\n\t", "  "))).forEach { entries ->
            val snapshot = ExportSnapshot(0L, "solo", entries)
            assertTrue(snapshot.entries.isEmpty())
            ExportFormat.entries.forEach { format ->
                assertThrows(IllegalArgumentException::class.java) { snapshot.render(format) }
            }
        }
    }

    @Test fun filenamesIncludeBrandModeUtcTimeAndMatchingExtension() {
        val snapshot = ExportSnapshot(1_234L, "face_to_face", listOf(entry()))
        assertEquals("译路随行_face_to_face_19700101T000001234Z.txt", snapshot.fileName(ExportFormat.TXT))
        assertEquals("译路随行_face_to_face_19700101T000001234Z.json", snapshot.fileName(ExportFormat.JSON))
    }

    @Test fun filenamesNeutralizeUnsafeOrEmptyModesAndBoundLengthWithoutChangingMetadata() {
        val unsafe = "../../face\\to:face*?\"<>|\u0000\r\n"
        val snapshot = ExportSnapshot(0L, unsafe, listOf(entry()))
        val name = snapshot.fileName(ExportFormat.JSON)
        assertTrue(name.startsWith("译路随行_"))
        assertFalse(name.contains(".."))
        assertFalse(name.any { it < ' ' || it in "/\\:*?\"<>|" })
        assertEquals(unsafe, JSONObject(snapshot.render(ExportFormat.JSON)).getString("mode"))
        assertEquals("译路随行_conversation_19700101T000000000Z.txt",
            ExportSnapshot(0L, "  .  ", listOf(entry())).fileName(ExportFormat.TXT))
        assertTrue(ExportSnapshot(0L, "长".repeat(500), listOf(entry()))
            .fileName(ExportFormat.JSON).toByteArray(Charsets.UTF_8).size < 255)
    }
}
