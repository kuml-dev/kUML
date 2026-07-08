package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Min/max multiplicity bound on one end of an [ErmRelationship].
 *
 * `max = -1` means unbounded ("many" / `*`). Covers crow's-foot (Martin),
 * Chen, and IDEF1X notations uniformly — the renderer (V3.4.2) maps this
 * pair to whichever glyphs the concrete [ErmNotation] uses.
 *
 * V3.4.1
 */
@Serializable
data class Cardinality(
    val min: Int,
    val max: Int,
) {
    /** `true` if this end is optional (zero is an allowed count). */
    val optional: Boolean get() = min == 0

    /** `true` if this end allows more than one related row. */
    val many: Boolean get() = max == -1 || max > 1

    companion object {
        val ZERO_ONE = Cardinality(0, 1)
        val ONE = Cardinality(1, 1)
        val ZERO_MANY = Cardinality(0, -1)
        val ONE_MANY = Cardinality(1, -1)
    }
}

/**
 * Whether an [ErmRelationship] is identifying (the child/weak entity's
 * primary key includes the parent's key through this relationship) or
 * non-identifying (a plain foreign key reference).
 *
 * V3.4.1
 */
@Serializable
enum class RelationshipKind { IDENTIFYING, NON_IDENTIFYING }

/**
 * A relationship (association) between two [ErmEntity] instances.
 *
 * [name] is the verb phrase labeling the relationship (Chen: diamond label;
 * IDEF1X: relationship name). [sourceRole] / [targetRole] are optional
 * role names for the respective ends (useful when the same pair of entities
 * is connected by more than one relationship).
 *
 * V3.4.1
 */
@Serializable
data class ErmRelationship(
    override val id: String,
    override val name: String?,
    val sourceEntityId: String,
    val targetEntityId: String,
    val sourceCardinality: Cardinality,
    val targetCardinality: Cardinality,
    val kind: RelationshipKind = RelationshipKind.NON_IDENTIFYING,
    val sourceRole: String? = null,
    val targetRole: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement
