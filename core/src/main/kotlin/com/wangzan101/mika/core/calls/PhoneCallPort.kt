package com.wangzan101.mika.core.calls

import kotlinx.serialization.json.JsonObject

/** Native phone calls. Permission and local opt-in are checked by the Android implementation. */
interface PhoneCallPort {
    suspend fun state(): JsonObject
    suspend fun dial(number: String, simId: String? = null, task: PhoneCallTask? = null): JsonObject
    /** [callId] must still identify the exact call reported by [state]. */
    suspend fun answer(callId: String): JsonObject
    suspend fun hangup(callId: String): JsonObject
    suspend fun takeover(): JsonObject
    /** Save instructions for a future incoming call; does not change answering settings. */
    suspend fun saveIncomingTask(target: String, say: String, minutes: Int): JsonObject =
        JsonObject(mapOf("ok" to kotlinx.serialization.json.JsonPrimitive(false), "message" to kotlinx.serialization.json.JsonPrimitive("Incoming call tasks unavailable")))
}

internal const val CALL_ID_FIELD = "call_id"
internal const val MAX_CALL_ID_LENGTH = 64
