package dev.kuml.uml

import dev.kuml.core.model.KumlElement
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.KumlNamespaceMember
import kotlinx.serialization.Serializable

/**
 * Marker for all UML elements.
 *
 * Sealed within this module so that exhaustive `when` expressions are possible
 * when processing UML models. Extends the open [KumlElement] core interface
 * (not sealed at the core level — see KumlElement KDoc for the rationale).
 */
@Serializable
sealed interface UmlElement : KumlElement

/**
 * A named, visibility-bearing UML element that belongs to a namespace.
 *
 * All structural and behavioural UML classifiers, features, and containers
 * extend this interface.
 *
 * V1: [stereotypes] holds simple string names. Full profile support
 * (`KumlStereotypeApplication`) is deferred to V1.1.
 */
@Serializable
sealed interface UmlNamedElement :
    UmlElement,
    KumlNamespaceMember {
    override val name: String
    val visibility: Visibility
    val stereotypes: List<String>
    override val metadata: Map<String, KumlMetaValue>
}

// ── Enumerations ──────────────────────────────────────────────────────────────

/** UML visibility modifiers. */
enum class Visibility { PUBLIC, PRIVATE, PROTECTED, PACKAGE }

/** UML aggregation kinds for association ends. */
enum class AggregationKind { NONE, SHARED, COMPOSITE }
