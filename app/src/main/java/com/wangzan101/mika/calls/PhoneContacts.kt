package com.wangzan101.mika.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Reads the phone's contacts when READ_CONTACTS is granted. Denied access looks up nothing. */
class PhoneContacts(context: Context) {
    private val app = context.applicationContext

    fun granted(): Boolean =
        app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun entries(): List<PhoneNumbers.Entry> {
        if (!granted()) return emptyList()
        val cached = cache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.at < CACHE_MS) return cached.rows
        val rows = load()
        cache = Snapshot(now, rows)
        return rows
    }

    fun names(): List<String> = entries().map { it.name }.filter { it.isNotBlank() }.distinct().take(80)

    fun lookup(number: String?): PhoneNumbers.Entry? {
        if (number.isNullOrBlank()) return null
        return entries().firstOrNull { PhoneNumbers.same(it.number, number) }
    }

    /** "张三 · 13800138000", or the number itself when there is no match or no permission. */
    fun label(number: String?): String {
        val raw = number?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val name = lookup(raw)?.name?.takeIf { it.isNotBlank() } ?: return raw
        return "$name · $raw"
    }

    fun resolveSpoken(spoken: String): PhoneNumbers.Entry? = PhoneNumbers.resolveName(entries(), spoken)

    private fun load(): List<PhoneNumbers.Entry> = runCatching {
        val resolver = app.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )?.use { cursor ->
            val nameAt = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberAt = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameAt < 0 || numberAt < 0) return emptyList()
            val rows = ArrayList<PhoneNumbers.Entry>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext() && rows.size < MAX_ROWS) {
                val name = cursor.getString(nameAt)?.trim().orEmpty()
                val number = cursor.getString(numberAt)?.trim().orEmpty()
                if (name.isNotEmpty() && PhoneNumbers.matchKeys(number).isNotEmpty()) {
                    rows += PhoneNumbers.Entry(name, number)
                }
            }
            rows
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private data class Snapshot(val at: Long, val rows: List<PhoneNumbers.Entry>)

    companion object {
        private const val CACHE_MS = 30_000L
        private const val MAX_ROWS = 2_000
        @Volatile private var cache: Snapshot? = null

        fun clearCache() { cache = null }
    }
}
