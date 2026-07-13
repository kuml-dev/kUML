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

    /**
     * Parses a `«Column».sqlType` override string into an [ErmDataType].
     *
     * A `VARCHAR(n)`-shaped override (explicit length) is recognised first and mapped to
     * [ErmDataType.Varchar] with that length — without this, [map] only matches the bare
     * `"varchar"` keyword and any explicit length falls through to [ErmDataType.Custom],
     * which the Exposed emitter renders as a generic `text(...)` fallback instead of
     * `varchar(col, n)`.
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
        val mapped = map(trimmed)
        return if (mapped is ErmDataType.Custom) ErmDataType.Custom(trimmed) else mapped
    }
}
