package com.wangzan101.mika.chat

/** Presentation of a single work result. The original answer stays in the run record. */
internal data class WorkReply(val summary: String, val full: String) {
    companion object {
        const val DETAILS_MARKER = "<!-- mika:details -->"
        private const val SUMMARY_LIMIT = 240
        private val fence = Regex(" {0,3}(`{3,}|~{3,})(.*)")
        private val paragraphBreak = Regex("\\n[ \\t]*\\n")

        fun from(answer: String, streaming: Boolean = false): WorkReply {
            var openFence: String? = null
            var inDetails = false
            val summary = mutableListOf<String>()
            val details = mutableListOf<String>()
            val lines = answer.lines()
            lines.forEachIndexed { index, line ->
                val match = fence.matchEntire(line)
                if (match != null) {
                    val (ticks, suffix) = match.destructured
                    val open = openFence
                    if (open == null) openFence = ticks
                    else if (ticks.first() == open.first() && ticks.length >= open.length && suffix.isBlank()) {
                        openFence = null
                    }
                }
                val candidate = line.trim()
                if (openFence == null && candidate == DETAILS_MARKER) {
                    inDetails = true
                } else if (!(streaming && openFence == null && index == lines.lastIndex &&
                           candidate.startsWith("<!--") && DETAILS_MARKER.startsWith(candidate))) {
                    (if (inDetails) details else summary).add(line)
                }
            }
            val introduction = summary.joinToString("\n").trim()
            val extra = details.joinToString("\n").trim()
            val full = if (inDetails) listOf(introduction, extra).filter(String::isNotEmpty).joinToString("\n\n") else answer
            return WorkReply(preview(introduction.ifEmpty { extra }), full)
        }

        private fun preview(text: String): String {
            if (text.startsWith('`') || text.startsWith("~~~") ||
                text.startsWith('{') || text.startsWith('[') && !text.lineSequence().first().contains("](")) return text
            if (text.length <= SUMMARY_LIMIT && text.lineSequence().count(String::isNotBlank) <= 3) return text
            val paragraph = text.split(paragraphBreak, limit = 2).first()
            val short = paragraph.lineSequence().take(3).joinToString("\n")
            if (short.length <= SUMMARY_LIMIT) return short
            val end = if (short[SUMMARY_LIMIT - 1].isHighSurrogate()) SUMMARY_LIMIT - 1 else SUMMARY_LIMIT
            return short.take(end).trimEnd() + "…"
        }
    }
}
