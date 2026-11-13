package com.wangzan101.mika.calls.tasks

import android.content.Context
import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

internal interface CallTaskBackend {
    fun read(): String?
    fun write(value: String): Boolean
}

class CallTaskStore internal constructor(
    private val backend: CallTaskBackend,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<List<CallAssistantTask>>(emptyList())
    val items = mutable.asStateFlow()
    private var loaded = false

    @Synchronized fun load(): Boolean {
        if (loaded) return true
        return try {
            val raw = backend.read()
            require(raw == null || raw.length <= MAX_STORAGE)
            val rows = raw?.let { json.decodeFromString(ListSerializer(CallAssistantTask.serializer()), it) }.orEmpty()
            require(rows.size <= MAX_TASKS && rows.map { it.id }.distinct().size == rows.size)
            rows.forEach(::validate)
            mutable.value = rows
            loaded = true
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized fun save(task: CallAssistantTask): CallAssistantTask {
        check(load()) { "通话任务未读入，不能覆盖" }
        val row = task.copy(
            id = task.id.ifBlank(newId),
            title = task.title.trim().ifBlank { task.target.trim().ifBlank { task.say.trim().take(24) } },
            target = task.target.trim(),
            windowLabel = task.windowLabel.trim(),
            say = task.say.trim(),
            cannotPromise = task.cannotPromise.trim(),
            number = task.number.trim(),
            createdAtMs = task.createdAtMs.takeIf { it > 0 } ?: now(),
        )
        validate(row)
        val before = mutable.value
        val next = if (before.any { it.id == row.id }) before.map { if (it.id == row.id) row else it } else before + row
        require(next.size <= MAX_TASKS) { "最多保存 $MAX_TASKS 条通话任务" }
        persist(next)
        return row
    }

    @Synchronized fun remove(id: String) {
        check(load()) { "通话任务未读入" }
        persist(mutable.value.filterNot { it.id == id })
    }

    /** Do not duplicate the same active incoming instruction after a retry. */
    @Synchronized fun saveIncomingOnce(task: CallAssistantTask): Pair<CallAssistantTask, Boolean> {
        check(load()) { "通话任务未读入，不能覆盖" }
        val existing = mutable.value.firstOrNull {
            it.kind == CallTaskTexts.KIND_INCOMING && it.statusAt(now()) == CallTaskStatus.ACTIVE &&
                it.target.trim() == task.target.trim() && it.number.trim() == task.number.trim() &&
                it.say.trim() == task.say.trim() && it.cannotPromise.trim() == task.cannotPromise.trim()
        }
        return if (existing != null) existing to false else save(task) to true
    }

    @Synchronized fun snapshot(): List<CallAssistantTask> {
        check(load()) { "通话任务无法读取" }
        return mutable.value.toList()
    }

    /** Refuse stale or missing edits rather than silently creating/replacing a task. */
    @Synchronized fun editIncoming(id: String, expectedSay: String, say: String?): Boolean {
        check(load()) { "通话任务无法读取" }
        val task = mutable.value.firstOrNull { it.id == id && it.kind == CallTaskTexts.KIND_INCOMING } ?: return false
        if (task.say != expectedSay) return false
        if (say == null) remove(id) else save(task.copy(say = say))
        return true
    }

    @Synchronized fun markDialed(id: String) {
        check(load()) { "通话任务未读入" }
        val row = mutable.value.firstOrNull { it.id == id } ?: return
        persist(mutable.value.map { if (it.id == id) row.copy(dialed = true) else it })
    }

    @Synchronized fun incomingPrompt(atMs: Long, caller: String?): String {
        check(load()) { "通话任务无法读取" }
        return CallTaskTexts.renderIncoming(mutable.value, atMs, caller)
    }

    private fun persist(next: List<CallAssistantTask>) {
        val saved = runCatching {
            backend.write(json.encodeToString(ListSerializer(CallAssistantTask.serializer()), next))
        }.getOrDefault(false)
        check(saved) { "通话任务保存失败" }
        mutable.value = next
    }

    private fun validate(row: CallAssistantTask) {
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(row.id)) { "任务编号无效" }
        require(row.kind == CallTaskTexts.KIND_DIAL || row.kind == CallTaskTexts.KIND_INCOMING) { "任务类型无效" }
        require(row.title.length in 1..64) { "标题需为 1–64 字" }
        require(row.target.length <= 80) { "对象过长" }
        require(row.windowLabel.length <= 80) { "生效时间过长" }
        require(row.say.length in 1..800) { "AI 要说需为 1–800 字" }
        require(row.cannotPromise.length <= 400) { "不能承诺过长" }
        if (row.number.isNotBlank()) require(CallPolicy.outgoingNumber(row.number) != null || CallPolicy.incomingNumber(row.number) != null) {
            "电话号码无效"
        }
        require(row.endMs == null || row.startMs == null || row.endMs > row.startMs) { "生效时间无效" }
    }

    companion object {
        const val MAX_TASKS = 30
        private const val MAX_STORAGE = 200_000

        fun create(context: Context): CallTaskStore {
            val prefs = context.applicationContext.getSharedPreferences("mika_call_tasks", Context.MODE_PRIVATE)
            val cipher = KeystoreKeyCipher("mika-call-tasks")
            return CallTaskStore(object : CallTaskBackend {
                override fun read(): String? {
                    val encoded = prefs.getString("encrypted_tasks_v1", null) ?: return null
                    require(encoded.length <= 500_000)
                    val plain = cipher.decrypt(Base64.getDecoder().decode(encoded))
                    return try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
                }
                override fun write(value: String): Boolean {
                    val plain = value.toByteArray(Charsets.UTF_8)
                    return try {
                        prefs.edit().putString("encrypted_tasks_v1", Base64.getEncoder().encodeToString(cipher.encrypt(plain))).commit()
                    } finally { plain.fill(0) }
                }
            })
        }
    }
}
