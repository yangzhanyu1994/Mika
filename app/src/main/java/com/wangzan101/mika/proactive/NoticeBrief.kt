package com.wangzan101.mika.proactive

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Local notification summaries. Windows are half-open [start, end) in the device zone.
 *
 * If the end minute is greater than the start minute, both fall on the same local date
 * as the chosen end. If the end minute is less than or equal to the start minute, the
 * window crosses midnight: it starts on the previous local date.
 *
 * The reminder clock is independent. Each enabled slot fires once per local day at its
 * reminder time and summarises the window whose end is the latest configured end at or
 * before that instant.
 *
 * Notification text is untrusted. A summary only displays it. It never becomes a command.
 * Verification codes are omitted from stored text and from logs.
 */
enum class BriefSlot { Morning, Afternoon, AllDay }

data class BriefSpec(
    val enabled: Boolean = false,
    val remindMinute: Int = 8 * 60 + 30,
    val fromMinute: Int = 0,
    val toMinute: Int = 8 * 60 + 30,
) {
    fun normalized(): BriefSpec = copy(
        remindMinute = remindMinute.mod(24 * 60),
        fromMinute = fromMinute.mod(24 * 60),
        toMinute = toMinute.mod(24 * 60),
    )
}

data class BriefItem(
    val id: String,
    val packageName: String,
    val atMs: Long,
    val title: String,
    val text: String,
    val otp: Boolean = false,
)

data class BriefResult(
    val slot: BriefSlot,
    val title: String,
    val body: String,
    val sources: List<String>,
    val synthetic: Boolean,
    val empty: Boolean,
    val blockedReason: String? = null,
)

object NoticeBrief {
    val DEFAULTS = mapOf(
        BriefSlot.Morning to BriefSpec(remindMinute = 8 * 60 + 30, fromMinute = 0, toMinute = 8 * 60 + 30),
        BriefSlot.Afternoon to BriefSpec(remindMinute = 18 * 60, fromMinute = 12 * 60, toMinute = 18 * 60),
        BriefSlot.AllDay to BriefSpec(remindMinute = 21 * 60 + 30, fromMinute = 0, toMinute = 21 * 60 + 30),
    )

    private val otp = Regex("(?i)(验证码|校验码|動態密碼|动态密码|one[- ]?time|otp|verification code)")

    fun redact(title: String, text: String): Pair<String, Boolean> {
        val raw = "$title\n$text"
        if (!otp.containsMatchIn(raw) && !Regex("(?<!\\d)\\d{4,8}(?!\\d)").containsMatchIn(text)) return text.trim() to false
        if (!otp.containsMatchIn(raw)) return text.trim() to false
        return "" to true
    }

    fun idOf(packageName: String, title: String, text: String, atMs: Long, otpItem: Boolean): String {
        val bucket = atMs / 300_000L
        val body = if (otpItem) "otp" else text.hashCode().toUInt().toString(16)
        return "$packageName|$title|$body|$bucket"
    }

    /** Latest window end at or before [fire], then the matching start. */
    fun window(fire: ZonedDateTime, spec: BriefSpec): Pair<ZonedDateTime, ZonedDateTime> {
        val s = spec.normalized()
        val endClock = LocalTime.of(s.toMinute / 60, s.toMinute % 60)
        val endToday = fire.toLocalDate().atTime(endClock).atZone(fire.zone)
        val end = if (!endToday.isAfter(fire)) endToday else endToday.minusDays(1)
        val startDate: LocalDate = if (s.fromMinute < s.toMinute) end.toLocalDate() else end.toLocalDate().minusDays(1)
        val start = startDate.atTime(LocalTime.of(s.fromMinute / 60, s.fromMinute % 60)).atZone(fire.zone)
        return start to end
    }

    fun nextFire(now: ZonedDateTime, spec: BriefSpec): ZonedDateTime {
        val s = spec.normalized()
        val clock = LocalTime.of(s.remindMinute / 60, s.remindMinute % 60)
        val today = now.toLocalDate().atTime(clock).atZone(now.zone)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun crossesMidnight(spec: BriefSpec): Boolean {
        val s = spec.normalized()
        return s.fromMinute >= s.toMinute
    }

    fun summarize(
        slot: BriefSlot,
        items: List<BriefItem>,
        selfPackage: String,
        listenerGranted: Boolean,
        canPost: Boolean,
        synthetic: Boolean,
        labelOf: (String) -> String = { it },
    ): BriefResult {
        val title = when (slot) {
            BriefSlot.Morning -> "上午总结"
            BriefSlot.Afternoon -> "下午总结"
            BriefSlot.AllDay -> "全天总结"
        }.let { if (synthetic) "[合成测试] $it" else it }
        if (!canPost) return BriefResult(slot, title, "没有发通知权限，这次没有弹出提醒。", emptyList(), synthetic, true, "post")
        if (!listenerGranted) return BriefResult(slot, title, "没有通知使用权，这一时段没有采集。", emptyList(), synthetic, true, "listen")
        val usable = items.filter { it.packageName != selfPackage && (it.text.isNotBlank() || it.otp) }
        if (usable.isEmpty()) return BriefResult(slot, title, "这一时段没有记下需要汇总的通知。", emptyList(), synthetic, true, null)
        val ranked = usable.sortedByDescending { score(it) }.take(5)
        val lines = ranked.joinToString("\n") { item ->
            val who = labelOf(item.packageName)
            val said = if (item.otp) "有一条验证码通知，数字未写入" else item.text.lineSequence().firstOrNull().orEmpty().take(80)
            "· $who：${item.title.take(24)} $said".trim()
        }
        val sources = ranked.map { labelOf(it.packageName) }.distinct()
        val body = lines + "\n来源：" + sources.joinToString("、") + "\n打开只查看，不会代替你操作。"
        return BriefResult(slot, title, body, sources, synthetic, false, null)
    }

    private fun score(item: BriefItem): Int {
        var s = 1
        if (item.otp) s += 3
        if (item.text.length > 12) s += 1
        return s
    }

    fun accept(selfPackage: String, packageName: String, title: String, text: String, atMs: Long): BriefItem? {
        if (packageName == selfPackage || packageName.isBlank()) return null
        val (safe, otpItem) = redact(title, text)
        if (!otpItem && safe.isBlank() && title.isBlank()) return null
        return BriefItem(idOf(packageName, title, safe, atMs, otpItem), packageName, atMs, title.take(80), safe.take(240), otpItem)
    }
}

/** In-memory deduped log used by the scheduler and by tests. */
class NoticeBriefLog {
    private val items = LinkedHashMap<String, BriefItem>()

    fun add(item: BriefItem): Boolean {
        if (item.id in items) return false
        items[item.id] = item
        while (items.size > 400) items.remove(items.keys.first())
        return true
    }

    fun between(startMs: Long, endMs: Long): List<BriefItem> =
        items.values.filter { it.atMs in startMs until endMs }.sortedBy { it.atMs }

    fun clear() = items.clear()
}
