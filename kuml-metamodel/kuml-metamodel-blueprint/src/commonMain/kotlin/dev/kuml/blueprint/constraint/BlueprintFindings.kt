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

    /**
     * One id is used by more than one element kind (actor, channel, touchpoint, phase, step, connection). Warning only:
     * the drawing is unchanged and `--strict` does not promote it. Note ids are a separate namespace and not compared.
     */
    public const val CROSS_KIND_ID: String = "KUML-BP-W002"
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

/**
 * An id that is used by two or more element kinds of a [BlueprintModel].
 *
 * @property id The shared id.
 * @property kinds The kind names that use it, in the fixed order actor, channel, touchpoint, phase, step, connection.
 */
public data class CrossKindId(
    val id: String,
    val kinds: List<String>,
) {
    /** Names the id and the kind names only, never a title or description. */
    public fun message(): String =
        "id used by several element kinds: $id (${kinds.joinToString(", ")}); anchors, notes and imports are ambiguous"
}

/** Finds step ids that occur more than once. Pure; the DSL cannot produce duplicates, JSON and OKF imports can. */
public object BlueprintDuplicateIds {
    /** One entry per duplicated id, in order of first occurrence. Only step ids count; ids shared across kinds are reported by [crossKindIds]. */
    public fun duplicateStepIds(model: BlueprintModel): List<DuplicateStepId> =
        model.steps
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .map { (id, count) -> DuplicateStepId(id = id, count = count) }

    /**
     * One entry per id that is used by at least two element kinds (actors, channels, touchpoints, phases, steps,
     * connections), in order of first occurrence; kinds are listed in that fixed order. Blank ids are ignored, and a
     * duplicate within one kind does not by itself create an entry. Notes and note links are not compared (separate
     * id namespace). Pure and linear in the model size.
     */
    public fun crossKindIds(model: BlueprintModel): List<CrossKindId> {
        val byKind: List<Pair<String, List<String>>> =
            listOf(
                "actor" to model.actors.map { it.id },
                "channel" to model.channels.map { it.id },
                "touchpoint" to model.touchpoints.map { it.id },
                "phase" to model.phases.map { it.id },
                "step" to model.steps.map { it.id },
                "connection" to model.connections.map { it.id },
            )
        val kindsById = LinkedHashMap<String, MutableList<String>>()
        for ((kind, ids) in byKind) {
            for (id in ids.distinct()) {
                if (id.isBlank()) continue
                kindsById.getOrPut(id) { mutableListOf() }.add(kind)
            }
        }
        return kindsById.filterValues { it.size >= 2 }.map { (id, kinds) -> CrossKindId(id = id, kinds = kinds.toList()) }
    }
}
