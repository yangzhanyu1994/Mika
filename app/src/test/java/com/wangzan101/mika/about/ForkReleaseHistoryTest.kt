package com.wangzan101.mika.about

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ForkReleaseHistoryTest {
    @Test fun parsesOnlyVersionEntriesAndStopsAtNextSection() {
        val source = """
            # Project
            ### 近期版本更新说明
            Introductory text.
            #### [v0.1.65](https://example.com/65) · 2026-11-14

            - First update.
            #### [v0.1.64](https://example.com/64) · 2026-11-14

            - Older update.
            ### Other section
            unrelated content
        """.trimIndent()
        val entries = ForkReleaseHistory.parse(source)
        assertEquals(listOf("0.1.65", "0.1.64"), entries.map { it.version })
        assertEquals("- First update.", entries[0].notes)
        assertEquals("- Older update.", entries[1].notes)
        assertTrue(ForkReleaseHistory.parse("no changelog").isEmpty())
    }

    @Test fun bundledHistoryContainsRecentVersionsInOrder() {
        val source = File("src/main/assets/fork-release-history.md").readText()
        val entries = ForkReleaseHistory.parse(source)
        assertEquals("0.1.65", entries.first().version)
        assertEquals("0.1.40", entries.last().version)
        assertEquals(26, entries.size)
        assertTrue(entries.first().notes.contains("更新记录"))
    }
}
