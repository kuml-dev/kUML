package dev.kuml.transform.umlerm

/**
 * Name-derivation and identifier-safety helpers shared across
 * [UmlToErmTransformer]'s mapping phases.
 *
 * Lifted from [dev.kuml.codegen.m2m.exposed.UmlToExposedPsmTransformer]'s
 * `toSnakeCase`/`toPlural`/`requireSafeSqlIdentifier` conventions (kept as a
 * single shared object here rather than duplicated per-phase, since this
 * module has several call sites: entities, columns, junction tables, FKs).
 *
 * V3.4.6
 */
internal object SqlIdentifiers {
    private val SAFE = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")

    /** Converts a `PascalCase`/`camelCase` name to `snake_case`. */
    fun toSnakeCase(name: String): String {
        val sb = StringBuilder()
        for ((i, ch) in name.withIndex()) {
            if (ch.isUpperCase() && i > 0) sb.append('_')
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * Validates that [name] is safe to embed as a SQL identifier — UML names are
     * unconstrained strings, and V3.4.7's SQL-dialect generator will later
     * interpolate the derived identifier verbatim into DDL. Only
     * `[A-Za-z_][A-Za-z0-9_]{0,62}` (PostgreSQL's unquoted identifier limit) is
     * accepted; anything else fails the transform with [UnsafeUmlNameException]
     * rather than being silently mangled or passed through.
     *
     * @throws UnsafeUmlNameException if [name] is not a safe SQL identifier.
     */
    fun requireSafe(
        name: String,
        what: String,
        elementId: String,
    ): String {
        if (!SAFE.matches(name)) {
            throw UnsafeUmlNameException(
                "uml-to-erm: derived $what '$name' (element $elementId) is not a safe SQL identifier — " +
                    "only [A-Za-z_][A-Za-z0-9_]{0,62} is accepted, refusing to transform.",
            )
        }
        return name
    }
}

/** Naive English pluralisation — same rule as the Exposed PSM transformer. */
internal fun String.toPlural(): String =
    when {
        endsWith("y") && length > 1 && this[length - 2].lowercaseChar() !in "aeiou" ->
            dropLast(1) + "ies"
        endsWith("s") || endsWith("x") || endsWith("z") || endsWith("ch") || endsWith("sh") ->
            "${this}es"
        else -> "${this}s"
    }

/** Naive English singularisation — inverse-ish of [toPlural], used for FK column name derivation. */
internal fun String.toSingular(): String =
    when {
        endsWith("ies") && length > 3 -> "${dropLast(3)}y"
        endsWith("ses") || endsWith("xes") || endsWith("zes") || endsWith("ches") || endsWith("shes") ->
            dropLast(2)
        endsWith("s") && !endsWith("ss") -> dropLast(1)
        else -> this
    }

/**
 * Thrown by [UmlToErmTransformer] when a class/attribute/association name yields a
 * derived table/column/constraint identifier that is not safe to embed in generated
 * SQL DDL. See [SqlIdentifiers.requireSafe].
 */
public class UnsafeUmlNameException(
    message: String,
) : RuntimeException(message)

/**
 * Thrown by [UmlToErmTransformer] when a `«Column».fkEntity`/`fkAttribute` override
 * names a UML class or target column that does not exist in the diagram — a typo in
 * an explicit, user-authored FK override must fail loudly rather than silently
 * leaving the column unreferenced (unlike association-derived FKs, which are only
 * ever synthesized from structures the transformer itself already validated).
 */
public class UnresolvedColumnForeignKeyException(
    message: String,
) : RuntimeException(message)
