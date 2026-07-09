package dev.kuml.codegen.reverse.sql

/**
 * Postgres identifier folding for the SQL→ERM reverse engine (V3.4.9).
 *
 * JSqlParser preserves each identifier token verbatim — including the
 * surrounding double quotes when the source SQL quoted it (`"Users"` stays
 * `"Users"`, not `Users`). Postgres itself folds *unquoted* identifiers to
 * lowercase at parse time and leaves *quoted* identifiers exactly as
 * written, case-sensitive. [fold] mirrors that behaviour so table names
 * collected from `CREATE TABLE` and referenced from inline/table-level/
 * `ALTER TABLE` foreign keys resolve to the same key regardless of how the
 * DDL happened to case them — stolperfalle #3 of the V3.4.9 plan.
 */
internal object SqlIdentifiers {
    /**
     * Normalizes a raw JSqlParser identifier token: strips one pair of
     * surrounding double quotes (un-escaping a doubled `""` inside), or
     * lowercases the token when it carries no quotes.
     */
    fun fold(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1).replace("\"\"", "\"")
        } else {
            trimmed.lowercase()
        }
    }
}
