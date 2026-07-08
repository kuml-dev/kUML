package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * An IDEF1X category (subtype) cluster: one generic supertype entity
 * specialised into N mutually-exclusive category (subtype) entities via a
 * discriminator column.
 *
 * [complete] `true` → double completeness bar (every supertype row belongs to
 * exactly one subtype); `false` → single bar (partial categorisation).
 * [discriminatorAttributeId] optionally names the supertype column whose value
 * selects the subtype; rendered near the category circle when set.
 *
 * Subtypes are, by IDEF1X convention, always dependent entities — they never
 * carry an own primary key (they inherit the supertype's), which is why
 * [dev.kuml.erm.constraint.ErmConstraintChecker] exempts them from the
 * "non-weak entity needs a primary key" rule.
 *
 * V3.4.5
 */
@Serializable
data class ErmCategory(
    override val id: String,
    override val name: String?,
    val supertypeEntityId: String,
    val subtypeEntityIds: List<String>,
    val complete: Boolean = false,
    val discriminatorAttributeId: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement
