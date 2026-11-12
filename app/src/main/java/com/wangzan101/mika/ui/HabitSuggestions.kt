package com.wangzan101.mika.ui

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.task.TriggerRuntime

/**
 * Suggestions drawn only from what this phone has already done.
 * Nothing here is uploaded. With no history the list is empty, so the home card stays hidden.
 */
object HabitSuggestions {
    data class Use(val text: String, val atMs: Long)

    const val LIMIT = 4

    /** Most-used first. Equal counts keep the more recently used wording. Similar lines collapse into one. */
    fun rank(uses: List<Use>, limit: Int = LIMIT): List<String> {
        val clusters = mutableListOf<Cluster>()
        for (use in uses) {
            val text = normalize(use.text) ?: continue
            val cluster = clusters.firstOrNull { similar(it.key, keyOf(text)) }
            if (cluster == null) {
                clusters += Cluster(keyOf(text), text, use.atMs, 1)
            } else {
                cluster.count += 1
                if (use.atMs >= cluster.atMs) {
                    cluster.atMs = use.atMs
                    cluster.text = text
                }
            }
        }
        return clusters
            .sortedWith(compareByDescending<Cluster> { it.count }.thenByDescending { it.atMs }.thenBy { it.text })
            .take(limit.coerceAtLeast(0))
            .map { it.text }
    }

    /** Local files only: recent instructions, saved schedules, and saved call tasks. */
    fun fromDevice(context: Context): List<String> {
        val uses = ArrayList<Use>()
        val conversations = ConversationStore.shared(context)
        conversations.list().take(30).forEach { summary ->
            runCatching { conversations.turns(summary.id) }.getOrNull()?.takeLast(12)?.forEach { turn ->
                val at = turn.atMs ?: summary.updatedAt
                uses += Use(turn.userText, at)
                turn.steers.forEach { uses += Use(it, at) }
            }
        }
        runCatching { TriggerRuntime.snapshot(context).triggers }.getOrNull()?.forEach { plan ->
            val text = plan.reminderText?.takeIf { it.isNotBlank() } ?: plan.goal
            uses += Use(text, plan.lastFiredAtMs ?: plan.baselineAtMs ?: 0L)
        }
        (context.applicationContext as? MikaApp)?.calls?.tasks?.let { store ->
            runCatching {
                store.load()
                store.items.value.forEach { task ->
                    uses += Use(task.say.ifBlank { task.title }, task.createdAtMs)
                }
            }
        }
        return rank(uses)
    }

    internal fun normalize(raw: String): String? {
        val text = raw.replace('\n', ' ').trim().replace(Regex("\\s+"), " ")
        if (text.length < 2 || text.startsWith("[[")) return null
        return text
    }

    internal fun keyOf(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    /** Same words, or one line is a slightly longer form of the other. */
    internal fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        return short.length >= 8 && long.startsWith(short) && long.length <= short.length * 3 / 2 + 2
    }

    private class Cluster(val key: String, var text: String, var atMs: Long, var count: Int)
}
