package dev.kuml.cli

/** Shared parsing helpers for command options. */
internal object CliOptions {
    /**
     * Parses `key=value` pairs separated by commas (for example `idType=uuid,package=com.x`).
     * Blank entries are skipped; an entry without `=` yields an empty value.
     */
    fun parseKeyValuePairs(raw: String?): Map<String, String> =
        buildMap {
            raw?.split(",")?.forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) return@forEach
                val (k, v) =
                    trimmed
                        .split("=", limit = 2)
                        .let { if (it.size == 2) it[0].trim() to it[1].trim() else it[0].trim() to "" }
                put(k, v)
            }
        }
}
