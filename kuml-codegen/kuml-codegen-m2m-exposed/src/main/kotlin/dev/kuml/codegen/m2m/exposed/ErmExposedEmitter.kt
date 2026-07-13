package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.api.customtype.CustomTypeHooks
import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformError
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.constraint.ViolationSeverity
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmMetadataKeys
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ReferentialAction

/**
 * V3.4.8 (V3.4.10: retargeted at Exposed 1.3.1's `org.jetbrains.exposed.v1.*`
 * package layout, see ADR-0016 retrofit notes) — the single source of truth
 * for Kotlin Exposed `Table` object generation from an [ErmModel]. All three
 * entry points in this module
 * ([ErmToExposedTransformer]'s ERM-direct M2M step, [ErmExposedGenerator]'s
 * ERM-first CLI/plugin path, and [UmlToExposedViaErmScriptTransformer]'s chained
 * UML→ERM→Exposed path) delegate here; none of them contains any Exposed-rendering
 * logic of its own. Mirrors [dev.kuml.codegen.sql.ErmSqlEmitter] one-to-one
 * (V3.4.7's equivalent split for SQL DDL).
 *
 * Emission pipeline (see [emit]):
 *  1. Validate the model with [ErmConstraintChecker] — any `ERROR`-severity
 *     violation fails generation with [TransformResult.Failure] instead of
 *     emitting structurally broken Kotlin. This is the *only* validation gate
 *     for the ERM-first path (which never goes through a UML transformer), and
 *     a deliberate second gate for the chained UML-direct path (whose
 *     `UmlToErmTransformer` step already validates once).
 *  2. Derive and validate a Kotlin `object` name for every [ErmEntity] (needed
 *     up front so foreign-key columns can resolve their target object name).
 *  3. Render one [GeneratedFile] per [ErmEntity], path `"<ObjectName>.kt"`.
 *
 * ### Mapping specification (`ErmEntity` → Kotlin Exposed `Table` object)
 *
 * - **Object name**: PascalCase of `entity.name` (already snake_case/plural —
 *   the standard shape produced by `UmlToErmTransformer` — but works for any
 *   ERM-first name too), e.g. `order_items` → `OrderItems`.
 * - **Columns**: Kotlin property = camelCase(`attr.name`); the Exposed column
 *   string literal is `attr.name` verbatim (already the intended DB column
 *   name — unlike the UML-direct [UmlToExposedTransformer], which still has to
 *   *derive* a column name from a UML property name).
 * - **Type mapping**: exhaustive `when` over [ErmDataType] into the matching
 *   Exposed DSL call (`integer`/`long`/`short`/`decimal`/`double`/`float`/
 *   `varchar`/`text`/`bool`/`date`/`time`/`datetime`/`uuid`/`blob`). `Json` falls
 *   back to `text(...)` plus an explanatory comment, since it has no derivable
 *   idiomatic Exposed column builder. `Custom` does the same *unless*
 *   [CustomTypeHooks] recognizes the raw string as a PostGIS geometry descriptor
 *   (ADR-0016 §2.3) — recognized geometry columns render as `geometry(name, sqlType)`,
 *   a dependency-free custom `ColumnType<String>` extension emitted once per
 *   generation into a `PostGisColumnTypes.kt` support file (see [emit]).
 * - **TimescaleDB hypertables** ([ErmMetadataKeys.HYPERTABLE] metadata marker):
 *   Exposed has no matching construct, so a marked entity only gets an
 *   explanatory `// Note:` comment in its generated `object` body — the actual
 *   `create_hypertable(...)` call is SQL-DDL-only, see
 *   `ErmSqlEmitter.renderHypertables`.
 * - **Modifiers**: `.nullable()` when nullable and not a primary-key column;
 *   `.uniqueIndex()` when unique and not a primary-key column; `.autoIncrement()`
 *   only when `autoIncrement && type is Integer`. A raw [ErmAttribute.default] is
 *   emitted as a `// TODO default = "..."` comment rather than a typed Exposed
 *   `.default(...)` call (no way to safely infer a typed literal from a raw
 *   dialect-neutral string).
 * - **Foreign keys**: a non-self-referential [ErmForeignKey] becomes
 *   `reference(...)` (not-null) or `optReference(...)` (nullable), with
 *   `onDelete`/`onUpdate` named arguments when the referential action is not
 *   [ReferentialAction.NO_ACTION]. Exposed 1.3.1's `Table.reference()`/
 *   `optReference()` overloads that apply to a plain (non-`IdTable`)
 *   `Table` object — which is what this emitter always generates — take the
 *   *target column* (`Column<T>`), not the target `Table`, as their second
 *   argument: the emitted call therefore reads `reference("author_id",
 *   Authors.id, ...)`, resolving [ErmForeignKey.targetAttributeId] when set,
 *   or falling back to the target entity's single-column primary key
 *   otherwise (failing generation if the target has no such single column).
 *   **Self-referential** foreign keys
 *   (`fk.targetEntityId == entity.id`) are *not* rendered as `reference()` —
 *   referencing the enclosing `object` from inside its own initializer body
 *   does not compile — instead a plain typed column (using the attribute's own
 *   declared [ErmAttribute.type]) is emitted with an explanatory comment.
 * - **Primary key**: a single-column PK emits
 *   `override val primaryKey: PrimaryKey = PrimaryKey(<prop>)`; a composite PK
 *   (e.g. junction-table entities) emits `PrimaryKey(<p1>, <p2>, ...)`; an empty
 *   PK (weak entity without one of its own) omits the override entirely, with a
 *   comment noting why (Exposed permits a PK-less `Table`).
 * - **Indexes / checks / views**: left as comments (MVP) — see "Known
 *   limitations" below.
 *
 * The conceptual win over the older, still-supported `uml-to-exposed` (Variante
 * B, [UmlToExposedTransformer]): many-to-many associations are now represented
 * as genuine junction `Table` objects with a composite primary key (because
 * `UmlToErmTransformer` already materializes them as junction [ErmEntity]
 * instances), instead of a `// *-to-many not represented` comment.
 *
 * ### Known limitations (not addressed in this wave)
 * - [ErmEntity.indexes], [ErmEntity.checks], and [ErmModel.views] are emitted
 *   as comments only — Exposed's `index {}`/`check {}` DSLs need typed
 *   `Op<Boolean>`/column references that are not mechanically derivable from
 *   the ERM model's raw expression strings, and Exposed has no idiomatic view
 *   construct at all.
 * - IDEF1X categories ([ErmModel.categories]) are not represented — Exposed's
 *   `Table` DSL has no supertype/subtype construct to map them onto.
 * - `Timestamp.withTimeZone` is not distinguished — always rendered via
 *   `datetime(...)`, matching the `java.time`-based `org.jetbrains.exposed.v1.javatime`
 *   module (no `timestampWithTimeZone()` call is emitted).
 * - [ErmDataType.Uuid] renders via `javaUUID(...)` (`org.jetbrains.exposed.v1.core.java.javaUUID`),
 *   yielding `Column<java.util.UUID>` — the direct Exposed-1.x continuation of the
 *   pre-1.0 `uuid(...)` contract (which returned `Column<java.util.UUID>` too).
 *   Exposed 1.x's own `Table.uuid(...)` member now returns `Column<kotlin.uuid.Uuid>`
 *   instead and is intentionally not used here, to keep this emitter's documented
 *   "Type mapping" contract (and existing consumers' `java.util.UUID`-typed code)
 *   stable across the Exposed-version retarget.
 * - Two attributes whose camelCase-converted names collide (e.g. `user_id` and
 *   `userId` on the same entity) produce two Kotlin `val`s with the same
 *   property name — a non-compiling collision. Not defended against, mirroring
 *   the equivalent limitation already accepted in [UmlToExposedTransformer].
 *
 * ### Generated-code-injection / path-traversal defenses
 *
 * ERM-first `.kuml.kts` scripts build an [ErmModel] directly via the
 * `ermModel { }` DSL and therefore never pass through `UmlToErmTransformer`'s
 * `SqlIdentifiers.requireSafe` gate. The identifier/string-literal defenses
 * below (intentionally duplicated from [UmlToExposedTransformer] rather than
 * shared, exactly as [UmlToExposedPsmTransformer] already duplicates its own
 * `toSnakeCase`/`toPlural` helpers — see that class's KDoc — to keep this
 * addition fully independent of the existing, still-supported Variante B
 * transformer, which must not be modified) are therefore applied unconditionally,
 * regardless of entry point:
 * - Every string embedded inside a Kotlin string literal is escaped via
 *   [kotlinStringLiteral].
 * - Every ERM name used as a Kotlin *identifier* (entity name → object name,
 *   attribute name → property name) is validated by [requireValidKotlinIdentifier]
 *   against the Kotlin identifier grammar and the Kotlin hard-keyword list.
 * - [GeneratedFile.relativePath] is checked by [requireSafeRelativePath] to be a
 *   single path segment with no `/`, `\`, or `..`.
 * - Raw [ErmAttribute.default] / [ErmDataType.Custom.raw] text embedded in a
 *   `//` comment is sanitized by [commentSafe].
 */
internal class ErmExposedEmitter(
    private val packageName: String = DEFAULT_PACKAGE,
) {
    fun emit(model: ErmModel): TransformResult<List<GeneratedFile>> {
        val violations = ErmConstraintChecker().check(model).filter { it.severity == ViolationSeverity.ERROR }
        if (violations.isNotEmpty()) {
            return TransformResult.Failure(
                violations.map { TransformError("erm-to-exposed: ${it.message}", it.elementId) },
            )
        }

        // Pass 1: derive + validate every entity's Kotlin object name, and every attribute's
        // Kotlin property name, up front — needed so foreign-key columns in *other* entities
        // can resolve both their reference() target object name and target column property
        // name, even for entities rendered later in Pass 2.
        val objectNameById = mutableMapOf<String, String>()
        val attrPropertyNameById = mutableMapOf<String, String>()
        val nameErrors = mutableListOf<TransformError>()
        for (entity in model.entities) {
            val raw = entity.name ?: entity.id
            val objectName = toPascalCase(raw)
            try {
                requireValidKotlinIdentifier(objectName, "entity name", entity.id)
                requireSafeRelativePath("$objectName.kt", entity.id)
            } catch (e: InvalidIdentifierException) {
                nameErrors += TransformError(e.message ?: "invalid entity name", entity.id)
                continue
            }
            objectNameById[entity.id] = objectName

            for (attr in entity.attributes) {
                val rawAttrName = attr.name ?: attr.id
                val propName = toCamelCase(rawAttrName)
                try {
                    requireValidKotlinIdentifier(propName, "attribute name", attr.id)
                } catch (e: InvalidIdentifierException) {
                    nameErrors += TransformError(e.message ?: "invalid attribute name", attr.id)
                    continue
                }
                attrPropertyNameById[attr.id] = propName
            }
        }
        if (nameErrors.isNotEmpty()) return TransformResult.Failure(nameErrors)

        val duplicateNames =
            objectNameById.values
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys
        if (duplicateNames.isNotEmpty()) {
            return TransformResult.Failure(
                listOf(
                    TransformError(
                        "erm-to-exposed: multiple entities map to the same Kotlin object name(s) " +
                            "${duplicateNames.joinToString(", ")} — refusing to emit colliding files.",
                    ),
                ),
            )
        }

        // Pass 2: render.
        val errors = mutableListOf<TransformError>()
        val files = mutableListOf<GeneratedFile>()
        var trace = TransformTrace()

        for (entity in model.entities) {
            when (val result = renderEntity(entity, model, objectNameById, attrPropertyNameById)) {
                is EntityResult.Ok -> {
                    files += result.file
                    trace = trace.plus(TraceabilityLink(entity.id, result.file.relativePath, RULE_ENTITY_TO_TABLE))
                    for (attr in entity.attributes) {
                        val rule = if (attr.foreignKey != null) RULE_FK_TO_REFERENCE else RULE_ATTR_TO_COLUMN
                        trace = trace.plus(TraceabilityLink(attr.id, result.file.relativePath, rule))
                    }
                }
                is EntityResult.Error -> errors += result.error
            }
        }

        if (errors.isNotEmpty()) return TransformResult.Failure(errors)

        // ADR-0016 §2.3 — one shared support file, emitted only when at least one entity
        // has a Custom column recognized as a PostGIS geometry type by CustomTypeHooks.
        val hasGeometryColumn =
            model.entities.any { entity ->
                entity.attributes.any { attr ->
                    (attr.type as? ErmDataType.Custom)?.let { CustomTypeHooks.recognize(it.raw) != null } == true
                }
            }
        if (hasGeometryColumn) {
            if (POSTGIS_SUPPORT_FILE_OBJECT_NAME in objectNameById.values) {
                return TransformResult.Failure(
                    listOf(
                        TransformError(
                            "erm-to-exposed: entity name '$POSTGIS_SUPPORT_FILE_OBJECT_NAME' collides with the " +
                                "generated PostGIS geometry support file — rename the entity.",
                        ),
                    ),
                )
            }
            files += GeneratedFile("$POSTGIS_SUPPORT_FILE_OBJECT_NAME.kt", renderPostGisSupportFile())
        }

        return TransformResult.Success(files, trace)
    }

    private fun renderPostGisSupportFile(): String {
        val sb = StringBuilder()
        sb.appendLine("// Generated by kuml-codegen-m2m-exposed (erm-to-exposed) — do not edit manually.")
        sb.appendLine(
            "// Support file for ErmDataType.Custom geometry columns recognized by CustomTypeHooks (ADR-0016 §2.3).",
        )
        sb.appendLine()
        sb.appendLine("package $packageName")
        sb.appendLine()
        sb.appendLine("import org.jetbrains.exposed.v1.core.Column")
        sb.appendLine("import org.jetbrains.exposed.v1.core.ColumnType")
        sb.appendLine("import org.jetbrains.exposed.v1.core.Table")
        sb.appendLine()
        sb.appendLine("private class GeometryColumnType(private val sql: String) : ColumnType<String>() {")
        sb.appendLine("    override fun sqlType(): String = sql")
        sb.appendLine("    override fun valueFromDB(value: Any): String = value.toString()")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("public fun Table.geometry(name: String, sqlType: String): Column<String> =")
        sb.appendLine("    registerColumn(name, GeometryColumnType(sqlType))")
        return sb.toString()
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private sealed class EntityResult {
        data class Ok(
            val file: GeneratedFile,
        ) : EntityResult()

        data class Error(
            val error: TransformError,
        ) : EntityResult()
    }

    private fun renderEntity(
        entity: ErmEntity,
        model: ErmModel,
        objectNameById: Map<String, String>,
        attrPropertyNameById: Map<String, String>,
    ): EntityResult {
        val objectName = objectNameById.getValue(entity.id)
        val tableNameLiteral =
            try {
                kotlinStringLiteral(entity.name ?: entity.id)
            } catch (e: InvalidIdentifierException) {
                return EntityResult.Error(TransformError(e.message ?: "invalid table name", entity.id))
            }

        val imports = sortedSetOf("org.jetbrains.exposed.v1.core.Column", "org.jetbrains.exposed.v1.core.Table")
        val columnLines = mutableListOf<String>()

        for (attr in entity.attributes) {
            val rawName = attr.name ?: attr.id
            val propName = attrPropertyNameById.getValue(attr.id)

            val colLiteral = kotlinStringLiteral(rawName)
            val fk = attr.foreignKey
            val line =
                when {
                    fk == null -> renderBaseColumnLine(propName, attr, colLiteral, imports)
                    fk.targetEntityId == entity.id -> {
                        // Self-referential FK — reference() cannot target the enclosing object
                        // from inside its own initializer body. Emit a plain typed column instead.
                        val base = renderBaseColumnLine(propName, attr, colLiteral, imports)
                        "$base // self-referential FK (target: this entity) — reference() omitted, see KDoc"
                    }
                    else -> {
                        val targetEntity =
                            model.entityById(fk.targetEntityId)
                                ?: return EntityResult.Error(
                                    TransformError(
                                        "erm-to-exposed: foreign key on attribute '$rawName' targets unknown " +
                                            "entity '${fk.targetEntityId}'",
                                        attr.id,
                                    ),
                                )
                        val targetObjectName = objectNameById.getValue(targetEntity.id)
                        val targetAttr =
                            if (fk.targetAttributeId != null) {
                                targetEntity.attributes.firstOrNull { it.id == fk.targetAttributeId }
                                    ?: return EntityResult.Error(
                                        TransformError(
                                            "erm-to-exposed: foreign key on attribute '$rawName' targets unknown " +
                                                "attribute '${fk.targetAttributeId}' on entity " +
                                                "'${targetEntity.name ?: targetEntity.id}'",
                                            attr.id,
                                        ),
                                    )
                            } else {
                                targetEntity.primaryKey.singleOrNull()
                                    ?: return EntityResult.Error(
                                        TransformError(
                                            "erm-to-exposed: foreign key on attribute '$rawName' targets entity " +
                                                "'${targetEntity.name ?: targetEntity.id}' whose primary key is " +
                                                "not a single column (composite or empty) — reference()/" +
                                                "optReference() require an explicit targetAttributeId.",
                                            attr.id,
                                        ),
                                    )
                            }
                        val targetPropName = attrPropertyNameById.getValue(targetAttr.id)
                        renderReferenceColumnLine(
                            propName,
                            attr,
                            colLiteral,
                            targetObjectName,
                            targetPropName,
                            fk,
                            imports,
                        )
                    }
                }
            columnLines += line
            attr.default?.let { columnLines += "    // TODO default = \"${commentSafe(it)}\"" }
        }

        val pkAttrs = entity.primaryKey
        val pkLine =
            when {
                pkAttrs.isEmpty() -> null
                else ->
                    "    override val primaryKey: PrimaryKey = PrimaryKey(" +
                        pkAttrs.joinToString(", ") { attrPropertyNameById.getValue(it.id) } +
                        ")"
            }

        val sb = StringBuilder()
        sb.appendLine("// Generated by kuml-codegen-m2m-exposed (erm-to-exposed) — do not edit manually.")
        sb.appendLine()
        sb.appendLine("package $packageName")
        sb.appendLine()
        for (import in imports) sb.appendLine("import $import")
        sb.appendLine()
        sb.appendLine("public object $objectName : Table(\"$tableNameLiteral\") {")
        for (line in columnLines) sb.appendLine(line)
        if (columnLines.isNotEmpty()) sb.appendLine()

        if (pkLine != null) {
            sb.appendLine(pkLine)
        } else {
            sb.appendLine("    // Weak entity with no primary key of its own — Exposed permits a Table without one.")
        }

        if (entity.indexes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("    // Note: ${entity.indexes.size} index(es) declared on this entity are not emitted —")
            sb.appendLine("    // Exposed's index {} DSL needs typed column references, not wired up in this wave.")
        }
        if (entity.checks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("    // Note: ${entity.checks.size} check constraint(s) declared on this entity are not")
            sb.appendLine("    // emitted — Exposed's check {} DSL needs a typed Op<Boolean>, not a raw SQL string.")
        }
        if (entity.metadata[ErmMetadataKeys.HYPERTABLE] is KumlMetaValue.Entries) {
            sb.appendLine()
            sb.appendLine(
                "    // Note: entity marked as TimescaleDB hypertable — emitted only in SQL DDL, not in Exposed.",
            )
        }

        sb.append("}")
        sb.appendLine()

        if (model.views.isNotEmpty() && entity.id == model.entities.first().id) {
            sb.appendLine()
            sb.appendLine(
                "// Note: ${model.views.size} view(s) declared on this model are not emitted — " +
                    "Exposed has no idiomatic view DSL.",
            )
        }

        return EntityResult.Ok(GeneratedFile("$objectName.kt", sb.toString()))
    }

    private fun renderBaseColumnLine(
        propName: String,
        attr: ErmAttribute,
        colLiteral: String,
        imports: MutableSet<String>,
    ): String {
        val rendered = renderBaseColumnCall(attr.type, colLiteral)
        imports += rendered.imports

        var call = rendered.call
        if (attr.autoIncrement && attr.type is ErmDataType.Integer) call += ".autoIncrement()"
        if (attr.nullable && !attr.primaryKey) call += ".nullable()"
        if (attr.unique && !attr.primaryKey) call += ".uniqueIndex()"
        // The trailing explanatory comment (Json/Custom fallback) must come after every
        // modifier call, not before — appending `.nullable()` etc. *after* a `//` comment
        // would silently swallow the modifier into the comment text (still compiles, but
        // produces a Column<T?> type declaration backed by a non-nullable expression).
        rendered.trailingComment?.let { call += " // $it" }

        val ktType = if (attr.nullable && !attr.primaryKey) "${rendered.ktType}?" else rendered.ktType
        return "    public val $propName: Column<$ktType> = $call"
    }

    private fun renderReferenceColumnLine(
        propName: String,
        attr: ErmAttribute,
        colLiteral: String,
        targetObjectName: String,
        targetPropName: String,
        fk: ErmForeignKey,
        imports: MutableSet<String>,
    ): String {
        val rendered = renderBaseColumnCall(attr.type, colLiteral)
        // The Kotlin type of a reference()/optReference() column always matches the FK
        // attribute's own declared type (its underlying storage type), same as a plain column.
        imports += rendered.imports

        val optionArgs =
            buildList {
                referenceOptionName(fk.onDelete)?.let { add("onDelete = ReferenceOption.$it") }
                referenceOptionName(fk.onUpdate)?.let { add("onUpdate = ReferenceOption.$it") }
            }
        if (optionArgs.isNotEmpty()) imports += "org.jetbrains.exposed.v1.core.ReferenceOption"

        // Exposed 1.3.1's Table.reference()/optReference() overloads that accept a plain
        // (non-IdTable) Table — which is what this emitter always generates — take the
        // *target column*, not the target Table, as their second argument.
        val args = (listOf("\"$colLiteral\"", "$targetObjectName.$targetPropName") + optionArgs).joinToString(", ")
        val call = if (attr.nullable) "optReference($args)" else "reference($args)"
        val ktType = if (attr.nullable) "${rendered.ktType}?" else rendered.ktType
        return "    public val $propName: Column<$ktType> = $call"
    }

    private fun referenceOptionName(action: ReferentialAction): String? =
        when (action) {
            ReferentialAction.NO_ACTION -> null
            ReferentialAction.RESTRICT -> "RESTRICT"
            ReferentialAction.CASCADE -> "CASCADE"
            ReferentialAction.SET_NULL -> "SET_NULL"
            ReferentialAction.SET_DEFAULT -> "SET_DEFAULT"
        }

    // ── Type mapping ─────────────────────────────────────────────────────────

    private data class ColumnCallRendering(
        val call: String,
        val ktType: String,
        val imports: Set<String>,
        val trailingComment: String? = null,
    )

    private fun renderBaseColumnCall(
        type: ErmDataType,
        colLiteral: String,
    ): ColumnCallRendering =
        when (type) {
            is ErmDataType.Integer ->
                when (type.bits) {
                    16 -> ColumnCallRendering("short(\"$colLiteral\")", "Short", emptySet())
                    64 -> ColumnCallRendering("long(\"$colLiteral\")", "Long", emptySet())
                    else -> ColumnCallRendering("integer(\"$colLiteral\")", "Int", emptySet())
                }
            is ErmDataType.Decimal ->
                ColumnCallRendering(
                    "decimal(\"$colLiteral\", ${type.precision}, ${type.scale})",
                    "BigDecimal",
                    setOf("java.math.BigDecimal"),
                )
            is ErmDataType.Real ->
                if (type.double) {
                    ColumnCallRendering("double(\"$colLiteral\")", "Double", emptySet())
                } else {
                    ColumnCallRendering("float(\"$colLiteral\")", "Float", emptySet())
                }
            is ErmDataType.Varchar ->
                ColumnCallRendering("varchar(\"$colLiteral\", ${type.length})", "String", emptySet())
            ErmDataType.Text -> ColumnCallRendering("text(\"$colLiteral\")", "String", emptySet())
            ErmDataType.Boolean -> ColumnCallRendering("bool(\"$colLiteral\")", "Boolean", emptySet())
            ErmDataType.Date ->
                ColumnCallRendering(
                    "date(\"$colLiteral\")",
                    "LocalDate",
                    setOf("org.jetbrains.exposed.v1.javatime.date", "java.time.LocalDate"),
                )
            ErmDataType.Time ->
                ColumnCallRendering(
                    "time(\"$colLiteral\")",
                    "LocalTime",
                    setOf("org.jetbrains.exposed.v1.javatime.time", "java.time.LocalTime"),
                )
            is ErmDataType.Timestamp ->
                ColumnCallRendering(
                    "datetime(\"$colLiteral\")",
                    "LocalDateTime",
                    setOf("org.jetbrains.exposed.v1.javatime.datetime", "java.time.LocalDateTime"),
                )
            ErmDataType.Uuid ->
                ColumnCallRendering(
                    "javaUUID(\"$colLiteral\")",
                    "UUID",
                    setOf("java.util.UUID", "org.jetbrains.exposed.v1.core.java.javaUUID"),
                )
            ErmDataType.Blob ->
                ColumnCallRendering(
                    "blob(\"$colLiteral\")",
                    "ExposedBlob",
                    setOf("org.jetbrains.exposed.v1.core.statements.api.ExposedBlob"),
                )
            ErmDataType.Json ->
                ColumnCallRendering(
                    "text(\"$colLiteral\")",
                    "String",
                    emptySet(),
                    trailingComment = "ErmDataType.Json fallback — Exposed's json() needs a serializer",
                )
            is ErmDataType.Custom -> {
                val geo = CustomTypeHooks.recognize(type.raw)
                if (geo != null) {
                    // geo.postgresType() is a machine-generated string (enum name + regex-captured,
                    // digit-only SRID) — not user text — but it is still routed through
                    // kotlinStringLiteral for defense in depth, matching every other embedded literal.
                    ColumnCallRendering(
                        "geometry(\"$colLiteral\", \"${kotlinStringLiteral(geo.postgresType())}\")",
                        "String",
                        emptySet(),
                    )
                } else {
                    ColumnCallRendering(
                        "text(\"$colLiteral\")",
                        "String",
                        emptySet(),
                        trailingComment = "Custom(${commentSafe(type.raw)}) fallback",
                    )
                }
            }
        }

    // ── Name conversion ──────────────────────────────────────────────────────

    /** Converts a `snake_case` (or already-`PascalCase`) name to `PascalCase`. */
    private fun toPascalCase(raw: String): String =
        raw
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            .ifEmpty { raw }

    /** Converts a `snake_case` (or already-`camelCase`) name to `camelCase`. */
    private fun toCamelCase(raw: String): String {
        val pascal = toPascalCase(raw)
        return pascal.replaceFirstChar { it.lowercaseChar() }
    }

    // ── Generated-code-injection / path-traversal defenses (V3.4.8) ────────────
    // Intentionally duplicated from UmlToExposedTransformer — see this file's KDoc.

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

    private fun commentSafe(raw: String): String =
        raw
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("$STAR$SLASH", "$STAR $SLASH")

    private fun requireValidKotlinIdentifier(
        name: String,
        what: String,
        elementId: String,
    ) {
        if (!KOTLIN_IDENTIFIER_REGEX.matches(name)) {
            throw InvalidIdentifierException(
                "erm-to-exposed: $what '$name' (element $elementId) is not a safe Kotlin identifier " +
                    "— only [a-zA-Z_][a-zA-Z0-9_]* is accepted, refusing to emit generated code.",
            )
        }
        if (name in KOTLIN_HARD_KEYWORDS) {
            throw InvalidIdentifierException(
                "erm-to-exposed: $what '$name' (element $elementId) is a Kotlin hard keyword " +
                    "— refusing to emit generated code.",
            )
        }
    }

    private fun requireSafeRelativePath(
        relativePath: String,
        elementId: String,
    ) {
        val hasSeparator = relativePath.contains('/') || relativePath.contains('\\')
        val isTraversal = relativePath == "." || relativePath == ".."
        if (hasSeparator || isTraversal || relativePath.isBlank()) {
            throw InvalidIdentifierException(
                "erm-to-exposed: generated file path '$relativePath' (element $elementId) is not a safe " +
                    "single path segment — refusing to write outside the output directory.",
            )
        }
    }

    private class InvalidIdentifierException(
        message: String,
    ) : RuntimeException(message)

    companion object {
        const val DEFAULT_PACKAGE = "com.example.tables"

        /** File/object base name of the shared PostGIS geometry support file (ADR-0016 §2.3). */
        const val POSTGIS_SUPPORT_FILE_OBJECT_NAME = "PostGisColumnTypes"
        const val RULE_ENTITY_TO_TABLE = "erm-entity-to-exposed-table"
        const val RULE_ATTR_TO_COLUMN = "erm-attribute-to-exposed-column"
        const val RULE_FK_TO_REFERENCE = "erm-fk-to-exposed-reference"

        private val KOTLIN_IDENTIFIER_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

        // Split to avoid an accidental block-comment-terminator sequence inside this source file's own KDoc.
        private const val STAR = "*"
        private const val SLASH = "/"

        private val KOTLIN_HARD_KEYWORDS =
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
