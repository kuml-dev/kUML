package dev.kuml.c4.constraint

import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship

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

    /**
     * Two or more relationships connect the same source to the same target (same direction and bidirectional flag) and
     * differ only in letter case or whitespace of label, technology or description. Warning only: the drawing is
     * unchanged. Nacharbeit 121b.
     */
    public const val NEAR_DUPLICATE_RELATIONSHIP: String = "KUML-C4-W002"
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

/**
 * A group of relationships that differ only in letter case or whitespace (Nacharbeit 121b).
 *
 * @property source Source element id.
 * @property target Target element id.
 * @property label Label of the first member, whitespace collapsed, case preserved.
 * @property technology Technology of the first member, whitespace collapsed, or `null`.
 * @property count How many relationships are in the normalised group (at least 2).
 * @property relationshipIds Ids of the group, in model order.
 */
public data class NearDuplicateRelationship(
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
        return "near-duplicate relationship: $source -> $target \"$label\"$tech " +
            "($count occurrences: ${relationshipIds.joinToString(", ")}); they differ only in letter case or whitespace"
    }
}

/**
 * Finds relationships that differ only in letter case or whitespace. The normalised key (trim, collapse whitespace,
 * lowercase) is used for detection only; ids, direction and the bidirectional flag must match exactly. A group is
 * reported only if it holds at least two distinct exact variants; purely identical groups are `KUML-C4-W001`.
 * Pure and linear.
 */
public object C4NearDuplicateRelationships {
    private val WHITESPACE_RUN = Regex("\\s+")

    private data class NormKey(
        val source: String,
        val target: String,
        val bidirectional: Boolean,
        val label: String,
        val technology: String?,
        val description: String?,
    )

    private data class ExactKey(
        val label: String,
        val technology: String?,
        val description: String?,
    )

    private fun String.collapsed(): String = trim().replace(WHITESPACE_RUN, " ")

    private fun String.normalised(): String = collapsed().lowercase()

    private fun distinctExactVariants(group: List<C4Relationship>): Int =
        group
            .map { ExactKey(label = it.label, technology = it.technology, description = it.description) }
            .distinct()
            .size

    /** One entry per normalised group with at least two distinct exact variants, in order of first occurrence. */
    public fun findNear(model: C4Model): List<NearDuplicateRelationship> =
        model.relationships
            .groupBy {
                NormKey(
                    source = it.source,
                    target = it.target,
                    bidirectional = it.bidirectional,
                    label = it.label.normalised(),
                    technology = it.technology?.normalised(),
                    description = it.description?.normalised(),
                )
            }.values
            .filter { group -> group.size >= 2 && distinctExactVariants(group) >= 2 }
            .map { group ->
                val first = group.first()
                NearDuplicateRelationship(
                    source = first.source,
                    target = first.target,
                    label = first.label.collapsed(),
                    technology = first.technology?.collapsed(),
                    count = group.size,
                    relationshipIds = group.map { it.id },
                )
            }
}
