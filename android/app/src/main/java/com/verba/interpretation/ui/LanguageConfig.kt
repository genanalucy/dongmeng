package com.verba.interpretation.ui

/** Shared four-language allowlist. Provider routing is added in the next phase. */
enum class TranslationLanguage(val code: String, val displayName: String) {
    CHINESE("zh", "中文"),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    VIETNAMESE("vi", "Tiếng Việt");

    companion object {
        fun displayName(code: String): String = entries.firstOrNull { it.code == code }?.displayName ?: code
    }
}

fun supportsTranslationPair(source: String, target: String): Boolean =
    source != target && TranslationLanguage.entries.any { it.code == source } && TranslationLanguage.entries.any { it.code == target }
