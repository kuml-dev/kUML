package dev.kuml.sysml2

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.kerml.KermlNamespaceMember
import kotlinx.serialization.Serializable

/**
 * Sealed root for every SysML 2 **usage** — the "where is it used" side of
 * the definition/usage duality.
 *
 * A SysML 2 usage is structurally a KerML feature with the additional
 * `definitionId` reference. The MVP exposes the four usages that BDD
 * actually renders: [PartUsage], [AttributeUsage], [PortUsage],
 * [ConnectionUsage].
 *
 * Why a separate root from [Sysml2Definition]: definitions sit on the
 * KerML *type* side, usages on the *feature* side. They are both
 * [Sysml2Element]s but they specialise different KerML markers.
 */
@Serializable
sealed interface Sysml2Usage :
    Sysml2Element,
    KermlNamespaceMember {
    /** Id of the [Sysml2Definition] this usage refers to. */
    val definitionId: String

    /** Inherited from the underlying KerML feature. */
    val multiplicity: KermlMultiplicity
}

/**
 * `engine : Engine` — a part-usage anchored on a [PartDefinition].
 *
 * The bread-and-butter element inside another `PartDefinition`: it states
 * that the containing part has a sub-part of the referenced type and
 * multiplicity.
 */
@Serializable
data class PartUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `mass : Mass = 1500[kg]` — an attribute usage with an optional default
 * expression in raw source form.
 *
 * V2.0.3 stores the default as a string; a typed expression tree lands in
 * a follow-up V2.x wave alongside the action / constraint surfaces.
 */
@Serializable
data class AttributeUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    val defaultExpression: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `port inlet : Inlet` — a port usage typed by a [PortDefinition].
 *
 * The simplest port shape is enough for the BDD MVP. Directionality
 * (`in`/`out`/`inout`) and conjugation (`~Inlet`) are V2.x.
 */
@Serializable
data class PortUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `connect inlet to outlet` — a connection-usage on a part definition.
 *
 * Endpoints reference the SysML 2 element ids of the two ends. Both ends
 * are usually [PortUsage]s in a BDD/IBD context, but in V2.0.3 we keep the
 * end model flexible (`String` id) so connections can reference other
 * element kinds without a metamodel rewrite.
 */
@Serializable
data class ConnectionUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    /** Id of the source endpoint (typically a [PortUsage]). */
    val sourceEndId: String,
    /** Id of the target endpoint (typically a [PortUsage]). */
    val targetEndId: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `actor reader : Reader` — V2.0.7 actor-usage, typed by an
 * [dev.kuml.sysml2.ActorDefinition].
 *
 * Sits in the metamodel for symmetry with [PartUsage] and so that future
 * UC-Diagram polish (system-boundary frames hosting actor-usages on the
 * frame edge) has a clean attachment point. The V2.0.7 bridge does not
 * currently consume actor-usages — UC diagrams render *actor definitions*
 * directly via [dev.kuml.sysml2.UcDiagram.elementIds].
 */
@Serializable
data class ActorUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `usecase borrowBook : BorrowBook` — V2.0.7 use-case-usage, typed by a
 * [dev.kuml.sysml2.UseCaseDefinition].
 *
 * Symmetric to [ActorUsage]; the V2.0.7 MVP renders [UseCaseDefinition]s
 * directly and does not consume use-case-usages from the bridge.
 */
@Serializable
data class UseCaseUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `«include» BorrowBook ⟶ Authenticate` — V2.0.7 include-relationship-usage.
 *
 * Captures that the use case [sourceUseCaseId] *always* includes the
 * behaviour of [targetUseCaseId] (e.g. `BorrowBook` always runs
 * `Authenticate` as a prerequisite).
 *
 * `definitionId` carries the target use-case definition id so existing
 * KerML-aware tooling sees the include as "a usage typed by the included
 * use case". The V2.0.7 bridge primarily uses the diagram-level
 * [dev.kuml.sysml2.UcInclude] edge entries; this metamodel type exists for
 * future-proofing and symmetry with [ConnectionUsage].
 */
@Serializable
data class IncludeUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    /** Id of the source [UseCaseDefinition] (the *including* use case). */
    val sourceUseCaseId: String,
    /** Id of the target [UseCaseDefinition] (the *included* use case). */
    val targetUseCaseId: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `«extend» PayLateFee ⟶ ReturnBook` — V2.0.7 extend-relationship-usage.
 *
 * Captures that the use case [sourceUseCaseId] *optionally* extends the
 * behaviour of [targetUseCaseId] (e.g. `PayLateFee` extends `ReturnBook`
 * only when there is an actual fee to settle).
 *
 * Same metamodel rationale as [IncludeUsage]; the bridge primarily uses
 * the diagram-level [dev.kuml.sysml2.UcExtend] entries.
 */
@Serializable
data class ExtendUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    /** Id of the source [UseCaseDefinition] (the *extending* use case). */
    val sourceUseCaseId: String,
    /** Id of the target [UseCaseDefinition] (the *extended* use case). */
    val targetUseCaseId: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage
