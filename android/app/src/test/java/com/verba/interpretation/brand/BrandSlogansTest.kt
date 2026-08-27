package com.verba.interpretation.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandSlogansTest {
    @Test fun parsesNonBlankSlogansInSourceOrder() {
        val slogans = BrandSlogans.parse("""{"slogans":["实时同传，智联世界","  ","连接每一次交流"]}""")

        assertEquals(listOf("实时同传，智联世界", "连接每一次交流"), slogans)
    }

    @Test fun invalidOrEmptyConfigurationProducesNoCandidates() {
        assertTrue(BrandSlogans.parse("{}").isEmpty())
        assertTrue(BrandSlogans.parse("not json").isEmpty())
    }
}
