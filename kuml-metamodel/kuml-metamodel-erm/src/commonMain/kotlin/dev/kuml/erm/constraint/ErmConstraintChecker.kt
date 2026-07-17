package dev.kuml.erm.constraint

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.RelationshipKind

/**
 * A single constraint violation found by [ErmConstraintChecker].
 *
 * @property elementId ID of the offending element, or null for model-level issues.
 * @property message Human-readable description.
 * @property severity Whether this blocks rendering/validation (ERROR) or is advisory (WARNING).
 *
 * V3.4.1
 */
public data class ConstraintViolation(
    val elementId: String?,
    val message: String,
    val severity: ViolationSeverity,
)

/** Severity of a [ConstraintViolation]. */
public enum class ViolationSeverity {
    /** Structural error — the model is incomplete or internally inconsistent. */
    ERROR,

    /** Advisory warning — the model is valid but may not follow best practice. */
    WARNING,
}

/**
 * Checks an [ErmModel] for structural constraint violations.
 *
 * Rules:
 *  1. At least one entity — ERROR.
 *  2. Each entity has at least one attribute — WARNING.
 *  3. Each non-weak entity has at least one primary-key attribute — ERROR.
 *     A weak entity's own primary key may be empty, but it must then be the
 *     target of an identifying relationship — ERROR if not.
 *  4. Unique attribute names within each entity — ERROR.
 *  5. Unique entity names within the model — ERROR.
 *  6. `ErmForeignKey.targetEntityId` resolves; `targetAttributeId` (if set)
 *     resolves within the target entity — ERROR.
 *  7. FK attribute type matches the target column type — ERROR; unless the
 *     target type is [ErmDataType.Custom] (WARNING, cannot verify).
 *  8. `ErmRelationship.sourceEntityId` / `targetEntityId` resolve — ERROR.
 *  9. An identifying relationship's target (child) entity should be `weak`
 *     — WARNING otherwise (best practice, not a hard requirement).
 * 10. `ErmIndex.attributeIds` resolve within the same entity and are non-empty — ERROR.
 * 11. `ErmView.query` is non-blank — ERROR; `referencedEntityIds` resolve — WARNING.
 * 12. `ErmDiagram.elementIds` resolve to an existing model element — ERROR.
 * 13. A many-to-many relationship marked `IDENTIFYING` — WARNING (likely a
 *     missing junction-table resolution; relevant once V3.4.6 does M2M→ERM).
 * 14. `autoIncrement` only makes sense on [ErmDataType.Integer] columns — WARNING otherwise.
 * 15. `ErmCheckConstraint.expression` is non-blank — ERROR.
 * 16. `ErmCategory.supertypeEntityId` resolves — ERROR.
 * 17. Every `ErmCategory.subtypeEntityIds` entry resolves — ERROR; the list is
 *     non-empty — ERROR.
 * 18. A category's supertype is not also one of its own subtypes — ERROR
 *     (no self-categorisation cycle).
 * 19. `ErmCategory.discriminatorAttributeId`, if set, belongs to the
 *     category's supertype entity — ERROR.
 * 20. `ErmDataType.Enum.values` is non-empty — ERROR (an empty literal set
 *     would render an invalid `CHECK (col IN ())` and a useless Kotlin
 *     `enum class` with no constants).
 * 21. `ErmDataType.Enum.values` contains no duplicate literals — ERROR
 *     (duplicates would render duplicate Kotlin enum constants, a compile
 *     error in the generated code).
 *
 * Category subtypes are exempt from rule 3's primary-key requirement — by
 * IDEF1X convention they inherit the supertype's key and legitimately have no
 * primary-key attribute of their own, without needing to be `weak` or the
 * target of an identifying relationship.
 *
 * An empty result means the model passes all checks.
 *
 * V3.4.1, category rules (16-19) V3.4.5, enum rules (20-21) ADR-0016 retrofit
 */
public class ErmConstraintChecker {
    public fun check(model: ErmModel): List<ConstraintViolation> =
        buildList {
            // 1. minimum content
            if (model.entities.isEmpty()) {
                add(ConstraintViolation(null, "ERM model '${model.name}' has no entities", ViolationSeverity.ERROR))
            }

            val entityIds = model.entities.map { it.id }.toSet()
            val entityNameCounts = model.entities.groupingBy { it.name }.eachCount()

            // 5. unique entity names
            entityNameCounts.filter { it.value > 1 }.forEach { (entityName, _) ->
                add(
                    ConstraintViolation(
                        null,
                        "Entity name '$entityName' is used more than once in model '${model.name}'",
                        ViolationSeverity.ERROR,
                    ),
                )
            }

            // Identifying relationships, keyed by target (child) entity — used by rules 3 and 9.
            val identifyingTargets =
                model.relationships
                    .filter { it.kind == RelationshipKind.IDENTIFYING }
                    .map { it.targetEntityId }
                    .toSet()

            // Category subtypes — exempt from rule 3's primary-key requirement (they inherit the supertype's key).
            val categorySubtypeIds = model.categories.flatMap { it.subtypeEntityIds }.toSet()

            model.entities.forEach { entity ->
                // 2. at least one attribute
                if (entity.attributes.isEmpty()) {
                    add(
                        ConstraintViolation(
                            entity.id,
                            "Entity '${entity.name ?: entity.id}' has no attributes",
                            ViolationSeverity.WARNING,
                        ),
                    )
                }

                // 3. primary key presence (non-weak) / identifying relationship (weak)
                // Category subtypes are exempt — they inherit the supertype's primary key.
                if (entity.primaryKey.isEmpty() && entity.id !in categorySubtypeIds) {
                    if (!entity.weak) {
                        add(
                            ConstraintViolation(
                                entity.id,
                                "Entity '${entity.name ?: entity.id}' has no primary key",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    } else if (entity.id !in identifyingTargets) {
                        add(
                            ConstraintViolation(
                                entity.id,
                                "Weak entity '${entity.name ?: entity.id}' has no primary key and is not the " +
                                    "target of an identifying relationship",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }
                }

                // 4. unique attribute names within the entity
                entity.attributes
                    .groupingBy { it.name }
                    .eachCount()
                    .filter { it.value > 1 }
                    .forEach { (attrName, _) ->
                        add(
                            ConstraintViolation(
                                entity.id,
                                "Attribute name '$attrName' is used more than once in entity '${entity.name ?: entity.id}'",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }

                // 6 & 7. foreign keys
                entity.attributes.forEach { attribute ->
                    val fk = attribute.foreignKey
                    if (fk != null) {
                        val targetEntity = model.entityById(fk.targetEntityId)
                        if (targetEntity == null) {
                            add(
                                ConstraintViolation(
                                    attribute.id,
                                    "Foreign key '${attribute.name ?: attribute.id}' targets unknown entity '${fk.targetEntityId}'",
                                    ViolationSeverity.ERROR,
                                ),
                            )
                        } else {
                            val targetAttr =
                                if (fk.targetAttributeId != null) {
                                    val resolved = targetEntity.attributes.firstOrNull { it.id == fk.targetAttributeId }
                                    if (resolved == null) {
                                        add(
                                            ConstraintViolation(
                                                attribute.id,
                                                "Foreign key '${attribute.name ?: attribute.id}' targets unknown attribute " +
                                                    "'${fk.targetAttributeId}' on entity '${targetEntity.name ?: targetEntity.id}'",
                                                ViolationSeverity.ERROR,
                                            ),
                                        )
                                    }
                                    resolved
                                } else {
                                    targetEntity.primaryKey.singleOrNull()
                                }
                            if (targetAttr != null && targetAttr.type != attribute.type) {
                                val severity =
                                    if (targetAttr.type is ErmDataType.Custom || attribute.type is ErmDataType.Custom) {
                                        ViolationSeverity.WARNING
                                    } else {
                                        ViolationSeverity.ERROR
                                    }
                                add(
                                    ConstraintViolation(
                                        attribute.id,
                                        "Foreign key '${attribute.name ?: attribute.id}' has type '${attribute.type.render()}' " +
                                            "but target column has type '${targetAttr.type.render()}'",
                                        severity,
                                    ),
                                )
                            }
                        }
                    }

                    // 14. autoIncrement only on Integer columns
                    if (attribute.autoIncrement && attribute.type !is ErmDataType.Integer) {
                        add(
                            ConstraintViolation(
                                attribute.id,
                                "Attribute '${attribute.name ?: attribute.id}' is autoIncrement but has non-integer type " +
                                    "'${attribute.type.render()}'",
                                ViolationSeverity.WARNING,
                            ),
                        )
                    }

                    // 20 & 21. enum literal set is non-empty and duplicate-free
                    val enumType = attribute.type
                    if (enumType is ErmDataType.Enum) {
                        if (enumType.values.isEmpty()) {
                            add(
                                ConstraintViolation(
                                    attribute.id,
                                    "Attribute '${attribute.name ?: attribute.id}' has enum type '${enumType.name}' " +
                                        "with no literal values",
                                    ViolationSeverity.ERROR,
                                ),
                            )
                        }
                        val duplicateValues =
                            enumType.values
                                .groupingBy { it }
                                .eachCount()
                                .filter { it.value > 1 }
                                .keys
                        if (duplicateValues.isNotEmpty()) {
                            add(
                                ConstraintViolation(
                                    attribute.id,
                                    "Attribute '${attribute.name ?: attribute.id}' has enum type '${enumType.name}' " +
                                        "with duplicate literal values: ${duplicateValues.joinToString(", ")}",
                                    ViolationSeverity.ERROR,
                                ),
                            )
                        }
                    }
                }

                // 10. index attribute references
                entity.indexes.forEach { index ->
                    if (index.attributeIds.isEmpty()) {
                        add(
                            ConstraintViolation(
                                index.id,
                                "Index '${index.name ?: index.id}' on entity '${entity.name ?: entity.id}' has no attributes",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }
                    val ownAttributeIds = entity.attributes.map { it.id }.toSet()
                    index.attributeIds.forEach { attrId ->
                        if (attrId !in ownAttributeIds) {
                            add(
                                ConstraintViolation(
                                    index.id,
                                    "Index '${index.name ?: index.id}' references attribute '$attrId' which is not " +
                                        "part of entity '${entity.name ?: entity.id}'",
                                    ViolationSeverity.ERROR,
                                ),
                            )
                        }
                    }
                }

                // 15. check-constraint expression non-blank
                entity.checks.forEach { check ->
                    if (check.expression.isBlank()) {
                        add(
                            ConstraintViolation(
                                check.id,
                                "Check constraint '${check.name ?: check.id}' on entity '${entity.name ?: entity.id}' has an empty expression",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }
                }
            }

            // 8, 9, 13. relationships
            model.relationships.forEach { rel ->
                if (rel.sourceEntityId !in entityIds) {
                    add(
                        ConstraintViolation(
                            rel.id,
                            "Relationship '${rel.name ?: rel.id}' sourceEntityId '${rel.sourceEntityId}' not found",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
                if (rel.targetEntityId !in entityIds) {
                    add(
                        ConstraintViolation(
                            rel.id,
                            "Relationship '${rel.name ?: rel.id}' targetEntityId '${rel.targetEntityId}' not found",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }

                if (rel.kind == RelationshipKind.IDENTIFYING) {
                    val targetEntity = model.entityById(rel.targetEntityId)
                    if (targetEntity != null && !targetEntity.weak) {
                        add(
                            ConstraintViolation(
                                rel.id,
                                "Identifying relationship '${rel.name ?: rel.id}' targets entity " +
                                    "'${targetEntity.name ?: targetEntity.id}' which is not marked as weak",
                                ViolationSeverity.WARNING,
                            ),
                        )
                    }
                }

                // 13. M:N identifying relationship — likely missing junction-table resolution.
                if (rel.kind == RelationshipKind.IDENTIFYING && rel.sourceCardinality.many && rel.targetCardinality.many) {
                    add(
                        ConstraintViolation(
                            rel.id,
                            "Relationship '${rel.name ?: rel.id}' is many-to-many and marked IDENTIFYING — this " +
                                "usually indicates a missing junction-table resolution",
                            ViolationSeverity.WARNING,
                        ),
                    )
                }
            }

            // 11. views
            model.views.forEach { view ->
                if (view.query.isBlank()) {
                    add(
                        ConstraintViolation(
                            view.id,
                            "View '${view.name ?: view.id}' has an empty query",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
                view.referencedEntityIds.forEach { refId ->
                    if (refId !in entityIds) {
                        add(
                            ConstraintViolation(
                                view.id,
                                "View '${view.name ?: view.id}' references unknown entity '$refId'",
                                ViolationSeverity.WARNING,
                            ),
                        )
                    }
                }
            }

            // 12. diagram element references
            model.diagrams.forEach { diagram ->
                diagram.elementIds.forEach { elementId ->
                    if (model.elementById(elementId) == null) {
                        add(
                            ConstraintViolation(
                                null,
                                "Diagram '${diagram.name}' references unknown element '$elementId'",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }
                }
            }

            // 16-19. IDEF1X categories
            model.categories.forEach { category ->
                // 16. supertype resolves
                val supertype = model.entityById(category.supertypeEntityId)
                if (supertype == null) {
                    add(
                        ConstraintViolation(
                            category.id,
                            "Category '${category.name ?: category.id}' supertypeEntityId " +
                                "'${category.supertypeEntityId}' not found",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }

                // 17. subtypes resolve and non-empty
                if (category.subtypeEntityIds.isEmpty()) {
                    add(
                        ConstraintViolation(
                            category.id,
                            "Category '${category.name ?: category.id}' has no subtype entities",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
                category.subtypeEntityIds.forEach { subtypeId ->
                    if (subtypeId !in entityIds) {
                        add(
                            ConstraintViolation(
                                category.id,
                                "Category '${category.name ?: category.id}' subtype entity '$subtypeId' not found",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }
                }

                // 18. no self-categorisation cycle
                if (category.supertypeEntityId in category.subtypeEntityIds) {
                    add(
                        ConstraintViolation(
                            category.id,
                            "Category '${category.name ?: category.id}' has supertype " +
                                "'${category.supertypeEntityId}' listed as one of its own subtypes",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }

                // 19. discriminator attribute belongs to the supertype
                val discriminatorId = category.discriminatorAttributeId
                if (discriminatorId != null && supertype != null) {
                    if (supertype.attributes.none { it.id == discriminatorId }) {
                        add(
                            ConstraintViolation(
                                category.id,
                                "Category '${category.name ?: category.id}' discriminatorAttributeId " +
                                    "'$discriminatorId' is not an attribute of supertype " +
                                    "'${supertype.name ?: supertype.id}'",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }
                }
            }
        }
}
