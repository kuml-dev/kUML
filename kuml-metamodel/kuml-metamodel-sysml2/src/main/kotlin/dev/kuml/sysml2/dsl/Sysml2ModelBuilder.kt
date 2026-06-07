package dev.kuml.sysml2.dsl

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.ReqContains
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.ReqVerify
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.units.UnitValue

/**
 * Top-level entry — `sysml2Model("HybridVehicle") { … }`.
 *
 * Mirrors the existing `umlModel(...)` / `c4Model(...)` shape so SysML 2
 * scripts look at-home next to existing kUML scripts. The DSL produces a
 * fully-built [Sysml2Model] — no half-states, no resolver phase, no
 * cross-module dependency on `kuml-core-dsl`.
 */
fun sysml2Model(
    name: String,
    block: Sysml2ModelBuilder.() -> Unit = {},
): Sysml2Model = Sysml2ModelBuilder(name).apply(block).build()

/**
 * Builder for [Sysml2Model]. Collects definitions, top-level usages, and
 * diagrams as the user populates the model.
 *
 * Definitions accumulate in registration order — the layout/renderer
 * traverses them in that order, so the user's source ordering controls
 * deterministic output. (Same convention the UML builder uses.)
 */
@Sysml2Dsl
class Sysml2ModelBuilder(
    private val name: String,
) {
    private val definitions = mutableListOf<Sysml2Definition>()
    private val usages = mutableListOf<Sysml2Usage>()
    private val diagrams = mutableListOf<Sysml2Diagram>()

    // ── Definitions ──────────────────────────────────────────────────────

    /**
     * `part def Vehicle { … }` — declare a [PartDefinition].
     *
     * The optional [specializesId] gives the parent definition for the
     * `Type :> Type` relationship; the lambda gets a [DefinitionBuilder]
     * so the body can declare attributes, ports, sub-parts.
     */
    fun partDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        specializesId: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): PartDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            PartDefinition(
                id = id,
                name = name,
                qualifiedName = name,
                isAbstract = isAbstract,
                features = builder.features(),
                specializations = specializesId?.let { listOf(KermlSpecialization(id, it)) }.orEmpty(),
            )
        definitions += def
        return def
    }

    /** `attribute def Mass { … }` — a value-typed definition. */
    fun attributeDef(
        name: String,
        id: String = name,
        block: DefinitionBuilder.() -> Unit = {},
    ): AttributeDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            AttributeDefinition(
                id = id,
                name = name,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /** `port def Inlet { … }` — a port-typed definition. */
    fun portDef(
        name: String,
        id: String = name,
        block: DefinitionBuilder.() -> Unit = {},
    ): PortDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            PortDefinition(
                id = id,
                name = name,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /** `connection def PowerLine { … }` — a connection-typed definition. */
    fun connectionDef(
        name: String,
        id: String = name,
        block: DefinitionBuilder.() -> Unit = {},
    ): ConnectionDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ConnectionDefinition(
                id = id,
                name = name,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /**
     * `actor def Reader { … }` — V2.0.7 actor-typed definition.
     *
     * Mirrors [partDef]. The optional body lets future polish waves attach
     * attribute usages (e.g. an actor with a `role : Role` attribute); the
     * V2.0.7 MVP uses actors as flat leaf nodes in [UcDiagram]s.
     */
    fun actorDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): ActorDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ActorDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /**
     * `use case def BorrowBook { … }` — V2.0.7 use-case-typed definition.
     *
     * Mirrors [partDef]. Use cases are nodes in a [UcDiagram] and the
     * endpoints of [UcAssociation] / [UcInclude] / [UcExtend] edges.
     */
    fun useCaseDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): UseCaseDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            UseCaseDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
            )
        definitions += def
        return def
    }

    /**
     * `requirement def TopSpeedRequirement { … }` — V2.0.8 requirement-typed
     * definition.
     *
     * Mirrors [partDef] and adds three V2.0.8-specific slots:
     *  - [reqId] — optional human-readable id like `"R-001"`, used by the
     *    SVG renderer to prefix the name as `"R-001 :: TopSpeedRequirement"`.
     *  - [text] — the requirement statement in natural language, rendered
     *    as a word-wrapped third compartment. Empty string ⇒ no text
     *    compartment.
     *  - [subject] — optional id of the element the requirement constrains
     *    (carried through the metamodel for future automatic subject-edge
     *    inference; V2.0.8 only stores it).
     *
     * Requirements are nodes in a [ReqDiagram] and the endpoints of
     * [ReqSatisfy] / [ReqVerify] / [ReqDerive] / [ReqContains] edges.
     */
    fun requirementDef(
        name: String,
        id: String = name,
        reqId: String = "",
        text: String = "",
        subject: String? = null,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): RequirementDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            RequirementDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                text = text,
                reqId = reqId,
                subject = subject,
            )
        definitions += def
        return def
    }

    /**
     * `state def Red { … }` — V2.0.9 state-typed definition.
     *
     * Mirrors [partDef] and adds three V2.0.9-specific slots ([entryAction],
     * [exitAction], [doAction]) plus the two pseudo-state markers
     * ([isInitial], [isFinal]). Regular states use the defaults of both
     * markers (`false`); the initial pseudo-state sets `isInitial = true`
     * and the final pseudo-state sets `isFinal = true`.
     *
     * States are nodes in an [StmDiagram]; transitions between them are
     * captured separately via [transition] / [transitionById] which register
     * a [TransitionUsage] on the model's `usages` list.
     */
    fun stateDef(
        name: String,
        id: String = name,
        isInitial: Boolean = false,
        isFinal: Boolean = false,
        entryAction: String? = null,
        exitAction: String? = null,
        doAction: String? = null,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): StateDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            StateDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                isInitial = isInitial,
                isFinal = isFinal,
                entryAction = entryAction,
                exitAction = exitAction,
                doAction = doAction,
            )
        definitions += def
        return def
    }

    /**
     * `transition Off → Red trigger 'powerOn'` — V2.0.9 transition-usage
     * between two [StateDefinition]s (or pseudo-states).
     *
     * Registers the resulting [TransitionUsage] in [Sysml2Model.usages] (via
     * [registerUsage] — the V2.0.6 architecture-bonus). The bridge picks
     * transitions back up from `model.usages` when projecting an
     * [StmDiagram], so the surface does not need to declare them on the
     * diagram itself — see [StmDiagram] KDoc for the rationale.
     *
     * Default [id] convention: `transition:<source>::<target>` —
     * deterministic, readable, collision-free for unique state-pairs.
     * Callers can override the id when two distinct transitions connect the
     * same pair (e.g. `transition:Red::Green:timer` vs
     * `transition:Red::Green:emergency`).
     */
    fun transition(
        name: String,
        source: StateDefinition,
        target: StateDefinition,
        trigger: String? = null,
        guard: String? = null,
        effect: String? = null,
        id: String = "transition:${source.id}::${target.id}",
    ): TransitionUsage =
        transitionById(
            name = name,
            sourceStateId = source.id,
            targetStateId = target.id,
            trigger = trigger,
            guard = guard,
            effect = effect,
            id = id,
        )

    /**
     * Id-only variant of [transition] — for forward refs / id-only setups.
     *
     * Useful when a transition needs to be wired between two states whose
     * `StateDefinition` references are not yet in scope (e.g. when reading
     * the state graph from an external source and replaying it through the
     * builder).
     */
    fun transitionById(
        name: String,
        sourceStateId: String,
        targetStateId: String,
        trigger: String? = null,
        guard: String? = null,
        effect: String? = null,
        id: String = "transition:$sourceStateId::$targetStateId",
    ): TransitionUsage {
        val usage =
            TransitionUsage(
                id = id,
                name = name,
                qualifiedName = name,
                sourceStateId = sourceStateId,
                targetStateId = targetStateId,
                trigger = trigger,
                guard = guard,
                effect = effect,
            )
        registerUsage(usage)
        return usage
    }

    /**
     * Register a typed [Sysml2Usage] so the model's `usages` list carries the
     * full typed view alongside the KerML `features`. V2.0.6 added this so the
     * IBD bridge can read `model.usages.filterIsInstance<ConnectionUsage>()`
     * directly — `KermlFeature` loses the `sourceEndId`/`targetEndId` of a
     * `ConnectionUsage`, which the IBD wiring projection actually needs.
     *
     * Called from [DefinitionBuilder] for every `part(...) / attribute(...) /
     * port(...) / connect(...)` invocation. Stays `internal` so it's part of
     * the module's contract but not the public DSL surface.
     */
    internal fun registerUsage(usage: Sysml2Usage) {
        usages += usage
    }

    // ── Diagrams ─────────────────────────────────────────────────────────

    /**
     * `bdd("Structural overview") { include(Vehicle); include(Engine) }` — a
     * Block Definition Diagram projecting a subset of the model.
     */
    fun bdd(
        name: String,
        block: BdDiagramBuilder.() -> Unit = {},
    ): BdDiagram {
        val builder = BdDiagramBuilder().apply(block)
        val diagram = BdDiagram(name = name, elementIds = builder.ids())
        diagrams += diagram
        return diagram
    }

    /**
     * `ibd("HybridVehicle wiring", owner = hybrid) { … }` — an Internal Block
     * Diagram projecting the internal structure of [owner].
     *
     * Without an `include`-block the bridge renders **all** part-usages of the
     * owner (the empty-`elementIds` short-hand). With one or more `include(...)`
     * calls the bridge restricts the visible part-usages to that subset —
     * mirrors the BDD's semantic where empty means "no restriction".
     */
    fun ibd(
        name: String,
        owner: PartDefinition,
        block: IbdDiagramBuilder.() -> Unit = {},
    ): IbdDiagram {
        val builder = IbdDiagramBuilder().apply(block)
        val diagram =
            IbdDiagram(
                name = name,
                ownerId = owner.id,
                elementIds = builder.ids(),
            )
        diagrams += diagram
        return diagram
    }

    /**
     * `ucDiagram("Library — top-level use cases") { … }` — V2.0.7 Use Case
     * Diagram.
     *
     * The block declares which actors + use cases participate
     * (`include(...)`) and the associations / include / extend edges between
     * them (`association(...)`, `include(uc1, uc2)`, `extend(uc1, uc2)`).
     * IDs for edges are deterministic so layout + serialisation + diff stay
     * stable across runs.
     */
    fun ucDiagram(
        name: String,
        block: UcDiagramBuilder.() -> Unit = {},
    ): UcDiagram {
        val builder = UcDiagramBuilder().apply(block)
        val diagram =
            UcDiagram(
                name = name,
                elementIds = builder.ids(),
                associations = builder.associations(),
                includes = builder.includes(),
                extends = builder.extends(),
            )
        diagrams += diagram
        return diagram
    }

    /**
     * `reqDiagram("Vehicle — top-level requirements") { … }` — V2.0.8
     * Requirement Diagram.
     *
     * The block declares which requirements (and optionally satisfying parts /
     * verifying use-cases) participate (`include(...)`) and the four
     * traceability edges between them (`satisfy(...)`, `verify(...)`,
     * `derive(...)`, `contains(...)`). IDs for edges are deterministic
     * (`satisfy:<src>::<req>` etc.) so layout + serialisation + diff stay
     * stable across runs.
     */
    fun reqDiagram(
        name: String,
        block: ReqDiagramBuilder.() -> Unit = {},
    ): ReqDiagram {
        val builder = ReqDiagramBuilder().apply(block)
        val diagram =
            ReqDiagram(
                name = name,
                elementIds = builder.ids(),
                satisfies = builder.satisfies(),
                verifies = builder.verifies(),
                derives = builder.derives(),
                contains = builder.containsList(),
            )
        diagrams += diagram
        return diagram
    }

    /**
     * `stmDiagram("Traffic light — phase cycle") { … }` — V2.0.9 State
     * Transition Diagram.
     *
     * The block declares which [StateDefinition]s (initial / final pseudo-
     * states + regular states) participate (`include(...)` /
     * `includeById(...)`). Transitions are *not* declared on the diagram —
     * they live on the model via [transition] / [transitionById] and the
     * bridge auto-includes them when both endpoints are visible. See
     * [StmDiagram] KDoc for why STM follows the BDD/IBD "transitions live on
     * the model" pattern rather than the UC/REQ "edges live on the diagram"
     * pattern.
     */
    fun stmDiagram(
        name: String,
        block: StmDiagramBuilder.() -> Unit = {},
    ): StmDiagram {
        val builder = StmDiagramBuilder().apply(block)
        val diagram = StmDiagram(name = name, elementIds = builder.ids())
        diagrams += diagram
        return diagram
    }

    // ── V2.0.10 ACT ──────────────────────────────────────────────────────

    /**
     * `action def ValidateOrder { … }` — V2.0.10 action-typed definition.
     *
     * Declares a *regular action* node by default (`kind = Action`); the
     * pseudo-node helpers ([initialNode] / [finalNode] / [flowFinalNode] /
     * [decisionNode] / [mergeNode] / [forkNode] / [joinNode]) delegate to
     * this method with a fixed [kind]. The [action] body is optional — when
     * set, the SVG renderer surfaces it as a second text line beneath the
     * name (truncated at ~30 chars).
     *
     * Actions are nodes in an [ActDiagram]; control / object flows between
     * them are captured separately via [controlFlow] / [objectFlow], which
     * register the resulting usage on the model's `usages` list (V2.0.6
     * architecture bonus).
     */
    fun actionDef(
        name: String,
        id: String = name,
        action: String? = null,
        kind: ActivityNodeKind = ActivityNodeKind.Action,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): ActionDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ActionDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                kind = kind,
                action = action,
            )
        definitions += def
        return def
    }

    /**
     * `initial` — V2.0.10 initial activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Initial`. The
     * renderer draws a small filled circle; the [action] slot is ignored
     * for pseudo-nodes.
     */
    fun initialNode(
        name: String = "Initial",
        id: String = name,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Initial)

    /**
     * `final` — V2.0.10 final activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Final`. The
     * renderer draws a donut (outer ring + inner filled disc); the [action]
     * slot is ignored for pseudo-nodes.
     */
    fun finalNode(
        name: String = "Final",
        id: String = name,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Final)

    /**
     * `flow final` — V2.0.10 flow-final activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = FlowFinal`. The
     * renderer draws a circle with an X inside (two diagonal lines), marking
     * the end of a single token — other concurrent tokens continue.
     */
    fun flowFinalNode(
        name: String = "FlowFinal",
        id: String = name,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.FlowFinal)

    /**
     * `decide` — V2.0.10 decision activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Decision`. The
     * renderer draws a diamond; the node branches on guards (1 incoming →
     * N outgoing).
     */
    fun decisionNode(
        name: String,
        id: String = name,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Decision)

    /**
     * `merge` — V2.0.10 merge activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Merge`. The
     * renderer draws a diamond; the node merges alternative branches
     * (N incoming → 1 outgoing).
     */
    fun mergeNode(
        name: String,
        id: String = name,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Merge)

    /**
     * `fork` — V2.0.10 fork activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Fork`. The
     * renderer draws a synchronisation bar; the node splits into parallel
     * branches (1 incoming → N outgoing).
     */
    fun forkNode(
        name: String,
        id: String = name,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Fork)

    /**
     * `join` — V2.0.10 join activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Join`. The
     * renderer draws a synchronisation bar; the node synchronises parallel
     * branches (N incoming → 1 outgoing).
     */
    fun joinNode(
        name: String,
        id: String = name,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Join)

    /**
     * `flow A → B [guard]` — V2.0.10 control-flow usage between two
     * [ActionDefinition]s (regular actions or any activity-node pseudo-kind).
     *
     * Registers the resulting [ControlFlowUsage] in [Sysml2Model.usages] (via
     * [registerUsage] — the V2.0.6 architecture bonus). The bridge picks
     * control flows back up from `model.usages` when projecting an
     * [ActDiagram], so the surface does not need to declare them on the
     * diagram itself — see [ActDiagram] KDoc for the rationale (token flow
     * lives on the model because the future Behaviour-Runtime wave needs it).
     *
     * Default [id] convention: `controlFlow:<source>::<target>` —
     * deterministic, readable, collision-free for unique node-pairs.
     * Callers can override the id when two distinct control flows connect
     * the same pair (e.g. a Decision fans out via two guarded edges to the
     * same Merge).
     */
    fun controlFlow(
        name: String,
        source: ActionDefinition,
        target: ActionDefinition,
        guard: String? = null,
        id: String = "controlFlow:${source.id}::${target.id}",
    ): ControlFlowUsage =
        controlFlowById(
            name = name,
            sourceNodeId = source.id,
            targetNodeId = target.id,
            guard = guard,
            id = id,
        )

    /**
     * Id-only variant of [controlFlow] — for forward refs / id-only setups.
     */
    fun controlFlowById(
        name: String,
        sourceNodeId: String,
        targetNodeId: String,
        guard: String? = null,
        id: String = "controlFlow:$sourceNodeId::$targetNodeId",
    ): ControlFlowUsage {
        val usage =
            ControlFlowUsage(
                id = id,
                name = name,
                qualifiedName = name,
                sourceNodeId = sourceNodeId,
                targetNodeId = targetNodeId,
                guard = guard,
            )
        registerUsage(usage)
        return usage
    }

    /**
     * `flow A → B of Order` — V2.0.10 object-flow usage between two
     * [ActionDefinition]s.
     *
     * Same registration semantics as [controlFlow]; the [objectType] slot
     * records the type of the object the token carries (`"Order"`,
     * `"Document"`) — raw string in V2.0.10 MVP.
     *
     * Default [id] convention: `objectFlow:<source>::<target>`.
     */
    fun objectFlow(
        name: String,
        source: ActionDefinition,
        target: ActionDefinition,
        objectType: String? = null,
        id: String = "objectFlow:${source.id}::${target.id}",
    ): ObjectFlowUsage =
        objectFlowById(
            name = name,
            sourceNodeId = source.id,
            targetNodeId = target.id,
            objectType = objectType,
            id = id,
        )

    /**
     * Id-only variant of [objectFlow] — for forward refs / id-only setups.
     */
    fun objectFlowById(
        name: String,
        sourceNodeId: String,
        targetNodeId: String,
        objectType: String? = null,
        id: String = "objectFlow:$sourceNodeId::$targetNodeId",
    ): ObjectFlowUsage {
        val usage =
            ObjectFlowUsage(
                id = id,
                name = name,
                qualifiedName = name,
                sourceNodeId = sourceNodeId,
                targetNodeId = targetNodeId,
                objectType = objectType,
            )
        registerUsage(usage)
        return usage
    }

    /**
     * `actDiagram("Order processing — workflow") { … }` — V2.0.10 Activity
     * Diagram.
     *
     * The block declares which [ActionDefinition]s (regular actions +
     * pseudo-nodes) participate (`include(...)` / `includeById(...)`).
     * Control flows and object flows are *not* declared on the diagram —
     * they live on the model via [controlFlow] / [objectFlow] and the bridge
     * auto-includes them when both endpoints are visible. See [ActDiagram]
     * KDoc for the rationale (flows ARE the model, not a diagram-only
     * assertion).
     */
    fun actDiagram(
        name: String,
        block: ActDiagramBuilder.() -> Unit = {},
    ): ActDiagram {
        val builder = ActDiagramBuilder().apply(block)
        val diagram = ActDiagram(name = name, elementIds = builder.ids())
        diagrams += diagram
        return diagram
    }

    // ── V2.0.11 SEQ ──────────────────────────────────────────────────────

    /**
     * `lifeline def User { … }` — V2.0.11 lifeline-typed definition for the
     * SysML 2 Sequence Diagram.
     *
     * Mirrors [partDef] and adds one V2.0.11-specific slot:
     *  - [represents] — optional id of the represented participant (typically
     *    a [PartDefinition], but can also point at an [ActorDefinition] or
     *    other definition kind). Read-only metadata in the V2.0.11 MVP;
     *    surfaces as a tooltip / link in V2.x polish.
     *
     * Lifelines are nodes in a [SeqDiagram]; messages between them are
     * captured separately via [message] / [messageById] which register a
     * [MessageUsage] on the model's `usages` list (V2.0.6 architecture bonus).
     */
    fun lifelineDef(
        name: String,
        id: String = name,
        represents: String? = null,
        isAbstract: Boolean = false,
        block: DefinitionBuilder.() -> Unit = {},
    ): LifelineDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            LifelineDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                represents = represents,
            )
        definitions += def
        return def
    }

    /**
     * `message login(user, pwd) : Browser → AuthService [seqNo=2]` — V2.0.11
     * message-usage between two [LifelineDefinition]s.
     *
     * Registers the resulting [MessageUsage] in [Sysml2Model.usages] (via
     * [registerUsage] — the V2.0.6 architecture bonus). The bridge does NOT
     * pick messages up as layout-graph edges (see [SeqDiagram] KDoc for the
     * architecture divergence); the SVG renderer reads them directly from
     * `model.usages` and draws horizontal arrows at sequence-indexed
     * Y positions.
     *
     * Default [id] convention: `message:<source>-<target>-<seqNo>` —
     * deterministic, readable, collision-free for unique (source, target,
     * seqNo) triples. The dash separator (vs `::`) keeps the id readable
     * when the same lifeline pair exchanges many messages.
     */
    fun message(
        label: String,
        source: LifelineDefinition,
        target: LifelineDefinition,
        seqNo: Int,
        kind: MessageKind = MessageKind.Sync,
        id: String = "message:${source.id}-${target.id}-$seqNo",
        name: String = label,
    ): MessageUsage =
        messageById(
            label = label,
            sourceLifelineId = source.id,
            targetLifelineId = target.id,
            seqNo = seqNo,
            kind = kind,
            id = id,
            name = name,
        )

    /**
     * Id-only variant of [message] — for forward refs / id-only setups.
     *
     * Useful when a message needs to be wired between two lifelines whose
     * [LifelineDefinition] references are not yet in scope (e.g. when
     * reading the interaction from an external source and replaying it
     * through the builder).
     */
    fun messageById(
        label: String,
        sourceLifelineId: String,
        targetLifelineId: String,
        seqNo: Int,
        kind: MessageKind = MessageKind.Sync,
        id: String = "message:$sourceLifelineId-$targetLifelineId-$seqNo",
        name: String = label,
    ): MessageUsage {
        val usage =
            MessageUsage(
                id = id,
                name = name,
                qualifiedName = name,
                sourceLifelineId = sourceLifelineId,
                targetLifelineId = targetLifelineId,
                seqNo = seqNo,
                messageLabel = label,
                kind = kind,
            )
        registerUsage(usage)
        return usage
    }

    /**
     * `seqDiagram("Login flow") { include(user); include(browser); … }` —
     * V2.0.11 Sequence Diagram.
     *
     * The block declares which [LifelineDefinition]s participate
     * (`include(...)` / `includeById(...)`), in left-to-right declaration
     * order. Messages are *not* declared on the diagram — they live on the
     * model via [message] / [messageById] and the renderer auto-includes
     * them when both endpoints are visible. See [SeqDiagram] KDoc for the
     * rationale (messages ARE the model + the architecture divergence on
     * how they reach the renderer).
     */
    fun seqDiagram(
        name: String,
        block: SeqDiagramBuilder.() -> Unit = {},
    ): SeqDiagram {
        val builder = SeqDiagramBuilder().apply(block)
        val diagram = SeqDiagram(name = name, elementIds = builder.ids())
        diagrams += diagram
        return diagram
    }

    fun build(): Sysml2Model =
        Sysml2Model(
            name = name,
            definitions = definitions.toList(),
            usages = usages.toList(),
            diagrams = diagrams.toList(),
        )
}

/**
 * Scope inside a definition body — collects nested usages.
 *
 * Holds a [parentId] so generated ids are unambiguous: `Vehicle::engine`
 * for a part-usage `engine` inside `Vehicle`. The parent-qualified id is
 * what the model layer uses to disambiguate when two definitions both have
 * a feature with the same simple name.
 */
@Sysml2Dsl
class DefinitionBuilder internal constructor(
    private val parentId: String,
    private val modelBuilder: Sysml2ModelBuilder,
) {
    private val collected = mutableListOf<dev.kuml.kerml.KermlFeature>()

    /**
     * Declare an attribute-usage: `mass : Mass = 1500[kg]`.
     *
     * The [typeId] points at an [AttributeDefinition] declared elsewhere
     * in the model. We don't resolve right now — the build pass is later.
     */
    fun attribute(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
        default: UnitValue? = null,
    ): AttributeUsage {
        val qn = "$parentId::$name"
        val usage =
            AttributeUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
                defaultExpression = default?.toSpecForm(),
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a part-usage: `engine : Engine`. */
    fun part(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): PartUsage {
        val qn = "$parentId::$name"
        val usage =
            PartUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a port-usage: `port inlet : Inlet`. */
    fun port(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): PortUsage {
        val qn = "$parentId::$name"
        val usage =
            PortUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a connection-usage: `connect engine.inlet to tank.outlet`. */
    fun connect(
        name: String,
        typeId: String,
        sourceEndId: String,
        targetEndId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ConnectionUsage {
        val qn = "$parentId::$name"
        val usage =
            ConnectionUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
                sourceEndId = sourceEndId,
                targetEndId = targetEndId,
            )
        collected += toFeature(usage, typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    internal fun features(): List<dev.kuml.kerml.KermlFeature> = collected.toList()

    /**
     * Shadow SysML 2 usages onto the KerML feature layer so consumers that
     * walk a definition's `features` list — like a future serialiser or a
     * KerML-level diff — see a consistent view.
     */
    private fun toFeature(
        usage: Sysml2Usage,
        typeId: String,
    ): dev.kuml.kerml.KermlFeature {
        val defaultExpr = (usage as? AttributeUsage)?.defaultExpression
        return dev.kuml.kerml.KermlFeature(
            id = usage.id,
            name = usage.name,
            qualifiedName = usage.qualifiedName,
            typeId = typeId,
            definitionId = usage.definitionId,
            multiplicity = usage.multiplicity,
            defaultExpression = defaultExpr,
        )
    }
}

/** Scope for `bdd("…") { include(Vehicle); include(Engine) }`. */
@Sysml2Dsl
class BdDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()

    /** Add a SysML 2 element (by reference) to the diagram. */
    fun include(definition: Sysml2Definition) {
        ids += definition.id
    }

    /** Add a SysML 2 element by raw id — for forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/**
 * Scope for `ibd("…", owner = Vehicle) { include(engine); include(battery) }`.
 *
 * Mirrors [BdDiagramBuilder] but selects [Sysml2Usage]s (typically
 * [PartUsage]s) instead of definitions. An empty include-block means
 * "show all of the owner's part-usages" — the bridge enforces that
 * empty-list semantics.
 */
@Sysml2Dsl
class IbdDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()

    /** Add a SysML 2 usage (by reference) to the IBD's visible set. */
    fun include(usage: Sysml2Usage) {
        ids += usage.id
    }

    /** Add a SysML 2 usage by raw id — for forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/**
 * Scope for `ucDiagram("…") { include(reader); include(borrowBook);
 * association(reader, borrowBook); include(borrowBook, authenticate);
 * extend(payLateFee, returnBook) }` — V2.0.7.
 *
 * Two distinct `include`-flavours live on this builder; they are disambig-
 * uated by Kotlin overload resolution on parameter types:
 *  - [include]`(Sysml2Definition)` *adds a node to the diagram* (actor or
 *    use case).
 *  - [include]`(UseCaseDefinition, UseCaseDefinition)` *creates an
 *    `«include»` relationship edge* between two use cases.
 *
 * The compiler picks the right overload based on whether one or two
 * `UseCaseDefinition`s are passed in. Don't try to call the relationship
 * form with positional arguments that would collapse into the
 * single-argument form — the type signature carries the intent.
 *
 * Edge id conventions:
 *  - association: `assoc:<actorId>::<useCaseId>`
 *  - include relationship: `include:<sourceUcId>::<targetUcId>`
 *  - extend relationship:  `extend:<sourceUcId>::<targetUcId>`
 */
@Sysml2Dsl
class UcDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()
    private val associations = mutableListOf<UcAssociation>()
    private val includes = mutableListOf<UcInclude>()
    private val extends = mutableListOf<UcExtend>()

    /**
     * Add a SysML 2 definition (actor or use case) as a visible node in
     * the UC diagram. See class KDoc for the overload-resolution rule that
     * distinguishes this from the include-relationship form.
     */
    fun include(definition: Sysml2Definition) {
        ids += definition.id
    }

    /** Add a node by raw id — forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    /**
     * Create an actor-to-use-case association edge. Returns the resulting
     * [UcAssociation] so callers can hold on to it for further reference.
     */
    fun association(
        actor: ActorDefinition,
        useCase: UseCaseDefinition,
    ): UcAssociation {
        val assoc = associationById(actor.id, useCase.id)
        return assoc
    }

    /** Id-only variant of [association] — for forward refs. */
    fun associationById(
        actorId: String,
        useCaseId: String,
    ): UcAssociation {
        val assoc = UcAssociation(id = "assoc:$actorId::$useCaseId", actorId = actorId, useCaseId = useCaseId)
        associations += assoc
        return assoc
    }

    /**
     * Create an `«include»` relationship between two use cases —
     * `source` always executes `target` as part of its own behaviour.
     *
     * Distinct overload from [include]`(Sysml2Definition)`: the two-argument
     * shape with two [UseCaseDefinition]s creates the relationship; the
     * one-argument shape with a [Sysml2Definition] adds a node.
     */
    fun include(
        source: UseCaseDefinition,
        target: UseCaseDefinition,
    ): UcInclude = includeById(source.id, target.id)

    /** Id-only variant of the include-relationship form — for forward refs. */
    fun includeById(
        sourceId: String,
        targetId: String,
    ): UcInclude {
        val inc = UcInclude(id = "include:$sourceId::$targetId", sourceUseCaseId = sourceId, targetUseCaseId = targetId)
        includes += inc
        return inc
    }

    /**
     * Create an `«extend»` relationship between two use cases —
     * `source` optionally extends `target`'s behaviour.
     */
    fun extend(
        source: UseCaseDefinition,
        target: UseCaseDefinition,
    ): UcExtend = extendById(source.id, target.id)

    /** Id-only variant of the extend-relationship form — for forward refs. */
    fun extendById(
        sourceId: String,
        targetId: String,
    ): UcExtend {
        val ext = UcExtend(id = "extend:$sourceId::$targetId", sourceUseCaseId = sourceId, targetUseCaseId = targetId)
        extends += ext
        return ext
    }

    internal fun ids(): List<String> = ids.toList()

    internal fun associations(): List<UcAssociation> = associations.toList()

    internal fun includes(): List<UcInclude> = includes.toList()

    internal fun extends(): List<UcExtend> = extends.toList()
}

/**
 * Scope for `reqDiagram("…") { include(req); satisfy(part, req); verify(uc, req);
 * derive(child, parent); contains(parent, child) }` — V2.0.8.
 *
 * Mirror of [UcDiagramBuilder] for the V2.0.8 Requirement Diagram. The block
 * collects nodes (`include(...)` / `includeById(...)`) and the four edge
 * kinds (`satisfy(...)` / `verify(...)` / `derive(...)` / `contains(...)`).
 *
 * Naming pitfall — the DSL surface exposes a method named `contains` for the
 * `ReqContains` edge, which collides with Kotlin's `Collection.contains`. To
 * keep the public surface natural (`contains(parent, child)`), the internal
 * accessor that returns the collected `ReqContains` list is named
 * [containsList] instead of `contains`.
 *
 * Edge id conventions:
 *  - satisfy: `satisfy:<sourceId>::<requirementId>`
 *  - verify:  `verify:<sourceId>::<requirementId>`
 *  - derive:  `derive:<sourceRequirementId>::<targetRequirementId>`
 *  - contains: `contains:<parentRequirementId>::<childRequirementId>`
 */
@Sysml2Dsl
class ReqDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()
    private val satisfies = mutableListOf<ReqSatisfy>()
    private val verifies = mutableListOf<ReqVerify>()
    private val derives = mutableListOf<ReqDerive>()
    private val containsEdges = mutableListOf<ReqContains>()

    /** Add a SysML 2 definition (requirement, part, use-case, actor) as a node. */
    fun include(definition: Sysml2Definition) {
        ids += definition.id
    }

    /** Add a node by raw id — forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    /**
     * Create a **satisfy** edge — [source] (Part / UseCase / Component)
     * satisfies the [requirement]. Returns the [ReqSatisfy] so callers can
     * hold on to it for further reference.
     */
    fun satisfy(
        source: Sysml2Definition,
        requirement: RequirementDefinition,
    ): ReqSatisfy = satisfyById(source.id, requirement.id)

    /** Id-only variant of [satisfy] — for forward refs. */
    fun satisfyById(
        sourceId: String,
        requirementId: String,
    ): ReqSatisfy {
        val edge =
            ReqSatisfy(
                id = "satisfy:$sourceId::$requirementId",
                sourceId = sourceId,
                requirementId = requirementId,
            )
        satisfies += edge
        return edge
    }

    /**
     * Create a **verify** edge — [source] (TestCase / UseCase) verifies the
     * [requirement].
     */
    fun verify(
        source: Sysml2Definition,
        requirement: RequirementDefinition,
    ): ReqVerify = verifyById(source.id, requirement.id)

    /** Id-only variant of [verify] — for forward refs. */
    fun verifyById(
        sourceId: String,
        requirementId: String,
    ): ReqVerify {
        val edge =
            ReqVerify(
                id = "verify:$sourceId::$requirementId",
                sourceId = sourceId,
                requirementId = requirementId,
            )
        verifies += edge
        return edge
    }

    /**
     * Create a **derive** edge — the [source] requirement is derived from
     * the [target] requirement (child ⟵ parent).
     */
    fun derive(
        source: RequirementDefinition,
        target: RequirementDefinition,
    ): ReqDerive = deriveById(source.id, target.id)

    /** Id-only variant of [derive] — for forward refs. */
    fun deriveById(
        sourceRequirementId: String,
        targetRequirementId: String,
    ): ReqDerive {
        val edge =
            ReqDerive(
                id = "derive:$sourceRequirementId::$targetRequirementId",
                sourceRequirementId = sourceRequirementId,
                targetRequirementId = targetRequirementId,
            )
        derives += edge
        return edge
    }

    /**
     * Create a **contains** edge — the [parent] requirement contains the
     * [child] requirement (decomposition).
     */
    fun contains(
        parent: RequirementDefinition,
        child: RequirementDefinition,
    ): ReqContains = containsById(parent.id, child.id)

    /** Id-only variant of [contains] — for forward refs. */
    fun containsById(
        parentRequirementId: String,
        childRequirementId: String,
    ): ReqContains {
        val edge =
            ReqContains(
                id = "contains:$parentRequirementId::$childRequirementId",
                parentRequirementId = parentRequirementId,
                childRequirementId = childRequirementId,
            )
        containsEdges += edge
        return edge
    }

    internal fun ids(): List<String> = ids.toList()

    internal fun satisfies(): List<ReqSatisfy> = satisfies.toList()

    internal fun verifies(): List<ReqVerify> = verifies.toList()

    internal fun derives(): List<ReqDerive> = derives.toList()

    /**
     * Returns the collected [ReqContains] edges. Named [containsList] (not
     * `contains`) to avoid colliding with both the public [contains] DSL
     * method and Kotlin's `Collection.contains` extension at the call site.
     */
    internal fun containsList(): List<ReqContains> = containsEdges.toList()
}

/**
 * Scope for `stmDiagram("…") { include(red); include(green); include(yellow) }`
 * — V2.0.9 State Transition Diagram.
 *
 * Mirrors [BdDiagramBuilder] (selects nodes only — no edges on the diagram).
 * Transitions are auto-included by the bridge from `Sysml2Model.usages`
 * whenever both endpoint state-ids are in this builder's [ids] list. See
 * [StmDiagram] KDoc for the rationale (transitions ARE the model, not a
 * diagram-only assertion).
 */
@Sysml2Dsl
class StmDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()

    /** Add a [StateDefinition] (regular state or pseudo-state) as a node. */
    fun include(state: StateDefinition) {
        ids += state.id
    }

    /** Add a state by raw id — forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/**
 * Scope for `actDiagram("…") { include(initial); include(validate); … }` —
 * V2.0.10 Activity Diagram.
 *
 * Mirrors [BdDiagramBuilder] / [StmDiagramBuilder] (selects nodes only — no
 * edges on the diagram). Control flows and object flows are auto-included
 * by the bridge from `Sysml2Model.usages` whenever both endpoint node-ids
 * are in this builder's [ids] list. See [ActDiagram] KDoc for the rationale
 * (token flow ARE the model, not a diagram-only assertion).
 */
@Sysml2Dsl
class ActDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()

    /** Add an [ActionDefinition] (any [ActivityNodeKind]) as a node. */
    fun include(node: ActionDefinition) {
        ids += node.id
    }

    /** Add a node by raw id — forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/**
 * Scope for `seqDiagram("…") { include(user); include(browser); include(authService) }`
 * — V2.0.11 Sequence Diagram.
 *
 * Mirrors [BdDiagramBuilder] / [StmDiagramBuilder] / [ActDiagramBuilder]
 * (selects lifelines only — no edges on the diagram). Messages are
 * auto-included by the renderer from `Sysml2Model.usages` whenever both
 * endpoint lifeline-ids are in this builder's [ids] list. See [SeqDiagram]
 * KDoc for the rationale (messages ARE the model, not a diagram-only
 * assertion) plus the architecture divergence on how messages reach the
 * renderer (directly, bypassing the LayoutGraph edge slot).
 */
@Sysml2Dsl
class SeqDiagramBuilder internal constructor() {
    private val ids = mutableListOf<String>()

    /** Add a [LifelineDefinition] as a node (left-to-right declaration order). */
    fun include(lifeline: LifelineDefinition) {
        ids += lifeline.id
    }

    /** Add a lifeline by raw id — forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}
