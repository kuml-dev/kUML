package dev.kuml.codegen.sql

import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmIndex
import dev.kuml.erm.model.ErmView

/**
 * ADR-0016 (deferred item) — a newly-added column on an already-existing
 * [entity]. [entity] is always the **new** model's snapshot of the owning
 * entity (up-to-date name/attribute-id resolution for FK/naming lookups).
 */
internal data class AddedAttribute(
    val entity: ErmEntity,
    val attribute: ErmAttribute,
)

/** A newly-added index on an already-existing [entity] (new-model snapshot). */
internal data class AddedIndex(
    val entity: ErmEntity,
    val index: ErmIndex,
)

/** A newly-added `CHECK` constraint on an already-existing [entity] (new-model snapshot). */
internal data class AddedCheck(
    val entity: ErmEntity,
    val check: ErmCheckConstraint,
)

/**
 * The additive-only delta between two [dev.kuml.erm.model.ErmModel] snapshots,
 * as computed by [ErmSchemaDiffGenerator.diff]. Every field here corresponds to
 * exactly one *safe* DDL statement kind ([ErmSchemaDiffEmitter] renders them in
 * this order): `CREATE TABLE`, `ALTER TABLE … ADD COLUMN`, `CREATE INDEX`,
 * `CREATE VIEW`, `ALTER TABLE … ADD CONSTRAINT … CHECK`.
 *
 * Never constructed directly for a diff containing destructive or ambiguous
 * changes — see [DiffOutcome.Refused] for that path instead.
 */
internal data class ErmSchemaDiff(
    val newEntities: List<ErmEntity> = emptyList(),
    val newAttributes: List<AddedAttribute> = emptyList(),
    val newIndexes: List<AddedIndex> = emptyList(),
    val newViews: List<ErmView> = emptyList(),
    val newChecks: List<AddedCheck> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            newEntities.isEmpty() &&
                newAttributes.isEmpty() &&
                newIndexes.isEmpty() &&
                newViews.isEmpty() &&
                newChecks.isEmpty()
}

/**
 * Result of [ErmSchemaDiffGenerator.diff] — either a purely additive
 * [ErmSchemaDiff], or a [Refused] outcome carrying **every** destructive or
 * ambiguous change found (not just the first one), so a caller can report the
 * complete set of blockers in one shot instead of a fix-one-rerun-repeat loop.
 */
internal sealed interface DiffOutcome {
    data class Ok(
        val diff: ErmSchemaDiff,
    ) : DiffOutcome

    data class Refused(
        val reasons: List<String>,
    ) : DiffOutcome
}
