package dev.kuml.blueprint.constraint

import dev.kuml.blueprint.model.BlueprintModel

/**
 * Finding codes of the Service Blueprint family (`KUML-BP-*`).
 *
 * Nacharbeit 111.
 */
public object BlueprintCodes {
    /**
     * Two or more steps share one id. Warning only: the drawing is unchanged, because the geometry keeps one card
     * per id (`associateBy`).
     */
    public const val DUPLICATE_STEP_ID: String = "KUML-BP-W001"
}

/**
 * A step id that occurs more than once in a [BlueprintModel].
 *
 * @property id The duplicated step id.
 * @property count How often the id occurs among the steps (at least 2).
 */
public data class DuplicateStepId(
    val id: String,
    val count: Int,
) {
    /** Names the id and the count, never a note body or a step name. */
    public fun message(): String = "duplicate step id: $id ($count occurrences); only one step is drawn"
}

/** Finds step ids that occur more than once. Pure; the DSL cannot produce duplicates, JSON and OKF imports can. */
public object BlueprintDuplicateIds {
    /** One entry per duplicated id, in order of first occurrence. Only step ids count (not ids shared with phases or actors). */
    public fun duplicateStepIds(model: BlueprintModel): List<DuplicateStepId> =
        model.steps
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .map { (id, count) -> DuplicateStepId(id = id, count = count) }
}
