package com.wangzan101.mika.calls.tasks

import com.wangzan101.mika.calls.PhoneNumbers
import com.wangzan101.mika.core.trigger.PlanDraft
import com.wangzan101.mika.core.trigger.PlanRepeat
import com.wangzan101.mika.plans.PlanUtterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CallTaskTextsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val fridayMorning = ZonedDateTime.of(2026, 11, 13, 9, 0, 0, 0, zone)

    @Test fun incomingWindowUsesExplicitDateInsteadOfToday() {
        val (start, end) = CallTaskTexts.resolveWindow("2026-11-14 08:00-12:00", fridayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 8, 0, 0, 0, zone).toInstant().toEpochMilli(), start!!)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), end!!)
        val (crossStart, crossEnd) = CallTaskTexts.resolveWindow("2026-11-14 08:00 至 2026-11-15 12:00", fridayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 8, 0, 0, 0, zone).toInstant().toEpochMilli(), crossStart!!)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), crossEnd!!)
        assertEquals(null to null, CallTaskTexts.resolveWindow("明天下午到后天早上", fridayMorning))
    }

    @Test fun convertingAnExistingTaskToPermanentPreservesInstructionAndDoesNotExpire() {
        val existing = CallAssistantTask("one", CallTaskTexts.KIND_INCOMING, "快递", "快递员", "今天下午",
            startMs = 100, endMs = 200, say = "放门口")
        val changed = existing.copy(say = "放西门", startMs = 300, endMs = 400)
        val permanent = CallTaskParser.applyLifetime(changed, existing, true)
        assertEquals("one", permanent.id)
        assertEquals("放西门", permanent.say)
        assertNull(permanent.startMs)
        assertNull(permanent.endMs)
        assertEquals(CallTaskStatus.ACTIVE, permanent.statusAt(Long.MAX_VALUE))
        val unchanged = CallTaskParser.applyLifetime(changed, existing, false)
        assertEquals(200L, unchanged.endMs!!)
    }

    @Test fun courierSentenceLooksLikeACallTaskAndWeatherDoesNot() {
        assertTrue(CallTaskTexts.looksLike("我今天上午有个快递，快递员打电话来，就让他放门口鲜奶箱"))
        assertTrue(CallTaskTexts.looksLike("帮我打给餐厅，预约周六晚上的位子"))
        assertFalse(CallTaskTexts.looksLike("今天天气怎么样"))
        assertFalse(CallTaskTexts.looksLike("记住我喜欢咖啡"))
    }

    @Test fun morningWindowIsEightToNoonOnTheNamedDay() {
        val (start, end) = CallTaskTexts.resolveWindow("今天 08:00-12:00", fridayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 8, 0, 0, 0, zone).toInstant().toEpochMilli(), start!!)
        assertEquals(ZonedDateTime.of(2026, 11, 13, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), end!!)
        val morning = CallTaskTexts.resolveWindow("今天上午", fridayMorning)
        assertEquals(start, morning.first)
        assertEquals(end, morning.second)
    }

    @Test fun saturdayDinnerLandsOnTheComingSaturdayEvening() {
        val (start, end) = CallTaskTexts.resolveWindow("周六晚上", fridayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 18, 0, 0, 0, zone).toInstant().toEpochMilli(), start!!)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 22, 0, 0, 0, zone).toInstant().toEpochMilli(), end!!)
    }

    @Test fun incomingTaskIsActiveOnlyInsideItsWindow() {
        val (start, end) = CallTaskTexts.resolveWindow("今天上午", fridayMorning)
        val task = CallAssistantTask("a", CallTaskTexts.KIND_INCOMING, "今天上午的快递", "快递员", "今天上午",
            start, end, "放门口鲜奶箱")
        assertEquals(CallTaskStatus.ACTIVE, task.statusAt(fridayMorning.toInstant().toEpochMilli()))
        assertEquals(CallTaskStatus.LATER, task.statusAt(start!! - 1))
        assertEquals(CallTaskStatus.DONE, task.statusAt(end!!))
        val dial = task.copy(kind = CallTaskTexts.KIND_DIAL, dialed = false)
        assertEquals(CallTaskStatus.PENDING, dial.statusAt(fridayMorning.toInstant().toEpochMilli()))
        assertEquals(CallTaskStatus.DONE, dial.copy(dialed = true).statusAt(0))
    }

    @Test fun activeIncomingTaskIsMergedIntoThePrompt() {
        val task = CallAssistantTask("a", CallTaskTexts.KIND_INCOMING, "快递", "快递员", "今天上午",
            null, null, "放门口鲜奶箱", "不替我签收付款")
        val prompt = CallTaskTexts.renderIncoming(listOf(task), fridayMorning.toInstant().toEpochMilli(), null)
        assertTrue(prompt.contains("放门口鲜奶箱"))
        assertTrue(prompt.contains("不替我签收付款"))
        assertTrue(prompt.contains("快递员"))
        val numbered = task.copy(target = "+8613800138000", number = "13800138000")
        assertEquals("", CallTaskTexts.renderIncoming(listOf(numbered), 0, "13900000000"))
        assertTrue(CallTaskTexts.renderIncoming(listOf(numbered), 0, "+86 138 0013 8000").contains("放门口鲜奶箱"))
    }

    @Test fun modelJsonBecomesATaskAndResolvesASpokenContact() {
        val parsed = CallTaskParser.fromJson("""
            {"is_task":true,"kind":"incoming","title":"今天上午的快递","target":"快递员",
             "window":"今天上午","say":"请把快递放在门口鲜奶箱","cannot_promise":"不替我签收付款","number":""}
        """.trim(), fridayMorning)
        assertEquals(CallTaskTexts.KIND_INCOMING, parsed!!.kind)
        assertEquals("请把快递放在门口鲜奶箱", parsed.say)
        val withName = CallTaskTexts.attachContact(
            parsed.copy(target = "张三"),
            listOf(PhoneNumbers.Entry("张三", "13800138000"), PhoneNumbers.Entry("李四", "13900000000")),
        )
        assertEquals("张三", withName.target)
        assertEquals("13800138000", withName.number)
        assertNull(CallTaskParser.fromJson("""{"is_task":false}"""))
    }

    @Test fun phoneNumbersMatchCountryCodesWithoutMatchingSuffixes() {
        assertTrue(PhoneNumbers.same("+86 138-0013-8000", "13800138000"))
        assertTrue(PhoneNumbers.same("+65 9123 4567", "91234567"))
        assertTrue(PhoneNumbers.same("0065-9123-4567", "+6591234567"))
        assertFalse(PhoneNumbers.same("800138000", "13800138000"))
        assertFalse(PhoneNumbers.same("112", "13800138000"))
        val hit = PhoneNumbers.resolveName(
            listOf(PhoneNumbers.Entry("张三", "13800138000"), PhoneNumbers.Entry("张三丰", "13900000000")),
            "张三丰",
        )
        assertEquals("13900000000", hit!!.number)
        assertNull(PhoneNumbers.resolveName(
            listOf(PhoneNumbers.Entry("张三", "13800138000"), PhoneNumbers.Entry("张三", "13900000000")),
            "张三",
        ))
    }

    @Test fun planSentenceFillsDailyClockAndContent() {
        val base = PlanDraft(content = "", firstAt = "2026-11-13 10:00", zone = "Asia/Shanghai")
        val next = PlanUtterance.apply("""
            {"is_plan":true,"reminder":true,"content":"如果会下雨就提醒我","repeat":"daily","clock":"08:00","weekdays":[],"condition":"","zone":"Asia/Shanghai"}
        """.trim(), base, fridayMorning)
        assertEquals("如果会下雨就提醒我", next!!.content)
        assertEquals(PlanRepeat.Daily, next.repeat)
        assertEquals("08:00", next.clock)
        assertTrue(next.reminder)
        assertNull(PlanUtterance.apply("""{"is_plan":false}""", base))
    }

    @Test fun tasksRoundTripThroughTheStore() {
        val memory = object : com.wangzan101.mika.calls.tasks.CallTaskBackend {
            var raw: String? = null
            override fun read() = raw
            override fun write(value: String) = true.also { raw = value }
        }
        val store = CallTaskStore(memory, now = { 10L }, newId = { "task1" })
        val saved = store.save(CallAssistantTask("", CallTaskTexts.KIND_INCOMING, "快递", "快递员", "今天上午",
            1L, 2L, "放门口"))
        assertEquals("task1", saved.id)
        val reopened = CallTaskStore(memory)
        assertTrue(reopened.load())
        assertEquals("放门口", reopened.items.value.single().say)
        reopened.markDialed("missing")
        reopened.remove("task1")
        assertTrue(reopened.items.value.isEmpty())
    }

    @Test fun retryReusesExactActiveTaskAndStaleEditDoesNotChangeIt() {
        val memory = object : CallTaskBackend {
            var raw: String? = null
            override fun read() = raw
            override fun write(value: String) = true.also { raw = value }
        }
        var nextId = 0
        val store = CallTaskStore(memory, now = { 100L }, newId = { "task${++nextId}" })
        val parcel = CallAssistantTask("", CallTaskTexts.KIND_INCOMING, "快递", "快递员", say = "放门口", endMs = 1000L)
        val first = store.saveIncomingOnce(parcel)
        val retry = store.saveIncomingOnce(parcel.copy(endMs = 2000L))
        assertTrue(first.second)
        assertFalse(retry.second)
        assertEquals(first.first.id, retry.first.id)
        assertEquals(1000L, retry.first.endMs!!)
        val food = store.saveIncomingOnce(parcel.copy(title = "外卖", target = "外卖员", say = "放前台"))
        assertTrue(food.second)
        assertEquals(2, store.snapshot().size)
        assertFalse(store.editIncoming(first.first.id, "过期内容", null))
        assertEquals(2, store.snapshot().size)
        assertTrue(store.editIncoming(first.first.id, "放门口", "放西门"))
        assertEquals("放西门", store.snapshot().first().say)
        assertTrue(store.editIncoming(food.first.id, "放前台", null))
        assertEquals(1, store.snapshot().size)
        assertTrue(CallTaskTexts.isManagementRequest("把外卖电话交代改成放前台"))
        assertFalse(CallTaskTexts.isManagementRequest("外卖员来电时让他放前台"))
    }
}
