package com.wangzan101.mika.calls

import com.wangzan101.mika.calls.instructions.CallInstruction

/** One default instruction, folded from the old call notes and the saved instruction list. */
object CallDefaultBrief {
    const val MAX_CHARS = 4_000

    fun compose(prompt: String, rules: List<CallInstruction>): String {
        val extra = rules.joinToString("\n") { row ->
            listOf(row.title, row.situation, row.response).filter { it.isNotBlank() }.joinToString("：")
        }
        return listOf(prompt.trim(), extra.trim()).filter { it.isNotEmpty() }.joinToString("\n").take(MAX_CHARS)
    }

    fun migrate(settings: CallSettings, store: com.wangzan101.mika.calls.instructions.CallInstructionStore) {
        if (settings.state.value.defaultBriefMigrated) return
        if (!store.load()) return
        val text = compose(settings.state.value.systemPrompt, store.items.value)
        settings.save(settings.state.value.copy(defaultBrief = text, defaultBriefMigrated = true))
        store.items.value.toList().forEach { row -> runCatching { store.remove(row.id) } }
    }
}
