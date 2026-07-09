package dev.kuml.codegen.reverse.sql

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind

/**
 * Derives [ErmRelationship]s from the foreign keys attached to every entity's
 * attributes by [ConstraintResolver.resolveForeignKeys] (V3.4.9).
 *
 * Mirrors the relationship convention verified against
 * `UmlToErmTransformer.kt` (V3.4.6, lines 460-708) so UML→ERM and SQL→ERM stay
 * consistent: `sourceEntityId` is always the parent (PK/referenced side),
 * `targetEntityId` is always the child (FK side).
 *
 * Cardinality/identifying-ness is inferred purely from column-level facts —
 * there is no association object in raw SQL to carry it, unlike the UML path:
 *  - the FK column is part of the child's own primary key ⇒ [RelationshipKind.IDENTIFYING]
 *    and the child [MutableErmEntity.weak] flag is set — the plan's core convention (§1).
 *  - a nullable FK column ⇒ the parent side is optional (`Cardinality.ZERO_ONE`);
 *    a `NOT NULL` FK column ⇒ the parent side is mandatory (`Cardinality.ONE`).
 *  - an FK column that is the *sole* primary-key column, or that also carries a
 *    `UNIQUE` constraint, can have at most one child row per parent ⇒ 1:1
 *    (`Cardinality.ZERO_ONE` on the child side).
 *  - an FK column that is *one of several* composite primary-key columns (the
 *    junction-table shape — two FKs forming the PK together) ⇒ many child rows
 *    per parent are still possible for a *fixed* value of this one column, since
 *    the other FK column varies ⇒ `Cardinality.ZERO_MANY` on the child side.
 *  - otherwise (a plain, non-unique FK column) ⇒ `Cardinality.ZERO_MANY` (ordinary 1:N).
 */
internal object RelationshipInferrer {
    fun infer(entities: Map<String, MutableErmEntity>): List<ErmRelationship> {
        val relationships = mutableListOf<ErmRelationship>()
        var relCounter = 0

        for ((childId, childEntity) in entities) {
            val compositePk = childEntity.attributes.count { it.primaryKey } > 1
            for (attr in childEntity.attributes) {
                val fk = attr.foreignKey ?: continue
                if (!entities.containsKey(fk.targetEntityId)) continue

                val kind = if (attr.primaryKey) RelationshipKind.IDENTIFYING else RelationshipKind.NON_IDENTIFYING
                if (attr.primaryKey) childEntity.weak = true

                val sourceCardinality = if (attr.nullable) Cardinality.ZERO_ONE else Cardinality.ONE
                val targetCardinality =
                    when {
                        attr.primaryKey && compositePk -> Cardinality.ZERO_MANY
                        attr.primaryKey -> Cardinality.ZERO_ONE
                        attr.unique -> Cardinality.ZERO_ONE
                        else -> Cardinality.ZERO_MANY
                    }

                relationships +=
                    ErmRelationship(
                        id = "rel_${relCounter++}",
                        name = null,
                        sourceEntityId = fk.targetEntityId,
                        targetEntityId = childId,
                        sourceCardinality = sourceCardinality,
                        targetCardinality = targetCardinality,
                        kind = kind,
                    )
            }
        }
        return relationships
    }
}
