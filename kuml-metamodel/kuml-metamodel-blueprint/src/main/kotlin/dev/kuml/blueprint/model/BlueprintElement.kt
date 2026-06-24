package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlElement
import kotlinx.serialization.Serializable

/**
 * Sealed root interface for all User-Journey / Service-Blueprint model elements.
 *
 * Extends the open [KumlElement] core interface from kuml-core-model so that
 * exhaustive `when` expressions are possible when processing blueprint models
 * (in bridge, renderer, constraint checker).
 *
 * `id` and `metadata` are re-declared as overrides because [KumlElement] is a
 * plain interface; [name] is added here for the whole hierarchy.
 *
 * V3.1.21 — Journey/Blueprint Core-Metamodell
 */
@Serializable
sealed interface BlueprintElement : KumlElement {
    override val id: String
    val name: String?
    override val metadata: Map<String, dev.kuml.core.model.KumlMetaValue>
}
