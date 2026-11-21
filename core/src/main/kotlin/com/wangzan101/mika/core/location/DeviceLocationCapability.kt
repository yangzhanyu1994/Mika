package com.wangzan101.mika.core.location

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface DeviceLocationPort {
    suspend fun read(origin: RunOrigin, attendance: Attendance): DeviceLocationReading
}

data class DeviceLocationReading(
    val decision: LocationDecision,
    /** System geocoder label, or null when there is no geocoder or it returned nothing. */
    val place: String?,
    val geocoder: String,
    val collectedAtMs: Long?,
)

internal class DeviceLocationCapability(val port: DeviceLocationPort) : Capability {
    override val id = "location"
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = locationTools
    override fun sections(env: com.wangzan101.mika.core.capability.PromptEnv): List<String> = listOf(
        "When the user asks where they are, call device_location and answer from that result. " +
            "Report coordinates, accuracy, collection time, and whether the fix is cached. " +
            "Use place only when geocoder is system. If geocoder is none or unavailable, give the coordinates and do not invent a street or city. " +
            "A failure is the answer: do not guess a location, and do not send the coordinates anywhere except this reply.",
    )
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    companion object { val KEY = CapabilityKey<DeviceLocationCapability>("location") }
}

internal val locationTools: List<AgentTool> = listOf(DeviceLocationTool)

private object DeviceLocationTool : AgentTool {
    override val capability = "location"
    override val offScreen = true
    override val risk = Risk.Read
    override val verb = Verb.DeviceLocation
    override val label = "当前位置"
    override val logged = false
    override val def = ToolDef("device_location", Prompts.tool("device_location"), emptyMap(), emptyList())
    override fun available(ctx: ToolContext) = ctx[DeviceLocationCapability.KEY] != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val spec = ctx.config.runSpec
        val origin = spec?.provenance?.origin ?: RunOrigin.Ui
        val attendance = spec?.provenance?.attendance ?: Attendance.Present
        val reading = ctx[DeviceLocationCapability.KEY]!!.port.read(origin, attendance)
        val decision = reading.decision
        return ToolExecution(buildJsonObject {
            put("ok", decision.failure == null && decision.sample != null)
            decision.failure?.let { put("failure", it) }
            put("cached", decision.cached)
            put("geocoder", reading.geocoder)
            reading.collectedAtMs?.let { put("collectedAtMs", it) }
            decision.sample?.let { sample ->
                put("latitude", sample.latitude)
                put("longitude", sample.longitude)
                put("accuracyMeters", sample.accuracyMeters)
                put("ageMs", sample.ageMs)
                put("provider", sample.provider)
            }
            reading.place?.let { put("place", it) }
        })
    }
}
