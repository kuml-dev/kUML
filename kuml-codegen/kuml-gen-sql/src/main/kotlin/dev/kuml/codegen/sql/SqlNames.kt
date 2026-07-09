package dev.kuml.codegen.sql

/**
 * Thrown by [SqlNames] when a table/column/constraint/index/view name — taken
 * verbatim from an [dev.kuml.erm.model.ErmModel] (whether derived by
 * `UmlToErmTransformer` from a UML name, or set directly by an ERM-first
 * `.kuml.kts` script) — is not a safe SQL identifier. ERM element names are
 * plain, unconstrained strings, so without this guard a crafted name (e.g.
 * containing `"`, `;`, `--`, whitespace, or other SQL metacharacters) would
 * flow unescaped into [ErmSqlEmitter]'s string-interpolated DDL, producing
 * malformed or SQL-injectable output. No mangling/escaping fallback is
 * attempted here — an unsafe name fails loudly instead of being silently
 * rewritten into something the model author didn't ask for.
 *
 * V1.1.4 introduced this for the UML-only [SqlDdlGenerator]; V3.4.7 narrowed
 * [SqlNames] down to just this identifier-safety guard — table/column-name
 * *derivation* (stereotype-tag lookup, pluralisation, camelCase→snake_case)
 * now lives in `UmlToErmTransformer`'s `SqlIdentifiers`, since the DDL
 * generator consumes an already-named [dev.kuml.erm.model.ErmModel] rather
 * than deriving names itself from raw UML.
 */
public class UnsafeSqlIdentifierException(
    message: String,
) : RuntimeException(message)

internal object SqlNames {
    /** `[A-Za-z_][A-Za-z0-9_]*`, capped at 63 chars (PostgreSQL's unquoted identifier limit). */
    private val SAFE_SQL_IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")

    /**
     * Validates that [name] is a safe, unquoted SQL identifier before it is allowed to be
     * interpolated into generated DDL. Applied to every table/column/constraint/index/view
     * name [ErmSqlEmitter] reads from an [dev.kuml.erm.model.ErmModel] — defense in depth
     * even for the UML-direct path (whose names were already validated once by
     * `UmlToErmTransformer`'s `SqlIdentifiers.requireSafe`), since an ERM-first script can
     * set names directly and must not be able to rely on that earlier gate.
     *
     * @throws UnsafeSqlIdentifierException if [name] does not match [SAFE_SQL_IDENTIFIER_REGEX].
     */
    fun requireSafe(
        name: String,
        what: String,
        source: String,
    ): String {
        if (!SAFE_SQL_IDENTIFIER_REGEX.matches(name)) {
            throw UnsafeSqlIdentifierException(
                "kuml-gen-sql: $what '$name' (from '$source') is not a safe SQL identifier " +
                    "— only [A-Za-z_][A-Za-z0-9_]{0,62} is accepted, refusing to emit DDL.",
            )
        }
        return name
    }
}
