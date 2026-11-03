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
    /** 不额外限制写操作。次数、间隔和验证暂停仍由「降低风控」总开关决定。 */
    Normal,
}

/** 会改到账号或交易的操作。打开、滚动、阅读不算。 */
enum class SocialWrite {
    Send, Post, Comment, Like, Follow, AddFriend, Delete, Submit, Order, Pay, Refresh, Price;

    fun phrase(): String = when (this) {
        Send -> "发送消息"
        Post -> "发布"
        Comment -> "评论或回复"
        Like -> "点赞"
        Follow -> "关注"
        AddFriend -> "加好友"
        Delete -> "删除"
        Submit -> "提交"
        Order -> "下单或结算"
        Pay -> "付款"
        Refresh -> "擦亮"
        Price -> "修改价格"
    }
}

/** 购物和低浏览 App 里单独计数的动作。 */
enum class BrowseKind {
    Search, PageTurn, Detail;

    fun phrase(): String = when (this) {
        Search -> "搜索"
        PageTurn -> "翻页"
        Detail -> "打开详情"
    }
}

data class CautionLimits(
    val hourly: Int,
    val daily: Int,
    /** 两次自动操作之间至少隔这么久;0 表示不额外等待。 */
    val minDelayMs: Long,
    val maxDelayMs: Long,
    /** 出现风控提示后,这个 App 暂停自动操作多久。 */
    val cooldownMs: Long,
    val searchHourly: Int = UNLIMITED,
    val searchDaily: Int = UNLIMITED,
    val pageHourly: Int = UNLIMITED,
    val pageDaily: Int = UNLIMITED,
    val detailHourly: Int = UNLIMITED,
    val detailDaily: Int = UNLIMITED,
) {
    init {
        require(hourly > 0 && daily > 0)
        require(minDelayMs in 0..maxDelayMs)
        require(cooldownMs >= 0)
        require(searchHourly > 0 && searchDaily > 0 && pageHourly > 0 && pageDaily > 0 && detailHourly > 0 && detailDaily > 0)
    }

    fun capped(kind: BrowseKind): Pair<Int, Int> = when (kind) {
        BrowseKind.Search -> searchHourly to searchDaily
        BrowseKind.PageTurn -> pageHourly to pageDaily
        BrowseKind.Detail -> detailHourly to detailDaily
    }

    companion object {
        /** 没有单独上限时用这个数,正常使用到不了。 */
        const val UNLIMITED = 1_000_000
    }
}

data class AppCaution(val mode: AppCautionMode, val limits: CautionLimits)

/**
 * 降低风控的默认值。间隔每次在最小和最大之间另抽一次,不按固定秒数重复。
 * 微信、小红书、淘宝、闲鱼、京东默认每次确认;购物 App 另限搜索、翻页、打开详情,小红书浏览更少。
 */
object CautionDefaults {
    const val WECHAT = "com.tencent.mm"
    const val XHS = "com.xingin.xhs"
    const val TAOBAO = "com.taobao.taobao"
    const val XIANYU = "com.taobao.idlefish"
    const val JD = "com.jingdong.app.mall"

    val GUARDED = setOf(WECHAT, XHS, TAOBAO, XIANYU, JD)
    /** 搜索、翻页、打开详情要单独计数的 App。 */
    val BROWSE = setOf(XHS, TAOBAO, XIANYU, JD)
    /** 旧名字,设置页用来排除「其他应用」列表。 */
    val SOCIAL = GUARDED

    const val DELAY_MIN_MS = 3_000L
    const val DELAY_MAX_MS = 15_000L
    const val COOLDOWN_MS = 24 * 60 * 60 * 1000L
    /** 用户把间隔调得再长,一次也不超过一分钟,免得任务一直睡。 */
    const val MAX_DELAY_MS = 60_000L

    val WECHAT_LIMITS = paced(hourly = 80, daily = 200)
    val XHS_LIMITS = paced(
        hourly = 24, daily = 60,
        searchHourly = 5, searchDaily = 12,
        pageHourly = 12, pageDaily = 30,
        detailHourly = 8, detailDaily = 20,
    )
    val SHOP_LIMITS = paced(
        hourly = 30, daily = 80,
        searchHourly = 6, searchDaily = 16,
        pageHourly = 10, pageDaily = 24,
        detailHourly = 8, detailDaily = 20,
    )
    val OPEN_LIMITS = paced(hourly = 60, daily = 180)

    fun forPackage(packageName: String): AppCaution = when (packageName) {
        WECHAT -> AppCaution(AppCautionMode.ConfirmWrites, WECHAT_LIMITS)
        XHS -> AppCaution(AppCautionMode.ConfirmWrites, XHS_LIMITS)
        TAOBAO, XIANYU, JD -> AppCaution(AppCautionMode.ConfirmWrites, SHOP_LIMITS)
        else -> AppCaution(AppCautionMode.Normal, OPEN_LIMITS)
    }

    fun appName(packageName: String): String = when (packageName) {
        WECHAT -> "微信"
        XHS -> "小红书"
        TAOBAO -> "淘宝"
        XIANYU -> "闲鱼"
        JD -> "京东"
        else -> packageName
    }

    private fun paced(
        hourly: Int,
        daily: Int,
        searchHourly: Int = CautionLimits.UNLIMITED,
        searchDaily: Int = CautionLimits.UNLIMITED,
        pageHourly: Int = CautionLimits.UNLIMITED,
        pageDaily: Int = CautionLimits.UNLIMITED,
        detailHourly: Int = CautionLimits.UNLIMITED,
        detailDaily: Int = CautionLimits.UNLIMITED,
    ) = CautionLimits(
        hourly, daily, DELAY_MIN_MS, DELAY_MAX_MS, COOLDOWN_MS,
        searchHourly, searchDaily, pageHourly, pageDaily, detailHourly, detailDaily,
    )
}

/** 屏幕上的风控、验证提示。长文里顺带出现的词不算,避免把一篇帖子当成验证页。只认出提示,不去解。 */
object RiskSignals {
    val PHRASES = listOf(
        "操作过于频繁", "操作频繁", "请稍后再试", "安全验证", "拖动滑块", "滑块验证", "滑动滑块",
        "向右滑动滑块", "请拖动滑块", "拼图验证", "拖动滑块完成", "请按住滑块", "人脸验证", "人脸识别",
        "刷脸验证", "刷脸支付", "身份验证", "账号异常", "账号已被限制", "账号被限制",
        "too many attempts", "try again later", "security verification", "slide to verify",
        "drag the slider", "slider verification", "face verification", "identity verification",
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

/** 从按钮名、输入内容判断是不是要确认的写操作。搜索框里打字不算发送。 */
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
        "确认提交" to SocialWrite.Submit, "提交订单" to SocialWrite.Order, "提交" to SocialWrite.Submit, "submit" to SocialWrite.Submit,
        "立即购买" to SocialWrite.Order, "领券购买" to SocialWrite.Order, "马上抢" to SocialWrite.Order,
        "去结算" to SocialWrite.Order, "提交订单" to SocialWrite.Order, "立即下单" to SocialWrite.Order,
        "下单" to SocialWrite.Order, "结算" to SocialWrite.Order, "购买" to SocialWrite.Order,
        "buy now" to SocialWrite.Order, "checkout" to SocialWrite.Order, "place order" to SocialWrite.Order,
        "确认支付" to SocialWrite.Pay, "立即支付" to SocialWrite.Pay, "去支付" to SocialWrite.Pay,
        "确认付款" to SocialWrite.Pay, "去付款" to SocialWrite.Pay, "付款" to SocialWrite.Pay, "支付" to SocialWrite.Pay,
        "pay now" to SocialWrite.Pay, "pay" to SocialWrite.Pay,
        "一键擦亮" to SocialWrite.Refresh, "擦亮" to SocialWrite.Refresh,
        "修改价格" to SocialWrite.Price, "编辑价格" to SocialWrite.Price, "改价" to SocialWrite.Price,
        "降价" to SocialWrite.Price, "调价" to SocialWrite.Price,
    )

    fun isInput(verb: Verb?): Boolean = verb in STEPS

    fun classify(verb: Verb?, label: String?, typed: String?, searchField: Boolean, pressEnter: Boolean): SocialWrite? {
        if (verb == Verb.TypeText) {
            if (searchField) return null
            if (!pressEnter) return null
            return if (looksLikeComment(label)) SocialWrite.Comment else SocialWrite.Send
        }
        if (verb != Verb.Tap && verb != Verb.Swipe && verb != Verb.PressKey && verb != Verb.Interact) return null
        if (verb == Verb.PressKey && pressEnter && !searchField) {
            return if (looksLikeComment(label)) SocialWrite.Comment else SocialWrite.Send
        }
        return match(label)
    }

    fun match(label: String?): SocialWrite? {
        val text = Hant.fold(label?.trim().orEmpty()).lowercase()
        if (text.isEmpty() || text.length > 16) return null
        if (text.startsWith("支付宝") || text == "微信支付" || text == "支付方式" || text == "价格" || text == "销量" || text == "综合") return null
        val bare = text.trimEnd { it.isDigit() || it in "()（）[]【】·. " }.trim()
        return RULES.firstOrNull { (word, _) ->
            val w = word.lowercase()
            bare == w || (w.length >= 2 && bare.startsWith(w) && bare.length <= w.length + 6)
        }?.second
    }

    private fun looksLikeComment(label: String?): Boolean {
        val text = Hant.fold(label?.trim().orEmpty())
        return "评论" in text || "回复" in text
    }

    private val STEPS = setOf(
        Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.Scroll, Verb.Swipe, Verb.PressKey, Verb.Back, Verb.Interact,
        Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.PhoneGesture, Verb.Navigate,
    )
}

/** 淘宝、闲鱼、京东和小红书上的搜索、翻页、打开详情。只按按钮和动作认,不改手势。 */
object BrowseActions {
    private val SEARCH = listOf("搜索", "搜一搜", "search")
    private val PAGE = listOf("下一页", "上一页", "加载更多", "查看更多", "更多宝贝")
    private val CHROME = setOf(
        "首页", "推荐", "分类", "购物车", "消息", "我的", "返回", "关闭", "取消", "筛选", "综合", "销量", "价格",
        "发现", "视频", "市集", "店铺", "客服", "分享", "更多", "back", "home", "cart", "搜索",
    )

    fun classify(packageName: String, verb: Verb?, label: String?, searchField: Boolean, pressEnter: Boolean): BrowseKind? {
        if (packageName !in CautionDefaults.BROWSE) return null
        if (verb == Verb.Scroll || verb == Verb.Swipe) return BrowseKind.PageTurn
        if (searchField && (verb == Verb.TypeText || (pressEnter && (verb == Verb.PressKey || verb == Verb.TypeText)))) return BrowseKind.Search
        if (verb != Verb.Tap && verb != Verb.Interact) return null
        val text = Hant.fold(label?.trim().orEmpty()).lowercase()
        if (text.isEmpty()) return null
        if (SEARCH.any { it == text || (it in text && text.length <= 8) }) return BrowseKind.Search
        if (text in CHROME) return null
        if (PAGE.any { it in text }) return BrowseKind.PageTurn
        val priced = "¥" in text || "￥" in text || text.any { it.isDigit() }
        if (priced || text.length >= 4) return BrowseKind.Detail
        return null
    }
}

sealed interface CautionVerdict {
    data object Allow : CautionVerdict
    /** 这一步不做,任务可以继续读。 */
    data class Blocked(val message: String, val write: SocialWrite) : CautionVerdict
    /** 先问用户;同意之后才能做这一步。 */
    data class Ask(val write: SocialWrite, val summary: String) : CautionVerdict
    /** 停掉整个任务,并通知用户。 */
    data class Stop(val message: String, val takeover: Boolean, val packageName: String) : CautionVerdict
}

/**
 * 次数、冷却、间隔和屏幕文字。纯逻辑,钟和随机数由外面给。
 * [enabled] 为假时不加这些限制(设置里的「降低风控」关掉)。
 * 间隔在最小和最大之间每次另抽,不按固定节拍。
 */
class AppCautionRuntime(
    private val config: (String) -> AppCaution = CautionDefaults::forPackage,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    initialPauses: Map<String, Long> = emptyMap(),
    initialLog: List<AppActionEntry> = emptyList(),
    private val onChange: () -> Unit = {},
    private val enabled: () -> Boolean = { true },
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val browseHits = HashMap<String, HashMap<BrowseKind, ArrayDeque<Long>>>()
    private val pauses = HashMap<String, Long>().apply { putAll(initialPauses) }
    private val lastStepAt = HashMap<String, Long>()
    private val pendingBrowse = HashMap<String, BrowseKind>()
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
        if (!enabled()) return CautionVerdict.Allow
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
        val browse = if (SocialWrites.classify(verb, label, typed, searchField, pressEnter) == null) {
            BrowseActions.classify(pkg, verb, label, searchField, pressEnter)
        } else null
        if (browse != null && browseExceeded(pkg, browse, policy.limits)) {
            return CautionVerdict.Stop(Messages.browse(pkg, browse, policy.limits), takeover = false, packageName = pkg)
        }
        if (SocialWrites.isInput(verb) && quotaExceeded(pkg, policy.limits)) {
            return CautionVerdict.Stop(Messages.quota(pkg, policy.limits), takeover = false, packageName = pkg)
        }
        val write = SocialWrites.classify(verb, label, typed, searchField, pressEnter)
        val verdict = when {
            write == null -> CautionVerdict.Allow
            policy.mode == AppCautionMode.Normal -> CautionVerdict.Allow
            policy.mode == AppCautionMode.ReadOnly -> CautionVerdict.Blocked(Messages.readOnly(pkg, write), write)
            else -> CautionVerdict.Ask(write, write.phrase() + (label?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""))
        }
        if (verdict is CautionVerdict.Allow && browse != null) pendingBrowse[pkg] = browse else pendingBrowse.remove(pkg)
        return verdict
    }

    /**
     * 这一步真要做之前还要等多久。第一次不等。
     * 等待时间在这个 App 的最小和最大间隔之间随机取,已经过去的时间算进去,只补不够的部分。
     */
    fun delayBefore(packageName: String?, verb: Verb?): Long {
        if (!enabled() || !SocialWrites.isInput(verb) || packageName == null) return 0
        val limits = config(packageName).limits
        if (limits.maxDelayMs <= 0) return 0
        val last = lastStepAt[packageName] ?: return 0
        val gap = gapMs(limits)
        return (gap - (now() - last)).coerceAtLeast(0)
    }

    /** 只记进操作记录,不计入次数(拦住的写操作)。 */
    fun note(packageName: String?, summary: String) {
        if (!enabled()) return
        val pkg = packageName ?: return
        entries.addLast(AppActionEntry(now(), pkg, summary))
        while (entries.size > LOG_MAX) entries.removeFirst()
        onChange()
    }

    fun record(packageName: String?, summary: String, verb: Verb?) {
        if (!enabled()) return
        val pkg = packageName ?: return
        if (SocialWrites.isInput(verb)) {
            val at = now()
            hits.getOrPut(pkg) { ArrayDeque() }.addLast(at)
            lastStepAt[pkg] = at
            prune(hits.getValue(pkg), at)
            pendingBrowse.remove(pkg)?.let { kind ->
                val stamps = browseHits.getOrPut(pkg) { HashMap() }.getOrPut(kind) { ArrayDeque() }
                stamps.addLast(at)
                prune(stamps, at)
            }
        }
        entries.addLast(AppActionEntry(now(), pkg, summary))
        while (entries.size > LOG_MAX) entries.removeFirst()
        onChange()
    }

    fun pause(packageName: String, cooldownMs: Long) {
        pauses[packageName] = now() + cooldownMs
        onChange()
    }

    /** 这一次抽到的间隔。最小和最大相同才是固定值;默认两者不同,所以没有固定节拍。 */
    internal fun gapMs(limits: CautionLimits): Long {
        val lo = limits.minDelayMs.coerceIn(0, CautionDefaults.MAX_DELAY_MS)
        val hi = limits.maxDelayMs.coerceIn(lo, CautionDefaults.MAX_DELAY_MS)
        return if (lo >= hi) hi else random.nextLong(lo, hi + 1)
    }

    private fun quotaExceeded(pkg: String, limits: CautionLimits): Boolean {
        val at = now()
        val stamps = hits[pkg] ?: return false
        prune(stamps, at)
        val hour = stamps.count { at - it < HOUR }
        return hour >= limits.hourly || stamps.size >= limits.daily
    }

    private fun browseExceeded(pkg: String, kind: BrowseKind, limits: CautionLimits): Boolean {
        val (hourly, daily) = limits.capped(kind)
        if (hourly >= CautionLimits.UNLIMITED && daily >= CautionLimits.UNLIMITED) return false
        val at = now()
        val stamps = browseHits[pkg]?.get(kind) ?: return false
        prune(stamps, at)
        val hour = stamps.count { at - it < HOUR }
        return hour >= hourly || stamps.size >= daily
    }

    private fun prune(stamps: ArrayDeque<Long>, at: Long) {
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
            "只读时可以打开、滚动、阅读和总结。发送、发布、评论、点赞、关注、下单、付款、擦亮和改价都不会做。" +
            "可以在设置 › 降低风控里改成「每次确认」或「正常」。"

    fun declined(pkg: String, write: SocialWrite): String =
        "你没有同意在「${CautionDefaults.appName(pkg)}」里${write.phrase()}，这一步没有执行。"

    fun needsSomeone(pkg: String, write: SocialWrite): String =
        "「${CautionDefaults.appName(pkg)}」里的${write.phrase()}需要你先确认。这次没有人可以确认，所以没有执行。"

    fun quota(pkg: String, limits: CautionLimits): String =
        "「${CautionDefaults.appName(pkg)}」的操作次数到了上限（每小时 ${limits.hourly} 次，每天 ${limits.daily} 次），任务已停下。" +
            "可以在设置 › 降低风控里调高上限，或等计数过去再继续。"

    fun browse(pkg: String, kind: BrowseKind, limits: CautionLimits): String {
        val (hourly, daily) = limits.capped(kind)
        return "「${CautionDefaults.appName(pkg)}」的${kind.phrase()}次数到了上限（每小时 $hourly 次，每天 $daily 次），任务已停下。" +
            "可以在设置 › 降低风控里调高，或等计数过去再继续。"
    }

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
