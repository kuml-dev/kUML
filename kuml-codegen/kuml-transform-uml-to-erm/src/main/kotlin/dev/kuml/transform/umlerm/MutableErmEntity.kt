package dev.kuml.transform.umlerm

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmIndex

/**
 * Mutable working representation of an [ErmEntity] under construction.
 *
 * [UmlToErmTransformer] builds entities directly (not via
 * [dev.kuml.erm.dsl.ErmModelBuilder]) because entities and their attribute
 * sets are mutated across several phases (attribute mapping, inheritance
 * materialisation, association resolution) before being frozen into the
 * immutable [ErmEntity]. IDs remain deterministic — `entity_<ix>` /
 * `attr_<entityIx>_<n>` / `check_<entityIx>_<n>` — matching the DSL builder's
 * own scheme so golden/snapshot tests stay stable.
 *
 * V3.4.6
 */
internal class MutableErmEntity(
    val id: String,
    private val ix: Int,
    var name: String?,
) {
    var weak: Boolean = false
    var metadata: Map<String, KumlMetaValue> = emptyMap()
    val attributes: MutableList<ErmAttribute> = mutableListOf()
    val checks: MutableList<ErmCheckConstraint> = mutableListOf()
    val indexes: MutableList<ErmIndex> = mutableListOf()

    private var attrCounter = 0
    private var checkCounter = 0
    private var indexCounter = 0

    fun nextAttrId(): String = "attr_${ix}_${attrCounter++}"

    fun nextCheckId(): String = "check_${ix}_${checkCounter++}"

    fun nextIndexId(): String = "index_${ix}_${indexCounter++}"

    /** All attributes currently marked [ErmAttribute.primaryKey]. */
    val primaryKey: List<ErmAttribute> get() = attributes.filter { it.primaryKey }

    fun hasAttributeNamed(name: String): Boolean = attributes.any { it.name == name }

    fun toErmEntity(): ErmEntity =
        ErmEntity(
            id = id,
            name = name,
            attributes = attributes.toList(),
            weak = weak,
            checks = checks.toList(),
            indexes = indexes.toList(),
            metadata = metadata,
        )
}
