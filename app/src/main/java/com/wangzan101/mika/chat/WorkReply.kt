package com.wangzan101.mika.chat

/** Presentation of a single work result. The original answer stays in the run record. */
internal data class WorkReply(val summary: String, val full: String) {
    companion object {
        const val DETAILS_MARKER = "<!-- mika:details -->"
        private const val SUMMARY_LIMIT = 240
        private val fence = Regex(" {0,3}(`{3,}|~{3,})(.*)")
        private val paragraphBreak = Regex("\\n[ \\t]*\\n")
        private val receipt = Regex(
            """(?:[^。！？\d]{0,16}(?:查到|读到)了?|(?:已经|已)(?:查到|读到|读取|获取|找到)[^。！？\d]{1,16}|查询完成|读取完成|读取成功|已完成|(?:I (?:have )?)?(?:read|retrieved|checked|found) the (?:requested )?(?:data|results?|status))""",
            RegexOption.IGNORE_CASE,
        )
        private val updateTime = Regex(
            """(?:(?:数据|页面|车况)?(?:最后)?更新(?:时间(?:是|为)?|于)|(?:last |data )?updated(?: at| on)?)[：:\s]*(?:\d{4}[-/]\d{1,2}[-/]\d{1,2}[ T])?\d{1,2}:\d{2}(?::\d{2})?(?:\s*[（(](?:页面|page\b)[^）)]*[）)])?""",
            RegexOption.IGNORE_CASE,
        )
        private val sentenceBreak = Regex("""[，,。！？!?；;\n]|\.\s+""")
        private val heading = Regex(
            """#{1,6}\s+.*|\*\*(?:[^*]*[：:]|[^*]*(?:状态|明细|详情|结果|summary|status|details|results?)(?:[（(][^）)]*[）)])?)\*\*[：:]?""",
            RegexOption.IGNORE_CASE,
        )

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
            val leading = introduction.ifEmpty { extra }
            if (!streaming && isMetadataOnly(leading.lineSequence().first())) {
                val findings = sequenceOf(leading.substringAfter('\n', ""), extra)
                    .map(::findingsPreview).firstOrNull(String::isNotEmpty)
                if (findings != null) return WorkReply(findings, full)
            }
            return WorkReply(preview(leading), full)
        }

        private fun isMetadataOnly(text: String): Boolean {
            val clauses = text.replace("**", "").trimEnd('.').split(sentenceBreak).map(String::trim).filter(String::isNotEmpty)
            return clauses.isNotEmpty() && clauses.all { receipt.matches(it) || updateTime.matches(it) }
        }

        private fun findingsPreview(text: String): String {
            val paragraphs = text.split(paragraphBreak)
            for (paragraph in paragraphs) {
                val lines = paragraph.lineSequence().map(String::trim)
                    .dropWhile { it.isEmpty() || heading.matches(it) || isMetadataOnly(it) }
                    .joinToString("\n")
                if (lines.isNotBlank()) return preview(lines)
            }
            return ""
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
