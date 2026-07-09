package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformError
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass

/**
 * Transforms a UML class diagram into Kotlin Exposed `Table` object source files.
 *
 * For each [UmlClass] in the diagram:
 * - Emits one Kotlin `object <Plural> : Table("<snake_plural>") { ... }`
 * - Each [dev.kuml.uml.UmlProperty] (attribute) becomes an Exposed column val
 *   (`varchar`, `integer`, `bool`, `double`, …)
 * - The attribute named `"id"` (case-insensitive) or with stereotype `«id»`
 *   becomes `val id = long("id").autoIncrement()`
 * - If no id attribute exists, a synthetic `val id = long("id").autoIncrement()` is generated
 * - `override val primaryKey = PrimaryKey(id)` is always emitted
 * - Association with `upper == 1` on the *target* end → FK column via `.reference()`
 *   on the *source* class (many-to-one / one-to-one direction)
 * - Association with `upper == null` or `upper > 1` on the target end → **not** emitted
 *   as a Table property (Exposed has no collection-valued Table columns; that is a
 *   DAO/query-layer concern) — a one-line comment is left instead
 * - Self-referential associations (source class == target class) are skipped with a
 *   comment: referencing the enclosing `object` from inside its own initializer body
 *   does not compile in Kotlin/Exposed, so no `reference()` call is emitted for them.
 * - Stereotype `«transient»` on attribute → attribute is skipped entirely (not a DB column);
 *   a comment is left noting the skip
 *
 * Output: one [GeneratedFile] per [UmlClass], path = `"<Plural>.kt"`.
 *
 * Known limitations (not defended against in this wave):
 * - UML attribute names colliding with `org.jetbrains.exposed.sql.Table` members
 *   (e.g. `columns`, `primaryKey`, `tableName`, `ddl`) will produce non-compiling
 *   output in a real consuming project.
 * - Kotlin hard keywords as attribute/class names (e.g. `class`, `object`, `fun`) are
 *   rejected outright with a [TransformError] rather than escaped with backticks — see
 *   "Generated-code-injection / path-traversal defenses" below.
 * - Two differently-cased attributes that snake_case to the same column name
 *   (e.g. `orderId` and `order_id` on the same class) produce two Kotlin `val`s with
 *   the same underlying Exposed column name — compiles in Kotlin, invalid at the
 *   DB/DDL level.
 * - Association direction is inferred purely from end-list position (`ends[0]` =
 *   source/owning side). A class that only ever appears as `ends[1]` never receives
 *   an FK column, even for 1:1 relationships — identical limitation to
 *   [dev.kuml.codegen.m2m.jpa.UmlToJpaTransformer].
 * - Naive `toPlural()` heuristic does not handle irregular plurals (e.g. `Person` →
 *   `Persons`, not `People`) — same limitation as the JPA transformer's pluralizer.
 *
 * V2.x deferred: PostGIS geometry columns, TimescaleDB hypertable annotations,
 * composite/multi-column primary keys, `«embedded»` (Exposed has no direct embeddable
 * column-group concept), DAO/`Entity` class generation (this transformer emits only
 * the `Table` DSL layer, not `org.jetbrains.exposed.dao.*`), nullable FK columns for
 * `lower == 0` association ends (`.nullable()`).
 *
 * `kuml-gen-sql` (PostgreSQL DDL generator, already exists) is the intended next step
 * for producing the Flyway `V1__init.sql` baseline — not wired up in this wave.
 *
 * ### Generated-code-injection / path-traversal defenses
 *
 * The generated output is plain Kotlin *source text* that a consuming project may later
 * compile, so UML-model-controlled strings (class names, attribute names, package name)
 * must never be able to break out of a Kotlin string-literal or identifier position:
 * - Every string embedded inside a Kotlin string literal (table/column names passed to
 *   `Table(...)`, `varchar(...)`, `reference(...)`, etc.) is escaped via [kotlinStringLiteral]
 *   (escapes `\`, `"`, newlines, carriage returns, tabs, and `$` template-interpolation starts).
 * - Every UML name used as a Kotlin *identifier* (class name → object name, attribute name →
 *   property name) is validated by [requireValidKotlinIdentifier] against the Kotlin
 *   identifier grammar and the Kotlin hard-keyword list; invalid names fail the transform
 *   with a [TransformError] instead of being silently embedded (no best-effort mangling that
 *   could still be gamed).
 * - [GeneratedFile.relativePath] is derived from the validated object name only (never from
 *   raw, unsanitized UML text) and is additionally checked to be a single path segment with
 *   no `/`, `\`, or `..` — see [requireSafeRelativePath].
 *
 * ### Superseded by the ERM path (V3.4.8)
 *
 * `erm-to-exposed` / `uml-to-exposed-via-erm` (V3.4.8,
 * [dev.kuml.codegen.m2m.exposed.ErmToExposedTransformer] /
 * [dev.kuml.codegen.m2m.exposed.UmlToExposedViaErmScriptTransformer]) chain
 * `UmlToErmTransformer`'s typed [dev.kuml.erm.model.ErmModel] into
 * [dev.kuml.codegen.m2m.exposed.ErmExposedEmitter] and emit genuine junction
 * `Table` objects for many-to-many associations (composite primary key)
 * instead of this transformer's `// *-to-many not represented` comment. This
 * class remains fully supported for backward compatibility — its own tests
 * are unmodified and green — but new integrations should prefer the ERM path.
 */
@Deprecated(
    "Superseded by the uml-to-erm + erm-to-exposed chain (uml-to-exposed-via-erm). Kept for backward compatibility.",
)
public class UmlToExposedTransformer : KumlTransformer<KumlDiagram, List<GeneratedFile>> {
    override val id: String = "uml-to-exposed"
    override val description: String =
        "UML class diagram → Kotlin Exposed Table objects (Table, reference(), …)"

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> {
        val packageName = ctx.options["package"] ?: DEFAULT_PACKAGE
        val errors = mutableListOf<TransformError>()
        val files = mutableListOf<GeneratedFile>()
        var trace = TransformTrace()

        val classById: Map<String, UmlClass> =
            source.elements.filterIsInstance<UmlClass>().associateBy { it.id }
        val classIdByName: Map<String, String> =
            source.elements.filterIsInstance<UmlClass>().associate { it.name to it.id }
        val associations: List<UmlAssociation> =
            source.elements.filterIsInstance<UmlAssociation>()

        // Table object name lookup needed for reference() targets (Plural object name)
        val tableObjectNameById: Map<String, String> =
            classById.mapValues { (_, cls) -> toTableObjectName(cls.name) }

        for (cls in classById.values) {
            val result =
                generateTable(
                    cls = cls,
                    packageName = packageName,
                    classById = classById,
                    classIdByName = classIdByName,
                    associations = associations,
                    tableObjectNameById = tableObjectNameById,
                )
            when (result) {
                is TableResult.Ok -> {
                    files += result.file
                    trace = trace.plus(TraceabilityLink(cls.id, result.file.relativePath, RULE_CLASS_TO_TABLE))
                }
                is TableResult.Error -> errors += result.error
            }
        }

        if (errors.isNotEmpty()) return TransformResult.Failure(errors)
        return TransformResult.Success(files, trace)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private sealed class TableResult {
        data class Ok(
            val file: GeneratedFile,
        ) : TableResult()

        data class Error(
            val error: TransformError,
        ) : TableResult()
    }

    private fun generateTable(
        cls: UmlClass,
        packageName: String,
        classById: Map<String, UmlClass>,
        classIdByName: Map<String, String>,
        associations: List<UmlAssociation>,
        tableObjectNameById: Map<String, String>,
    ): TableResult {
        try {
            requireValidKotlinIdentifier(cls.name, "class name", cls.id)
        } catch (e: InvalidIdentifierException) {
            return TableResult.Error(TransformError(e.message ?: "invalid class name", cls.id))
        }

        val objectName = toTableObjectName(cls.name) // e.g. "Users"
        val tableName = toSnakeCase(cls.name).toPlural() // e.g. "users"

        try {
            requireSafeRelativePath("$objectName.kt", cls.id)
        } catch (e: InvalidIdentifierException) {
            return TableResult.Error(TransformError(e.message ?: "unsafe generated file path", cls.id))
        }

        val sb = StringBuilder()

        sb.appendLine("// Generated by kuml-codegen-m2m (uml-to-exposed) — do not edit manually.")
        sb.appendLine()
        sb.appendLine("package $packageName")
        sb.appendLine()
        sb.appendLine("import org.jetbrains.exposed.sql.Column")
        sb.appendLine("import org.jetbrains.exposed.sql.Table")
        sb.appendLine()
        sb.appendLine("public object $objectName : Table(\"${kotlinStringLiteral(tableName)}\") {")

        val lines = mutableListOf<String>()
        var hasIdAttribute = false
        var idPropertyName = "id"

        for (attr in cls.attributes) {
            val attrName = attr.name
            try {
                requireValidKotlinIdentifier(attrName, "attribute name", cls.id)
            } catch (e: InvalidIdentifierException) {
                return TableResult.Error(TransformError(e.message ?: "invalid attribute name", cls.id))
            }

            val colName = toSnakeCase(attrName)
            val escapedColName = kotlinStringLiteral(colName)
            val stereotypeNames =
                attr.stereotypes.map { it.lowercase() } +
                    attr.appliedStereotypes.map { it.stereotypeName.lowercase() }

            when {
                attrName.lowercase() == "id" || "id" in stereotypeNames -> {
                    hasIdAttribute = true
                    idPropertyName = attrName
                    lines += "    public val $attrName: Column<Long> = long(\"$escapedColName\").autoIncrement()"
                }
                "transient" in stereotypeNames -> {
                    lines += "    // '${commentSafe(attrName)}' is «transient» — skipped, not persisted as an Exposed column."
                }
                else -> {
                    val exposedCall = exposedColumnCall(attr.type.name, escapedColName)
                    lines += "    public val $attrName: ${exposedKotlinColumnType(attr.type.name)} = $exposedCall"
                }
            }
        }

        // Association FK columns (many-to-one / one-to-one side only)
        val assocLines =
            try {
                buildAssociationColumns(cls, classById, classIdByName, associations, tableObjectNameById)
            } catch (e: InvalidIdentifierException) {
                return TableResult.Error(TransformError(e.message ?: "invalid association-derived name", cls.id))
            }
        lines.addAll(assocLines.columnLines)

        // Synthetic id if none found
        if (!hasIdAttribute) {
            lines.add(
                0,
                "    // Synthetic primary key — no «id» attribute found in the UML model.\n" +
                    "    public val id: Column<Long> = long(\"id\").autoIncrement()",
            )
            idPropertyName = "id"
        }

        for (line in lines) {
            sb.appendLine(line)
        }
        if (lines.isNotEmpty()) sb.appendLine()

        sb.appendLine("    override val primaryKey: PrimaryKey = PrimaryKey($idPropertyName)")

        // One-to-many collection note (Exposed has no Table-level collection columns)
        if (assocLines.collectionNoteNeeded) {
            sb.appendLine()
            sb.appendLine(
                "    // Note: *-to-many association(s) are not represented on the Table object.",
            )
            sb.appendLine(
                "    // Exposed models collections at the DAO/query layer, not as Table columns.",
            )
        }

        // Self-referential association note
        if (assocLines.selfReferenceNoteNeeded) {
            sb.appendLine()
            sb.appendLine(
                "    // Note: self-referential association(s) skipped — referencing the enclosing",
            )
            sb.appendLine(
                "    // object from inside its own initializer body does not compile in Kotlin/Exposed.",
            )
        }

        sb.append("}")
        sb.appendLine()

        return TableResult.Ok(GeneratedFile("$objectName.kt", sb.toString()))
    }

    private data class AssociationColumns(
        val columnLines: List<String>,
        val collectionNoteNeeded: Boolean,
        val selfReferenceNoteNeeded: Boolean,
    )

    private fun buildAssociationColumns(
        cls: UmlClass,
        classById: Map<String, UmlClass>,
        classIdByName: Map<String, String>,
        associations: List<UmlAssociation>,
        tableObjectNameById: Map<String, String>,
    ): AssociationColumns {
        val lines = mutableListOf<String>()
        var collectionNoteNeeded = false
        var selfReferenceNoteNeeded = false

        for (assoc in associations) {
            if (assoc.ends.size != 2) continue
            val sourceEnd = assoc.ends[0]
            val targetEnd = assoc.ends[1]

            val sourceId = sourceEnd.typeId
            val targetId = targetEnd.typeId
            val matchesSource =
                sourceId == cls.id ||
                    sourceId == cls.name ||
                    classIdByName[sourceId] == cls.id

            if (!matchesSource) continue

            val targetClass =
                classById[targetId] ?: classById[classIdByName[targetId] ?: ""] ?: continue
            val upper = targetEnd.multiplicity.upper

            if (upper == null || upper > 1) {
                // *-to-many: not representable on the Table object — leave a note, emit nothing.
                collectionNoteNeeded = true
                continue
            }

            if (targetClass.id == cls.id) {
                // Self-referential association: reference() cannot target the enclosing
                // object from inside its own initializer body.
                selfReferenceNoteNeeded = true
                continue
            }

            // many-to-one / one-to-one: FK column via reference()
            requireValidKotlinIdentifier(targetClass.name, "association target class name", cls.id)
            val targetObjectName = tableObjectNameById[targetClass.id] ?: toTableObjectName(targetClass.name)
            val propName = targetClass.name.replaceFirstChar { it.lowercase() }
            val fkColName = "${toSnakeCase(propName)}_id"
            val fkPropertyName = "${propName}Id"
            requireValidKotlinIdentifier(fkPropertyName, "derived FK property name", cls.id)
            lines +=
                "    public val $fkPropertyName: Column<Long> = " +
                "reference(\"${kotlinStringLiteral(fkColName)}\", $targetObjectName)"
        }

        return AssociationColumns(lines, collectionNoteNeeded, selfReferenceNoteNeeded)
    }

    // ── Type mapping ──────────────────────────────────────────────────────────

    /** Kotlin-side declared type of the generated `val` (for readability/explicit typing). */
    private fun exposedKotlinColumnType(umlType: String): String =
        when (umlType.lowercase()) {
            "string", "str" -> "Column<String>"
            "integer", "int" -> "Column<Int>"
            "long" -> "Column<Long>"
            "boolean", "bool" -> "Column<Boolean>"
            "double", "float" -> "Column<Double>"
            else -> "Column<String>"
        }

    /** Right-hand-side Exposed DSL call that produces the column. */
    private fun exposedColumnCall(
        umlType: String,
        colName: String,
    ): String =
        when (umlType.lowercase()) {
            "string", "str" -> "varchar(\"$colName\", 255)"
            "integer", "int" -> "integer(\"$colName\")"
            "long" -> "long(\"$colName\")"
            "boolean", "bool" -> "bool(\"$colName\")"
            "double", "float" -> "double(\"$colName\")"
            else -> "varchar(\"$colName\", 255)"
        }

    // ── Name conversion ───────────────────────────────────────────────────────

    private fun toSnakeCase(name: String): String {
        val sb = StringBuilder()
        for ((i, ch) in name.withIndex()) {
            if (ch.isUpperCase() && i > 0) sb.append('_')
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun String.toPlural(): String =
        when {
            endsWith("y") && length > 1 && this[length - 2].lowercaseChar() !in "aeiou" ->
                dropLast(1) + "ies"
            endsWith("s") ||
                endsWith("x") ||
                endsWith("z") ||
                endsWith("ch") ||
                endsWith("sh") -> "${this}es"
            else -> "${this}s"
        }

    /** PascalCase pluralized Kotlin object name, e.g. "User" -> "Users", "Category" -> "Categories". */
    private fun toTableObjectName(className: String): String {
        val plural = className.toPlural()
        return plural.replaceFirstChar { it.uppercase() }
    }

    // ── Generated-code-injection / path-traversal defenses ─────────────────────

    /**
     * Escapes a plain string so it is safe to embed inside a Kotlin double-quoted string
     * literal (`"..."`). Escapes backslash, double quote, `$` (template interpolation),
     * newline, carriage return, and tab. This is applied to every UML-model-controlled
     * string that is embedded as *string-literal content* in the generated Kotlin source
     * (table names, column names) — never to strings used as Kotlin identifiers, which are
     * instead validated by [requireValidKotlinIdentifier] and rejected outright if unsafe.
     */
    private fun kotlinStringLiteral(raw: String): String =
        buildString {
            for (ch in raw) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

    /**
     * Makes a string safe to embed inside a single-line `//` Kotlin comment: strips
     * newlines/carriage returns (which would otherwise let attacker-controlled UML names
     * inject new lines of "commented-out" — or worse, no-longer-commented — source) and
     * neutralizes the block-comment terminator sequence (star-slash) defensively.
     */
    private fun commentSafe(raw: String): String =
        raw
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("$STAR$SLASH", "$STAR $SLASH")

    /**
     * Validates that [name] is safe to emit verbatim as a Kotlin identifier (Kotlin object
     * name, `val` property name): must match the plain Kotlin identifier grammar
     * (`[a-zA-Z_][a-zA-Z0-9_]*`) and must not be a Kotlin hard keyword. No backtick-escaping
     * or mangling fallback is attempted — an invalid name fails the transform with a
     * [TransformError] via the caller, so a crafted UML model cannot smuggle Kotlin syntax
     * (string-literal breakout, comment breakout, keyword redefinition, etc.) into the
     * generated source.
     *
     * @throws InvalidIdentifierException if [name] is not a safe Kotlin identifier.
     */
    private fun requireValidKotlinIdentifier(
        name: String,
        what: String,
        elementId: String,
    ) {
        if (!KOTLIN_IDENTIFIER_REGEX.matches(name)) {
            throw InvalidIdentifierException(
                "uml-to-exposed: $what '$name' (element $elementId) is not a safe Kotlin identifier " +
                    "— only [a-zA-Z_][a-zA-Z0-9_]* is accepted, refusing to emit generated code.",
            )
        }
        if (name in KOTLIN_HARD_KEYWORDS) {
            throw InvalidIdentifierException(
                "uml-to-exposed: $what '$name' (element $elementId) is a Kotlin hard keyword " +
                    "— refusing to emit generated code.",
            )
        }
    }

    /**
     * Validates that a [GeneratedFile.relativePath] is a single, safe path segment: no path
     * separators (`/`, `\`), and not a `.`/`..` traversal segment. Because [relativePath] here
     * is always built from a name already checked by [requireValidKotlinIdentifier] (which
     * disallows `/`, `\`, and `.` entirely), this is a defense-in-depth belt-and-braces check
     * rather than the primary defense.
     *
     * @throws InvalidIdentifierException if [relativePath] is not a safe single path segment.
     */
    private fun requireSafeRelativePath(
        relativePath: String,
        elementId: String,
    ) {
        val hasSeparator = relativePath.contains('/') || relativePath.contains('\\')
        val isTraversal = relativePath == "." || relativePath == ".."
        if (hasSeparator || isTraversal || relativePath.isBlank()) {
            throw InvalidIdentifierException(
                "uml-to-exposed: generated file path '$relativePath' (element $elementId) is not a safe " +
                    "single path segment — refusing to write outside the output directory.",
            )
        }
    }

    private class InvalidIdentifierException(
        message: String,
    ) : RuntimeException(message)

    private companion object {
        const val DEFAULT_PACKAGE = "com.example.tables"
        const val RULE_CLASS_TO_TABLE = "uml-class-to-exposed-table"

        val KOTLIN_IDENTIFIER_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

        // Split to avoid an accidental block-comment-terminator sequence inside this source file's own KDoc/comments.
        const val STAR = "*"
        const val SLASH = "/"

        // Kotlin hard keywords (always reserved, cannot be used as identifiers without backticks).
        val KOTLIN_HARD_KEYWORDS =
            setOf(
                "as",
                "break",
                "class",
                "continue",
                "do",
                "else",
                "false",
                "for",
                "fun",
                "if",
                "in",
                "interface",
                "is",
                "null",
                "object",
                "package",
                "return",
                "super",
                "this",
                "throw",
                "true",
                "try",
                "typealias",
                "typeof",
                "val",
                "var",
                "when",
                "while",
            )
    }
}

/** ServiceLoader provider for [UmlToExposedTransformer]. */
@Suppress("DEPRECATION")
public class UmlToExposedTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlToExposedTransformer = UmlToExposedTransformer()
}
