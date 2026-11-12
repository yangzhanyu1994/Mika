package com.wangzan101.mika.calls.tasks

import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.calls.PhoneNumbers
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.ZonedDateTime

/** A saved AI call task. Incoming tasks are instructions; dial tasks place an outgoing call. */
@Serializable
data class CallAssistantTask(
    val id: String,
    val kind: String,
    val title: String,
    val target: String = "",
    val windowLabel: String = "",
    val startMs: Long? = null,
    val endMs: Long? = null,
    val say: String,
    val cannotPromise: String = "",
    val number: String = "",
    val dialed: Boolean = false,
    val createdAtMs: Long = 0L,
) {
    fun statusAt(now: Long): CallTaskStatus = when (kind) {
        KIND_DIAL -> if (dialed) CallTaskStatus.DONE else CallTaskStatus.PENDING
        else -> when {
            endMs != null && now >= endMs -> CallTaskStatus.DONE
            startMs != null && now < startMs -> CallTaskStatus.LATER
            else -> CallTaskStatus.ACTIVE
        }
    }

    fun appliesToCaller(caller: String?): Boolean {
        val asNumber = CallPolicy.incomingNumber(target) ?: CallPolicy.incomingNumber(number)
        if (asNumber == null) return true
        return caller != null && PhoneNumbers.same(asNumber, caller)
    }
}

enum class CallTaskStatus { ACTIVE, PENDING, DONE, LATER }

enum class CallTaskKind { DIAL, INCOMING, DEFAULT }

@Serializable
data class ParsedCallTask(
    val kind: String,
    val title: String,
    val target: String = "",
    val windowLabel: String = "",
    val say: String,
    val cannotPromise: String = "",
    val number: String = "",
)

object CallTaskTexts {
    const val KIND_DIAL = "dial"
    const val KIND_INCOMING = "incoming"
    const val KIND_DEFAULT = "default"

    private val callWords = listOf("打电话", "来电", "拨打", "打给", "接听", "去电", "来电话")
    private val sceneWords = listOf("快递", "外卖", "快递员", "放门口", "交代", "预约", "鲜奶", "门口")
    private val clockRange = Regex("""(\d{1,2}):(\d{2})\s*[-~～到至]\s*(\d{1,2}):(\d{2})""")
    private val weekdays = listOf(
        "周一" to DayOfWeek.MONDAY, "星期一" to DayOfWeek.MONDAY,
        "周二" to DayOfWeek.TUESDAY, "星期二" to DayOfWeek.TUESDAY,
        "周三" to DayOfWeek.WEDNESDAY, "星期三" to DayOfWeek.WEDNESDAY,
        "周四" to DayOfWeek.THURSDAY, "星期四" to DayOfWeek.THURSDAY,
        "周五" to DayOfWeek.FRIDAY, "星期五" to DayOfWeek.FRIDAY,
        "周六" to DayOfWeek.SATURDAY, "星期六" to DayOfWeek.SATURDAY,
        "周日" to DayOfWeek.SUNDAY, "周天" to DayOfWeek.SUNDAY, "星期日" to DayOfWeek.SUNDAY,
    )

    fun looksLike(text: String): Boolean {
        val words = text.trim()
        if (words.length !in 8..800) return false
        if (callWords.any { words.contains(it) }) return true
        val scene = sceneWords.any { words.contains(it) }
        return scene && (words.contains("电话") || words.contains("打"))
    }

    /** Today / tomorrow / a weekday, plus an explicit clock range or 上午/下午/晚上. */
    fun resolveWindow(label: String, now: ZonedDateTime): Pair<Long?, Long?> {
        val text = label.trim()
        if (text.isEmpty()) return null to null
        var day = now.toLocalDate()
        when {
            text.contains("后天") -> day = day.plusDays(2)
            text.contains("明天") -> day = day.plusDays(1)
            text.contains("今天") || text.contains("今日") -> Unit
            else -> {
                val weekday = weekdays.firstOrNull { text.contains(it.first) }?.second
                if (weekday != null) {
                    val delta = (weekday.value - now.dayOfWeek.value + 7) % 7
                    day = day.plusDays(delta.toLong())
                }
            }
        }
        val range = clockRange.find(text)
        val startMin: Int
        val endMin: Int
        if (range != null) {
            val sh = range.groupValues[1].toInt()
            val sm = range.groupValues[2].toInt()
            val eh = range.groupValues[3].toInt()
            val em = range.groupValues[4].toInt()
            if (sh !in 0..23 || eh !in 0..23 || sm !in 0..59 || em !in 0..59) return null to null
            startMin = sh * 60 + sm
            endMin = eh * 60 + em
        } else {
            val pair = when {
                text.contains("上午") || text.contains("早上") -> 8 * 60 to 12 * 60
                text.contains("中午") -> 11 * 60 to 13 * 60
                text.contains("下午") -> 12 * 60 to 18 * 60
                text.contains("晚上") || text.contains("傍晚") -> 18 * 60 to 22 * 60
                else -> 0 to 24 * 60
            }
            startMin = pair.first
            endMin = pair.second
        }
        val zone = now.zone
        val start = day.atStartOfDay(zone).plusMinutes(startMin.toLong()).toInstant().toEpochMilli()
        var end = day.atStartOfDay(zone).plusMinutes(endMin.toLong()).toInstant().toEpochMilli()
        if (end <= start) end += 24 * 60 * 60 * 1000
        return start to end
    }

    fun renderIncoming(tasks: List<CallAssistantTask>, now: Long, caller: String?): String {
        val active = tasks.filter {
            it.kind == KIND_INCOMING && it.statusAt(now) == CallTaskStatus.ACTIVE && it.appliesToCaller(caller)
        }
        if (active.isEmpty()) return ""
        return buildString {
            append("机主为本通来电准备的通话任务。只在来电者符合对象、且仍在生效时间内时使用；与默认交代冲突时，以更严格的一项为准。")
            append("未写明的地址、门禁、金额、时间和承诺不得猜测。\n")
            active.forEachIndexed { index, task ->
                append(index + 1).append(". ").append(task.title.ifBlank { task.target }).append('\n')
                if (task.target.isNotBlank()) append("对象：").append(task.target).append('\n')
                if (task.windowLabel.isNotBlank()) append("生效时间：").append(task.windowLabel).append('\n')
                append("要说：").append(task.say).append('\n')
                if (task.cannotPromise.isNotBlank()) append("不能承诺：").append(task.cannotPromise).append('\n')
            }
        }
    }

    fun attachContact(parsed: ParsedCallTask, entries: List<PhoneNumbers.Entry>): ParsedCallTask {
        if (parsed.number.isNotBlank() && CallPolicy.outgoingNumber(parsed.number) != null) return parsed
        val hit = PhoneNumbers.resolveName(entries, parsed.target).takeIf { it != null }
            ?: PhoneNumbers.resolveName(entries, parsed.title)
            ?: return parsed
        return parsed.copy(target = hit.name, number = hit.number)
    }
}

internal const val KIND_DIAL = CallTaskTexts.KIND_DIAL
internal const val KIND_INCOMING = CallTaskTexts.KIND_INCOMING
