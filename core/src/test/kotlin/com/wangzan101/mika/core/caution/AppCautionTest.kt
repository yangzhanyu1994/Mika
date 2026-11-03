package com.wangzan101.mika.core.caution

import com.wangzan101.mika.core.action.Approval
import com.wangzan101.mika.core.action.ApprovalPolicy
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AppCautionTest {
    @Test fun guardedAppsStartByAskingAndPaceIsJittered() {
        for (pkg in CautionDefaults.GUARDED) {
            assertEquals(pkg, AppCautionMode.ConfirmWrites, CautionDefaults.forPackage(pkg).mode)
        }
        assertEquals(AppCautionMode.Normal, CautionDefaults.forPackage("com.example.notes").mode)
        assertEquals(CautionDefaults.DELAY_MIN_MS, CautionDefaults.forPackage("com.example.notes").limits.minDelayMs)
        assertEquals(CautionDefaults.DELAY_MAX_MS, CautionDefaults.SHOP_LIMITS.maxDelayMs)
        assertTrue(CautionDefaults.DELAY_MIN_MS < CautionDefaults.DELAY_MAX_MS)
        assertEquals(5, CautionDefaults.XHS_LIMITS.searchHourly)
        assertEquals(12, CautionDefaults.XHS_LIMITS.pageHourly)
        assertEquals(6, CautionDefaults.SHOP_LIMITS.searchHourly)
        assertEquals(10, CautionDefaults.SHOP_LIMITS.pageHourly)
        assertEquals(8, CautionDefaults.SHOP_LIMITS.detailHourly)
        assertTrue(CautionDefaults.TAOBAO in CautionDefaults.BROWSE)
        assertTrue(CautionDefaults.XIANYU in CautionDefaults.BROWSE)
        assertTrue(CautionDefaults.JD in CautionDefaults.BROWSE)
    }

    @Test fun readOnlyBlocksWritesAndAllowsReading() {
        val runtime = runtime(AppCautionMode.ReadOnly)
        val screen = screen("发送")
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.Scroll, null, null, false, false, screen) is CautionVerdict.Allow)
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.LaunchApp, null, null, false, false, screen) is CautionVerdict.Allow)
        val blocked = runtime.assess(CautionDefaults.WECHAT, Verb.Tap, "发送", null, false, false, screen)
        assertTrue(blocked is CautionVerdict.Blocked)
        assertTrue((blocked as CautionVerdict.Blocked).message.contains("只读"))
        assertTrue(blocked.message.contains("发送"))
        val search = runtime.assess(CautionDefaults.WECHAT, Verb.TypeText, "搜索", "天气", true, false, screen)
        assertTrue(search is CautionVerdict.Allow)
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.TypeText, "输入框", "你好", false, false, screen) is CautionVerdict.Allow)
    }

    @Test fun confirmWritesAsksAndNormalDoesNot() {
        val ask = runtime(AppCautionMode.ConfirmWrites)
            .assess(CautionDefaults.XHS, Verb.Tap, "点赞", null, false, false, screen("点赞"))
        assertTrue(ask is CautionVerdict.Ask)
        assertEquals(SocialWrite.Like, (ask as CautionVerdict.Ask).write)
        assertEquals(Approval.Ask, ApprovalPolicy.decideWrite(AppCautionMode.ConfirmWrites, canAsk = true))
        assertEquals(Approval.Deny, ApprovalPolicy.decideWrite(AppCautionMode.ConfirmWrites, canAsk = false))
        assertEquals(Approval.Refuse, ApprovalPolicy.decideWrite(AppCautionMode.ReadOnly, canAsk = true))
        assertEquals(Approval.Allow, ApprovalPolicy.decideWrite(AppCautionMode.Normal, canAsk = true))
        assertTrue(runtime(AppCautionMode.Normal).assess(CautionDefaults.XHS, Verb.Tap, "关注", null, false, false, screen("关注")) is CautionVerdict.Allow)
    }

    @Test fun guardedAppsAskBeforeTheListedWrites() {
        val runtime = AppCautionRuntime()
        fun ask(pkg: String, label: String) = runtime.assess(pkg, Verb.Tap, label, null, false, false, screen(pkg, label))
        assertEquals(SocialWrite.Send, (ask(CautionDefaults.WECHAT, "发送") as CautionVerdict.Ask).write)
        assertEquals(SocialWrite.Comment, (ask(CautionDefaults.XHS, "评论") as CautionVerdict.Ask).write)
        assertEquals(SocialWrite.Follow, (ask(CautionDefaults.XHS, "关注") as CautionVerdict.Ask).write)
        assertEquals(SocialWrite.Order, (ask(CautionDefaults.TAOBAO, "立即购买") as CautionVerdict.Ask).write)
        assertEquals(SocialWrite.Pay, (ask(CautionDefaults.JD, "确认支付") as CautionVerdict.Ask).write)
        assertEquals(SocialWrite.Refresh, (ask(CautionDefaults.XIANYU, "擦亮") as CautionVerdict.Ask).write)
        assertEquals(SocialWrite.Price, (ask(CautionDefaults.XIANYU, "改价") as CautionVerdict.Ask).write)
        assertEquals(SocialWrite.Post, (ask(CautionDefaults.XHS, "发布") as CautionVerdict.Ask).write)
        assertNull(SocialWrites.match("价格"))
        assertNull(SocialWrites.match("支付宝"))
    }

    @Test fun writesMatchTheListedActions() {
        assertEquals(SocialWrite.Send, SocialWrites.match("发送"))
        assertEquals(SocialWrite.Post, SocialWrites.match("发布"))
        assertEquals(SocialWrite.Comment, SocialWrites.match("评论"))
        assertEquals(SocialWrite.Like, SocialWrites.match("点赞"))
        assertEquals(SocialWrite.Follow, SocialWrites.match("关注"))
        assertEquals(SocialWrite.AddFriend, SocialWrites.match("添加好友"))
        assertEquals(SocialWrite.Delete, SocialWrites.match("删除"))
        assertEquals(SocialWrite.Submit, SocialWrites.match("提交"))
        assertEquals(SocialWrite.Send, SocialWrites.match("Send"))
        assertEquals(SocialWrite.Order, SocialWrites.match("去结算"))
        assertEquals(SocialWrite.Pay, SocialWrites.match("付款"))
        assertEquals(SocialWrite.Refresh, SocialWrites.match("一键擦亮"))
        assertEquals(SocialWrite.Price, SocialWrites.match("修改价格"))
        assertNull(SocialWrites.match("今天的发送记录很长所以不是按钮"))
        assertNull(SocialWrites.classify(Verb.Scroll, "发送", null, false, false))
    }

    @Test fun quotaStopsTheTask() {
        var clock = 1_000_000L
        val runtime = AppCautionRuntime(now = { clock }, config = {
            AppCaution(AppCautionMode.Normal, CautionLimits(2, 3, 0, 0, 0))
        })
        runtime.record(CautionDefaults.WECHAT, "点击", Verb.Tap)
        runtime.record(CautionDefaults.WECHAT, "点击", Verb.Tap)
        val stop = runtime.assess(CautionDefaults.WECHAT, Verb.Tap, "聊天", null, false, false, screen("聊天"))
        assertTrue(stop is CautionVerdict.Stop)
        assertTrue((stop as CautionVerdict.Stop).message.contains("上限"))
        assertTrue(stop.message.contains("微信"))
        clock += AppCautionRuntime.HOUR + 1
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.Scroll, null, null, false, false, screen("聊天")) is CautionVerdict.Allow)
        runtime.record(CautionDefaults.WECHAT, "滚动", Verb.Scroll)
        val daily = runtime.assess(CautionDefaults.WECHAT, Verb.Scroll, null, null, false, false, screen("聊天"))
        assertTrue(daily is CautionVerdict.Stop)
        assertTrue((daily as CautionVerdict.Stop).message.contains("每天"))
    }

    @Test fun shoppingAndXiaohongshuCountSearchPageTurnsAndDetails() {
        val runtime = AppCautionRuntime()
        repeat(CautionDefaults.SHOP_LIMITS.searchHourly) {
            val step = runtime.assess(CautionDefaults.TAOBAO, Verb.TypeText, "搜索", "耳机", true, false, screen(CautionDefaults.TAOBAO, "搜索"))
            assertTrue(step is CautionVerdict.Allow)
            runtime.record(CautionDefaults.TAOBAO, "输入", Verb.TypeText)
        }
        val search = runtime.assess(CautionDefaults.TAOBAO, Verb.TypeText, "搜索", "充电器", true, false, screen(CautionDefaults.TAOBAO, "搜索"))
        assertTrue(search is CautionVerdict.Stop)
        assertTrue((search as CautionVerdict.Stop).message.contains("搜索"))

        val pages = AppCautionRuntime()
        repeat(CautionDefaults.XHS_LIMITS.pageHourly) {
            assertTrue(pages.assess(CautionDefaults.XHS, Verb.Scroll, null, null, false, false, screen(CautionDefaults.XHS, "推荐")) is CautionVerdict.Allow)
            pages.record(CautionDefaults.XHS, "滚动", Verb.Scroll)
        }
        val page = pages.assess(CautionDefaults.XHS, Verb.Swipe, null, null, false, false, screen(CautionDefaults.XHS, "推荐"))
        assertTrue(page is CautionVerdict.Stop)
        assertTrue((page as CautionVerdict.Stop).message.contains("翻页"))

        val details = AppCautionRuntime()
        repeat(CautionDefaults.SHOP_LIMITS.detailHourly) {
            assertTrue(details.assess(CautionDefaults.JD, Verb.Tap, "降噪耳机 ¥199", null, false, false, screen(CautionDefaults.JD, "降噪耳机 ¥199")) is CautionVerdict.Allow)
            details.record(CautionDefaults.JD, "点击", Verb.Tap)
        }
        val detail = details.assess(CautionDefaults.JD, Verb.Tap, "充电器 ¥59", null, false, false, screen(CautionDefaults.JD, "充电器 ¥59"))
        assertTrue(detail is CautionVerdict.Stop)
        assertTrue((detail as CautionVerdict.Stop).message.contains("详情"))
        assertNull(BrowseActions.classify(CautionDefaults.TAOBAO, Verb.Tap, "首页", false, false))
        assertEquals(BrowseKind.Search, BrowseActions.classify(CautionDefaults.XIANYU, Verb.Tap, "搜索", false, false))
    }

    @Test fun delayIsRandomInsideTheRangeAndNotAFixedCadence() {
        var clock = 10_000L
        val runtime = AppCautionRuntime(now = { clock }, random = Random(1), config = { CautionDefaults.forPackage(it) })
        assertEquals(0L, runtime.delayBefore(CautionDefaults.WECHAT, Verb.Tap))
        val waits = mutableListOf<Long>()
        repeat(12) {
            runtime.record(CautionDefaults.WECHAT, "点击", Verb.Tap)
            waits += runtime.delayBefore(CautionDefaults.WECHAT, Verb.Tap)
            clock += 60_000
        }
        assertTrue(waits.all { it in CautionDefaults.DELAY_MIN_MS..CautionDefaults.DELAY_MAX_MS })
        assertTrue(waits.distinct().size > 1)
        assertEquals(0L, runtime.delayBefore("com.example.notes", Verb.Tap))
        runtime.record("com.example.notes", "点击", Verb.Tap)
        val notes = runtime.delayBefore("com.example.notes", Verb.Tap)
        assertTrue("$notes", notes in CautionDefaults.DELAY_MIN_MS..CautionDefaults.DELAY_MAX_MS)

        var t = 0L
        val custom = AppCautionRuntime(now = { t }, random = Random(3), config = {
            AppCaution(AppCautionMode.Normal, CautionLimits(40, 40, 5_000, 8_000, CautionDefaults.COOLDOWN_MS))
        })
        val customWaits = mutableListOf<Long>()
        repeat(8) {
            custom.record(CautionDefaults.JD, "点击", Verb.Tap)
            customWaits += custom.delayBefore(CautionDefaults.JD, Verb.Tap)
            t += 60_000
        }
        assertTrue(customWaits.all { it in 5_000..8_000 })
        assertTrue(customWaits.distinct().size > 1)
    }

    @Test fun riskPhrasesStopNotifyAndPauseForADay() {
        var clock = 5_000_000L
        val runtime = AppCautionRuntime(now = { clock })
        val hit = runtime.assess(CautionDefaults.JD, Verb.Tap, "立即购买", null, false, false, screen(CautionDefaults.JD, "请向右拖动滑块完成拼图", "立即购买"))
        assertTrue(hit is CautionVerdict.Stop)
        val stop = hit as CautionVerdict.Stop
        assertTrue(stop.takeover)
        assertTrue(stop.message.contains("不会去通过验证"))
        assertTrue(stop.message.contains("京东"))
        assertEquals(clock + CautionDefaults.COOLDOWN_MS, runtime.pauses()[CautionDefaults.JD])
        clock += 23 * AppCautionRuntime.HOUR
        val cooled = runtime.assess(CautionDefaults.JD, Verb.Scroll, null, null, false, false, screen(CautionDefaults.JD, "首页"))
        assertTrue(cooled is CautionVerdict.Stop)
        assertTrue((cooled as CautionVerdict.Stop).message.contains("暂停"))
        clock += 2 * AppCautionRuntime.HOUR
        assertTrue(runtime.assess(CautionDefaults.JD, Verb.Scroll, null, null, false, false, screen(CautionDefaults.JD, "首页")) is CautionVerdict.Allow)

        val taobao = AppCautionRuntime()
        val slider = taobao.assess(CautionDefaults.TAOBAO, Verb.Observe, null, null, false, false, screen(CautionDefaults.TAOBAO, "滑动滑块验证"))
        assertTrue(slider is CautionVerdict.Stop)
        assertTrue((slider as CautionVerdict.Stop).takeover)
        val face = AppCautionRuntime().assess(CautionDefaults.XIANYU, Verb.Observe, null, null, false, false, screen(CautionDefaults.XIANYU, "请进行人脸识别"))
        assertTrue(face is CautionVerdict.Stop)
        val frequent = AppCautionRuntime().assess(CautionDefaults.WECHAT, Verb.Observe, null, null, false, false, screen("操作频繁"))
        assertTrue(frequent is CautionVerdict.Stop)
        assertEquals("try again later", RiskSignals.find("Please try again later"))
        assertEquals("滑块", RiskSignals.find("滑块"))
        val article = "这篇笔记很长，里面提到有人被封禁，还写了滑块两个字，但整段是一篇帖子，不是单独弹出来的验证提示，所以不能把阅读当成要你接手的安全页。后面又写了好几句闲话，把这一行撑过提示语的长度。"
        assertTrue(article.length > 80)
        assertNull(RiskSignals.find(article))
    }

    @Test fun turningTheSwitchOffDropsPacingLimitsAndConfirmation() {
        var on = true
        val runtime = AppCautionRuntime(enabled = { on })
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.Tap, "发送", null, false, false, screen("发送")) is CautionVerdict.Ask)
        on = false
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.Tap, "发送", null, false, false, screen("发送")) is CautionVerdict.Allow)
        assertTrue(runtime.assess(CautionDefaults.JD, Verb.Observe, null, null, false, false, screen(CautionDefaults.JD, "请拖动滑块完成验证")) is CautionVerdict.Allow)
        runtime.record(CautionDefaults.TAOBAO, "输入", Verb.TypeText)
        assertEquals(0L, runtime.delayBefore(CautionDefaults.TAOBAO, Verb.Tap))
        on = true
        assertEquals(0L, runtime.delayBefore(CautionDefaults.TAOBAO, Verb.Tap))
    }

    private fun runtime(mode: AppCautionMode) = AppCautionRuntime(config = {
        CautionDefaults.forPackage(it).copy(mode = mode)
    })

    private fun screen(label: String) = screen(CautionDefaults.WECHAT, label)

    private fun screen(packageName: String, vararg labels: String) = ScreenSnapshot(
        "s", packageName, labels.mapIndexed { i, name -> element(name, i) }, 1080, 2400,
    )

    private fun element(name: String, index: Int = 0) = UiElement("@e$index", "Button", name, 100, 100 + index * 40, listOf(0, 0, 200, 80), null, true, false, false)
}
