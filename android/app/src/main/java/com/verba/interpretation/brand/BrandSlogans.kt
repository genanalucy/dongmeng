package com.verba.interpretation.brand

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/** Loads launch/home slogans from the shared branding asset. */
object BrandSlogans {
    fun pick(context: Context, random: Random = Random.Default): String {
        val slogans = try {
            parse(context.assets.open("slogans.json").bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            emptyList()
        }
        return slogans.randomOrNull(random) ?: BrandConfig.tagline
    }

    internal fun parse(json: String): List<String> = try {
        val values = JSONObject(json).optJSONArray("slogans") ?: return emptyList()
        buildList {
            for (index in 0 until values.length()) {
                values.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
