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

/**
 * `requirement topSpeed : TopSpeedRequirement` — V2.0.8 requirement-usage,
 * typed by a [dev.kuml.sysml2.RequirementDefinition].
 *
 * Symmetric to [ActorUsage] / [UseCaseUsage]; the V2.0.8 MVP renders
 * [RequirementDefinition]s directly and does not consume requirement-usages
 * from the bridge. Lives in the metamodel for completeness so future
 * polish waves (nested requirement-usages, requirement specialisation) have
 * a clean attachment point. The SVG renderer falls back to the generic
 * usage-box dispatch (with `«requirement»` stereotype).
 */
@Serializable
data class RequirementUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `state s1 : State1` — V2.0.9 state-usage, typed by a
 * [dev.kuml.sysml2.StateDefinition].
 *
 * Carried for symmetry with [ActorUsage] / [UseCaseUsage] /
 * [RequirementUsage]. The V2.0.9 MVP renders
 * [dev.kuml.sysml2.StateDefinition]s directly (via the diagram-level
 * [dev.kuml.sysml2.StmDiagram.elementIds]) and does not consume state-usages
 * from the bridge; the SVG renderer falls back to the generic usage-box
 * dispatch.
 *
 * Lives in the metamodel for completeness so future polish waves (nested
 * state-usages, state specialisation, composite state membership) have a
 * clean attachment point.
 */
@Serializable
data class StateUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `action act1 : Action1` — V2.0.10 action-usage, typed by an
 * [dev.kuml.sysml2.ActionDefinition].
 *
 * Carried for symmetry with [ActorUsage] / [UseCaseUsage] /
 * [RequirementUsage] / [StateUsage]. The V2.0.10 MVP renders
 * [dev.kuml.sysml2.ActionDefinition]s directly (via the diagram-level
 * [dev.kuml.sysml2.ActDiagram.elementIds]) and does not consume action-usages
 * from the bridge; the SVG renderer falls back to the generic usage-box
 * dispatch.
 *
 * Lives in the metamodel for completeness so future polish waves (nested
 * action-usages, sub-activity refinement, pin-typed action ports) have a
 * clean attachment point.
 */
@Serializable
data class ActionUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `flow A → B` — V2.0.10 **control flow** edge between two
 * [dev.kuml.sysml2.ActionDefinition]s (or activity-node pseudo-nodes).
 *
 * Carries the SysML 2 token-passing semantics: a token leaves [sourceNodeId]
 * and arrives at [targetNodeId]. Optional [guard] expression (`[guard]`)
 * gates the flow at runtime; raw string in V2.0.10 MVP, identical reasoning
 * to the V2.0.9 transition guard.
 *
 * The default [definitionId] is the synthetic `"sysml2.controlFlow"` literal
 * so the [Sysml2Usage]-contract is satisfied without forcing callers to
 * declare a `ControlFlowDefinition` — control flows have no SysML 2
 * *definition* counterpart; they are pure usages between two activity-node
 * definitions.
 *
 * Edge id convention (set by the DSL):
 * `controlFlow:<sourceNodeId>::<targetNodeId>` — deterministic, readable,
 * collision-free for unique node-pairs. Callers can override the id when
 * two distinct control flows connect the same pair (e.g. when a Decision
 * fans out via two guarded edges to the same Merge).
 */
@Serializable
data class ControlFlowUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String = "sysml2.controlFlow",
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    /** Id of the source [dev.kuml.sysml2.ActionDefinition] (any activity-node kind). */
    val sourceNodeId: String,
    /** Id of the target [dev.kuml.sysml2.ActionDefinition] (any activity-node kind). */
    val targetNodeId: String,
    /** Optional guard expression (`"valid"`, `"!error"`). Raw string in V2.0.10 MVP. */
    val guard: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `flow A → B of Order` — V2.0.10 **object flow** edge between two
 * [dev.kuml.sysml2.ActionDefinition]s.
 *
 * Same shape as [ControlFlowUsage] plus an [objectType] slot that records
 * the type of the object the token carries (`"Order"`, `"Document"`). Raw
 * string in V2.0.10 MVP; a typed reference to a [PartDefinition] /
 * [AttributeDefinition] is V2.x polish — keeping the slot a string unblocks
 * rendering and Behaviour-Runtime hookup without committing to a typed
 * object-flow semantics that is still under discussion.
 *
 * The default [definitionId] is the synthetic `"sysml2.objectFlow"` literal
 * so the [Sysml2Usage]-contract is satisfied without forcing callers to
 * declare an `ObjectFlowDefinition`.
 *
 * Edge id convention (set by the DSL):
 * `objectFlow:<sourceNodeId>::<targetNodeId>`.
 */
@Serializable
data class ObjectFlowUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String = "sysml2.objectFlow",
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    /** Id of the source [dev.kuml.sysml2.ActionDefinition] (any activity-node kind). */
    val sourceNodeId: String,
    /** Id of the target [dev.kuml.sysml2.ActionDefinition] (any activity-node kind). */
    val targetNodeId: String,
    /**
     * Optional type of the object the token carries (`"Order"`, `"Document"`).
     * Raw string in V2.0.10 MVP; typed reference to a [PartDefinition] /
     * [AttributeDefinition] is V2.x polish.
     */
    val objectType: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `lifeline browser : Browser` — V2.0.11 lifeline-usage, typed by a
 * [dev.kuml.sysml2.LifelineDefinition].
 *
 * Carried for symmetry with [ActorUsage] / [UseCaseUsage] / [RequirementUsage]
 * / [StateUsage] / [ActionUsage]. The V2.0.11 MVP renders
 * [dev.kuml.sysml2.LifelineDefinition]s directly (via the diagram-level
 * [dev.kuml.sysml2.SeqDiagram.elementIds]) and does not consume
 * lifeline-usages from the bridge; the SVG renderer falls back to the generic
 * usage-box dispatch.
 *
 * Lives in the metamodel for completeness so future polish waves (nested
 * sub-interactions, lifeline specialisation, multi-actor scenarios) have a
 * clean attachment point.
 */
@Serializable
data class LifelineUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String,
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `message login(user, pwd) : Browser → AuthService [seqNo=2]` — V2.0.11
 * message-usage between two [dev.kuml.sysml2.LifelineDefinition]s.
 *
 * Carries the four SysML 2 concrete-syntax slots of a sequence-diagram
 * message:
 *  - [sourceLifelineId] / [targetLifelineId] — the two endpoints. Self-calls
 *    (`sourceLifelineId == targetLifelineId`) render as a small U-shape
 *    arrow on the source lifeline (V2.0.11 MVP — richer execution-specification
 *    visuals are V2.x).
 *  - [seqNo] — primary ordering key. Determines the vertical Y position of
 *    the message arrow in the SEQ diagram. The renderer sorts by [seqNo]
 *    ascending; messages with the same [seqNo] are stably ordered by id.
 *  - [messageLabel] — human-readable label rendered above the arrow
 *    (e.g. `"login(user, pwd)"`, `"sessionToken"`). Raw string in V2.0.11
 *    MVP — typed signature parsing is V2.x.
 *  - [kind] — Sync / Async / Reply discriminator (see [MessageKind]).
 *
 * **Architecture note — Messages are NOT LayoutGraph edges.** Unlike STM
 * transitions or ACT flows, SEQ messages do not produce [dev.kuml.layout.LayoutEdge]
 * entries in the layout graph. The reason is that ELK's hierarchical layout
 * is unfit for sequence diagrams: messages are not "edges to route" — they
 * are horizontal arrows at sequence-indexed Y positions between fixed-X
 * lifeline lanes. The Bridge therefore emits only the lifelines as nodes
 * (ELK arranges them as a horizontal row), and the SVG renderer draws
 * messages **directly** after the standard node loop, computing Y from
 * [seqNo] and X from the source / target lifeline centres. See
 * [dev.kuml.layout.bridge.Sysml2LayoutBridge.toLayoutGraph] (SEQ overload)
 * and [dev.kuml.io.svg.sysml2.renderSysml2SeqMessage] for the full design.
 *
 * The default [definitionId] is the synthetic `"sysml2.message"` literal so
 * the [Sysml2Usage]-contract is satisfied without forcing callers to declare
 * a `MessageDefinition` — messages have no SysML 2 *definition* counterpart;
 * they are pure usages between two lifeline definitions.
 *
 * Edge id convention (set by the DSL):
 * `message:<sourceLifelineId>-<targetLifelineId>-<seqNo>` — deterministic,
 * readable, collision-free for unique (source, target, seqNo) triples. The
 * dash separator (vs `::`) keeps the id readable when the same lifeline pair
 * exchanges many messages.
 */
@Serializable
data class MessageUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String = "sysml2.message",
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    /** Source lifeline id (a [dev.kuml.sysml2.LifelineDefinition] id). */
    val sourceLifelineId: String,
    /** Target lifeline id (a [dev.kuml.sysml2.LifelineDefinition] id). */
    val targetLifelineId: String,
    /** Sequence index — primary ordering key for vertical position. */
    val seqNo: Int,
    /** Human-readable label rendered above the arrow (e.g. `"login(user, pwd)"`). */
    val messageLabel: String,
    /** Sync / Async / Reply. */
    val kind: MessageKind = MessageKind.Sync,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage

/**
 * `transition Off → Red trigger 'powerOn' guard '…' effect '…'` — V2.0.9
 * transition usage between two [dev.kuml.sysml2.StateDefinition]s (or
 * pseudo-states).
 *
 * Carries the four SysML 2 concrete-syntax slots of a transition:
 *  - [sourceStateId] / [targetStateId] — the two endpoints. The bridge
 *    silently drops transitions whose endpoints aren't both in the diagram's
 *    visible node set (validator's job to flag dangling refs).
 *  - [trigger] — the optional event that fires the transition
 *    (`"timer60s"`, `"buttonPressed"`, …). Raw string in V2.0.9 MVP.
 *  - [guard] — the optional OCL-subset boolean guard
 *    (`"speed > 0"`, `"!charging"`). Raw string in V2.0.9 MVP.
 *  - [effect] — the optional action emitted when the transition fires
 *    (`"switchLights('green')"`). Raw string in V2.0.9 MVP.
 *
 * The default [definitionId] is the synthetic `"sysml2.transition"` literal
 * so the [Sysml2Usage]-contract is satisfied without forcing callers to
 * declare a `TransitionDefinition` — transitions have no SysML 2 *definition*
 * counterpart; they are pure usages between two state definitions.
 *
 * Edge id convention (set by the DSL):
 * `transition:<sourceStateId>::<targetStateId>` — deterministic, readable,
 * collision-free for unique state-pairs. Callers can override the id when
 * two distinct transitions connect the same pair of states.
 */
@Serializable
data class TransitionUsage(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val definitionId: String = "sysml2.transition",
    override val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    /** Id of the source [dev.kuml.sysml2.StateDefinition] (or pseudo-state). */
    val sourceStateId: String,
    /** Id of the target [dev.kuml.sysml2.StateDefinition] (or pseudo-state). */
    val targetStateId: String,
    /** Optional event that fires the transition (e.g. `"timer60s"`). */
    val trigger: String? = null,
    /** Optional OCL-subset boolean guard (e.g. `"speed > 0"`). */
    val guard: String? = null,
    /** Optional action emitted when the transition fires. */
    val effect: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Usage
