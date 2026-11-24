package dev.kuml.core.model.note

/**
 * Opt-in table "note text prefix to expected [NoteKind]" (workspace marker section `[note-prefixes]`), the basis of
 * `KUML-NOTE-W006`. A note whose text starts with a configured prefix word but whose kind differs is reported.
 *
 * Matching is an exact, case-insensitive comparison of the first word of the text (ended by `:` or whitespace); no
 * regular expression touches user text, and at most [MAX_SCAN_CHARS] characters are inspected. Keys longer than
 * [MAX_PREFIX_LENGTH] or empty are dropped and the table is capped at [MAX_ENTRIES] entries.
 */
class NotePrefixKinds(
    entries: Map<String, NoteKind>,
) {
    private val table: Map<String, NoteKind>

    init {
        val built = LinkedHashMap<String, NoteKind>()
        for ((rawKey, kind) in entries) {
            val key = rawKey.trim().lowercase()
            if (key.isEmpty() || key.length > MAX_PREFIX_LENGTH) continue
            if (key !in built && built.size >= MAX_ENTRIES) continue
            built[key] = kind
        }
        table = built
    }

    val isEmpty: Boolean get() = table.isEmpty()

    /** The first word of [body] when it is terminated within the scan window, otherwise `null`. */
    fun prefixToken(body: String): String? {
        val window = body.take(MAX_SCAN_CHARS)
        var start = 0
        while (start < window.length && window[start].isWhitespace()) start++
        var end = start
        while (end < window.length && window[end] != ':' && !window[end].isWhitespace()) end++
        if (end >= window.length && body.length > window.length) return null
        return window.substring(start, end).takeIf { it.isNotEmpty() }
    }

    /** The configured key (lowercase) and expected kind when [body] starts with a configured prefix, else `null`. */
    fun expectedKindFor(body: String): Pair<String, NoteKind>? {
        if (table.isEmpty()) return null
        val token = prefixToken(body)?.lowercase() ?: return null
        val kind = table[token] ?: return null
        return token to kind
    }

    companion object {
        val EMPTY: NotePrefixKinds = NotePrefixKinds(emptyMap())

        /** Upper bound of the inspected start of a note text. */
        const val MAX_SCAN_CHARS: Int = 64

        /** Upper bound of table entries. */
        const val MAX_ENTRIES: Int = 64

        /** Longer keys are dropped. */
        const val MAX_PREFIX_LENGTH: Int = 40
    }
}
