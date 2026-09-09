package com.verba.interpretation.cloud

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test

class CloudApiHistoryParsingTest {
    @Test
    fun optionalTimestampTreatsJsonNullAsAbsent() {
        val json = JSONObject("{\"deleted_at\":null}")

        assertNull(json.optionalTimestampMillis("deleted_at") { error("must not parse JSON null") })
    }
}
