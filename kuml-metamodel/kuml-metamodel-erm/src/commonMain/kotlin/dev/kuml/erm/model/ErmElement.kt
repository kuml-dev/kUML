package dev.kuml.erm.model

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Sealed root interface for all Entity-Relationship-Model elements.
 *
 * Extends the open [KumlElement] core interface from kuml-core-model so that
 * exhaustive `when` expressions are possible when processing ERM models
 * (in bridge, renderer, constraint checker).
 *
 * `id` and `metadata` are re-declared as overrides because [KumlElement] is a
 * plain interface; [name] is added here for the whole hierarchy.
 *
 * ERM is deliberately kept language-free of `kuml-metamodel-uml` — it depends
 * only on `kuml-core-model` to minimize coupling.
 *
 * V3.4.1 — ERM Core-Metamodell
 */
@Serializable
sealed interface ErmElement : KumlElement {
    override val id: String
    val name: String?
    override val metadata: Map<String, KumlMetaValue>
}
