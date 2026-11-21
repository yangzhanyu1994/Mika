package com.wangzan101.mika.core.clock

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One sample of the device clock. [sampledAt] is the instant that was read, not a later formatting time. */
data class ClockSample(val sampledAt: Instant, val zone: ZoneId)

internal object LocalClock {
    fun sample(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ClockSample =
        ClockSample(now, zone)

    fun payload(sample: ClockSample): JsonObject {
        val zoned = sample.sampledAt.atZone(sample.zone)
        return buildJsonObject {
            put("ok", true)
            put("epochMs", sample.sampledAt.toEpochMilli())
            put("zone", sample.zone.id)
            put("offset", zoned.offset.id)
            put("local", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zoned))
            put("sampledAt", sample.sampledAt.toString())
        }
    }
}

internal class ClockCapability : Capability {
    override val id = "clock"
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = clockTools
    override fun sections(env: com.wangzan101.mika.core.capability.PromptEnv): List<String> = listOf(
        "When the user asks the time or the date, call read_local_time and answer from that result. " +
            "Say the zone and that the time is the sampled instant. Do not change the device clock.",
    )
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    companion object { val KEY = CapabilityKey<ClockCapability>("clock") }
}

internal val clockTools: List<AgentTool> = listOf(ReadLocalTime)

private object ReadLocalTime : AgentTool {
    override val capability = "clock"
    override val offScreen = true
    override val risk = Risk.Read
    override val verb = Verb.ReadLocalTime
    override val label = "当前时间"
    override val def = ToolDef("read_local_time", Prompts.tool("read_local_time"), emptyMap(), emptyList())
    override fun available(ctx: ToolContext) = ctx[ClockCapability.KEY] != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution =
        ToolExecution(LocalClock.payload(LocalClock.sample()))
}
