package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLaunchTest {
    private val meituan = AppLaunch.App("com.sankuai.meituan", "美团")
    private val taobao = AppLaunch.App("com.taobao.taobao", "淘宝")
    private val wechat = AppLaunch.App("com.tencent.mm", "微信")
    private val clone = AppLaunch.App("com.tencent.mm", "微信", userId = 10, userName = "分身")
    private val work = AppLaunch.App("com.tencent.mm", "微信", userId = 12, userName = "Work")

    @Test fun namedAppOpensAndASpokenNameOverridesASavedPreference() {
        val prefs = listOf(AppLaunch.Preference("买菜", meituan, confirmed = true))
        val named = AppLaunch.effect("打开美团买菜", listOf(meituan, taobao), prefs)
        assertEquals(meituan, (named.decision as AppLaunch.Decision.Open).app)
        assertEquals(AppLaunch.Because.Named, (named.decision as AppLaunch.Decision.Open).because)
        val override = AppLaunch.effect("用淘宝买菜", listOf(meituan, taobao), prefs)
        assertEquals(taobao.packageName, (override.decision as AppLaunch.Decision.Open).app.packageName)
    }

    @Test fun confirmedPreferenceOpensButOneSuccessDoesNot() {
        val apps = listOf(meituan, taobao)
        val saved = AppLaunch.effect("以后用美团买菜", apps, emptyList())
        val preference = saved.save!!
        assertTrue(preference.confirmed)
        assertEquals("买菜", preference.phrase)
        assertEquals(meituan.packageName, (saved.decision as AppLaunch.Decision.Open).app.packageName)
        val again = AppLaunch.effect("买菜", apps, listOf(preference))
        assertEquals(AppLaunch.Because.Preference, (again.decision as AppLaunch.Decision.Open).because)
        val once = AppLaunch.effect("买菜", apps, listOf(AppLaunch.Preference("", meituan, confirmed = false)))
        assertTrue(once.decision is AppLaunch.Decision.None)
    }

    @Test fun shoppingAcrossAppsAndNegativeWordingAreNotGuessed() {
        val apps = listOf(meituan, taobao)
        val prefs = listOf(AppLaunch.Preference("买菜", meituan, confirmed = true), AppLaunch.Preference("购物", taobao, confirmed = true))
        assertTrue(AppLaunch.effect("帮我买牛奶", apps, prefs).decision is AppLaunch.Decision.None)
        assertTrue(AppLaunch.effect("美团和淘宝哪个便宜", apps, prefs).decision is AppLaunch.Decision.Ambiguous)
        val blocked = AppLaunch.effect("不要用美团买菜", apps, prefs)
        assertTrue(blocked.decision is AppLaunch.Decision.None)
        assertEquals("买菜", AppLaunch.effect("取消以后用美团买菜", apps, prefs).revokePhrase)
    }

    @Test fun sameNameCopiesStayAmbiguousUntilTheUserNamesTheCopy() {
        val apps = listOf(wechat, clone, work)
        assertTrue(AppLaunch.effect("打开微信", apps, emptyList()).decision is AppLaunch.Decision.Ambiguous)
        val cloneDecision = AppLaunch.effect("打开微信分身", apps, emptyList()).decision as AppLaunch.Decision.Open
        assertEquals(10, cloneDecision.app.userId)
        val workDecision = AppLaunch.effect("打开微信工作资料", apps, emptyList()).decision as AppLaunch.Decision.Open
        assertEquals(12, workDecision.app.userId)
    }

    @Test fun aSavedPreferenceDoesNotLaunchAfterTheAppIsGone() {
        val decision = AppLaunch.effect("买菜", emptyList(), listOf(AppLaunch.Preference("买菜", meituan, confirmed = true))).decision
        assertTrue(decision is AppLaunch.Decision.Missing)
    }

    @Test fun anUnknownNameIsNotInventedOrHardcoded() {
        val decision = AppLaunch.effect("打开科大物联", listOf(meituan), emptyList()).decision as AppLaunch.Decision.Missing
        assertEquals("科大物联", decision.name)
        assertNull(AppLaunch.effect("打开科大物联", listOf(meituan), emptyList()).save)
    }

    @Test fun catalogReuseTracksInstallRemovalAndRename() {
        val first = AppLaunch.accept(null, listOf(meituan, taobao))
        assertFalse(first.reused)
        val hot = AppLaunch.accept(first, listOf(taobao, meituan))
        assertTrue(hot.reused)
        val removed = AppLaunch.accept(hot, listOf(meituan))
        assertFalse(removed.reused)
        val renamed = AppLaunch.accept(AppLaunch.accept(null, listOf(meituan)), listOf(meituan.copy(label = "美团外卖")))
        assertFalse(renamed.reused)
        assertEquals("美团外卖", renamed.apps.single().label)
    }

    @Test fun preferencesSurviveRestartAndCanBeCleared() {
        val dir = File("build/app-launch-test").apply { deleteRecursively(); mkdirs() }
        val files = AppLaunchFiles(dir)
        files.save(AppLaunch.Preference("买菜", meituan, confirmed = true))
        files.noteUsed(taobao)
        val reloaded = AppLaunchFiles(dir).current()
        assertEquals(1, reloaded.count { it.confirmed })
        assertEquals(1, reloaded.count { !it.confirmed && it.phrase.isEmpty() })
        AppLaunchFiles(dir).removePhrase("买菜")
        assertTrue(AppLaunchFiles(dir).current().none { it.confirmed })
        AppLaunchFiles(dir).clear()
        assertTrue(AppLaunchFiles(dir).current().isEmpty())
        val live = listOf(meituan)
        assertFalse(AppLaunchFiles(dir).load(listOf(meituan.packageName to meituan.label), emptyList()).isEmpty())
        assertEquals(live.map { it.packageName }, AppLaunchFiles(dir).load(listOf(meituan.packageName to meituan.label), emptyList()).filter { it.userId == null }.map { it.packageName })
    }

    @Test fun openWaitsForTheNewScreenAndDoesNotReadAfterFailureOrCancel() = runTest {
        val screen = ReadyScreen("com.sankuai.meituan")
        val tools = toolDispatcher(screen)
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val attempt = AppLaunch.attempt(meituan, AppLaunch.Because.Named, tools, resolveMs = 3, prepare = {
            while (order.none { it == "open-start" }) yield()
            order += "prepare"
            gate.complete(Unit)
        }) {
            order += "open-start"
            gate.await()
            order += "open-end"
            tools.execute(it)
        }
        assertEquals(listOf("open-start", "prepare", "open-end"), order)
        assertTrue(attempt.verified)
        assertEquals(1, attempt.openCalls)
        assertEquals(1, attempt.freshReads)
        assertEquals(1, screen.opened.size)
        assertTrue(attempt.launchMs >= 0)
        assertTrue(attempt.screenReadyMs >= attempt.launchMs)
        assertFalse(screen.listed)

        val failed = ReadyScreen("com.sankuai.meituan", ActionResult.Failed("not_installed"))
        val failedAttempt = AppLaunch.attempt(meituan, AppLaunch.Because.Named, toolDispatcher(failed), 1) { toolDispatcher(failed).execute(it) }
        assertFalse(failedAttempt.opened)
        assertEquals(0, failedAttempt.freshReads)

        val chooser = AppLaunch.attempt(meituan, AppLaunch.Because.Named, tools, 1, run = {
            ToolExecution(buildJsonObject { put("ok", true); put("chooser", JsonPrimitive(true)) })
        })
        assertFalse(chooser.verified)
        assertEquals(0, chooser.freshReads)
        assertEquals("chooser", chooser.error)

        var read = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            try {
                AppLaunch.attempt(meituan, AppLaunch.Because.Named, tools, 1, run = {
                    started.complete(Unit)
                    suspendCancellableCoroutine { }
                })
                read = true
            } catch (_: CancellationException) {
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertFalse(read)
    }

    @Test fun launchArgsCarryTheExactPackageAndCopyUser() {
        val args = AppLaunch.openArgs(clone)
        assertEquals("com.tencent.mm", args["package"]!!.toString().trim('"'))
        assertEquals("10", args["user"]!!.toString())
        assertFalse(args.containsKey("snapshotId"))
    }
}

private class ReadyScreen(
    private val packageName: String,
    private val result: ActionResult = ActionResult.Ok(),
) : ScreenPort by FakeScreen() {
    val opened = mutableListOf<String>()
    var listed = false
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
        opened += packageName
        return result
    }
    override suspend fun listApps(): List<Pair<String, String>> {
        listed = true
        return emptyList()
    }
    override suspend fun snapshot(): ScreenSnapshot {
        val element = UiElement("@e1", "button", "首页", 10, 10, listOf(0, 0, 20, 20), "id", true, false, false)
        return ScreenSnapshot("snap", packageName, listOf(element), 100, 100)
    }
    override suspend fun peek(): ScreenSnapshot = snapshot()
}
