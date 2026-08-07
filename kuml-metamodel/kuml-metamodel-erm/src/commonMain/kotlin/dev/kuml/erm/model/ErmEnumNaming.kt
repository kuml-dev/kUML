package dev.kuml.erm.model

private val NON_ALPHANUMERIC_REGEX = Regex("[^A-Za-z0-9]+")

/**
 * Kotlin-safe PascalCase constant name for an [ErmDataType.Enum] literal — splits on any run of
 * non-alphanumeric characters (not just `_`), so a human-readable literal like `"In Progress"`
 * becomes `InProgress` instead of hard-failing generation. A leading digit gets an underscore
 * prefix (Kotlin identifiers can't start with one).
 *
 * Shared single source of truth for every code generator that needs to know whether an enum
 * literal is already a valid Kotlin identifier verbatim — currently `kuml-codegen-m2m-exposed`'s
 * `ErmExposedEmitter` (which emits a plain `enum class` + `Table.enumerationByName<T>(...)` when
 * every literal already is, or a `dbValue`-carrying `enum class` + `Table.customEnumeration(...)`
 * when at least one literal needed sanitizing) and `kuml-gen-sql`'s `ErmSqlEmitter` (which must
 * know the same thing to decide whether a DB-level `DEFAULT` clause for such a column is safe to
 * emit — see [ermEnumNeedsCustomMapping]'s KDoc). Extracted here for exactly the reason
 * [ermDefaultForeignKeyConstraintName] was: two independent emitters computing the same
 * information from first principles is how they silently disagree.
 */
public fun sanitizeEnumConstantName(raw: String): String {
    val cleaned =
        raw
            .split(NON_ALPHANUMERIC_REGEX)
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
    if (cleaned.isEmpty()) return ""
    return if (cleaned[0].isDigit()) "_$cleaned" else cleaned
}

/**
 * `true` when at least one of [values] needs [sanitizeEnumConstantName] to differ from the raw
 * literal itself — i.e. the enum is rendered via `Table.customEnumeration(...)` (a `dbValue`
 * constructor field + `fromDb`/`toDb` lambdas) rather than the simpler `Table.enumerationByName<T>(...)`.
 *
 * `ErmExposedEmitter` (`erm-to-exposed`) skips emitting a typed `.default(...)` for such a column
 * — empirically verified against a real Postgres container (Portal-Server's `SchemaSmokeTest`,
 * 2026-08-06) that Exposed 1.3.1's schema-diff (`MigrationUtils.statementsRequiredForDatabaseMigration`)
 * cannot correctly recognize a default value round-tripped through `customEnumeration`'s `toDb`
 * lambda: it keeps proposing a `SET DEFAULT`/`DROP DEFAULT` statement regardless of whether the
 * Kotlin side declares the matching default or not. `ErmSqlEmitter` (`erm-to-sql-flyway-baseline`)
 * uses this same predicate to skip the DDL-level `DEFAULT` clause for exactly the same columns —
 * so the two emitters agree (neither side declares a default), rather than the migration setting a
 * real DB default that Exposed's own tooling can never confirm matches the generated `Table`.
 */
public fun ermEnumNeedsCustomMapping(values: List<String>): Boolean = values.any { sanitizeEnumConstantName(it) != it }
