package dev.kuml.codegen.api.customtype

/**
 * ADR-0016 §2.3 — a fixed, named set of PostGIS geometry kinds recognized by
 * [PostGisTypeHook]. Deliberately not a general GIS type system: no SRID
 * catalog, no `exposed-spatial`/PostGIS runtime dependency, just enough to
 * let [ErmDataType.Custom][dev.kuml.erm.model.ErmDataType.Custom] strings of
 * the shape `geometry(Point,4326)` be recognized and rendered consistently
 * by both the SQL DDL emitter and the Exposed emitter.
 */
public enum class PostGisGeometryKind(
    public val pg: String,
) {
    POINT("Point"),
    LINESTRING("LineString"),
    POLYGON("Polygon"),
    GEOMETRY("Geometry"),
}

/**
 * A recognized PostGIS geometry descriptor, parsed from an
 * [ErmDataType.Custom][dev.kuml.erm.model.ErmDataType.Custom] raw string by
 * [PostGisTypeHook].
 */
public data class PostGisGeometryType(
    val kind: PostGisGeometryKind,
    val srid: Int?,
) {
    /** Canonical Postgres column type, e.g. `"geometry(Point,4326)"` / `"geometry(Polygon)"`. */
    public fun postgresType(): String = if (srid != null) "geometry(${kind.pg},$srid)" else "geometry(${kind.pg})"
}

/**
 * A single custom-type recognizer for an [ErmDataType.Custom][dev.kuml.erm.model.ErmDataType.Custom]
 * raw string. A fixed set ([CustomTypeHooks.DEFAULT]) is wired in today; this
 * interface exists to keep the recognizer set open in code without
 * introducing a runtime ServiceLoader/plugin SPI (out of scope, see ADR-0016 §2.3).
 */
public fun interface CustomTypeHook {
    public fun recognize(raw: String): PostGisGeometryType?
}

/**
 * Recognizes PostGIS geometry column descriptors of the shape
 * `geometry(Point|LineString|Polygon|Geometry[,SRID])` — whitespace-tolerant
 * and case-insensitive on the keyword.
 *
 * Security: the regex is anchored ([Regex.matchEntire]), the geometry keyword
 * is whitelisted (no wildcard alternation that could backtrack), and the SRID
 * capture group is bounded to at most 7 digits — a DoS guard against
 * pathologically long numeric input.
 */
public object PostGisTypeHook : CustomTypeHook {
    private val RE =
        Regex(
            """^\s*geometry\s*\(\s*(point|linestring|polygon|geometry)\s*(?:,\s*(\d{1,7})\s*)?\)\s*$""",
            RegexOption.IGNORE_CASE,
        )

    override fun recognize(raw: String): PostGisGeometryType? {
        val m = RE.matchEntire(raw) ?: return null
        val kind =
            when (m.groupValues[1].lowercase()) {
                "point" -> PostGisGeometryKind.POINT
                "linestring" -> PostGisGeometryKind.LINESTRING
                "polygon" -> PostGisGeometryKind.POLYGON
                else -> PostGisGeometryKind.GEOMETRY
            }
        val srid = m.groupValues[2].ifEmpty { null }?.toIntOrNull()
        return PostGisGeometryType(kind, srid)
    }
}

/**
 * The fixed set of custom-type recognizers consulted by [ErmSqlTypeMapper][dev.kuml.codegen.sql.ErmSqlTypeMapper]
 * and [ErmExposedEmitter][dev.kuml.codegen.m2m.exposed.ErmExposedEmitter]. [PostGisTypeHook] is the only
 * entry today — extensible in code, not a runtime plugin surface (ADR-0016 §2.3 scoping decision).
 */
public object CustomTypeHooks {
    public val DEFAULT: List<CustomTypeHook> = listOf(PostGisTypeHook)

    public fun recognize(raw: String): PostGisGeometryType? = DEFAULT.firstNotNullOfOrNull { it.recognize(raw) }
}
