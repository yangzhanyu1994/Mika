package com.wangzan101.mika.ui

import com.wangzan101.mika.capsule.CapsuleArc
import com.wangzan101.mika.capsule.CapsuleArcAction
import com.wangzan101.mika.capsule.CapsuleTapAction
import com.wangzan101.mika.proactive.BriefSlot
import com.wangzan101.mika.proactive.BriefSpec
import com.wangzan101.mika.proactive.NoticeBrief
import com.wangzan101.mika.settings.McpHealth
import com.wangzan101.mika.settings.McpProbe
import com.wangzan101.mika.wechat.WechatConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class ApprovedFollowUpTest {
    @Test fun everyUpstreamOrbIsUsedByARealState() {
        val modes = ThinkingOrbMap.coveredModes()
        assertEquals(ThinkingOrbMode.entries.toSet(), modes)
        assertEquals(ThinkingOrbMode.Wave, ThinkingOrbMap.of(OrbMotion.Listening).mode)
        assertEquals(ThinkingOrbMode.Wave, ThinkingOrbMap.of(OrbMotion.Speaking).mode)
        assertTrue(ThinkingOrbMap.of(OrbMotion.Speaking).audio)
        assertTrue(ThinkingOrbMap.of(OrbMotion.Complete).paused)
        assertFalse(ThinkingOrbMap.of(OrbMotion.Idle).paused)
    }

    @Test fun eachOrbFrameIsAHollowDottedFigure() {
        for (mode in ThinkingOrbMode.entries) {
            val frame = ThinkingOrbEngine.frame(mode, 64f, 0.8f, ThinkingOrbSize.Large)
            assertTrue(mode.name, frame.dots.size >= 6)
            assertTrue(frame.dots.all { it.x.isFinite() && it.y.isFinite() && it.r > 0f && it.r < 20f })
            val bright = frame.dots.maxOf { ThinkingOrbEngine.callLight(it.white) }
            val dim = frame.dots.minOf { ThinkingOrbEngine.callLight(it.white) }
            if (mode != ThinkingOrbMode.Morph) assertTrue("$mode shading", bright - dim > 0.05f)
        }
        assertTrue(ThinkingOrbEngine.frame(ThinkingOrbMode.Web, 64f, 0.4f, ThinkingOrbSize.Large).lines.isNotEmpty())
    }

    @Test fun hangupLiftIsAboutTwoCentimetresUntilTheScreenIsShort() {
        assertEquals(125.98, CallLayout.HANGUP_LIFT_DP.toDouble(), 0.2)
        val tall = CallLayout.hangupLiftDp(800f, 48f, 160f, 96f)
        assertEquals(CallLayout.HANGUP_LIFT_DP.toDouble(), tall.toDouble(), 0.2)
        val short = CallLayout.hangupLiftDp(420f, 48f, 160f, 96f)
        assertTrue(short < tall)
        assertTrue(short >= CallLayout.MIN_LIFT_DP)
        assertEquals(CallLayout.HEADER_DROP_DP * 1.15, CallLayout.headerDropDp(2f).toDouble(), 0.01)
    }

    @Test fun mcpBadgeNeverTreatsAMissingProbeAsConnected() {
        assertEquals(DrawerBadge.Disconnected, mcpConnectionBadge(true, null))
        assertEquals(DrawerBadge.Checking, mcpConnectionBadge(true, McpHealth(false, null, emptyList(), 1, McpProbe.Checking)))
        assertEquals(DrawerBadge.NeedsAuth, mcpConnectionBadge(true, McpHealth(false, "oauth_login_required", emptyList(), 1, McpProbe.NeedsAuth)))
        assertEquals(DrawerBadge.Failed, mcpConnectionBadge(true, McpHealth(false, "timeout", emptyList(), 1, McpProbe.Failed)))
        assertEquals(DrawerBadge.Connected, mcpConnectionBadge(true, McpHealth(true, null, listOf("a"), 1, McpProbe.Connected)))
        assertEquals(DrawerBadge.NotEnabled, mcpConnectionBadge(false, McpHealth(true, null, emptyList(), 1)))
    }

    @Test fun wechatRowDistinguishesConfigFromConnection() {
        assertEquals(WechatDrawerKind.Unconfigured, wechatDrawerKind(false, false, WechatConnectionState.NeedsBinding))
        assertEquals(WechatDrawerKind.ConfiguredIdle, wechatDrawerKind(true, false, WechatConnectionState.Off))
        assertEquals(WechatDrawerKind.Checking, wechatDrawerKind(true, true, WechatConnectionState.Connecting))
        assertEquals(WechatDrawerKind.Connected, wechatDrawerKind(true, true, WechatConnectionState.Connected))
        assertEquals(WechatDrawerKind.NeedsAttention, wechatDrawerKind(true, true, WechatConnectionState.Failed("x")))
        assertEquals(DrawerBadge.Connected, wechatDrawerBadge(WechatDrawerKind.Connected))
        assertEquals(DrawerBadge.NotEnabled, wechatDrawerBadge(WechatDrawerKind.ConfiguredIdle))
    }

    @Test fun arcOpensLeftFromARightCapsuleAndReturnsOnTheSamePath() {
        assertEquals(CapsuleTapAction.QuickMenu, CapsuleTapAction.parse(null))
        val open = CapsuleArc.layout(900f, 400f, dockRight = true, 1000f, 800f, progress = 1f)
        val mid = CapsuleArc.layout(900f, 400f, dockRight = true, 1000f, 800f, progress = 0.5f)
        val shut = CapsuleArc.layout(900f, 400f, dockRight = true, 1000f, 800f, progress = 0f)
        assertEquals(CapsuleArcAction.entries.toList(), open.map { it.action })
        assertTrue(open.all { it.labelOnLeft && it.x < 900f })
        assertTrue(open.zip(mid).all { (a, b) -> b.x in a.x..900f || abs(b.x - 900f) < abs(a.x - 900f) })
        assertTrue(shut.all { abs(it.x - 900f) < 1f && abs(it.y - 400f) < 1f })
        val left = CapsuleArc.layout(40f, 400f, dockRight = false, 1000f, 800f, 1f, margin = 8f)
        assertTrue(left.all { !it.labelOnLeft && it.x >= 8f })
    }

    @Test fun briefWindowStatesSameDayAndCrossesMidnightWithoutLoggingCodes() {
        val zone = ZoneId.of("Asia/Shanghai")
        val fire = ZonedDateTime.of(2026, 11, 21, 8, 30, 0, 0, zone)
        val morning = NoticeBrief.window(fire, BriefSpec(fromMinute = 0, toMinute = 8 * 60 + 30))
        assertEquals(21, morning.first.dayOfMonth)
        assertEquals(0, morning.first.hour)
        assertEquals(8, morning.second.hour)
        val cross = NoticeBrief.window(fire, BriefSpec(fromMinute = 20 * 60, toMinute = 8 * 60))
        assertTrue(NoticeBrief.crossesMidnight(BriefSpec(fromMinute = 20 * 60, toMinute = 8 * 60)))
        assertEquals(20, cross.first.dayOfMonth)
        assertEquals(20, cross.first.hour)
        val next = NoticeBrief.nextFire(fire.plusMinutes(1), BriefSpec(remindMinute = 8 * 60 + 30))
        assertEquals(22, next.dayOfMonth)
        val (text, otp) = NoticeBrief.redact("银行", "验证码 123456")
        assertTrue(otp)
        assertEquals("", text)
        val self = NoticeBrief.accept("com.wangzan101.mika", "com.wangzan101.mika", "上午总结", "again", 1)
        assertEquals(null, self)
        val kept = NoticeBrief.accept("com.wangzan101.mika", "com.chat", "会议", "改到三点", 1_000)
        assertEquals("改到三点", kept?.text)
        val summary = NoticeBrief.summarize(
            BriefSlot.Morning, listOf(kept!!), "com.wangzan101.mika", true, true, synthetic = true,
        )
        assertTrue(summary.title.startsWith("[合成测试]"))
        assertTrue(summary.body.contains("改到三点"))
        assertFalse(summary.body.contains("123456"))
        val quiet = NoticeBrief.summarize(BriefSlot.AllDay, emptyList(), "com.wangzan101.mika", false, true, false)
        assertEquals("listen", quiet.blockedReason)
    }
}
