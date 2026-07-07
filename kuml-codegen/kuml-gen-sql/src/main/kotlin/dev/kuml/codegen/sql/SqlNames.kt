package dev.kuml.codegen.sql

import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue

/**
 * Thrown by [SqlNames] when a table/column name — whether derived from a raw UML
 * class/property name or taken from an explicit `«Entity»{tableName=…}` /
 * `«Column»{name=…}` tag — is not a safe SQL identifier. UML names are plain,
 * unconstrained strings (see `UmlClass`/`UmlProperty`), so without this guard a
 * crafted model name (e.g. containing `"`, `;`, `--`, whitespace, or other SQL
 * metacharacters) would flow unescaped into [SqlDdlGenerator]'s string-interpolated
 * DDL (`CREATE TABLE $tableName (`, `ALTER TABLE $fkTable ...`), producing malformed
 * or SQL-injectable output. No mangling/escaping fallback is attempted here — an
 * unsafe name fails loudly instead of being silently rewritten into something the
 * model author didn't ask for.
 */
public class UnsafeSqlIdentifierException(
    message: String,
) : RuntimeException(message)

internal object SqlNames {
    /** `[A-Za-z_][A-Za-z0-9_]*`, capped at 63 chars (PostgreSQL's unquoted identifier limit). */
    private val SAFE_SQL_IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")

    /** Tabellen-Name aus Klasse — nutzt «Entity»{tableName=…} falls vorhanden, sonst lowercase + 's'. */
    fun tableName(
        className: String,
        stereotypes: List<AppliedStereotype>,
    ): String {
        val explicit =
            stereotypes
                .firstOrNull { it.stereotypeName == "Entity" }
                ?.tags
                ?.get("tableName")
                ?.stringOrNull()
        val name = explicit ?: pluralize(className.lowercase())
        return requireSafeIdentifier(name, what = "table name", source = className)
    }

    /** Spalten-Name aus Property — nutzt «Column»{name=…} falls vorhanden, sonst snake_case. */
    fun columnName(
        propertyName: String,
        stereotypes: List<AppliedStereotype>,
    ): String {
        val explicit =
            stereotypes
                .firstOrNull { it.stereotypeName == "Column" }
                ?.tags
                ?.get("name")
                ?.stringOrNull()
        val name = explicit ?: camelToSnake(propertyName)
        return requireSafeIdentifier(name, what = "column name", source = propertyName)
    }

    /**
     * Validates that [name] is a safe, unquoted SQL identifier before it is allowed to be
     * interpolated into generated DDL. Applied to both the explicit-tag path (`tableName`/
     * `name` tag values) and the derived-fallback path (lowercased/pluralized or
     * camelCase→snake_case UML names) — both are ultimately attacker/model-author controlled.
     *
     * @throws UnsafeSqlIdentifierException if [name] does not match [SAFE_SQL_IDENTIFIER_REGEX].
     */
    private fun requireSafeIdentifier(
        name: String,
        what: String,
        source: String,
    ): String {
        if (!SAFE_SQL_IDENTIFIER_REGEX.matches(name)) {
            throw UnsafeSqlIdentifierException(
                "kuml-gen-sql: derived $what '$name' (from '$source') is not a safe SQL identifier " +
                    "— only [A-Za-z_][A-Za-z0-9_]{0,62} is accepted, refusing to emit DDL.",
            )
        }
        return name
    }

    /** Simple Pluralisierung — V1.1.4 unterstützt nur reguläre Plurals. */
    fun pluralize(s: String): String =
        when {
            s.endsWith("s") || s.endsWith("x") || s.endsWith("ch") -> "${s}es"
            s.endsWith("y") -> s.dropLast(1) + "ies"
            else -> "${s}s"
        }

    /** Konvertiert camelCase / PascalCase nach snake_case. */
    fun camelToSnake(s: String): String =
        Regex("([a-z])([A-Z])")
            .replace(s) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .lowercase()

    private fun TagValue.stringOrNull(): String? = (this as? TagValue.StringVal)?.v
}
