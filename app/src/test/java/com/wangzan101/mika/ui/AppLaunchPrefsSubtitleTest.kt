package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.AppLaunch
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLaunchPrefsSubtitleTest {
    @Test fun omitsBlankPhraseForUnconfirmedEntry() {
        val item = AppLaunch.Preference("", AppLaunch.App("com.taobao.taobao", "淘宝"), confirmed = false)
        assertEquals("用过一次，不是默认 · com.taobao.taobao", appLaunchPrefSubtitle("用过一次，不是默认", item))
    }

    @Test fun includesPhraseAndCopyUserWhenPresent() {
        val item = AppLaunch.Preference("买菜", AppLaunch.App("com.tencent.mm", "微信", userId = 10), confirmed = true)
        assertEquals("默认打开 · 买菜 · com.tencent.mm · 10", appLaunchPrefSubtitle("默认打开", item))
    }
}
