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
 *
 * An empty result means the model passes all checks.
 *
 * V3.4.1
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
            val identifyingTargets = model.relationships.filter { it.kind == RelationshipKind.IDENTIFYING }.map { it.targetEntityId }.toSet()

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
                if (entity.primaryKey.isEmpty()) {
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
        }
}
