package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A first-class database view on model level.
 *
 * [query] is a raw, dialect-neutral `SELECT` body. [referencedEntityIds] is
 * an optional, explicitly-tracked list of entities the view reads from — used
 * by [dev.kuml.erm.constraint.ErmConstraintChecker] to flag dangling
 * references without parsing the SQL itself.
 *
 * V3.4.1
 */
@Serializable
data class ErmView(
    override val id: String,
    override val name: String?,
    val query: String,
    val referencedEntityIds: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement
