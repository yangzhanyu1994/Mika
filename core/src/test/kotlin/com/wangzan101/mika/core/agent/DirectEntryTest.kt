package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectEntryTest {
    @Test fun wifiAndBluetoothDoNotShareAJump() {
        assertEquals("android.settings.WIFI_SETTINGS", DirectEntry.match("打开无线网络")?.action)
        assertEquals("android.settings.BLUETOOTH_SETTINGS", DirectEntry.match("打开蓝牙")?.action)
        assertNull(DirectEntry.match("打开微信"))
        assertNull(DirectEntry.match("打开无线网络然后删除已保存的网络"))
        assertNull(DirectEntry.match("打开设置并付款"))
        assertNull(DirectEntry.match("在微信里打开无线网络"))
    }

    @Test fun openCallCarriesNoSnapshot() {
        val args = DirectEntry.openArgs(DirectEntry.Jump("android.settings.WIFI_SETTINGS"))
        assertEquals("android.settings.WIFI_SETTINGS", args["action"]!!.jsonPrimitive.content)
        assertFalse(args.containsKey("snapshotId"))
        assertFalse(args.containsKey("ref"))
    }

    @Test fun verifiedJumpReadsOnceAndDoesNotRetry() = runTest {
        val screen = JumpScreen(packageName = "com.android.settings")
        val tools = toolDispatcher(screen, deepLinks = true)
        val first = DirectEntry.attempt(tools, DirectEntry.Jump("android.settings.WIFI_SETTINGS"), tools::execute)
        assertEquals(1, first.openCalls)
        assertEquals(1, first.freshReads)
        assertTrue(first.verified)
        assertEquals(listOf("android.settings.WIFI_SETTINGS"), screen.opened)
        assertTrue(first.snapshotId?.isNotBlank() == true)
        val again = DirectEntry.attempt(tools, DirectEntry.Jump("android.settings.WIFI_SETTINGS"), tools::execute)
        assertEquals(2, screen.opened.size)
        assertEquals(1, again.openCalls)
    }

    @Test fun unresolvedIntentIsASingleAttemptWithoutARead() = runTest {
        val screen = JumpScreen(result = ActionResult.Failed("link_unresolved"))
        val tools = toolDispatcher(screen, deepLinks = true)
        val attempt = DirectEntry.attempt(tools, DirectEntry.Jump("android.settings.SETTINGS"), tools::execute)
        assertFalse(attempt.opened)
        assertEquals(1, attempt.openCalls)
        assertEquals(0, attempt.freshReads)
        assertEquals("link_unresolved", attempt.error)
        assertEquals(1, screen.opened.size)
    }

    @Test fun wrongForegroundStillReadsOnce() = runTest {
        val screen = JumpScreen(packageName = "com.other.app")
        val tools = toolDispatcher(screen, deepLinks = true)
        val attempt = DirectEntry.attempt(tools, DirectEntry.Jump("android.settings.SETTINGS"), tools::execute)
        assertTrue(attempt.opened)
        assertFalse(attempt.verified)
        assertEquals(1, attempt.openCalls)
        assertEquals(1, attempt.freshReads)
        assertEquals("com.other.app", attempt.foreground)
    }

    /**
     * 同一句「打开无线网络」：直达是 0 次模型、1 次打开、1 次事后读屏。
     * 没有直达时，模型至少要先读屏再点进设置，这里只锁直达这条路径的次数，不外推百分比。
     */
    @Test fun measuredPathUsesNoModelCall() = runTest {
        val screen = JumpScreen(packageName = "com.samsung.android.settings")
        val tools = toolDispatcher(screen, deepLinks = true)
        val jump = DirectEntry.match("请打开 WLAN")!!
        val attempt = DirectEntry.attempt(tools, jump, tools::execute)
        assertEquals(1, attempt.openCalls)
        assertEquals(1, attempt.freshReads)
        assertTrue(attempt.verified)
        assertTrue(DirectEntry.settingsForeground("com.samsung.android.settings"))
    }
}

private class JumpScreen(
    private val packageName: String = "com.android.settings",
    private val result: ActionResult = ActionResult.Ok(),
) : ScreenPort by FakeScreen() {
    val opened = mutableListOf<String?>()
    private var reads = 0
    private val element = UiElement("@e1", "button", "WLAN", 540, 120, listOf(0, 0, 999, 100), "android:id/title", true, false, false)

    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        opened += action
        return result
    }

    override suspend fun snapshot(): ScreenSnapshot {
        reads++
        return ScreenSnapshot("snap$reads", packageName, listOf(element), 1080, 2400)
    }

    override suspend fun peek(): ScreenSnapshot = snapshot()
}
