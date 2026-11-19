package dev.kuml.c4.constraint

import dev.kuml.c4.model.C4Model

/**
 * Finding codes of the C4 family (`KUML-C4-*`).
 *
 * Nacharbeit 113.
 */
public object C4Codes {
    /**
     * Two or more relationships connect the same source to the same target (same direction) with identical label,
     * technology, bidirectional flag and description. Warning only: the drawing is unchanged.
     */
    public const val DUPLICATE_RELATIONSHIP: String = "KUML-C4-W001"
}

/**
 * A group of identical relationships in a [C4Model].
 *
 * @property source Source element id.
 * @property target Target element id.
 * @property label The relationship label shown on the edge.
 * @property technology The technology, or `null`.
 * @property count How many identical relationships exist (at least 2).
 * @property relationshipIds Ids of the identical relationships, in model order.
 */
public data class DuplicateRelationship(
    val source: String,
    val target: String,
    val label: String,
    val technology: String?,
    val count: Int,
    val relationshipIds: List<String>,
) {
    /** Names endpoints, label, technology, count and ids; never the free-text description. */
    public fun message(): String {
        val tech = if (technology != null) " [$technology]" else ""
        return "duplicate relationship: $source -> $target \"$label\"$tech " +
            "($count occurrences: ${relationshipIds.joinToString(", ")}); identical relationships are redundant"
    }
}

/** Finds identical relationships of a C4 model. Pure and linear; compiled for every target. */
public object C4DuplicateRelationships {
    private data class Key(
        val source: String,
        val target: String,
        val label: String,
        val technology: String?,
        val bidirectional: Boolean,
        val description: String?,
    )

    /** One entry per group of two or more identical relationships, in order of first occurrence. */
    public fun find(model: C4Model): List<DuplicateRelationship> =
        model.relationships
            .groupBy {
                Key(
                    source = it.source,
                    target = it.target,
                    label = it.label,
                    technology = it.technology,
                    bidirectional = it.bidirectional,
                    description = it.description,
                )
            }.filterValues { it.size >= 2 }
            .map { (key, group) ->
                DuplicateRelationship(
                    source = key.source,
                    target = key.target,
                    label = key.label,
                    technology = key.technology,
                    count = group.size,
                    relationshipIds = group.map { it.id },
                )
            }
}
