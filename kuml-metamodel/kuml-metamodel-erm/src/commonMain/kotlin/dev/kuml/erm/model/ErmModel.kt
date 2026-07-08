package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Top-level container for an Entity-Relationship Model.
 *
 * The model is the single source of truth; [diagrams] are projections that
 * select which entities/relationships/views/indexes are visible and in which
 * [ErmNotation].
 *
 * V3.4.1
 */
@Serializable
data class ErmModel(
    val name: String,
    val entities: List<ErmEntity> = emptyList(),
    val relationships: List<ErmRelationship> = emptyList(),
    val views: List<ErmView> = emptyList(),
    val diagrams: List<ErmDiagram> = emptyList(),
    val metadata: Map<String, KumlMetaValue> = emptyMap(),
) {
    /** Looks up an entity by id. */
    fun entityById(id: String): ErmEntity? = entities.firstOrNull { it.id == id }

    /** Looks up an attribute by id across all entities. */
    fun attributeById(id: String): ErmAttribute? =
        entities.firstNotNullOfOrNull { entity -> entity.attributes.firstOrNull { it.id == id } }

    /** Looks up an index by id across all entities. */
    fun indexById(id: String): ErmIndex? =
        entities.firstNotNullOfOrNull { entity -> entity.indexes.firstOrNull { it.id == id } }

    /** Looks up a check constraint by id across all entities. */
    fun checkById(id: String): ErmCheckConstraint? =
        entities.firstNotNullOfOrNull { entity -> entity.checks.firstOrNull { it.id == id } }

    /** Looks up a view by id. */
    fun viewById(id: String): ErmView? = views.firstOrNull { it.id == id }

    /** Looks up a relationship by id. */
    fun relationshipById(id: String): ErmRelationship? = relationships.firstOrNull { it.id == id }

    /**
     * Looks up any ERM element by id — entities, their nested attributes,
     * indexes, and check constraints, plus model-level relationships and
     * views.
     */
    fun elementById(id: String): ErmElement? =
        entities.firstOrNull { it.id == id }
            ?: attributeById(id)
            ?: indexById(id)
            ?: checkById(id)
            ?: relationships.firstOrNull { it.id == id }
            ?: views.firstOrNull { it.id == id }

    /** All relationships with an end (source or target) on the given entity. */
    fun relationshipsOf(entityId: String): List<ErmRelationship> =
        relationships.filter { it.sourceEntityId == entityId || it.targetEntityId == entityId }

    /** The first declared diagram, or `null` if none was declared. */
    fun firstDiagramOrNull(): ErmDiagram? = diagrams.firstOrNull()
}
