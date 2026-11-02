package com.wangzan101.mika.core.caution

import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.Hant
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiTreeNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/** 一个 App 里自动操作的松紧。 */
enum class AppCautionMode {
    /** 可以打开、滚动、阅读、总结;写操作直接拦住。 */
    ReadOnly,
    /** 写操作每次先问用户。 */
    ConfirmWrites,
    /** 不额外限制。 */
    Normal,
}

/** 会改到账号内容的操作。打开、滚动、阅读不算。 */
enum class SocialWrite {
    Send, Post, Comment, Like, Follow, AddFriend, Delete, Submit;

    fun phrase(): String = when (this) {
        Send -> "发送消息"
        Post -> "发布"
        Comment -> "评论或回复"
        Like -> "点赞"
        Follow -> "关注"
        AddFriend -> "加好友"
        Delete -> "删除"
        Submit -> "提交"
    }
}

data class CautionLimits(
    val hourly: Int,
    val daily: Int,
    /** 两次输入之间至少隔这么久;0 表示不额外等待。 */
    val minDelayMs: Long,
    val maxDelayMs: Long,
    /** 出现风控提示后,这个 App 暂停自动操作多久。 */
    val cooldownMs: Long,
) {
    init {
        require(hourly > 0 && daily > 0)
        require(minDelayMs in 0..maxDelayMs)
        require(cooldownMs >= 0)
    }
}

data class AppCaution(val mode: AppCautionMode, val limits: CautionLimits)

/** 微信、小红书的默认值,以及其他 App 不额外限制时的值。 */
object CautionDefaults {
    const val WECHAT = "com.tencent.mm"
    const val XHS = "com.xingin.xhs"
    val SOCIAL = setOf(WECHAT, XHS)

    val SOCIAL_LIMITS = CautionLimits(
        hourly = 40,
        daily = 160,
        minDelayMs = 800,
        maxDelayMs = 2_500,
        cooldownMs = 24 * 60 * 60 * 1000L,
    )
    val OPEN_LIMITS = CautionLimits(
        hourly = Int.MAX_VALUE / 4,
        daily = Int.MAX_VALUE / 4,
        minDelayMs = 0,
        maxDelayMs = 0,
        cooldownMs = 24 * 60 * 60 * 1000L,
    )

    fun forPackage(packageName: String): AppCaution =
        if (packageName in SOCIAL) AppCaution(AppCautionMode.ReadOnly, SOCIAL_LIMITS)
        else AppCaution(AppCautionMode.Normal, OPEN_LIMITS)

    fun appName(packageName: String): String = when (packageName) {
        WECHAT -> "微信"
        XHS -> "小红书"
        else -> packageName
    }
}

/** 屏幕上的风控、验证提示。长文里顺带出现的词不算,避免把一篇帖子当成验证页。 */
object RiskSignals {
    val PHRASES = listOf(
        "操作过于频繁", "操作频繁", "请稍后再试", "安全验证", "拖动滑块", "滑块验证",
        "人脸验证", "身份验证", "账号异常", "账号已被限制", "账号被限制",
        "too many attempts", "try again later", "security verification", "slide to verify",
        "drag the slider", "face verification", "identity verification",
        "account restricted", "account has been restricted", "unusual activity",
    )
    /** 太短,只有整行就是这个词才算。 */
    private val EXACT = listOf("滑块", "封禁", "违规", "banned", "violation")

    fun find(text: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val folded = line.lowercase()
            EXACT.firstOrNull { folded == it.lowercase() }?.let { return it }
            if (line.length > 80) continue
            PHRASES.firstOrNull { it.lowercase() in folded }?.let { return it }
        }
        return null
    }
}

/** 从按钮名、输入内容判断是不是写操作。搜索框里打字不算。 */
object SocialWrites {
    private val RULES: List<Pair<String, SocialWrite>> = listOf(
        "发送消息" to SocialWrite.Send, "发送" to SocialWrite.Send, "send message" to SocialWrite.Send, "send" to SocialWrite.Send,
        "发布" to SocialWrite.Post, "发表" to SocialWrite.Post, "post" to SocialWrite.Post,
        "评论" to SocialWrite.Comment, "回复" to SocialWrite.Comment, "comment" to SocialWrite.Comment, "reply" to SocialWrite.Comment,
        "点赞" to SocialWrite.Like, "喜欢" to SocialWrite.Like, "like" to SocialWrite.Like, "赞" to SocialWrite.Like,
        "关注" to SocialWrite.Follow, "回关" to SocialWrite.Follow, "follow" to SocialWrite.Follow,
        "添加好友" to SocialWrite.AddFriend, "添加朋友" to SocialWrite.AddFriend, "加好友" to SocialWrite.AddFriend,
        "加朋友" to SocialWrite.AddFriend, "通过验证" to SocialWrite.AddFriend, "add friend" to SocialWrite.AddFriend,
        "删除" to SocialWrite.Delete, "撤回" to SocialWrite.Delete, "delete" to SocialWrite.Delete,
        "确认提交" to SocialWrite.Submit, "提交" to SocialWrite.Submit, "submit" to SocialWrite.Submit,
    )

    fun isInput(verb: Verb?): Boolean = verb in INPUT

    fun classify(verb: Verb?, label: String?, typed: String?, searchField: Boolean, pressEnter: Boolean): SocialWrite? {
        if (verb == Verb.TypeText) {
            if (searchField) return null
            if (pressEnter || !typed.isNullOrBlank()) return SocialWrite.Send
            return null
        }
        if (verb != Verb.Tap && verb != Verb.Swipe && verb != Verb.PressKey && verb != Verb.Interact) return null
        if (verb == Verb.PressKey && pressEnter && !searchField) return SocialWrite.Send
        return match(label)
    }

    fun match(label: String?): SocialWrite? {
        val text = Hant.fold(label?.trim().orEmpty()).lowercase()
        if (text.isEmpty() || text.length > 16) return null
        val bare = text.trimEnd { it.isDigit() || it in "()（）[]【】·. " }.trim()
        return RULES.firstOrNull { (word, _) ->
            val w = word.lowercase()
            bare == w || (w.length >= 2 && bare.startsWith(w) && bare.length <= w.length + 4)
        }?.second
    }

    private val INPUT = setOf(Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.Scroll, Verb.Swipe, Verb.PressKey, Verb.Back, Verb.Interact)
}

sealed interface CautionVerdict {
    data object Allow : CautionVerdict
    /** 这一步不做,任务可以继续读。 */
    data class Blocked(val message: String, val write: SocialWrite) : CautionVerdict
    /** 先问用户;同意之后才能做这一步。 */
    data class Ask(val write: SocialWrite, val summary: String) : CautionVerdict
    /** 停掉整个任务。 */
    data class Stop(val message: String, val takeover: Boolean, val packageName: String) : CautionVerdict
}

/** 次数、冷却、间隔和屏幕文字。纯逻辑,钟和随机数由外面给。 */
class AppCautionRuntime(
    private val config: (String) -> AppCaution = CautionDefaults::forPackage,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    initialPauses: Map<String, Long> = emptyMap(),
    initialLog: List<AppActionEntry> = emptyList(),
    private val onChange: () -> Unit = {},
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val pauses = HashMap<String, Long>().apply { putAll(initialPauses) }
    private val lastInputAt = HashMap<String, Long>()
    private val entries = ArrayDeque<AppActionEntry>().apply { initialLog.takeLast(LOG_MAX).forEach { addLast(it) } }
    @Volatile private var ocr: Pair<String?, String> = null to ""

    fun settings(packageName: String): AppCaution = config(packageName)

    fun pauses(): Map<String, Long> = pauses.toMap()

    fun log(): List<AppActionEntry> = entries.toList()

    /** 读屏时的 OCR 文本,和这一屏的无障碍文字一起看。 */
    fun noteOcr(packageName: String?, text: String) {
        ocr = packageName to text
    }

    fun assess(
        packageName: String?,
        verb: Verb?,
        label: String?,
        typed: String?,
        searchField: Boolean,
        pressEnter: Boolean,
        screen: ScreenSnapshot?,
    ): CautionVerdict {
        val pkg = packageName ?: screen?.packageName ?: return CautionVerdict.Allow
        val policy = config(pkg)
        val until = pauses[pkg]
        if (until != null && now() < until) {
            return CautionVerdict.Stop(Messages.cooldown(pkg, until), takeover = false, packageName = pkg)
        }
        if (until != null) pauses.remove(pkg).also { onChange() }
        val signal = RiskSignals.find(visibleText(pkg, screen))
        if (signal != null) {
            pause(pkg, policy.limits.cooldownMs)
            return CautionVerdict.Stop(Messages.risk(pkg, signal, policy.limits.cooldownMs), takeover = true, packageName = pkg)
        }
        if (SocialWrites.isInput(verb) && quotaExceeded(pkg, policy.limits)) {
            return CautionVerdict.Stop(Messages.quota(pkg, policy.limits), takeover = false, packageName = pkg)
        }
        val write = SocialWrites.classify(verb, label, typed, searchField, pressEnter) ?: return CautionVerdict.Allow
        return when (policy.mode) {
            AppCautionMode.Normal -> CautionVerdict.Allow
            AppCautionMode.ReadOnly -> CautionVerdict.Blocked(Messages.readOnly(pkg, write), write)
            AppCautionMode.ConfirmWrites -> CautionVerdict.Ask(write, write.phrase() + (label?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""))
        }
    }

    /** 这一步真要做之前还要等多久。第一次不等。 */
    fun delayBefore(packageName: String?, verb: Verb?): Long {
        if (!SocialWrites.isInput(verb) || packageName == null) return 0
        val limits = config(packageName).limits
        if (limits.maxDelayMs <= 0) return 0
        val last = lastInputAt[packageName] ?: return 0
        val gap = if (limits.minDelayMs >= limits.maxDelayMs) limits.maxDelayMs
        else random.nextLong(limits.minDelayMs, limits.maxDelayMs + 1)
        return (gap - (now() - last)).coerceAtLeast(0)
    }

    /** 只记进操作记录,不计入次数(拦住的写操作)。 */
    fun note(packageName: String?, summary: String) {
        val pkg = packageName ?: return
        entries.addLast(AppActionEntry(now(), pkg, summary))
        while (entries.size > LOG_MAX) entries.removeFirst()
        onChange()
    }

    fun record(packageName: String?, summary: String, verb: Verb?) {
        val pkg = packageName ?: return
        if (SocialWrites.isInput(verb)) {
            val at = now()
            hits.getOrPut(pkg) { ArrayDeque() }.addLast(at)
            lastInputAt[pkg] = at
            prune(pkg, at)
        }
        entries.addLast(AppActionEntry(now(), pkg, summary))
        while (entries.size > LOG_MAX) entries.removeFirst()
        onChange()
    }

    fun pause(packageName: String, cooldownMs: Long) {
        pauses[packageName] = now() + cooldownMs
        onChange()
    }

    private fun quotaExceeded(pkg: String, limits: CautionLimits): Boolean {
        val at = now()
        prune(pkg, at)
        val stamps = hits[pkg] ?: return false
        val hour = stamps.count { at - it < HOUR }
        val day = stamps.size
        return hour >= limits.hourly || day >= limits.daily
    }

    private fun prune(pkg: String, at: Long) {
        val stamps = hits[pkg] ?: return
        while (stamps.isNotEmpty() && at - stamps.first() >= DAY) stamps.removeFirst()
    }

    private fun visibleText(pkg: String, screen: ScreenSnapshot?): String = buildString {
        screen?.let { append(ScreenText.of(it)) }
        val (ocrPkg, text) = ocr
        if (text.isNotBlank() && (ocrPkg == null || ocrPkg == pkg)) {
            if (isNotEmpty()) append('\n')
            append(text)
        }
    }

    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
        const val LOG_MAX = 300
    }
}

data class AppActionEntry(val atMs: Long, val packageName: String, val summary: String)

object Messages {
    fun readOnly(pkg: String, write: SocialWrite): String =
        "「${CautionDefaults.appName(pkg)}」当前是只读，这一步（${write.phrase()}）没有执行。" +
            "只读时可以打开、滚动、阅读和总结，不能发送、发布、评论、点赞、关注、加好友、删除或提交。" +
            "可以在设置 › 账号保护里改成「每次确认」或「正常」。"

    fun declined(pkg: String, write: SocialWrite): String =
        "你没有同意在「${CautionDefaults.appName(pkg)}」里${write.phrase()}，这一步没有执行。"

    fun needsSomeone(pkg: String, write: SocialWrite): String =
        "「${CautionDefaults.appName(pkg)}」里的${write.phrase()}需要你先确认。这次没有人可以确认，所以没有执行。"

    fun quota(pkg: String, limits: CautionLimits): String =
        "「${CautionDefaults.appName(pkg)}」的操作次数到了上限（每小时 ${limits.hourly} 次，每天 ${limits.daily} 次），任务已停下。" +
            "可以在设置 › 账号保护里调高上限，或等计数过去再继续。"

    fun risk(pkg: String, phrase: String, cooldownMs: Long): String =
        "「${CautionDefaults.appName(pkg)}」的屏幕上出现了「$phrase」。已停止操作，不会去通过验证。请你自己处理这个页面。" +
            "这个 App 的自动操作暂停 ${cooldownMs / AppCautionRuntime.HOUR} 小时。"

    fun cooldown(pkg: String, untilMs: Long): String =
        "「${CautionDefaults.appName(pkg)}」的自动操作还在暂停中。这是上次出现安全验证之后的冷却，这次不会继续操作。"
}

/** 无障碍树上能看见的字,一行一段。 */
object ScreenText {
    fun of(screen: ScreenSnapshot): String = buildString {
        fun line(s: String?) {
            val t = s?.trim().orEmpty()
            if (t.isNotEmpty()) append(t).append('\n')
        }
        screen.elements.forEach {
            line(it.name)
            line(it.placeholder)
        }
        screen.windows.forEach { window ->
            line(window.title)
            window.nodes.forEach { walk(it, ::line) }
        }
    }

    private fun walk(node: UiTreeNode, line: (String?) -> Unit) {
        line(node.text)
        line(node.description)
        line(node.hintText)
        node.children.forEach { walk(it, line) }
    }
}

/** 这一步点的或正在输入的控件。 */
object CautionTarget {
    fun label(screen: ScreenSnapshot?, args: JsonObject): String? {
        val ref = arg(args, "ref")
        val byRef = ref?.let { id -> screen?.elements?.firstOrNull { it.ref == id } }
        val at = if (byRef == null) point(screen, args) else null
        return (byRef ?: at)?.let { it.name.ifBlank { it.placeholder } }
    }

    fun searchField(screen: ScreenSnapshot?, args: JsonObject): Boolean {
        val el = element(screen, args) ?: focused(screen)
        if (el == null) return false
        if (el.searchField) return true
        val text = Hant.fold("${el.name} ${el.placeholder.orEmpty()} ${el.resourceId.orEmpty()}").lowercase()
        return "搜索" in text || "搜寻" in text || "search" in text
    }

    fun element(screen: ScreenSnapshot?, args: JsonObject): UiElement? {
        val ref = arg(args, "ref")
        return ref?.let { id -> screen?.elements?.firstOrNull { it.ref == id } } ?: point(screen, args)
    }

    private fun focused(screen: ScreenSnapshot?): UiElement? = screen?.elements?.firstOrNull { it.focused && it.editable }

    private fun point(screen: ScreenSnapshot?, args: JsonObject): UiElement? {
        val x = arg(args, "x")?.toIntOrNull() ?: return null
        val y = arg(args, "y")?.toIntOrNull() ?: return null
        return screen?.elements?.filter { it.clickable && inside(it.bounds, x, y) }?.minByOrNull { area(it.bounds) }
    }

    private fun inside(bounds: List<Int>, x: Int, y: Int): Boolean {
        if (bounds.size < 4) return false
        return x in bounds[0]..bounds[2] && y in bounds[1]..bounds[3]
    }

    private fun area(bounds: List<Int>): Int =
        if (bounds.size < 4) Int.MAX_VALUE else (bounds[2] - bounds[0]).coerceAtLeast(0) * (bounds[3] - bounds[1]).coerceAtLeast(0)

    private fun arg(args: JsonObject, key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
}

/** 读屏拿到 OCR 之后交给当前的保护逻辑。没有装上时什么也不做。 */
object CautionScreenText {
    @Volatile var sink: ((String?, String) -> Unit)? = null
}
