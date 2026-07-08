package dev.kuml.erm.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A first-class SQL `CHECK` constraint, embedded in the owning [ErmEntity].
 *
 * [expression] is a raw, dialect-neutral SQL boolean expression (e.g.
 * `"price > 0"`). Deliberately a plain string rather than an OCL AST — DB
 * checks are SQL boolean expressions, not OCL invariants, and ERM stays
 * independent of the OCL module.
 *
 * V3.4.1
 */
@Serializable
data class ErmCheckConstraint(
    override val id: String,
    override val name: String?,
    val expression: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : ErmElement
