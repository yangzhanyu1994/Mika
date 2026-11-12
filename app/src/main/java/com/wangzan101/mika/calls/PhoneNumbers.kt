package com.wangzan101.mika.calls

/**
 * Match phone numbers across spaces, dashes and country codes (+86, +65).
 * A shorter local number matches the same subscriber number with a country prefix.
 * Arbitrary suffixes do not match, so "800138000" is not "13800138000".
 */
object PhoneNumbers {
    data class Entry(val name: String, val number: String)

    fun matchKeys(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        var digits = raw.filter(Char::isDigit)
        if (digits.startsWith("00")) digits = digits.drop(2)
        if (digits.length !in 7..15) return emptySet()
        val keys = mutableSetOf(digits)
        fun local(prefix: String, length: IntRange) {
            if (!digits.startsWith(prefix)) return
            val rest = digits.removePrefix(prefix)
            if (rest.length in length) {
                keys += rest
                keys += prefix + rest
            }
        }
        local("86", 10..12)
        local("65", 8..8)
        return keys
    }

    fun same(a: String?, b: String?): Boolean {
        val left = matchKeys(a)
        val right = matchKeys(b)
        return left.isNotEmpty() && left.intersect(right).isNotEmpty()
    }

    /** One person, one number. Several numbers or several people stay unresolved. */
    fun resolveName(entries: List<Entry>, spoken: String): Entry? {
        val query = spoken.trim()
        if (query.length < 2) return null
        val exact = entries.filter { it.name.equals(query, ignoreCase = true) }
        val named = if (exact.isNotEmpty()) exact else entries.filter { namesMatch(it.name, query) }
        val numbers = named.map { it.number }.distinct()
        return if (numbers.size == 1) named.first() else null
    }

    fun namesMatch(name: String, query: String): Boolean {
        val left = name.trim()
        val right = query.trim()
        if (left.length < 2 || right.length < 2) return false
        return left.equals(right, ignoreCase = true) ||
            (left.length >= 2 && right.contains(left, ignoreCase = true)) ||
            (right.length >= 2 && left.contains(right, ignoreCase = true))
    }
}
