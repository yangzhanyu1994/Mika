package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitSuggestionsTest {
    private fun use(text: String, at: Long) = HabitSuggestions.Use(text, at)

    @Test fun emptyHistoryShowsNothing() {
        assertEquals(emptyList<String>(), HabitSuggestions.rank(emptyList()))
        assertEquals(emptyList<String>(), HabitSuggestions.rank(listOf(use("  ", 1), use("a", 2), use("[[screen:s]]", 3))))
    }

    @Test fun frequencyThenRecencyAndSimilarLinesCollapse() {
        val ranked = HabitSuggestions.rank(listOf(
            use("快递来电就让他放门口", 1),
            use("快递来电就让他放门口", 2),
            use("快递来电就让他放门口。", 3),
            use("明早 8 点提醒我开会", 4),
            use("明早8点提醒我开会", 5),
            use("查天气", 7),
            use("记下门禁", 8),
            use("打开日历", 9),
            use("打开日历看看明天", 6),
        ))
        assertEquals(
            listOf("快递来电就让他放门口。", "明早8点提醒我开会", "打开日历", "记下门禁"),
            ranked,
        )
        assertFalse(ranked.any { it.contains("github.com") || it.contains("小红书") })
        assertFalse(HabitSuggestions.similar(HabitSuggestions.keyOf("打开日历"), HabitSuggestions.keyOf("打开日历看看明天")))
        assertTrue(HabitSuggestions.similar(
            HabitSuggestions.keyOf("帮我打电话给物业问停车"),
            HabitSuggestions.keyOf("帮我打电话给物业问停车吧"),
        ))
    }

    @Test fun sameCountKeepsTheNewerWordingFirst() {
        val ranked = HabitSuggestions.rank(listOf(
            use("提醒我喝水", 10),
            use("记下门禁密码", 80),
        ))
        assertEquals(listOf("记下门禁密码", "提醒我喝水"), ranked)
    }

    @Test fun hintRotationWrapsAndStopsAtASingleLine() {
        assertEquals(1, InputHints.next(0, 4))
        assertEquals(0, InputHints.next(3, 4))
        assertEquals(0, InputHints.next(5, 1))
        assertTrue(InputHints.plan.none { it in InputHints.call })
        val banned = listOf(
            com.wangzan101.mika.R.string.chat_example_github_star,
            com.wangzan101.mika.R.string.chat_example_follow,
            com.wangzan101.mika.R.string.chat_example_group_summary,
            com.wangzan101.mika.R.string.chat_example_alarms,
        )
        assertTrue((InputHints.home + InputHints.plan + InputHints.call + InputHints.brief).none { it in banned })
    }
}
