package dev.kuml.sysml2

import dev.kuml.core.model.KumlElement
import dev.kuml.kerml.KermlElement
import kotlinx.serialization.Serializable

/**
 * Marker for every element in the SysML 2 metamodel.
 *
 * SysML 2 layers on top of KerML, narrowing KerML's [KermlElement] markers
 * with SysML-specific semantics: a `PartDefinition` is *a* KerML classifier
 * with the additional meaning "this represents a part of a system". The
 * KerML structure is faithfully preserved — any SysML 2 element can be
 * downcast to its KerML role for cross-cutting tooling (layout,
 * serialisation, hash-based authenticity checks).
 *
 * V2.0.3 scope: **structural slice — definitions, usages, ports, connections**.
 * Behaviour (action/state/requirement/constraint) and the M2 reflection
 * layer are deferred to follow-up V2.x waves.
 *
 * The interface is sealed in this module so any add-on (Layout-Bridge,
 * Renderer, simulator) can `when` exhaustively over the SysML 2 element
 * kinds without a fallback branch.
 */
@Serializable
sealed interface Sysml2Element :
    KumlElement,
    KermlElement
