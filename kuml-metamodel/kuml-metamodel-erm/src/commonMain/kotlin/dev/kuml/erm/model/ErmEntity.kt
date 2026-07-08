package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A database table / entity type.
 *
 * [weak] marks a weak entity — one whose primary key depends (fully or
 * partially) on an owning strong entity via an
 * [ErmRelationship] of [RelationshipKind.IDENTIFYING]. Weak entities may have
 * an empty [primaryKey] of their own attributes, but must still be reachable
 * through an identifying relationship — enforced by
 * [dev.kuml.erm.constraint.ErmConstraintChecker], not the constructor.
 *
 * [indexes] and [checks] are first-class, entity-embedded (not modeled as
 * free-floating model-level elements) because they only ever apply to
 * columns of exactly this entity.
 *
 * V3.4.1
 */
@Serializable
data class ErmEntity(
    override val id: String,
    override val name: String?,
    val attributes: List<ErmAttribute> = emptyList(),
    val weak: Boolean = false,
    val indexes: List<ErmIndex> = emptyList(),
    val checks: List<ErmCheckConstraint> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement {
    /** All attributes marked [ErmAttribute.primaryKey]. */
    val primaryKey: List<ErmAttribute> get() = attributes.filter { it.primaryKey }

    /** Looks up an attribute of this entity by its declared name. */
    fun attributeByName(name: String): ErmAttribute? = attributes.firstOrNull { it.name == name }
}
