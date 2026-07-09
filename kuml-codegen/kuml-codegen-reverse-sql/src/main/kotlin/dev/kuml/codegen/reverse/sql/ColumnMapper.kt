package dev.kuml.codegen.reverse.sql

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ReferentialAction
import net.sf.jsqlparser.statement.create.table.ColumnDefinition

/**
 * Maps a single JSqlParser [ColumnDefinition] to an [ErmAttribute] (V3.4.9).
 *
 * JSqlParser does **not** parse inline column constraints (`PRIMARY KEY`,
 * `NOT NULL`, `UNIQUE`, `DEFAULT ...`, `REFERENCES ...`, `CHECK (...)`,
 * `GENERATED ALWAYS AS IDENTITY`) into structured fields — they arrive as a
 * flat [ColumnDefinition.getColumnSpecs] token list (e.g.
 * `["NOT", "NULL", "REFERENCES", "\"Users\"", "(id)"]`), with any parenthesized
 * expression (a `CHECK` body, a function-call `DEFAULT`) already grouped into
 * a single balanced token. [map] hand-parses that token stream.
 */
internal object ColumnMapper {
    /**
     * @property targetTableName folded target table name.
     * @property targetColumnName folded target column name, or `null` when the
     *   `REFERENCES` clause named no column (⇒ the target's primary key).
     */
    data class RawForeignKeyRef(
        val targetTableName: String,
        val targetColumnName: String?,
        val onDelete: ReferentialAction,
        val onUpdate: ReferentialAction,
    )

    data class MappedColumn(
        val attribute: ErmAttribute,
        val inlineForeignKey: RawForeignKeyRef?,
        val checkExpression: String?,
    )

    /** Token-scan boundary keywords — anything that starts a new inline-constraint clause. */
    private val BOUNDARY_KEYWORDS =
        setOf("NOT", "NULL", "PRIMARY", "UNIQUE", "DEFAULT", "REFERENCES", "CHECK", "COLLATE", "GENERATED")

    fun map(
        cd: ColumnDefinition,
        attrId: String,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
    ): MappedColumn {
        val name = SqlIdentifiers.fold(cd.columnName)
        val typeMapped = SqlTypeMapper.map(cd.colDataType)
        typeMapped.diagnosticCode?.let { code ->
            val message =
                when (code) {
                    "REV-SQL-015" -> "Column '$name': CHAR/CHARACTER mapped to Varchar — fixed-length padding semantics are lost."
                    else -> "Column '$name': type '${cd.colDataType.dataType}' has no direct ErmDataType equivalent — mapped to Custom."
                }
            diagnostics += ReverseDiagnostic(ReverseDiagnostic.Severity.INFO, code, message, file = fileHint)
        }

        val spec = parseSpecs(cd.columnSpecs.orEmpty(), diagnostics, fileHint, name)

        var autoIncrement = typeMapped.autoIncrement || spec.autoIncrement
        var default = spec.default
        // Postgres pg_dump expands SERIAL into `integer` + `DEFAULT nextval('..._seq'::regclass)` —
        // stolperfalle #6: detect that pattern and fold it into autoIncrement instead of keeping
        // a literal (and instance-specific, sequence-name-bearing) default expression.
        if (default != null && default.trim().startsWith("nextval(", ignoreCase = true)) {
            autoIncrement = true
            default = null
        }

        val attribute =
            ErmAttribute(
                id = attrId,
                name = name,
                type = typeMapped.type,
                primaryKey = spec.primaryKey,
                nullable = !spec.notNull,
                unique = spec.unique,
                default = default,
                autoIncrement = autoIncrement,
            )
        return MappedColumn(attribute, spec.referencesRef, spec.checkExpression)
    }

    private data class Spec(
        val notNull: Boolean = false,
        val primaryKey: Boolean = false,
        val unique: Boolean = false,
        val default: String? = null,
        val autoIncrement: Boolean = false,
        val referencesRef: RawForeignKeyRef? = null,
        val checkExpression: String? = null,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun parseSpecs(
        tokens: List<String>,
        diagnostics: MutableList<ReverseDiagnostic>,
        fileHint: String?,
        colName: String,
    ): Spec {
        var notNull = false
        var primaryKey = false
        var unique = false
        var default: String? = null
        var autoIncrement = false
        var fkRef: RawForeignKeyRef? = null
        var checkExpr: String? = null

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i].uppercase()
            when {
                tok == "NOT" && tokens.getOrNull(i + 1)?.uppercase() == "NULL" -> {
                    notNull = true
                    i += 2
                }
                tok == "NULL" -> i += 1
                tok == "PRIMARY" && tokens.getOrNull(i + 1)?.uppercase() == "KEY" -> {
                    primaryKey = true
                    notNull = true
                    i += 2
                }
                tok == "UNIQUE" -> {
                    unique = true
                    i += 1
                }
                tok == "DEFAULT" -> {
                    val first = tokens.getOrNull(i + 1)
                    if (first != null && first.equals("NULL", ignoreCase = true)) {
                        default = "NULL"
                        i += 2
                    } else {
                        val (value, next) = collectDefault(tokens, i + 1)
                        default = value
                        i = next
                    }
                }
                tok == "REFERENCES" -> {
                    val (ref, next) = parseInlineReferences(tokens, i + 1)
                    fkRef = ref
                    i = next
                }
                tok == "CHECK" -> {
                    val exprTok = tokens.getOrNull(i + 1)
                    checkExpr = exprTok?.removePrefix("(")?.removeSuffix(")")?.trim()
                    i += 2
                }
                tok == "GENERATED" -> {
                    // GENERATED ALWAYS AS IDENTITY / GENERATED BY DEFAULT AS IDENTITY (Postgres 10+
                    // identity columns) — modeled as autoIncrement, same as SERIAL.
                    autoIncrement = true
                    diagnostics +=
                        ReverseDiagnostic(
                            ReverseDiagnostic.Severity.INFO,
                            "REV-SQL-014",
                            "Column '$colName': GENERATED ... AS IDENTITY mapped as autoIncrement.",
                            file = fileHint,
                        )
                    var j = i + 1
                    while (j < tokens.size && tokens[j].uppercase() !in BOUNDARY_KEYWORDS) j++
                    i = j
                }
                tok == "COLLATE" -> i += 2
                else -> i += 1
            }
        }
        return Spec(notNull, primaryKey, unique, default, autoIncrement, fkRef, checkExpr)
    }

    private fun collectDefault(
        tokens: List<String>,
        start: Int,
    ): Pair<String, Int> {
        var j = start
        val parts = mutableListOf<String>()
        while (j < tokens.size && tokens[j].uppercase() !in BOUNDARY_KEYWORDS) {
            parts += tokens[j]
            j++
        }
        return parts.joinToString(" ") to j
    }

    private fun parseInlineReferences(
        tokens: List<String>,
        start: Int,
    ): Pair<RawForeignKeyRef?, Int> {
        val targetTableTok = tokens.getOrNull(start) ?: return null to start
        val targetTable = SqlIdentifiers.fold(targetTableTok)
        var next = start + 1
        var targetCol: String? = null
        val colTok = tokens.getOrNull(next)
        if (colTok != null && colTok.startsWith("(")) {
            targetCol = SqlIdentifiers.fold(colTok.removePrefix("(").removeSuffix(")"))
            next += 1
        }
        var onDelete = ReferentialAction.NO_ACTION
        var onUpdate = ReferentialAction.NO_ACTION
        while (next < tokens.size && tokens[next].uppercase() == "ON") {
            val kind = tokens.getOrNull(next + 1)?.uppercase()
            val a1 = tokens.getOrNull(next + 2)
            val a1u = a1?.uppercase()
            val twoWord = a1u == "NO" || a1u == "SET"
            val a2 = if (twoWord) tokens.getOrNull(next + 3) else null
            val action = parseInlineAction(a1, a2)
            val consumed = 2 + (if (twoWord) 2 else 1)
            when (kind) {
                "DELETE" -> onDelete = action
                "UPDATE" -> onUpdate = action
            }
            next += consumed
        }
        return RawForeignKeyRef(targetTable, targetCol, onDelete, onUpdate) to next
    }

    private fun parseInlineAction(
        tok1: String?,
        tok2: String?,
    ): ReferentialAction =
        when (tok1?.uppercase()) {
            "CASCADE" -> ReferentialAction.CASCADE
            "RESTRICT" -> ReferentialAction.RESTRICT
            "NO" -> ReferentialAction.NO_ACTION
            "SET" ->
                when (tok2?.uppercase()) {
                    "NULL" -> ReferentialAction.SET_NULL
                    "DEFAULT" -> ReferentialAction.SET_DEFAULT
                    else -> ReferentialAction.NO_ACTION
                }
            else -> ReferentialAction.NO_ACTION
        }
}
