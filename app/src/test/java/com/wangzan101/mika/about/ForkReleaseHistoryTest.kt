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

    @Test fun staleRemoteCannotHideBundledVersion() {
        val old = ForkReleaseHistory.Entry("0.1.64", "2026-11-14", "remote")
        val newer = ForkReleaseHistory.Entry("0.1.65", "2026-11-14", "bundled")
        val merged = ForkReleaseHistory.merge(listOf(old), listOf(newer, old.copy(notes = "bundled")))
        assertEquals(listOf("0.1.65", "0.1.64"), merged.map { it.version })
        assertEquals("remote", merged.last().notes)
    }

    @Test fun bundledHistoryContainsRecentVersionsInOrder() {
        val source = File("src/main/assets/fork-release-history.md").readText()
        val entries = ForkReleaseHistory.parse(source)
        assertEquals(listOf("0.1.92", "0.1.91", "0.1.90", "0.1.89", "0.1.88", "0.1.87", "0.1.86", "0.1.85", "0.1.84", "0.1.83", "0.1.82", "0.1.81", "0.1.80", "0.1.77"), entries.take(14).map { it.version })
        assertEquals("0.1.40", entries.last().version)
        assertEquals(51, entries.size)
        assertTrue(entries.first().notes.contains("MCP 查询"))
        assertTrue(entries.first().notes.contains("重试耗时"))
        assertTrue(entries.first().notes.contains("工作动效"))
        assertTrue(entries[1].notes.contains("默认跟随最新输出"))
        assertTrue(entries[1].notes.contains("恢复长按输入框说话"))
        assertTrue(entries[2].notes.contains("按需接入工作模式"))
        assertTrue(entries[2].notes.contains("思考动效"))
        assertTrue(entries[3].notes.contains("数字助理浮窗"))
        assertTrue(entries[4].notes.contains("语音播报配置"))
        assertTrue(entries[5].notes.contains("自动分流"))
        assertTrue(entries[6].notes.contains("豆包实时语音"))
        assertTrue(entries[7].notes.contains("数字助理浮窗"))
        assertTrue(entries[7].notes.contains("任务侧栏"))
        assertTrue(entries[8].notes.contains("流式回复"))
        assertTrue(entries[8].notes.contains("聊天动效"))
        assertTrue(entries[9].notes.contains("动态头像"))
        assertTrue(entries[9].notes.contains("浮窗续聊"))
        assertTrue(entries[10].notes.contains("助手外观"))
        assertTrue(entries[11].notes.contains("后台蓝光"))
        assertTrue(entries[12].notes.contains("首页引导"))
        assertTrue(entries[13].notes.contains("系统动态配色"))
    }
}
