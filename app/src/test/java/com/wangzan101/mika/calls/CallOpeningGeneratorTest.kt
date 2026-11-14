package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallOpeningGeneratorTest {
    @Test fun acceptsOnlyBoundedSingleLineJsonOpening() {
        assertEquals("请您下午一点半来上班。", CallOpeningGenerator.parse("""{"opening":"请您下午一点半来上班。"}"""))
        assertNull(CallOpeningGenerator.parse("not json"))
        assertNull(CallOpeningGenerator.parse("""{"opening":42}"""))
        assertNull(CallOpeningGenerator.parse("""{"opening":""}"""))
        assertNull(CallOpeningGenerator.parse("""{"opening":"第一行\n第二行"}"""))
        assertNull(CallOpeningGenerator.parse("""{"opening":"${"a".repeat(301)}"}"""))
    }
}
