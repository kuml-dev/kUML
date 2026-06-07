package dev.kuml.sysml2

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * Top-level container for a SysML 2 model.
 *
 * Holds all definitions ([definitions]) and standalone usages ([usages]) that
 * the model declares, plus any diagrams ([diagrams]) the author has tagged
 * for rendering.
 *
 * For V2.0.3, "diagram" started with a Block Definition Diagram ([BdDiagram])
 * that selects which definitions to show; V2.0.6 adds [IbdDiagram] (Internal
 * Block Diagram) for the wiring view of a single [PartDefinition]. The
 * remaining SysML 2 diagram kinds (REQ, PAR, ACT, SEQ, STM, UC) land in
 * follow-up waves. The model itself is diagram-agnostic — a single model
 * can drive many diagrams.
 */
@Serializable
data class Sysml2Model(
    /** Human-readable model name, e.g. `"HybridVehicle"`. */
    val name: String,
    /** All definitions in the model — parts, attributes, ports, connections. */
    val definitions: List<Sysml2Definition> = emptyList(),
    /** Top-level usages (rare; usages are usually nested in definitions). */
    val usages: List<Sysml2Usage> = emptyList(),
    /** Diagrams tagged for rendering. */
    val diagrams: List<Sysml2Diagram> = emptyList(),
    val metadata: Map<String, KumlMetaValue> = emptyMap(),
) {
    /** Indexed lookup by element id. Computed lazily; rebuilt per instance. */
    fun elementById(id: String): Sysml2Element? {
        // Search definitions, their owned features (re-cast through Usages), and
        // top-level usages. Returns the first match — ids are expected unique.
        definitions.firstOrNull { it.id == id }?.let { return it }
        usages.firstOrNull { it.id == id }?.let { return it }
        return null
    }
}

/**
 * Sealed root for every SysML 2 diagram kind. V2.0.3 implements [BdDiagram],
 * V2.0.6 adds [IbdDiagram], V2.0.7 adds [UcDiagram], V2.0.8 adds [ReqDiagram],
 * V2.0.9 adds [StmDiagram], V2.0.10 adds [ActDiagram], V2.0.11 adds
 * [SeqDiagram] — the remaining diagram kind (PAR) follows in a later wave.
 */
@Serializable
sealed interface Sysml2Diagram {
    /** Human-readable diagram title. */
    val name: String

    /** Ids of the SysML 2 elements the diagram is allowed to display. */
    val elementIds: List<String>
}

/**
 * **Block Definition Diagram** — the SysML 2 analogue of the UML class
 * diagram and the natural starting point for any structural modelling.
 * Shows [PartDefinition]s, their attributes, their ports, and their
 * specialisations.
 *
 * The diagram is a *projection*: the model owns the truth, the BDD just
 * names which definitions to render and how. Other diagram kinds will
 * share this projection pattern.
 */
@Serializable
data class BdDiagram(
    override val name: String,
    override val elementIds: List<String> = emptyList(),
) : Sysml2Diagram

/**
 * **Internal Block Diagram** (IBD) — the structural sibling of [BdDiagram]
 * that zooms *inside* a single [PartDefinition] (`ownerId`) and shows its
 * internal wiring:
 *
 *  - every owned `PartUsage` becomes a nested box,
 *  - every owned `PortUsage` surfaces as a compartment entry (V2.0.6 MVP —
 *    boundary port markers on the IBD frame are deferred to V2.x),
 *  - every owned `ConnectionUsage` becomes an edge between the two part-usage
 *    boxes its endpoints fall under.
 *
 * Whereas a BDD shows *types* (Vehicle, Engine, Battery), an IBD shows *the
 * wiring of one type* (inside Vehicle: `engine : Engine`, `battery : Battery`,
 * and a `PowerLine` connection between them).
 *
 * V2.0.6 MVP scope (per the wave plan):
 *  - One IBD per [PartDefinition]; nested IBDs (drilling further into a
 *    contained part) land in V2.x.
 *  - Boundary ports on the IBD frame: V2.x — needs port-position layout hints.
 *  - Typed connection styles per `ConnectionDefinition`: V2.x.
 *  - PNG export: V2.x (same as BDD).
 */
@Serializable
data class IbdDiagram(
    override val name: String,
    /** Id of the [PartDefinition] whose internals are projected. */
    val ownerId: String,
    /**
     * Optional filter — if empty, *all* of the owner's part-usages render.
     * If non-empty, only the listed usage ids survive the bridge's selection.
     */
    override val elementIds: List<String> = emptyList(),
) : Sysml2Diagram

/**
 * **Use Case Diagram** (UC) — the SysML 2 / UML capability view.
 *
 * Shows three primary kinds of elements (V2.0.7 MVP):
 *  - [ActorDefinition]s as stick-figure nodes — the external entities that
 *    interact with the system,
 *  - [UseCaseDefinition]s as ellipse nodes — the capabilities the system
 *    offers,
 *  - three kinds of edges between them:
 *    - [UcAssociation] — an actor participates in a use case
 *      (the canonical "stick figure connected to ellipse" line),
 *    - [UcInclude] — `«include»` from one use case to another (always
 *      executed as part of the source),
 *    - [UcExtend] — `«extend»` from one use case to another (optional
 *      extension of the target's behaviour).
 *
 * The diagram captures the edges directly (instead of deriving them from
 * usages) because UC-edge semantics are diagram-specific: an actor-to-
 * use-case association is not a structural feature of either side, and an
 * `«include»`/`«extend»` is a diagram-level relationship between two
 * use-case definitions. A richer [IncludeUsage]/[ExtendUsage] metamodel
 * lives in [Usages.kt] for future polish waves, but V2.0.7 reads the
 * edges off the diagram itself.
 *
 * V2.0.7 MVP scope (per the wave plan):
 *  - Flat graph: no system-boundary frame around use cases.
 *  - All three edge kinds render with the same plain solid line in SVG +
 *    TikZ. The dashed-line styling and the `«include»`/`«extend»` stereotype
 *    labels are deferred to V2.x.
 *  - No actor specialisation arrows, no use-case generalisation.
 *  - PNG export: V2.x (same as BDD + IBD).
 */
@Serializable
data class UcDiagram(
    override val name: String,
    /**
     * Ids of the [ActorDefinition]s + [UseCaseDefinition]s the diagram is
     * allowed to display. Order is preserved so layout / serialisation /
     * diff stay deterministic.
     */
    override val elementIds: List<String> = emptyList(),
    /** Actor ↔ UseCase associations (the classical UML "participates in" line). */
    val associations: List<UcAssociation> = emptyList(),
    /** `«include»` relationships between two use-case definitions. */
    val includes: List<UcInclude> = emptyList(),
    /** `«extend»` relationships between two use-case definitions. */
    val extends: List<UcExtend> = emptyList(),
) : Sysml2Diagram

/**
 * Actor ↔ use-case association edge in a [UcDiagram].
 *
 * Endpoints reference SysML 2 element ids by string so the diagram can be
 * authored before the referenced definitions exist (forward refs are fine).
 * The bridge silently drops associations whose endpoints aren't both in
 * the diagram's visible node set — validator's job to flag dangling refs.
 *
 * Edge id convention (set by the DSL): `assoc:<actorId>::<useCaseId>` —
 * deterministic, readable, collision-free for unique actor/use-case pairs.
 */
@Serializable
data class UcAssociation(
    val id: String,
    /** Id of the [ActorDefinition] that participates in the use case. */
    val actorId: String,
    /** Id of the [UseCaseDefinition] the actor participates in. */
    val useCaseId: String,
)

/**
 * `«include»` relationship between two use-case definitions in a
 * [UcDiagram].
 *
 * Semantics: the source use case *always* executes the target as part of
 * its own behaviour (`BorrowBook` includes `Authenticate` → every borrow
 * authenticates first).
 *
 * Edge id convention (set by the DSL): `include:<source>::<target>`.
 */
@Serializable
data class UcInclude(
    val id: String,
    /** Id of the *including* (source) [UseCaseDefinition]. */
    val sourceUseCaseId: String,
    /** Id of the *included* (target) [UseCaseDefinition]. */
    val targetUseCaseId: String,
)

/**
 * `«extend»` relationship between two use-case definitions in a
 * [UcDiagram].
 *
 * Semantics: the source use case *optionally* extends the target's
 * behaviour (`PayLateFee` extends `ReturnBook` only when there is a fee).
 *
 * Edge id convention (set by the DSL): `extend:<source>::<target>`.
 */
@Serializable
data class UcExtend(
    val id: String,
    /** Id of the *extending* (source) [UseCaseDefinition]. */
    val sourceUseCaseId: String,
    /** Id of the *extended* (target) [UseCaseDefinition]. */
    val targetUseCaseId: String,
)

/**
 * **Activity Diagram** (ACT) — V2.0.10 token-flow / workflow view.
 *
 * Shows the token flow through a workflow:
 *  - [ActionDefinition]s appear as one of seven shapes (regular Action,
 *    Initial / Final / FlowFinal pseudo-nodes, Decision / Merge diamonds,
 *    Fork / Join bars) — dispatch on [dev.kuml.sysml2.ActivityNodeKind].
 *  - [dev.kuml.sysml2.ControlFlowUsage]s and [dev.kuml.sysml2.ObjectFlowUsage]s
 *    are auto-included from `Sysml2Model.usages` whenever both endpoints are
 *    in [elementIds] — the same **Pattern A** the V2.0.6 IBD / V2.0.9 STM
 *    use. Flows live on the *model* (not on the diagram) because they are
 *    part of the activity's runtime identity, which the future
 *    Behaviour-Runtime wave needs.
 *
 * This follows the V2.0.9 STM convention (transitions on the model) rather
 * than the V2.0.7 UC / V2.0.8 REQ convention (edges on the diagram). The
 * decision rule is the same: edges that ARE the model round-trip through
 * the runtime; edges that are diagram-only assertions live on the diagram.
 * Token flow is firmly in the first camp.
 *
 * V2.0.10 MVP scope (per the wave plan):
 *  - Flat activity: no Activity-Partition (swimlanes), no interruptible
 *    regions, no pin notation on actions.
 *  - All seven node kinds, both edge kinds (control flow + object flow).
 *  - Edges render as plain solid lines; guard / objectType labels are V2.x
 *    polish (same `EdgeRendererDispatcher` lookup-miss limitation as UC /
 *    REQ / STM — the synthetic `KumlDiagram` hull has no `UmlRelationship`
 *    for `ControlFlowUsage` / `ObjectFlowUsage`).
 *  - Stream-flow / multicast semantics on Object Flow are V2.x polish.
 *  - PNG export: V2.x (same as BDD / IBD / UC / REQ / STM).
 *  - Token-Flow runtime execution: separate Behaviour-Runtime wave per the
 *    V2.0 roadmap; V2.0.10 only captures the structural projection.
 */
@Serializable
data class ActDiagram(
    override val name: String,
    /**
     * Ids of activity-node [ActionDefinition]s to display. Control flows and
     * object flows from [Sysml2Model.usages] whose
     * [dev.kuml.sysml2.ControlFlowUsage.sourceNodeId] AND `targetNodeId`
     * (resp. [dev.kuml.sysml2.ObjectFlowUsage.sourceNodeId] AND
     * `targetNodeId`) are both in this set are auto-included by the bridge.
     * Order is preserved so layout / serialisation / diff stay deterministic.
     */
    override val elementIds: List<String> = emptyList(),
) : Sysml2Diagram

/**
 * **State Transition Diagram** (STM) — V2.0.9 dynamic-behaviour view that
 * bridges to the upcoming Behaviour-Runtime line.
 *
 * Shows the lifecycle of a system part as a finite state machine:
 *  - [StateDefinition]s appear as rounded boxes (regular states) or as
 *    pseudo-state shapes (filled circle for initial, donut for final).
 *  - [dev.kuml.sysml2.TransitionUsage]s are auto-included from
 *    `Sysml2Model.usages` whenever both endpoints are in [elementIds] —
 *    transitions live on the *model* (not on the diagram) because they
 *    are part of the state-machine's runtime identity, which the future
 *    Behaviour-Runtime wave needs.
 *
 * This is the **inverse** convention from the V2.0.7 [UcDiagram] / V2.0.8
 * [ReqDiagram], whose edges are captured at the diagram level. Rationale:
 * UC associations and REQ traceability edges are *diagram-only* assertions,
 * whereas STM transitions ARE the model — they must round-trip end-to-end
 * through any runtime / animation / verification pipeline that the
 * V2.x-Behaviour-Runtime layer introduces.
 *
 * V2.0.9 MVP scope (per the wave plan):
 *  - Flat state machines: no composite / orthogonal / history states.
 *  - Initial + final pseudo-states only; fork / join are V2.x.
 *  - Transitions render as plain solid edges; the `trigger [guard] / effect`
 *    label is V2.x polish (the synthetic [KumlDiagram] hull has no
 *    `UmlRelationship` for TransitionUsages, so the edge dispatcher's
 *    element lookup misses and the edge falls back to the plain path —
 *    same limitation as UC / REQ).
 *  - PNG export: V2.x (same as BDD / IBD / UC / REQ).
 *  - Live Behaviour-Runtime hookup: separate "Executable Behaviour Runtime"
 *    wave per the V2.0 roadmap; V2.0.9 only captures the structural
 *    projection.
 */
@Serializable
data class StmDiagram(
    override val name: String,
    /**
     * Ids of the [StateDefinition]s to display. Transitions from
     * [Sysml2Model.usages] whose [dev.kuml.sysml2.TransitionUsage.sourceStateId]
     * AND [dev.kuml.sysml2.TransitionUsage.targetStateId] are both in this set
     * are auto-included by the bridge. Order is preserved so layout /
     * serialisation / diff stay deterministic.
     */
    override val elementIds: List<String> = emptyList(),
) : Sysml2Diagram

/**
 * **Requirement Diagram** (REQ) — V2.0.8 traceability view over the
 * structural ([BdDiagram] / [IbdDiagram]) and capability ([UcDiagram]) layers.
 *
 * Shows two primary kinds of elements (V2.0.8 MVP):
 *  - [RequirementDefinition]s as three-compartment boxes (`«requirement»` +
 *    optional `R-NNN ::`-prefixed name + word-wrapped requirement text).
 *  - [PartDefinition] / [UseCaseDefinition] / [ActorDefinition] nodes as the
 *    *subjects* / *satisfiers* / *verifiers* of those requirements (rendered
 *    as their usual BDD-box / use-case-ellipse / actor-stickfigur form).
 *
 * Plus four edge kinds between them:
 *  - [ReqSatisfy] — a part/use-case **satisfies** a requirement (design ⟶ req).
 *  - [ReqVerify] — a test/use-case **verifies** a requirement (test ⟶ req).
 *  - [ReqDerive] — a requirement is **derived** from another requirement
 *    (child ⟵ parent in the trace hierarchy).
 *  - [ReqContains] — a parent requirement **contains** a sub-requirement
 *    (decomposition).
 *
 * The diagram captures these edges directly (instead of deriving them from
 * usages) for the same reason the [UcDiagram] does: an edge between a
 * `Vehicle` and `R-001 TopSpeedRequirement` is not a structural feature of
 * either side — it is a diagram-level traceability assertion. A richer
 * `SatisfyUsage`/`VerifyUsage` metamodel lives in [Usages.kt] only for
 * future polish; V2.0.8 reads the edges off the diagram itself.
 *
 * V2.0.8 MVP scope (per the wave plan):
 *  - Flat graph: no requirement-group frame; subject-of relationships are
 *    captured via [RequirementDefinition.subject] but the bridge does not
 *    yet infer subject-edges automatically (V2.x).
 *  - All four edge kinds render with the same plain solid line in SVG +
 *    TikZ. The `«satisfy»` / `«verify»` / `«deriveReqt»` stereotype labels
 *    and dashed-line styling are deferred to V2.x.
 *  - PNG export: V2.x (same as BDD / IBD / UC).
 */
@Serializable
data class ReqDiagram(
    override val name: String,
    /**
     * Ids of the [RequirementDefinition]s plus any other node-bearing
     * definitions (parts, use-cases, actors) the diagram is allowed to
     * display. Order is preserved so layout / serialisation / diff stay
     * deterministic.
     */
    override val elementIds: List<String> = emptyList(),
    /** Part/UseCase/Component that satisfies a Requirement. */
    val satisfies: List<ReqSatisfy> = emptyList(),
    /** TestCase/UseCase that verifies a Requirement. */
    val verifies: List<ReqVerify> = emptyList(),
    /** Requirement derived from a parent Requirement. */
    val derives: List<ReqDerive> = emptyList(),
    /** Parent Requirement containing a sub-Requirement. */
    val contains: List<ReqContains> = emptyList(),
) : Sysml2Diagram

/**
 * **Satisfy** edge in a [ReqDiagram] — a design element (Part / UseCase /
 * Component) satisfies a [RequirementDefinition].
 *
 * Semantics: the [sourceId] (the implementation / design / behaviour) fulfils
 * the requirement [requirementId]. Endpoints reference SysML 2 element ids
 * by string so the diagram can be authored before the referenced definitions
 * exist (forward refs are fine). The bridge silently drops satisfies whose
 * endpoints aren't both in the diagram's visible node set — validator's job
 * to flag dangling refs.
 *
 * Edge id convention (set by the DSL): `satisfy:<sourceId>::<requirementId>`.
 */
@Serializable
data class ReqSatisfy(
    val id: String,
    /** Id of the satisfying element (Part / UseCase / Component). */
    val sourceId: String,
    /** Id of the [RequirementDefinition] being satisfied. */
    val requirementId: String,
)

/**
 * **Verify** edge in a [ReqDiagram] — a verification element (TestCase /
 * UseCase) verifies a [RequirementDefinition].
 *
 * Semantics: the [sourceId] (typically a test case or verification use case)
 * checks that the requirement [requirementId] holds. Same forward-ref +
 * silent-drop conventions as [ReqSatisfy].
 *
 * Edge id convention (set by the DSL): `verify:<sourceId>::<requirementId>`.
 */
@Serializable
data class ReqVerify(
    val id: String,
    /** Id of the verifying element (TestCase / UseCase). */
    val sourceId: String,
    /** Id of the [RequirementDefinition] being verified. */
    val requirementId: String,
)

/**
 * **Derive** edge in a [ReqDiagram] — a child [RequirementDefinition] is
 * derived from a parent [RequirementDefinition].
 *
 * Semantics: the [sourceRequirementId] requirement was derived from
 * [targetRequirementId] — i.e. it is a refinement / consequence / corollary
 * of the parent. Captures the trace hierarchy of requirements engineering.
 *
 * Edge id convention (set by the DSL):
 * `derive:<sourceRequirementId>::<targetRequirementId>`.
 */
@Serializable
data class ReqDerive(
    val id: String,
    /** Id of the *derived* (child) [RequirementDefinition]. */
    val sourceRequirementId: String,
    /** Id of the *parent* [RequirementDefinition] the source derives from. */
    val targetRequirementId: String,
)

/**
 * **Sequence Diagram** (SEQ) — V2.0.11 interaction view: the time-ordered
 * exchange of messages between participants. Last of the seven SysML 2
 * behavioural diagrams and **structurally different** from the other six:
 * instead of a free-form graph, SEQ has a time-ordered, axis-constrained
 * layout — lifelines on a horizontal axis at the top, time flowing
 * vertically downward, messages as horizontal arrows ordered by sequence
 * number.
 *
 * Shows:
 *  - [dev.kuml.sysml2.LifelineDefinition]s as vertical lanes — a `«lifeline»`
 *    box at the top with a dashed time-axis extending below.
 *  - [dev.kuml.sysml2.MessageUsage]s as horizontal arrows between lifelines,
 *    ordered by [dev.kuml.sysml2.MessageUsage.seqNo]. Auto-included from
 *    `Sysml2Model.usages` whenever both endpoints are in [elementIds] — the
 *    same **Pattern A** the V2.0.6 IBD / V2.0.9 STM / V2.0.10 ACT uses.
 *    Messages live on the *model* (not on the diagram) because they ARE the
 *    interaction's runtime identity, which the future Behaviour-Runtime
 *    wave needs for replay.
 *
 * **Architecture divergence — Messages are Renderer-direct, not LayoutGraph
 * edges.** Unlike STM transitions or ACT flows, SEQ messages do not produce
 * [dev.kuml.layout.LayoutEdge] entries. The reason: ELK's hierarchical
 * layout is unfit for sequence diagrams — messages are not "edges to route"
 * but horizontal arrows at sequence-indexed Y positions between fixed-X
 * lifeline lanes. The Bridge emits only the lifelines as nodes (so ELK
 * arranges them as a horizontal row, which IS the SEQ convention), and the
 * SVG renderer draws messages directly after the standard node loop. This
 * is the **second deliberate pattern divergence** in the SysML 2 line; the
 * first was V2.0.9 STM choosing Pattern A (transitions on the model) over
 * UC / REQ's Pattern B (edges on the diagram). See
 * [dev.kuml.layout.bridge.Sysml2LayoutBridge.toLayoutGraph] (SEQ overload)
 * KDoc for the full reasoning.
 *
 * V2.0.11 MVP scope (per the wave plan):
 *  - Flat interaction: no Combined Fragments (`alt` / `opt` / `loop` /
 *    `par` / `strict`) — separate V2.x wave because layout-engine work.
 *  - No Execution Specifications (the activation rectangles on a lifeline).
 *  - No `Create` / `Destroy` message kinds (lifecycle messages).
 *  - No Found / Lost messages (arrows to/from outside the diagram).
 *  - No co-region / general-ordering constraints.
 *  - No time / duration constraint annotations.
 *  - PNG export: V2.x.
 *  - Behaviour-Runtime replay: separate V2.x wave.
 */
@Serializable
data class SeqDiagram(
    override val name: String,
    /**
     * Ids of the [dev.kuml.sysml2.LifelineDefinition]s to display, in
     * left-to-right order. Messages from [Sysml2Model.usages] whose
     * [dev.kuml.sysml2.MessageUsage.sourceLifelineId] AND
     * [dev.kuml.sysml2.MessageUsage.targetLifelineId] are both in this set
     * are auto-included by the bridge. Order is preserved so layout /
     * serialisation / diff stay deterministic.
     */
    override val elementIds: List<String> = emptyList(),
) : Sysml2Diagram

/**
 * **Contains** edge in a [ReqDiagram] — a parent [RequirementDefinition]
 * contains a sub-[RequirementDefinition] (decomposition).
 *
 * Semantics: the [parentRequirementId] requirement decomposes into
 * sub-requirements; [childRequirementId] is one of them. Distinct from
 * [ReqDerive]: containment is "is part of", derivation is "follows from".
 *
 * Edge id convention (set by the DSL):
 * `contains:<parentRequirementId>::<childRequirementId>`.
 */
@Serializable
data class ReqContains(
    val id: String,
    /** Id of the *parent* (containing) [RequirementDefinition]. */
    val parentRequirementId: String,
    /** Id of the *child* (contained) [RequirementDefinition]. */
    val childRequirementId: String,
)
