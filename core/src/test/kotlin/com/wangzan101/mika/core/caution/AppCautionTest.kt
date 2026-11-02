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
    @Test fun wechatAndXiaohongshuStartReadOnly() {
        assertEquals(AppCautionMode.ReadOnly, CautionDefaults.forPackage(CautionDefaults.WECHAT).mode)
        assertEquals(AppCautionMode.ReadOnly, CautionDefaults.forPackage(CautionDefaults.XHS).mode)
        assertEquals(AppCautionMode.Normal, CautionDefaults.forPackage("com.example.notes").mode)
        assertEquals(800L, CautionDefaults.SOCIAL_LIMITS.minDelayMs)
        assertEquals(2_500L, CautionDefaults.SOCIAL_LIMITS.maxDelayMs)
        assertTrue(CautionDefaults.forPackage("com.example.notes").limits.minDelayMs == 0L)
    }

    @Test fun readOnlyBlocksWritesAndAllowsReading() {
        val runtime = runtime(AppCautionMode.ReadOnly)
        val screen = screen("发送")
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.Scroll, null, null, false, false, screen) is CautionVerdict.Allow)
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.LaunchApp, null, null, false, false, screen) is CautionVerdict.Allow)
        val blocked = runtime.assess(CautionDefaults.WECHAT, Verb.Tap, "发送", null, false, false, screen)
        assertTrue(blocked is CautionVerdict.Blocked)
        assertTrue((blocked as CautionVerdict.Blocked).message.contains("只读"))
        assertTrue((blocked).message.contains("发送"))
        val search = runtime.assess(CautionDefaults.WECHAT, Verb.TypeText, "搜索", "天气", true, false, screen)
        assertTrue(search is CautionVerdict.Allow)
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
        clock += AppCautionRuntime.HOUR + 1
        assertTrue(runtime.assess(CautionDefaults.WECHAT, Verb.Scroll, null, null, false, false, screen("聊天")) is CautionVerdict.Allow)
    }

    @Test fun delayIsRandomInsideTheRange() {
        val random = Random(1)
        var clock = 10_000L
        val runtime = AppCautionRuntime(now = { clock }, random = random, config = { CautionDefaults.forPackage(it) })
        assertEquals(0L, runtime.delayBefore(CautionDefaults.WECHAT, Verb.Tap))
        runtime.record(CautionDefaults.WECHAT, "点击", Verb.Tap)
        val wait = runtime.delayBefore(CautionDefaults.WECHAT, Verb.Tap)
        assertTrue("$wait", wait in 800..2_500)
        assertEquals(0L, runtime.delayBefore("com.example.notes", Verb.Tap))
        clock += 3_000
        assertEquals(0L, runtime.delayBefore(CautionDefaults.WECHAT, Verb.Tap))
    }

    @Test fun riskPhrasesStopAndPause() {
        val runtime = runtime(AppCautionMode.Normal)
        val hit = runtime.assess(CautionDefaults.WECHAT, Verb.Observe, null, null, false, false, screen("操作过于频繁，请稍后再试"))
        assertTrue(hit is CautionVerdict.Stop)
        val stop = hit as CautionVerdict.Stop
        assertTrue(stop.takeover)
        assertTrue(stop.message.contains("操作过于频繁"))
        assertTrue(stop.message.contains("不会去通过验证"))
        val cooled = runtime.assess(CautionDefaults.WECHAT, Verb.Scroll, null, null, false, false, screen("聊天"))
        assertTrue(cooled is CautionVerdict.Stop)
        assertTrue((cooled as CautionVerdict.Stop).message.contains("暂停"))
        assertEquals("try again later", RiskSignals.find("Please try again later"))
        assertEquals("滑块", RiskSignals.find("滑块"))
        assertNull(RiskSignals.find("这篇笔记很长，里面提到有人被封禁，还写了违规两个字，但不是验证页"))
    }

    @Test fun idleWirelessDebuggingDefaultsOnOnlyWhenItCanTurnBackOn() {
        assertEquals(true, WirelessIdle.enabled(null, canReenable = true))
        assertEquals(false, WirelessIdle.enabled(null, canReenable = false))
        assertEquals(true, WirelessIdle.enabled(true, canReenable = false))
        assertEquals(false, WirelessIdle.due(enabled = true, taskRunning = true, idleForMs = 120_000))
        assertEquals(false, WirelessIdle.due(enabled = true, taskRunning = false, idleForMs = 1_000))
        assertEquals(true, WirelessIdle.due(enabled = true, taskRunning = false, idleForMs = WirelessIdle.DEFAULT_GRACE_MS))
    }

    private fun runtime(mode: AppCautionMode) = AppCautionRuntime(config = {
        CautionDefaults.forPackage(it).copy(mode = mode)
    })

    private fun screen(label: String) = ScreenSnapshot(
        "s", CautionDefaults.WECHAT, listOf(element(label)), 1080, 2400,
    )

    private fun element(name: String) = UiElement("@e1", "Button", name, 100, 100, listOf(0, 0, 200, 80), null, true, false, false)
}
