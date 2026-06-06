package dev.kuml.sysml2

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.kerml.KermlFeature
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.kerml.KermlType
import kotlinx.serialization.Serializable

/**
 * Sealed root for every SysML 2 **definition** — the "what *is* it" side of
 * the definition/usage duality.
 *
 * A SysML 2 definition is structurally a KerML type that owns features. The
 * features themselves usually surface in tooling as SysML 2 usages
 * ([Sysml2Usage]) — but at the KerML layer there is no separate concept,
 * just `Feature`s and their `typeId`s. The MVP keeps the SysML 2 layer
 * thin and trusts the KerML primitives.
 */
@Serializable
sealed interface Sysml2Definition :
    Sysml2Element,
    KermlType

/**
 * `PartDefinition` — the SysML 2 successor to UML class / SysML 1 block.
 *
 * Represents a system part *type* (e.g. `Vehicle`, `Engine`, `Cylinder`).
 * Owns attribute / port / part usages via [features]. Inheritance is
 * encoded via [specializations] (KerML `:>`).
 */
@Serializable
data class PartDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `AttributeDefinition` — a SysML 2 attribute *type*, e.g. `Mass`, `Voltage`,
 * `Boolean`. Used in BDD diagrams as the typing reference for attribute
 * usages. Backed by a KerML data type (value semantics, not parts).
 */
@Serializable
data class AttributeDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `PortDefinition` — a typed connection point. The SysML 2 successor to
 * SysML 1 ports / UML interface-pair patterns.
 */
@Serializable
data class PortDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `ConnectionDefinition` — a typed relationship between two port-bearing
 * elements. Connection *usages* live on a `PartDefinition`'s feature list;
 * this is the type they instantiate.
 */
@Serializable
data class ConnectionDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `ActorDefinition` — V2.0.7 entry for the SysML 2 Use Case Diagram.
 *
 * Represents an external entity that interacts with the system under
 * consideration (a human user, a downstream service, a sensor, …). In a UC
 * Diagram, actors are the *sources* of associations to use cases — they
 * "participate in" a capability.
 *
 * Structurally identical to [PartDefinition] (KerML type that owns features)
 * — the differentiation is purely diagrammatic: actors render as stick
 * figures, parts render as boxes. Future polish waves may add actor-
 * specialisation arrows or system-boundary frames; V2.0.7 keeps the actor
 * as a flat leaf node.
 */
@Serializable
data class ActorDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `UseCaseDefinition` — V2.0.7 entry for the SysML 2 Use Case Diagram.
 *
 * Represents a capability or scenario the system offers, e.g.
 * `BorrowBook`, `Authenticate`, `PayLateFee`. In a UC Diagram, use cases
 * are the *targets* of actor-associations and the endpoints of the
 * `«include»` / `«extend»` relationships between two use cases.
 *
 * Structurally identical to [PartDefinition] — the distinction lives in
 * the renderer (use cases become ellipses, parts become boxes) and in the
 * way UC diagrams aggregate them via [dev.kuml.sysml2.UcAssociation] /
 * [dev.kuml.sysml2.UcInclude] / [dev.kuml.sysml2.UcExtend]. Use-case
 * generalisation (`UC :> ParentUC`) is V2.x polish.
 */
@Serializable
data class UseCaseDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `RequirementDefinition` — V2.0.8 entry for the SysML 2 Requirement Diagram.
 *
 * Represents a system requirement *type*: a constraint or expectation that a
 * design must satisfy, an external test must verify, or that other
 * requirements derive from / contain. Maps to SysML 2's `requirement def`
 * keyword.
 *
 * Carries three V2.0.8-specific fields on top of the structural base:
 *  - [text] — the requirement statement in natural language, e.g.
 *    `"The vehicle shall reach at least 180 km/h on flat road"`. Rendered as
 *    the third compartment of the box (word-wrapped). Empty string omits the
 *    text compartment in the SVG renderer.
 *  - [reqId] — the optional human-readable identifier, e.g. `"R-001"`. When
 *    set, the box title compartment shows `"R-001 :: TopSpeedRequirement"`.
 *  - [subject] — id of the element this requirement constrains (a
 *    [PartDefinition], [UseCaseDefinition], etc.). Maps to SysML 2's
 *    `subject`-keyword on `requirement def`. V2.0.8 carries this as a slot;
 *    automatic subject-edge inference is V2.x polish (see wave plan).
 *
 * Structurally otherwise identical to [PartDefinition] — a KerML type that
 * owns features. Renderer differentiation lives in
 * [dev.kuml.io.svg.sysml2.renderSysml2Definition] (three-compartment box
 * with `«requirement»`-stereotype).
 *
 * V2.0.8 MVP scope (per the wave plan):
 *  - Box-with-three-compartments rendering: `«requirement»`, name (+ optional
 *    `R-NNN ::`-prefix), text.
 *  - Four edge kinds via [ReqDiagram]: [ReqSatisfy], [ReqVerify],
 *    [ReqDerive], [ReqContains].
 *  - V2.x: dashed-line + `«satisfy»` / `«verify»` / `«deriveReqt»` stereotype
 *    labels on edges; typed constraint expressions; automatic subject-edge
 *    inference from [subject].
 */
@Serializable
data class RequirementDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    /** The requirement statement in natural language. Empty = omit text compartment. */
    val text: String = "",
    /** Optional human-readable identifier, e.g. `"R-001"`. Empty = name only. */
    val reqId: String = "",
    /** Optional id of the constrained element (PartDefinition, UseCaseDefinition, …). */
    val subject: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition
