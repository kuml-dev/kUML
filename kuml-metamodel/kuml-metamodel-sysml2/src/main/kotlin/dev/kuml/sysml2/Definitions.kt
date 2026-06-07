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

/**
 * `ActionDefinition` — V2.0.10 entry for the SysML 2 Activity Diagram.
 *
 * Represents one of the seven activity-node shapes that an ACT diagram
 * supports:
 *  - **Regular action** (`kind = Action`) — rounded box with the action body
 *    rendered as a second text line beneath the name.
 *  - **Initial node** (`kind = Initial`) — small filled circle marking the
 *    start of the activity.
 *  - **Final node** (`kind = Final`) — donut shape marking the end of the
 *    entire activity.
 *  - **Flow Final node** (`kind = FlowFinal`) — circle with an X inside,
 *    marking the end of one token (other concurrent tokens continue).
 *  - **Decision node** (`kind = Decision`) — diamond, branches on guards.
 *  - **Merge node** (`kind = Merge`) — diamond, merges alternative branches.
 *  - **Fork node** (`kind = Fork`) — synchronisation bar, splits into
 *    parallel branches.
 *  - **Join node** (`kind = Join`) — synchronisation bar, synchronises
 *    parallel branches.
 *
 * **Design rationale — one class + an enum, not seven sealed sub-types**:
 * the V2.0.9 STM wave handled two pseudo-state flavours with Boolean flags
 * on a single `StateDefinition`. ACT has seven flavours; a sealed-class
 * explosion (seven sub-types of [Sysml2Definition], each empty save for a
 * marker) would bloat the metamodel surface for no semantic gain. A single
 * [ActionDefinition] discriminated by [ActivityNodeKind] keeps the
 * metamodel compact, the SVG renderer's dispatch shape uniform, and the
 * layout-bridge's size-provider trivially expressible as a `when (kind)`.
 *
 * Carries one V2.0.10-specific data slot:
 *  - [action] — optional raw action body (`"log('processing')"`,
 *    `"computeTotal(items)"`). Only meaningful when
 *    `kind = ActivityNodeKind.Action`; ignored by the renderer for every
 *    other kind. Raw string in V2.0.10 MVP — the typed action AST (with a
 *    proper expression tree, side-effect typing, and behaviour-runtime
 *    hooks) is a separate V2.x wave, identical reasoning to the V2.0.9
 *    `entry/exit/do`-action strings on [StateDefinition].
 *
 * V2.0.10 MVP scope (per the wave plan):
 *  - Flat activity: no Activity-Partition (swimlanes), no interruptible
 *    regions, no pin notation on actions.
 *  - Token-Flow runtime execution is a separate Behaviour-Runtime wave;
 *    V2.0.10 only captures the structural projection.
 *  - Stream-flow / multicast semantics on Object Flow are V2.x polish.
 */
@Serializable
data class ActionDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    /** Which of the seven activity-node shapes this is. Defaults to a regular action. */
    val kind: ActivityNodeKind = ActivityNodeKind.Action,
    /**
     * Optional raw action body (`"log('processing')"`). Only meaningful for
     * `kind = ActivityNodeKind.Action`; ignored by the renderer for every
     * other kind.
     */
    val action: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `StateDefinition` — V2.0.9 entry for the SysML 2 State Transition Diagram.
 *
 * Represents a *state* of the system under modelling: a discrete situation
 * in which the system rests until a [dev.kuml.sysml2.TransitionUsage] fires.
 * Maps to SysML 2's `state def`/`state` keywords. Three flavours are encoded
 * by the two boolean pseudo-state markers ([isInitial], [isFinal]) plus the
 * "regular state" default (both `false`):
 *
 *  - **Initial pseudo-state** (`isInitial = true`) — rendered as a small
 *    filled circle. There is exactly one per state machine in the MVP (the
 *    enforcement is a validator concern; the metamodel allows multiple).
 *  - **Final pseudo-state** (`isFinal = true`) — rendered as a "donut" (an
 *    outer circle with an inner filled circle).
 *  - **Regular state** (both flags `false`) — rendered as a rounded-rect with
 *    the name centred at top and optional `entry / exit / do` action lines
 *    below a divider.
 *
 * Carries three V2.0.9-specific action slots ([entryAction], [exitAction],
 * [doAction]) holding the SysML 2 concrete-syntax action statement *as a
 * raw string*. The typed action AST (with a proper expression tree, side
 * effect typing, and behaviour-runtime hooks) is a separate V2.x wave —
 * keeping action strings in the MVP unblocks rendering without committing
 * to an action-language semantics that is still under discussion.
 *
 * V2.0.9 MVP scope (per the wave plan):
 *  - Flat state machine: no composite / orthogonal / history states. Each
 *    state is a leaf.
 *  - No fork / join pseudo-states. Initial + final are the only two
 *    pseudo-state kinds.
 *  - [isInitial] and [isFinal] are mutually exclusive *by spec* but the
 *    metamodel does not enforce it — that check belongs in the validator
 *    so callers can construct partially-invalid states for testing /
 *    diagnostics without a panic.
 *  - Behaviour runtime (live execution of the state machine) is a separate
 *    "Executable Behaviour Runtime" wave per the V2.0 plan; V2.0.9 only
 *    captures the structural projection.
 */
@Serializable
data class StateDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    /**
     * Pseudo-state marker: `true` = initial pseudo-state (filled circle).
     * Mutually exclusive with [isFinal] *by spec*; the metamodel does not
     * enforce the rule.
     */
    val isInitial: Boolean = false,
    /**
     * Pseudo-state marker: `true` = final pseudo-state (donut shape).
     * Mutually exclusive with [isInitial] *by spec*; the metamodel does not
     * enforce the rule.
     */
    val isFinal: Boolean = false,
    /** Optional `entry / do … ` action statement (raw string in MVP). */
    val entryAction: String? = null,
    /** Optional `exit / do … ` action statement (raw string in MVP). */
    val exitAction: String? = null,
    /** Optional `do … ` activity statement (raw string in MVP). */
    val doAction: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition
