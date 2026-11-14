package com.wangzan101.mika.about

import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The README is the maintained source; the packaged copy supports offline use. */
internal object ForkReleaseHistory {
    private const val SOURCE = "https://raw.githubusercontent.com/yangzhanyu1994/Mika/main/README.md"
    private const val MAX_BYTES = 512 * 1024L
    private val heading = Regex("(?m)^#### \\[v(\\d+\\.\\d+\\.\\d+)]\\([^\\n]*\\) · (\\d{4}-\\d{2}-\\d{2})\\s*$")
    private val client = NetProxy.shared.client(OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS))

    data class Entry(val version: String, val date: String, val notes: String)

    fun parse(text: String): List<Entry> {
        val section = text.substringAfter("### 近期版本更新说明", "")
            .substringBefore("\n### ")
        val hits = heading.findAll(section).toList()
        return hits.mapIndexedNotNull { i, match ->
            val end = hits.getOrNull(i + 1)?.range?.first ?: section.length
            val notes = section.substring(match.range.last + 1, end).trim()
            if (notes.isEmpty()) null else Entry(match.groupValues[1], match.groupValues[2], notes)
        }.distinctBy { it.version }
    }

    fun merge(remote: List<Entry>, bundled: List<Entry>): List<Entry> =
        (remote + bundled).distinctBy { it.version }.sortedWith { a, b -> AppVersion.compare(b.version, a.version) }

    fun fetch(): List<Entry> = runCatching {
        val request = Request.Builder().url(SOURCE).header("User-Agent", "Mika-release-history").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val body = checkNotNull(response.body)
            val length = body.contentLength()
            require(length <= MAX_BYTES)
            val source = body.source()
            source.request(MAX_BYTES + 1)
            require(source.buffer.size <= MAX_BYTES)
            val bytes = source.readByteArray()
            parse(bytes.toString(Charsets.UTF_8))
        }
    }.getOrDefault(emptyList())
}
