package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformError
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.KumlDiagram
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.constraint.ViolationSeverity
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCategory
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.ReferentialAction
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlProperty

/**
 * Transforms a UML class diagram (PIM) into an [ErmModel] (PSM) — the real
 * M2M transformation of kUML V3.4.6.
 *
 * Consumable programmatically, by a future `TransformChain` (`uml-to-erm` +
 * V3.4.7's `erm-to-sql`), and via [UmlToErmScriptTransformer] for a
 * CLI-renderable `.kuml.kts` result (the CLI's `TransformCommand` cannot run a
 * transformer whose output type is [ErmModel] directly — see that class's KDoc).
 *
 * ### Relationship to ADR-0016 Variante A (staggered replacement, not yet complete)
 *
 * This module is the typed ERM successor to the `«Table»`-PSM path from
 * ADR-0016 Variante A ([dev.kuml.codegen.m2m.exposed.UmlToExposedPsmTransformer]
 * / `kuml-profile-exposed`), but V3.4.6 only *adds* it — it does not yet retire
 * the Variante A path. `kuml-codegen-m2m-exposed` and `kuml-profile-exposed`
 * remain fully active (no `@Deprecated`, no ADR status change) because the
 * consumers that would need to switch over are staged across later waves:
 * - **V3.4.7** repoints `kuml-gen-sql` at typed [ErmModel] input instead of
 *   `«Entity»`-stereotype string-matching, which is what actually makes the
 *   Variante A dual-annotation workaround (`«Table»` + `«Entity»`) obsolete.
 * - **V3.4.8** *(optional)* repoints the Exposed-source-code transformer at
 *   [ErmModel] (`erm-to-exposed`, chained `uml-to-erm` → `erm-to-exposed`),
 *   which is what would let `UmlToExposedPsmTransformer` itself be deprecated.
 *
 * See [[kUML V3.4]] (Vault) for the full wave plan and ADR-0016 for the
 * Variante A decision record. Until V3.4.7/V3.4.8 land, both paths are
 * supported side by side — this is intentional staggering, not an oversight.
 *
 * Mapping summary:
 * - `UmlClass` → `ErmEntity` (table name from `«Entity».tableName` or
 *   `snake_case(name).plural()`); `UmlProperty` → `ErmAttribute` (column name from
 *   `«Column».columnName` or `snake_case(name)`); `«Transient»` skips a property;
 *   `«Id»` / an attribute literally named `id` becomes the primary key.
 * - `UmlGeneralization` hierarchies are materialised per
 *   [InheritanceStrategy] (`«Inheritance».strategy`, default [InheritanceStrategy.JOINED]).
 * - `UmlAssociation` with both ends many-valued resolves to a junction
 *   [dev.kuml.erm.model.ErmEntity] plus two `IDENTIFYING` relationships
 *   (`«JunctionTable»` overrides table/column names); otherwise to a foreign-key
 *   column plus one `NON_IDENTIFYING` relationship (`«FK»` overrides
 *   constraint name / referential actions). The FK column name defaults to
 *   `snake_case(refClass.name).singular() + "_id"`; if that name is already
 *   taken on the owning entity (two associations from the same class to the
 *   same target, e.g. `Order.createdBy` / `Order.assignedTo` → `User`), it is
 *   re-derived from the association-end role name instead (see Known limitations).
 * - A `UmlProperty` whose declared type resolves to a `UmlEnumeration` maps to
 *   [dev.kuml.erm.model.ErmDataType.Enum] (name + ordered literal list), plus an
 *   `ErmCheckConstraint` of the form `col IN ('Lit1', 'Lit2', ...)` on the owning
 *   entity — physically a `VARCHAR` + `CHECK` on every SQL dialect (V3.4.7,
 *   deliberately no Postgres `CREATE TYPE ... AS ENUM`), with the Exposed emitter
 *   additionally generating a matching Kotlin `enum class` (ADR-0016 retrofit).
 * - The resulting model is validated with [ErmConstraintChecker] before being
 *   returned; any `ERROR`-severity violation fails the transform instead of
 *   producing a structurally broken [ErmModel] (`WARNING`s are non-blocking).
 *
 * ### Identifier safety
 *
 * Every derived table/column/junction/FK identifier is validated with
 * [SqlIdentifiers.requireSafe] before being placed into the model — an unsafe
 * source name fails the transform with [UnsafeUmlNameException] instead of
 * silently emitting an unsafe identifier that a later SQL-dialect generator
 * (V3.4.7) would interpolate verbatim into DDL.
 *
 * ### Known limitations (V3.4.6 scope)
 * - Multiple inheritance (a class with more than one [UmlGeneralization] parent)
 *   is not modelled — only the first-declared parent per class is honoured.
 * - `ErmIndex` is not synthesized from `«Column».unique` (only the column-level
 *   `unique` flag is set); composite unique constraints are out of scope.
 * - Composite (multi-column) primary/foreign keys on the *source* side of an
 *   association are not supported — [dev.kuml.erm.model.ErmEntity.primaryKey]
 *   must be a single column for FK/junction type inference to succeed.
 * - FK column-name collisions (two associations from the same class to the
 *   same target class) are disambiguated using `UmlAssociationEnd.role` on the
 *   referenced or FK-owning end (falls back to the class name otherwise — see
 *   [addForeignKey]). If *neither* association carries a role name, the
 *   collision cannot be resolved and the transform fails with an
 *   [ErmConstraintChecker] "attribute name used more than once" `ERROR`; there
 *   is currently no `«FK»` tag to override the column name directly (only
 *   `constraintName` / `onDelete` / `onUpdate` are supported) — a future wave
 *   could add a `columnName` tag to the `«FK»` stereotype to close this gap
 *   without requiring role names.
 */
public class UmlToErmTransformer : KumlTransformer<KumlDiagram, ErmModel> {
    override val id: String = "uml-to-erm"
    override val description: String =
        "UML class diagram (PIM) → ERM model (PSM). Inheritance/junction/naming overrides via the ERM mapping profile."

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<ErmModel> =
        try {
            Session(ctx).run(source)
        } catch (e: UnsafeUmlNameException) {
            TransformResult.Failure(listOf(TransformError(e.message ?: "unsafe identifier", null)))
        }

    /** Per-call mutable state — a fresh instance is created for every [transform] invocation. */
    private class Session(
        private val ctx: TransformContext,
    ) {
        private var entityCounter = 0
        private var relCounter = 0
        private var categoryCounter = 0

        private val entities = LinkedHashMap<String, MutableErmEntity>()
        private val entityIdByClassId = mutableMapOf<String, String>()
        private val relationships = mutableListOf<ErmRelationship>()
        private val categories = mutableListOf<ErmCategory>()
        private var trace = TransformTrace()

        fun run(source: KumlDiagram): TransformResult<ErmModel> {
            val classes = source.elements.filterIsInstance<UmlClass>()
            val enums = source.elements.filterIsInstance<UmlEnumeration>()
            val associations = source.elements.filterIsInstance<UmlAssociation>()
            val generalizations = source.elements.filterIsInstance<UmlGeneralization>()

            val classById = classes.associateBy { it.id }
            val enumsByName = enums.associateBy { it.name }

            // Only the first-declared parent per class is honoured (V3.4.6 scope — no multiple inheritance).
            val parentOf = mutableMapOf<String, String>()
            val childrenOf = mutableMapOf<String, MutableList<String>>()
            for (gen in generalizations) {
                parentOf.putIfAbsent(gen.specificId, gen.generalId)
                childrenOf.getOrPut(gen.generalId) { mutableListOf() }.add(gen.specificId)
            }

            val plans = planInheritance(classes, classById, parentOf, childrenOf, ctx)

            // 1. Build entities for every table-owning class.
            for (cls in classes) {
                val plan = plans.getValue(cls.id)
                if (!plan.hasOwnTable) continue
                buildEntityForClass(cls, plan, classById, enumsByName, plans)
            }

            // 2. SINGLE_TABLE — merge descendant columns + discriminator into the root entity.
            applySingleTableMerges(classes, classById, plans, enumsByName)

            // 3. JOINED — add the PK/FK column + identifying relationship linking each subtype to its parent.
            applyJoinedLinks(classes, plans)

            // 4. IDEF1X categories for JOINED hierarchies (best-effort, non-blocking if skipped).
            applyCategories(classes, plans)

            // 5. Associations → FK column or M:N junction entity.
            for (assoc in associations) {
                mapAssociation(assoc, classById, plans)
            }

            val model =
                ErmModel(
                    name = source.name,
                    entities = entities.values.map { it.toErmEntity() },
                    relationships = relationships.toList(),
                    views = emptyList(),
                    diagrams = listOf(ErmDiagram(name = source.name, notation = ErmNotation.MARTIN)),
                    categories = categories.toList(),
                )

            val errors = ErmConstraintChecker().check(model).filter { it.severity == ViolationSeverity.ERROR }
            if (errors.isNotEmpty()) {
                return TransformResult.Failure(errors.map { TransformError(it.message, it.elementId) })
            }
            return TransformResult.Success(model, trace)
        }

        // ── Entity construction ──────────────────────────────────────────────

        private fun buildEntityForClass(
            cls: UmlClass,
            plan: ClassPlan,
            classById: Map<String, UmlClass>,
            enumsByName: Map<String, UmlEnumeration>,
            plans: Map<String, ClassPlan>,
        ) {
            val tableName = deriveTableName(cls)
            val entity = MutableErmEntity(id = "entity_$entityCounter", ix = entityCounter, name = tableName)
            entityCounter++
            entities[entity.id] = entity
            entityIdByClassId[cls.id] = entity.id
            trace = trace.plus(TraceabilityLink(cls.id, entity.id, RULE_CLASS_TO_ENTITY))

            val ownTemplates = cls.attributes.mapNotNull { mapAttributeToColumn(it, enumsByName) }
            val ancestorTemplates =
                if (plan.strategy == InheritanceStrategy.TABLE_PER_CLASS) {
                    collectAncestorTemplates(cls, classById, plans, enumsByName, ownTemplates.map { it.name }.toSet())
                } else {
                    emptyList()
                }
            val allTemplates = ancestorTemplates + ownTemplates

            val isJoinedSubtype = plan.strategy == InheritanceStrategy.JOINED && plan.parentId != null
            if (isJoinedSubtype) {
                // Own PK delegated to the parent — see applyJoinedLinks. Any own attribute that
                // mapAttributeToColumn already flagged as PK (e.g. an explicit "id") is dropped here.
                allTemplates.filterNot { it.primaryKey }.forEach { addColumn(entity, it) }
            } else {
                if (allTemplates.none { it.primaryKey }) {
                    addColumn(entity, syntheticPkTemplate(cls))
                }
                allTemplates.forEach { addColumn(entity, it) }
            }
        }

        /** All ancestor classes' mapped columns (root..parent order), skipping names already used by the class itself. */
        private fun collectAncestorTemplates(
            cls: UmlClass,
            classById: Map<String, UmlClass>,
            plans: Map<String, ClassPlan>,
            enumsByName: Map<String, UmlEnumeration>,
            excludeNames: Set<String>,
        ): List<ColumnTemplate> {
            val chain = mutableListOf<UmlClass>()
            var currentParentId = plans[cls.id]?.parentId
            while (currentParentId != null) {
                val parentCls = classById[currentParentId] ?: break
                chain.add(0, parentCls)
                currentParentId = plans[currentParentId]?.parentId
            }
            val seen = excludeNames.toMutableSet()
            val result = mutableListOf<ColumnTemplate>()
            for (ancestor in chain) {
                for (attr in ancestor.attributes) {
                    val template = mapAttributeToColumn(attr, enumsByName) ?: continue
                    if (!seen.add(template.name)) continue
                    result += template
                }
            }
            return result
        }

        private fun addColumn(
            entity: MutableErmEntity,
            template: ColumnTemplate,
        ) {
            val attrId = entity.nextAttrId()
            entity.attributes +=
                ErmAttribute(
                    id = attrId,
                    name = template.name,
                    type = template.type,
                    primaryKey = template.primaryKey,
                    nullable = template.nullable,
                    unique = template.unique,
                    default = template.default,
                    autoIncrement = template.autoIncrement,
                )
            trace = trace.plus(TraceabilityLink(template.sourceAttrId, attrId, RULE_PROPERTY_TO_COLUMN))
            template.checkExpression?.let { expr ->
                entity.checks += ErmCheckConstraint(id = entity.nextCheckId(), name = null, expression = expr)
            }
        }

        private fun mapAttributeToColumn(
            attr: UmlProperty,
            enumsByName: Map<String, UmlEnumeration>,
        ): ColumnTemplate? {
            val applied: List<AppliedStereotype> = attr.appliedStereotypes
            if (applied.hasErmStereotype(ErmProfileNames.TRANSIENT)) return null

            val columnStereo = applied.ermStereotype(ErmProfileNames.COLUMN)
            val idStereo = applied.ermStereotype(ErmProfileNames.ID)
            val isPk = attr.name.equals("id", ignoreCase = true) || idStereo != null

            val columnName =
                SqlIdentifiers.requireSafe(
                    columnStereo?.stringTag(ErmProfileNames.TAG_COLUMN_NAME)?.takeIf { it.isNotBlank() }
                        ?: SqlIdentifiers.toSnakeCase(attr.name),
                    "column name",
                    attr.id,
                )

            val sqlTypeOverride = columnStereo?.stringTag(ErmProfileNames.TAG_SQL_TYPE)?.takeIf { it.isNotBlank() }
            val enumDef = enumsByName[attr.type.name]
            var checkExpression: String? = null
            val type: ErmDataType =
                when {
                    sqlTypeOverride != null -> UmlErmTypeMapper.mapOverride(sqlTypeOverride)
                    enumDef != null -> {
                        val literalNames = enumDef.literals.map { it.name }
                        checkExpression =
                            "$columnName IN (${literalNames.joinToString(", ") { "'${it.replace("'", "''")}'" }})"
                        ErmDataType.Enum(name = enumDef.name, values = literalNames)
                    }
                    else -> UmlErmTypeMapper.map(attr.type.name)
                }

            val nullableOverride = columnStereo?.boolTag(ErmProfileNames.TAG_NULLABLE)
            val nullable = if (isPk) false else (nullableOverride ?: (attr.multiplicity.lower == 0))
            val unique = columnStereo?.boolTag(ErmProfileNames.TAG_UNIQUE) ?: false

            val autoIncrement =
                if (isPk) {
                    val explicit = idStereo?.boolTag(ErmProfileNames.TAG_AUTO_INCREMENT)
                    (explicit ?: true) && type is ErmDataType.Integer
                } else {
                    false
                }

            return ColumnTemplate(
                name = columnName,
                type = type,
                primaryKey = isPk,
                nullable = nullable,
                unique = unique,
                default = attr.defaultValue,
                autoIncrement = autoIncrement,
                sourceAttrId = attr.id,
                checkExpression = checkExpression,
            )
        }

        private fun syntheticPkTemplate(cls: UmlClass): ColumnTemplate {
            val idTypeOption = ctx.options["idType"]?.trim()?.lowercase()
            val type =
                when (idTypeOption) {
                    "uuid" -> ErmDataType.Uuid
                    "int", "integer" -> ErmDataType.Integer(32)
                    else -> ErmDataType.Integer(64)
                }
            return ColumnTemplate(
                name = "id",
                type = type,
                primaryKey = true,
                nullable = false,
                unique = false,
                default = null,
                autoIncrement = type is ErmDataType.Integer,
                sourceAttrId = cls.id,
            )
        }

        private fun deriveTableName(cls: UmlClass): String {
            val entityStereo = cls.appliedStereotypes.ermStereotype(ErmProfileNames.ENTITY)
            val override = entityStereo?.stringTag(ErmProfileNames.TAG_TABLE_NAME)?.takeIf { it.isNotBlank() }
            val name = override ?: SqlIdentifiers.toSnakeCase(cls.name).toPlural()
            return SqlIdentifiers.requireSafe(name, "table name", cls.id)
        }

        // ── Inheritance materialisation ──────────────────────────────────────

        private fun applySingleTableMerges(
            classes: List<UmlClass>,
            classById: Map<String, UmlClass>,
            plans: Map<String, ClassPlan>,
            enumsByName: Map<String, UmlEnumeration>,
        ) {
            val roots =
                classes.filter { cls ->
                    val plan = plans.getValue(cls.id)
                    plan.strategy == InheritanceStrategy.SINGLE_TABLE && plan.parentId == null && plan.childIds.isNotEmpty()
                }
            for (root in roots) {
                val rootPlan = plans.getValue(root.id)
                val rootEntityId = entityIdByClassId[root.id] ?: continue
                val rootEntity = entities.getValue(rootEntityId)

                val descendants = mutableListOf<UmlClass>()
                val stack = ArrayDeque(rootPlan.childIds)
                while (stack.isNotEmpty()) {
                    val id = stack.removeFirst()
                    val cls = classById[id] ?: continue
                    descendants += cls
                    stack.addAll(plans[id]?.childIds.orEmpty())
                }

                for (descendant in descendants) {
                    for (attr in descendant.attributes) {
                        val template = mapAttributeToColumn(attr, enumsByName)?.asNullableNonKey() ?: continue
                        addColumn(rootEntity, template)
                    }
                    trace = trace.plus(TraceabilityLink(descendant.id, rootEntityId, RULE_SINGLE_TABLE_MERGE))
                }

                val discriminatorColumn = SqlIdentifiers.requireSafe(discriminatorColumnNameOf(root), "discriminator column", root.id)
                if (!rootEntity.hasAttributeNamed(discriminatorColumn)) {
                    addColumn(
                        rootEntity,
                        ColumnTemplate(
                            name = discriminatorColumn,
                            type = ErmDataType.Varchar(255),
                            primaryKey = false,
                            nullable = false,
                            unique = false,
                            default = null,
                            autoIncrement = false,
                            sourceAttrId = root.id,
                        ),
                    )
                }
            }
        }

        private fun applyJoinedLinks(
            classes: List<UmlClass>,
            plans: Map<String, ClassPlan>,
        ) {
            // Must run root-to-leaf: a subtype's PK/FK "id" column can only be added once its
            // own parent already has a resolvable primaryKey — which for a JOINED grandparent
            // is itself only true after *that* class was processed here. Sorting by generalization
            // depth (root = 0) guarantees each class is visited only after its ancestors, regardless
            // of the declaration order of `classes` (see V3.4.6 review: declaration order broke
            // 3+-level JOINED hierarchies where an intermediate subtype was declared after its child).
            val ordered = classes.sortedBy { depthOf(it.id, plans) }
            for (cls in ordered) {
                val plan = plans.getValue(cls.id)
                val parentId = plan.parentId ?: continue
                if (plan.strategy != InheritanceStrategy.JOINED || !plan.hasOwnTable) continue

                val childEntityId = entityIdByClassId[cls.id] ?: continue
                val parentEntityId = physicalEntityIdOf(parentId, plans) ?: continue
                val childEntity = entities.getValue(childEntityId)
                val parentEntity = entities.getValue(parentEntityId)
                val parentPk = parentEntity.primaryKey.singleOrNull() ?: continue

                childEntity.weak = true
                val fkAttrId = childEntity.nextAttrId()
                childEntity.attributes.add(
                    0,
                    ErmAttribute(
                        id = fkAttrId,
                        name = "id",
                        type = parentPk.type,
                        primaryKey = true,
                        nullable = false,
                        foreignKey = ErmForeignKey(targetEntityId = parentEntityId, onDelete = ReferentialAction.CASCADE),
                    ),
                )

                val relId = "rel_${relCounter++}"
                relationships +=
                    ErmRelationship(
                        id = relId,
                        name = null,
                        sourceEntityId = parentEntityId,
                        targetEntityId = childEntityId,
                        sourceCardinality = Cardinality.ONE,
                        targetCardinality = Cardinality.ZERO_ONE,
                        kind = RelationshipKind.IDENTIFYING,
                    )
                trace = trace.plus(TraceabilityLink(cls.id, relId, RULE_GENERALIZATION_TO_IDENTIFYING))
            }
        }

        private fun applyCategories(
            classes: List<UmlClass>,
            plans: Map<String, ClassPlan>,
        ) {
            for (cls in classes) {
                val plan = plans.getValue(cls.id)
                if (plan.strategy != InheritanceStrategy.JOINED || plan.childIds.isEmpty()) continue
                val supertypeEntityId = entityIdByClassId[cls.id] ?: continue
                val subtypeEntityIds = plan.childIds.mapNotNull { entityIdByClassId[it] }
                if (subtypeEntityIds.isEmpty()) continue

                categories +=
                    ErmCategory(
                        id = "category_${categoryCounter++}",
                        name = null,
                        supertypeEntityId = supertypeEntityId,
                        subtypeEntityIds = subtypeEntityIds,
                    )
            }
        }

        /**
         * Number of generalization hops from [classId] up to its hierarchy root (root = 0).
         * Cycle-guarded like [planInheritance]'s `rootIdOf` — a malformed model with a
         * generalization cycle stops depth growth instead of looping forever.
         */
        private fun depthOf(
            classId: String,
            plans: Map<String, ClassPlan>,
        ): Int {
            var cur = classId
            var depth = 0
            val seen = mutableSetOf<String>()
            while (seen.add(cur)) {
                val parent = plans[cur]?.parentId ?: return depth
                cur = parent
                depth++
            }
            return depth
        }

        /** Resolves the class's own entity, or (for SINGLE_TABLE descendants) the nearest table-owning ancestor's entity. */
        private fun physicalEntityIdOf(
            classId: String,
            plans: Map<String, ClassPlan>,
        ): String? {
            var cur: String? = classId
            while (cur != null) {
                val plan = plans[cur] ?: return entityIdByClassId[cur]
                if (plan.hasOwnTable) return entityIdByClassId[cur]
                cur = plan.parentId
            }
            return null
        }

        // ── Association resolution ───────────────────────────────────────────

        private fun mapAssociation(
            assoc: UmlAssociation,
            classById: Map<String, UmlClass>,
            plans: Map<String, ClassPlan>,
        ) {
            if (assoc.ends.size != 2) return
            val sourceEnd = assoc.ends[0]
            val targetEnd = assoc.ends[1]
            val sourceClass = classById[sourceEnd.typeId] ?: return
            val targetClass = classById[targetEnd.typeId] ?: return

            val sourceMany = sourceEnd.multiplicity.upper == null || sourceEnd.multiplicity.upper!! > 1
            val targetMany = targetEnd.multiplicity.upper == null || targetEnd.multiplicity.upper!! > 1

            when {
                sourceMany && targetMany -> resolveManyToMany(assoc, sourceClass, targetClass, sourceEnd, targetEnd, plans)
                targetMany && !sourceMany ->
                    addForeignKey(assoc, fkClass = targetClass, fkEnd = targetEnd, refClass = sourceClass, refEnd = sourceEnd, plans)
                sourceMany && !targetMany ->
                    addForeignKey(assoc, fkClass = sourceClass, fkEnd = sourceEnd, refClass = targetClass, refEnd = targetEnd, plans)
                else ->
                    // 1:1 — put the FK on the target end referencing the source end, by convention.
                    addForeignKey(assoc, fkClass = targetClass, fkEnd = targetEnd, refClass = sourceClass, refEnd = sourceEnd, plans)
            }
        }

        private fun addForeignKey(
            assoc: UmlAssociation,
            fkClass: UmlClass,
            fkEnd: UmlAssociationEnd,
            refClass: UmlClass,
            refEnd: UmlAssociationEnd,
            plans: Map<String, ClassPlan>,
        ) {
            if (fkClass.id == refClass.id) return // self-referential — cosmetic only, out of V3.4.6 scope
            val fkEntityId = physicalEntityIdOf(fkClass.id, plans) ?: return
            val refEntityId = physicalEntityIdOf(refClass.id, plans) ?: return
            val fkEntity = entities.getValue(fkEntityId)
            val refEntity = entities.getValue(refEntityId)
            val refPk = refEntity.primaryKey.singleOrNull() ?: return

            val fkStereo = assoc.appliedStereotypes.ermStereotype(ErmProfileNames.FK)
            val onDelete = parseReferentialAction(fkStereo?.stringTag(ErmProfileNames.TAG_ON_DELETE))
            val onUpdate = parseReferentialAction(fkStereo?.stringTag(ErmProfileNames.TAG_ON_UPDATE))

            val defaultBaseName = SqlIdentifiers.toSnakeCase(refClass.name).toSingular() + "_id"
            val baseName =
                if (fkEntity.hasAttributeNamed(defaultBaseName)) {
                    // Two FK associations from the same fkClass to the same refClass collide on the
                    // class-derived default (e.g. Order.createdBy / Order.assignedTo -> User both
                    // wanting "user_id") — disambiguate via the association-end role names, analogous
                    // to the self-referential M:N junction path in resolveManyToMany. Falls back to
                    // fkEnd.role if refEnd.role is absent; if neither role is set, the collision cannot
                    // be resolved and surfaces as an ErmConstraintChecker ERROR (see Known limitations).
                    val roleBasis = (refEnd.role ?: fkEnd.role)?.takeIf { it.isNotBlank() }
                    roleBasis?.let { SqlIdentifiers.toSnakeCase(it) + "_id" } ?: defaultBaseName
                } else {
                    defaultBaseName
                }
            val columnName = SqlIdentifiers.requireSafe(baseName, "FK column name", assoc.id)

            val attrId = fkEntity.nextAttrId()
            fkEntity.attributes +=
                ErmAttribute(
                    id = attrId,
                    name = columnName,
                    type = refPk.type,
                    primaryKey = false,
                    nullable = refEnd.multiplicity.lower == 0,
                    foreignKey = ErmForeignKey(targetEntityId = refEntityId, onDelete = onDelete, onUpdate = onUpdate),
                )
            trace = trace.plus(TraceabilityLink(assoc.id, attrId, RULE_ASSOC_TO_FK))

            val relId = "rel_${relCounter++}"
            relationships +=
                ErmRelationship(
                    id = relId,
                    name = assoc.name,
                    sourceEntityId = refEntityId,
                    targetEntityId = fkEntityId,
                    sourceCardinality = Cardinality(min = if (refEnd.multiplicity.lower == 0) 0 else 1, max = 1),
                    targetCardinality =
                        Cardinality(
                            min = fkEnd.multiplicity.lower,
                            max = fkEnd.multiplicity.upper ?: -1,
                        ),
                    kind = RelationshipKind.NON_IDENTIFYING,
                    sourceRole = refEnd.role,
                    targetRole = fkEnd.role,
                )
        }

        private fun resolveManyToMany(
            assoc: UmlAssociation,
            sourceClass: UmlClass,
            targetClass: UmlClass,
            sourceEnd: UmlAssociationEnd,
            targetEnd: UmlAssociationEnd,
            plans: Map<String, ClassPlan>,
        ) {
            val sourceEntityId = physicalEntityIdOf(sourceClass.id, plans) ?: return
            val targetEntityId = physicalEntityIdOf(targetClass.id, plans) ?: return
            val sourceEntity = entities.getValue(sourceEntityId)
            val targetEntity = entities.getValue(targetEntityId)
            val sourcePk = sourceEntity.primaryKey.singleOrNull() ?: return
            val targetPk = targetEntity.primaryKey.singleOrNull() ?: return

            val junctionStereo = assoc.appliedStereotypes.ermStereotype(ErmProfileNames.JUNCTION_TABLE)
            val defaultTableName =
                "${SqlIdentifiers.toSnakeCase(sourceClass.name).toPlural()}_${SqlIdentifiers.toSnakeCase(targetClass.name).toPlural()}"
            val tableName =
                SqlIdentifiers.requireSafe(
                    junctionStereo?.stringTag(ErmProfileNames.TAG_TABLE_NAME)?.takeIf { it.isNotBlank() } ?: defaultTableName,
                    "junction table name",
                    assoc.id,
                )

            val junctionEntity = MutableErmEntity(id = "entity_$entityCounter", ix = entityCounter, name = tableName)
            entityCounter++
            junctionEntity.weak = true
            entities[junctionEntity.id] = junctionEntity
            trace = trace.plus(TraceabilityLink(assoc.id, junctionEntity.id, RULE_ASSOC_TO_JUNCTION))

            var sourceColName =
                junctionStereo?.stringTag(ErmProfileNames.TAG_SOURCE_COLUMN)?.takeIf { it.isNotBlank() }
                    ?: (SqlIdentifiers.toSnakeCase(sourceClass.name).toSingular() + "_id")
            var targetColName =
                junctionStereo?.stringTag(ErmProfileNames.TAG_TARGET_COLUMN)?.takeIf { it.isNotBlank() }
                    ?: (SqlIdentifiers.toSnakeCase(targetClass.name).toSingular() + "_id")
            if (sourceColName == targetColName) {
                // Self-referential M:N (both ends the same class) — disambiguate via role names, or a fixed suffix.
                sourceColName = sourceEnd.role?.let { SqlIdentifiers.toSnakeCase(it) } ?: "${sourceColName}_source"
                targetColName = targetEnd.role?.let { SqlIdentifiers.toSnakeCase(it) } ?: "${targetColName}_target"
            }
            sourceColName = SqlIdentifiers.requireSafe(sourceColName, "junction source column name", assoc.id)
            targetColName = SqlIdentifiers.requireSafe(targetColName, "junction target column name", assoc.id)

            val sourceAttrId = junctionEntity.nextAttrId()
            junctionEntity.attributes +=
                ErmAttribute(
                    id = sourceAttrId,
                    name = sourceColName,
                    type = sourcePk.type,
                    primaryKey = true,
                    nullable = false,
                    foreignKey = ErmForeignKey(targetEntityId = sourceEntityId, onDelete = ReferentialAction.CASCADE),
                )
            val targetAttrId = junctionEntity.nextAttrId()
            junctionEntity.attributes +=
                ErmAttribute(
                    id = targetAttrId,
                    name = targetColName,
                    type = targetPk.type,
                    primaryKey = true,
                    nullable = false,
                    foreignKey = ErmForeignKey(targetEntityId = targetEntityId, onDelete = ReferentialAction.CASCADE),
                )

            val rel1 = "rel_${relCounter++}"
            relationships +=
                ErmRelationship(
                    id = rel1,
                    name = null,
                    sourceEntityId = sourceEntityId,
                    targetEntityId = junctionEntity.id,
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    kind = RelationshipKind.IDENTIFYING,
                )
            val rel2 = "rel_${relCounter++}"
            relationships +=
                ErmRelationship(
                    id = rel2,
                    name = null,
                    sourceEntityId = targetEntityId,
                    targetEntityId = junctionEntity.id,
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    kind = RelationshipKind.IDENTIFYING,
                )
        }
    }

    private companion object {
        const val RULE_CLASS_TO_ENTITY = "uml-class-to-erm-entity"
        const val RULE_PROPERTY_TO_COLUMN = "uml-property-to-erm-column"
        const val RULE_ASSOC_TO_FK = "uml-association-to-erm-fk"
        const val RULE_ASSOC_TO_JUNCTION = "uml-association-to-erm-junction"
        const val RULE_GENERALIZATION_TO_IDENTIFYING = "uml-generalization-to-erm-identifying-relationship"
        const val RULE_SINGLE_TABLE_MERGE = "uml-generalization-to-erm-single-table-merge"
    }
}

/** ServiceLoader provider for [UmlToErmTransformer]. */
public class UmlToErmTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlToErmTransformer = UmlToErmTransformer()
}
