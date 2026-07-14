package dev.kuml.transform.umlerm

import dev.kuml.erm.model.ErmDataType

/**
 * Maps a UML property's type name to a dialect-neutral [ErmDataType].
 *
 * Matching is case-insensitive. Unknown type names fall back to
 * [ErmDataType.Custom] so the transformer never silently drops a type.
 *
 * A `«Column».sqlType` override (see [dev.kuml.profile.erm.ErmMappingProfile])
 * takes precedence over this table when present and parseable — see
 * [UmlToErmTransformer]'s attribute mapping.
 *
 * V3.4.6
 */
internal object UmlErmTypeMapper {
    fun map(umlTypeName: String): ErmDataType =
        when (umlTypeName.trim().lowercase()) {
            "string", "str", "varchar" -> ErmDataType.Varchar()
            "text", "clob" -> ErmDataType.Text
            "int", "integer" -> ErmDataType.Integer(32)
            "long", "bigint" -> ErmDataType.Integer(64)
            "short" -> ErmDataType.Integer(16)
            "boolean", "bool" -> ErmDataType.Boolean
            "double", "float", "real" -> ErmDataType.Real(double = true)
            "decimal", "bigdecimal", "money" -> ErmDataType.Decimal(precision = 19, scale = 2)
            "uuid" -> ErmDataType.Uuid
            "date", "localdate" -> ErmDataType.Date
            "time", "localtime" -> ErmDataType.Time
            "datetime", "timestamp", "instant", "localdatetime" -> ErmDataType.Timestamp(withTimeZone = false)
            "offsetdatetime", "zoneddatetime" -> ErmDataType.Timestamp(withTimeZone = true)
            "blob", "bytearray" -> ErmDataType.Blob
            "json", "jsonb" -> ErmDataType.Json
            else -> ErmDataType.Custom(umlTypeName)
        }

    /** Matches a `VARCHAR(n)` / `varchar( n )`-shaped override, case-insensitive. */
    private val VARCHAR_N_REGEX = Regex("""varchar\s*\(\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)

    /** Matches a `DECIMAL(p,s)` / `decimal( p , s )`-shaped override, case-insensitive. */
    private val DECIMAL_P_S_REGEX = Regex("""decimal\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)

    /**
     * Parses a `«Column».sqlType` override string into an [ErmDataType].
     *
     * A `VARCHAR(n)`-shaped override (explicit length) is recognised first and mapped to
     * [ErmDataType.Varchar] with that length — without this, [map] only matches the bare
     * `"varchar"` keyword and any explicit length falls through to [ErmDataType.Custom],
     * which the Exposed emitter renders as a generic `text(...)` fallback instead of
     * `varchar(col, n)`.
     *
     * A `DECIMAL(p,s)`-shaped override (explicit precision/scale) is recognised next and
     * mapped to [ErmDataType.Decimal] with that precision/scale — same rationale as the
     * `VARCHAR(n)` case: without this, [map] only matches the bare `"decimal"`/`"bigdecimal"`/
     * `"money"` keywords (falling back to a fixed `Decimal(19, 2)`), so any explicit
     * precision/scale in the override string fell through to [ErmDataType.Custom] and the
     * Exposed emitter rendered a generic `text(...)` fallback instead of `decimal(col, p, s)`
     * — found during a real MDA retrofit where every monetary column's hand-written
     * `decimal("col", p, s)` call carried a project-specific precision/scale that the bare
     * `"decimal"` keyword's fixed default couldn't reproduce.
     *
     * Otherwise reuses [map]'s vocabulary; anything unrecognised (including dialect-specific
     * types such as Postgres `tsvector`) becomes [ErmDataType.Custom] holding the
     * raw override string verbatim, so the override is never lost even if it can't
     * be interpreted structurally.
     */
    fun mapOverride(sqlType: String): ErmDataType {
        val trimmed = sqlType.trim()
        if (trimmed.isEmpty()) return ErmDataType.Custom(sqlType)
        VARCHAR_N_REGEX.matchEntire(trimmed)?.let { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { length ->
                return ErmDataType.Varchar(length)
            }
        }
        DECIMAL_P_S_REGEX.matchEntire(trimmed)?.let { match ->
            val precision = match.groupValues[1].toIntOrNull()
            val scale = match.groupValues[2].toIntOrNull()
            if (precision != null && precision > 0 && scale != null && scale >= 0 && scale <= precision) {
                return ErmDataType.Decimal(precision, scale)
            }
        }
        val mapped = map(trimmed)
        return if (mapped is ErmDataType.Custom) ErmDataType.Custom(trimmed) else mapped
    }
}
