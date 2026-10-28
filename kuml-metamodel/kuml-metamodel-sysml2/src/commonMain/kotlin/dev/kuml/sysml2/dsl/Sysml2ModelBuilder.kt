package dev.kuml.sysml2.dsl

import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.ActionUsage
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ActivityPartitionUsage
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.ActorUsage
import dev.kuml.sysml2.AllocationUsage
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.CombinedFragmentOperand
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintUsage
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.DurationConstraintUsage
import dev.kuml.sysml2.ExceptionHandlerUsage
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.FlowPropertyUsage
import dev.kuml.sysml2.FlowUsage
import dev.kuml.sysml2.GuardKind
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.InteractionUseUsage
import dev.kuml.sysml2.InterruptibleRegionDefinition
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.LifelineUsage
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.ReqContains
import dev.kuml.sysml2.ReqCopy
import dev.kuml.sysml2.ReqDerive
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.ReqRefine
import dev.kuml.sysml2.ReqSatisfy
import dev.kuml.sysml2.ReqTrace
import dev.kuml.sysml2.ReqVerify
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.RequirementGroup
import dev.kuml.sysml2.RequirementPriority
import dev.kuml.sysml2.RequirementStatus
import dev.kuml.sysml2.RequirementUsage
import dev.kuml.sysml2.STM_MAX_TEXT_LEN
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StateRegion
import dev.kuml.sysml2.StateUsage
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Comment
import dev.kuml.sysml2.Sysml2CommentLink
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Element
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.TestCaseDefinition
import dev.kuml.sysml2.TimeObservationUsage
import dev.kuml.sysml2.TransitionTimer
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcActorGeneralization
import dev.kuml.sysml2.UcAssociation
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UcExtend
import dev.kuml.sysml2.UcInclude
import dev.kuml.sysml2.UcSubject
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.UseCaseUsage
import dev.kuml.sysml2.parseTimerInstant
import dev.kuml.sysml2.projection.Sysml2PathResolver
import dev.kuml.sysml2.units.UnitValue
import kotlin.time.Duration

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
    private val comments = mutableListOf<Sysml2Comment>()
    private val commentLinks = mutableListOf<Sysml2CommentLink>()

    internal companion object {
        /** Upper bound for names, ids and paths accepted by the new structural DSL functions. */
        const val MAX_NAME_CHARS: Int = 512
    }

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
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
                constraints = builder.constraints(),
            )
        definitions += def
        return def
    }

    /** `attribute def Mass { … }` — a value-typed definition. */
    fun attributeDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        specializesId: String? = null,
        unit: dev.kuml.sysml2.units.Unit? = null,
        unitSymbol: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): AttributeDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val symbol = unitSymbol ?: unit?.symbol
        require(symbol == null || symbol.length <= MAX_NAME_CHARS) { "unit symbol longer than $MAX_NAME_CHARS characters" }
        val def =
            AttributeDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
                unit = symbol,
            )
        definitions += def
        return def
    }

    /** `port def Inlet { … }` — a port-typed definition. */
    fun portDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        specializesId: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): PortDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            PortDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
            )
        definitions += def
        return def
    }

    /** `connection def PowerLine { … }` — a connection-typed definition. */
    fun connectionDef(
        name: String,
        id: String = name,
        isAbstract: Boolean = false,
        specializesId: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): ConnectionDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ConnectionDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
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
        specializesId: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): ActorDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ActorDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
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
        specializesId: String? = null,
        description: String = "",
        block: DefinitionBuilder.() -> Unit = {},
    ): UseCaseDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            UseCaseDefinition(
                id = id,
                name = name,
                description = description,
                isAbstract = isAbstract,
                features = builder.features(),
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
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
        specializesId: String? = null,
        priority: RequirementPriority? = null,
        source: String = "",
        status: RequirementStatus? = null,
        isAssumption: Boolean = false,
        subjectRef: String? = null,
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
                priority = priority,
                source = source,
                status = status,
                isAssumption = isAssumption,
                subjectRef = subjectRef,
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
            )
        definitions += def
        return def
    }

    /**
     * `test case def TopSpeedTest { … }` — C1 verification element of a
     * [ReqDiagram]; source of `verify` edges.
     */
    fun testCaseDef(
        name: String,
        id: String = name,
        text: String = "",
        isAbstract: Boolean = false,
        specializesId: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): TestCaseDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            TestCaseDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                text = text,
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
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
        specializesId: String? = null,
        parent: StateDefinition? = null,
        region: StateRegion? = null,
        parentId: String? = null,
        regionId: String? = null,
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
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
                parentStateId = parent?.id ?: parentId,
                regionId = region?.id ?: regionId,
            )
        definitions += def
        return def
    }

    /**
     * Declares an orthogonal region [name] on the composite [owner] (wave A1).
     *
     * The owner definition is replaced in place by a copy carrying the region,
     * so a `val` the caller holds for [owner] becomes stale; all cross
     * references go through ids, so this is harmless.
     */
    fun region(
        owner: StateDefinition,
        name: String,
        id: String = "${owner.id}.$name",
    ): StateRegion = regionById(ownerId = owner.id, name = name, id = id)

    /** Id-only variant of [region] (used by the DSL printer). */
    fun regionById(
        ownerId: String,
        name: String? = null,
        id: String = "$ownerId.${name ?: "region"}",
    ): StateRegion {
        val idx = definitions.indexOfFirst { it is StateDefinition && it.id == ownerId }
        require(idx >= 0) {
            "region('$name'): unknown composite state '$ownerId'. Declare the state with stateDef(...) before adding regions."
        }
        val owner = definitions[idx] as StateDefinition
        require(owner.regions.none { it.id == id }) {
            "region id '$id' is already declared on state '$ownerId'. Choose a different id."
        }
        require(name == null || owner.regions.none { it.name == name }) {
            "region name '$name' is already declared on state '$ownerId'. Choose a different name."
        }
        val region = StateRegion(id = id, name = name)
        definitions[idx] = owner.copy(regions = owner.regions + region)
        return region
    }

    private fun pseudoState(
        kind: StateKind,
        name: String,
        id: String,
        parent: StateDefinition?,
        region: StateRegion?,
        parentId: String?,
        regionId: String?,
    ): StateDefinition {
        val def =
            StateDefinition(
                id = id,
                name = name,
                kind = kind,
                parentStateId = parent?.id ?: parentId,
                regionId = region?.id ?: regionId,
            )
        definitions += def
        return def
    }

    /** Choice pseudo-state (hollow diamond). */
    fun choice(
        name: String,
        id: String = name,
        parent: StateDefinition? = null,
        region: StateRegion? = null,
        parentId: String? = null,
        regionId: String? = null,
    ): StateDefinition =
        pseudoState(
            kind = StateKind.Choice,
            name = name,
            id = id,
            parent = parent,
            region = region,
            parentId = parentId,
            regionId = regionId,
        )

    /** Junction pseudo-state (filled dot). */
    fun junction(
        name: String,
        id: String = name,
        parent: StateDefinition? = null,
        region: StateRegion? = null,
        parentId: String? = null,
        regionId: String? = null,
    ): StateDefinition =
        pseudoState(
            kind = StateKind.Junction,
            name = name,
            id = id,
            parent = parent,
            region = region,
            parentId = parentId,
            regionId = regionId,
        )

    /** Fork pseudo-state (bar splitting into concurrent regions). */
    fun fork(
        name: String,
        id: String = name,
        parent: StateDefinition? = null,
        region: StateRegion? = null,
        parentId: String? = null,
        regionId: String? = null,
    ): StateDefinition =
        pseudoState(kind = StateKind.Fork, name = name, id = id, parent = parent, region = region, parentId = parentId, regionId = regionId)

    /** Join pseudo-state (bar synchronising concurrent regions). */
    fun join(
        name: String,
        id: String = name,
        parent: StateDefinition? = null,
        region: StateRegion? = null,
        parentId: String? = null,
        regionId: String? = null,
    ): StateDefinition =
        pseudoState(kind = StateKind.Join, name = name, id = id, parent = parent, region = region, parentId = parentId, regionId = regionId)

    /**
     * History pseudo-state of a composite (`H` shallow, `H*` deep). A history
     * state is meaningless without its composite, so [parent] (or [parentId])
     * is mandatory.
     */
    fun history(
        name: String,
        deep: Boolean = false,
        parent: StateDefinition? = null,
        region: StateRegion? = null,
        id: String = name,
        parentId: String? = null,
        regionId: String? = null,
    ): StateDefinition {
        require(parent != null || parentId != null) {
            "history('$name') needs a composite state: pass parent = <composite state> (e.g. history(\"H\", parent = on))."
        }
        return pseudoState(
            kind = if (deep) StateKind.DeepHistory else StateKind.ShallowHistory,
            name = name,
            id = id,
            parent = parent,
            region = region,
            parentId = parentId,
            regionId = regionId,
        )
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
        timer: TransitionTimer? = null,
        id: String = "transition:${source.id}::${target.id}",
    ): TransitionUsage =
        transitionById(
            name = name,
            sourceStateId = source.id,
            targetStateId = target.id,
            trigger = trigger,
            guard = guard,
            effect = effect,
            timer = timer,
            id = id,
        )

    /**
     * Variant of [transition] taking a [Sysml2Guard]: `guard = prose("Door is open")` marks the
     * guard as free-text prose (documentation of intent; not evaluated by the simulator).
     */
    fun transition(
        name: String,
        source: StateDefinition,
        target: StateDefinition,
        trigger: String? = null,
        guard: Sysml2Guard,
        effect: String? = null,
        timer: TransitionTimer? = null,
        id: String = "transition:${source.id}::${target.id}",
    ): TransitionUsage =
        transitionById(
            name = name,
            sourceStateId = source.id,
            targetStateId = target.id,
            trigger = trigger,
            guard = guard,
            effect = effect,
            timer = timer,
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
        timer: TransitionTimer? = null,
        id: String = "transition:$sourceStateId::$targetStateId",
    ): TransitionUsage =
        registerTransition(
            name = name,
            sourceStateId = sourceStateId,
            targetStateId = targetStateId,
            trigger = trigger,
            guard = guard,
            guardKind = GuardKind.Expression,
            effect = effect,
            timer = timer,
            id = id,
        )

    /** Id-only variant of the [Sysml2Guard] overload of [transition]. */
    fun transitionById(
        name: String,
        sourceStateId: String,
        targetStateId: String,
        trigger: String? = null,
        guard: Sysml2Guard,
        effect: String? = null,
        timer: TransitionTimer? = null,
        id: String = "transition:$sourceStateId::$targetStateId",
    ): TransitionUsage =
        registerTransition(
            name = name,
            sourceStateId = sourceStateId,
            targetStateId = targetStateId,
            trigger = trigger,
            guard = guard.text,
            guardKind = if (guard is Sysml2Guard.Prose) GuardKind.Prose else GuardKind.Expression,
            effect = effect,
            timer = timer,
            id = id,
        )

    /** `after(5.seconds)` — relative timer for a transition (`timer = after(5.seconds)`). */
    fun after(duration: Duration): TransitionTimer.After {
        require(duration.isFinite()) { "after(): duration must be finite" }
        require(duration.inWholeNanoseconds % 1_000_000L == 0L) { "after(): duration must be a whole number of milliseconds" }
        return TransitionTimer.After(duration)
    }

    /** `at("2026-01-01T08:00:00Z")` — absolute UTC timer (ISO-8601 with `Z`, millisecond precision). */
    fun at(iso: String): TransitionTimer.At =
        TransitionTimer.At(
            requireNotNull(parseTimerInstant(iso)) {
                "at(): '${iso.take(40)}' is not an ISO-8601 UTC instant with 'Z' and millisecond precision"
            },
        )

    /** Marks a guard as free-text prose: `guard = prose("Door is open")`. */
    fun prose(text: String): Sysml2Guard.Prose = Sysml2Guard.Prose(text)

    private fun registerTransition(
        name: String,
        sourceStateId: String,
        targetStateId: String,
        trigger: String?,
        guard: String?,
        guardKind: GuardKind,
        effect: String?,
        timer: TransitionTimer?,
        id: String,
    ): TransitionUsage {
        require((guard?.length ?: 0) <= STM_MAX_TEXT_LEN) { "transition guard longer than $STM_MAX_TEXT_LEN characters" }
        require((effect?.length ?: 0) <= STM_MAX_TEXT_LEN) { "transition effect longer than $STM_MAX_TEXT_LEN characters" }
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
                timer = timer,
                guardKind = guardKind,
            )
        registerUsage(usage)
        return usage
    }

    // ── Comments (notes) ─────────────────────────────────────────────────

    /**
     * Adds a model-level comment. It appears in every diagram that shows one of
     * its anchors; to show a free-standing comment, list it in a diagram via
     * `include(comment)`.
     */
    fun comment(
        text: String,
        vararg anchors: String,
        id: String? = null,
    ): Sysml2Comment = addComment(text = text, anchorIds = anchors.toList(), id = id)

    /** Adds a model-level comment anchored to the given elements. */
    fun comment(
        text: String,
        firstAnchor: Sysml2Element,
        vararg moreAnchors: Sysml2Element,
        id: String? = null,
    ): Sysml2Comment = addComment(text = text, anchorIds = (listOf(firstAnchor) + moreAnchors).map { it.id }, id = id)

    /** Alias of [comment]. */
    fun note(
        text: String,
        vararg anchors: String,
        id: String? = null,
    ): Sysml2Comment = addComment(text = text, anchorIds = anchors.toList(), id = id)

    /** Alias of [comment]. */
    fun note(
        text: String,
        firstAnchor: Sysml2Element,
        vararg moreAnchors: Sysml2Element,
        id: String? = null,
    ): Sysml2Comment = addComment(text = text, anchorIds = (listOf(firstAnchor) + moreAnchors).map { it.id }, id = id)

    internal fun addComment(
        text: String,
        anchorIds: List<String>,
        id: String?,
    ): Sysml2Comment {
        val noteId = id ?: nextCommentId()
        val comment = Sysml2Comment(note = KumlNote(id = noteId, body = text))
        comments += comment
        for (target in anchorIds.distinct()) {
            commentLinks +=
                Sysml2CommentLink(link = KumlNoteLink(id = "note-link:$noteId::$target", noteId = noteId, targetId = target))
        }
        return comment
    }

    private fun nextCommentId(): String {
        val used =
            buildSet {
                definitions.forEach { add(it.id) }
                usages.forEach { add(it.id) }
                comments.forEach { add(it.id) }
            }
        var n = 1
        while ("comment-$n" in used) n++
        return "comment-$n"
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

    private var allocationCounter = 0

    /**
     * `allocate(from = "Behavior", to = "Block")` — allocate one element onto
     * another. Both ids must reference definitions or usages of the model;
     * this is checked at [build] time so forward references stay possible.
     */
    fun allocate(
        from: String,
        to: String,
        name: String = "alloc${++allocationCounter}",
        id: String = "alloc:$name",
    ): AllocationUsage {
        require(name.length <= MAX_NAME_CHARS && id.length <= MAX_NAME_CHARS) { "name longer than $MAX_NAME_CHARS characters" }
        val usage = AllocationUsage(id = id, name = name, qualifiedName = name, sourceId = from, targetId = to)
        registerUsage(usage)
        return usage
    }

    /** Overload taking definitions instead of raw ids. */
    fun allocate(
        from: Sysml2Definition,
        to: Sysml2Definition,
        name: String = "alloc${++allocationCounter}",
        id: String = "alloc:$name",
    ): AllocationUsage = allocate(from = from.id, to = to.id, name = name, id = id)

    // ── Diagrams ─────────────────────────────────────────────────────────

    /**
     * `bdd("Structural overview") { include(Vehicle); include(Engine) }` — a
     * Block Definition Diagram projecting a subset of the model.
     */
    fun bdd(
        name: String,
        block: BdDiagramBuilder.() -> Unit = {},
    ): BdDiagram {
        val builder = BdDiagramBuilder(this).apply(block)
        val diagram = BdDiagram(name = name, elementIds = builder.ids(), noteIds = builder.noteIds(), layout = builder.layoutSpec())
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
        val builder = IbdDiagramBuilder(this).apply(block)
        val diagram =
            IbdDiagram(
                name = name,
                ownerId = owner.id,
                elementIds = builder.ids(),
                noteIds = builder.noteIds(),
                layout = builder.layoutSpec(),
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
        val builder = UcDiagramBuilder(this).apply(block)
        val diagram =
            UcDiagram(
                name = name,
                elementIds = builder.ids(),
                noteIds = builder.noteIds(),
                layout = builder.layoutSpec(),
                associations = builder.associations(),
                includes = builder.includes(),
                extends = builder.extends(),
                subjects = builder.subjects(),
                actorGeneralizations = builder.actorGeneralizations(),
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
        val builder = ReqDiagramBuilder(this).apply(block)
        val diagram =
            ReqDiagram(
                name = name,
                elementIds = builder.ids(),
                noteIds = builder.noteIds(),
                layout = builder.layoutSpec(),
                satisfies = builder.satisfies(),
                verifies = builder.verifies(),
                derives = builder.derives(),
                contains = builder.containsList(),
                refines = builder.refines(),
                traces = builder.traces(),
                copies = builder.copies(),
                groups = builder.groups(),
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
        val builder = StmDiagramBuilder(this).apply(block)
        val diagram = StmDiagram(name = name, elementIds = builder.ids(), noteIds = builder.noteIds(), layout = builder.layoutSpec())
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
        specializesId: String? = null,
        partition: ActivityPartitionDefinition? = null,
        pins: List<ActionPin> = emptyList(),
        signal: String? = null,
        timeExpression: String? = null,
        calledActivityId: String? = null,
        region: InterruptibleRegionDefinition? = null,
        showName: Boolean = false,
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
                partitionId = partition?.id,
                pins = pins,
                signal = signal,
                timeExpression = timeExpression,
                calledActivityId = calledActivityId,
                interruptibleRegionId = region?.id,
                showName = showName,
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
            )
        definitions += def
        return def
    }

    /** Wave C2 — `send signal` node (pentagon). [signal] defaults to [name]. */
    fun sendSignal(
        name: String,
        id: String = name,
        signal: String = name,
        partition: ActivityPartitionDefinition? = null,
        region: InterruptibleRegionDefinition? = null,
    ): ActionDefinition =
        actionDef(
            name = name,
            id = id,
            kind = ActivityNodeKind.SendSignal,
            signal = signal,
            partition = partition,
            region = region,
        )

    /** Wave C2 — `accept event` node (rectangle with a notch). [signal] defaults to [name]. */
    fun acceptEvent(
        name: String,
        id: String = name,
        signal: String = name,
        partition: ActivityPartitionDefinition? = null,
        region: InterruptibleRegionDefinition? = null,
    ): ActionDefinition =
        actionDef(
            name = name,
            id = id,
            kind = ActivityNodeKind.AcceptEvent,
            signal = signal,
            partition = partition,
            region = region,
        )

    /** Wave C2 — `accept time event` node (hourglass); [time] is the label (`"after 5 min"`). */
    fun acceptTimeEvent(
        name: String,
        id: String = name,
        time: String,
        partition: ActivityPartitionDefinition? = null,
        region: InterruptibleRegionDefinition? = null,
    ): ActionDefinition =
        actionDef(
            name = name,
            id = id,
            kind = ActivityNodeKind.AcceptTimeEvent,
            timeExpression = time,
            partition = partition,
            region = region,
        )

    /** Wave C2 — call-behavior action (rounded box with a rake symbol); [activity] is the called activity. */
    fun callBehavior(
        name: String,
        id: String = name,
        activity: ActionDefinition? = null,
        activityId: String? = activity?.id,
        partition: ActivityPartitionDefinition? = null,
        region: InterruptibleRegionDefinition? = null,
        pins: List<ActionPin> = emptyList(),
    ): ActionDefinition =
        actionDef(
            name = name,
            id = id,
            kind = ActivityNodeKind.CallBehavior,
            calledActivityId = activityId,
            partition = partition,
            region = region,
            pins = pins,
        )

    /**
     * Wave C2 — interruptible region (dashed rounded frame). Members join it through the
     * `region = …` argument of the node helpers; nesting through [parent].
     */
    fun interruptibleRegion(
        name: String,
        id: String = name,
        showLabel: Boolean = true,
        parent: InterruptibleRegionDefinition? = null,
    ): InterruptibleRegionDefinition {
        val def =
            InterruptibleRegionDefinition(
                id = id,
                name = name,
                showLabel = showLabel,
                parentRegionId = parent?.id,
            )
        definitions += def
        return def
    }

    /** Id-only variant of [exceptionHandler]. */
    fun exceptionHandlerById(
        protectedNodeId: String,
        handlerNodeId: String,
        type: String,
        handlerPin: String? = null,
        id: String = "exceptionHandler:$protectedNodeId::$handlerNodeId",
    ): ExceptionHandlerUsage {
        val usage =
            ExceptionHandlerUsage(
                id = id,
                name = type,
                protectedNodeId = protectedNodeId,
                handlerNodeId = handlerNodeId,
                exceptionType = type,
                handlerPin = handlerPin,
            )
        registerUsage(usage)
        return usage
    }

    /** Wave C2 — exception handler: [handler] runs when [protectedNode] raises [type]. */
    fun exceptionHandler(
        protectedNode: ActionDefinition,
        handler: ActionDefinition,
        type: String,
        handlerPin: String? = null,
        id: String = "exceptionHandler:${protectedNode.id}::${handler.id}",
    ): ExceptionHandlerUsage {
        val usage =
            ExceptionHandlerUsage(
                id = id,
                name = type,
                protectedNodeId = protectedNode.id,
                handlerNodeId = handler.id,
                exceptionType = type,
                handlerPin = handlerPin,
            )
        registerUsage(usage)
        return usage
    }

    /**
     * `initial` — V2.0.10 initial activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Initial`. The
     * renderer draws a small filled circle; the [action] slot is ignored
     * for pseudo-nodes. V2.0.16: accepts an optional [partition] so the
     * initial node can be placed inside a swimlane.
     */
    fun initialNode(
        name: String = "Initial",
        id: String = name,
        partition: ActivityPartitionDefinition? = null,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Initial, partition = partition)

    /**
     * `final` — V2.0.10 final activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Final`. The
     * renderer draws a donut (outer ring + inner filled disc); the [action]
     * slot is ignored for pseudo-nodes. V2.0.16: accepts an optional
     * [partition] so the final node can be placed inside a swimlane.
     */
    fun finalNode(
        name: String = "Final",
        id: String = name,
        partition: ActivityPartitionDefinition? = null,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.Final, partition = partition)

    /**
     * `flow final` — V2.0.10 flow-final activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = FlowFinal`. The
     * renderer draws a circle with an X inside (two diagonal lines), marking
     * the end of a single token — other concurrent tokens continue. V2.0.16:
     * accepts an optional [partition] so the flow-final can sit in a lane.
     */
    fun flowFinalNode(
        name: String = "FlowFinal",
        id: String = name,
        partition: ActivityPartitionDefinition? = null,
    ): ActionDefinition = actionDef(name = name, id = id, kind = ActivityNodeKind.FlowFinal, partition = partition)

    /**
     * `decide` — V2.0.10 decision activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Decision`. The
     * renderer draws a diamond; the node branches on guards (1 incoming →
     * N outgoing). V2.0.16: accepts an optional [partition].
     */
    fun decisionNode(
        name: String,
        id: String = name,
        partition: ActivityPartitionDefinition? = null,
        showName: Boolean = false,
        region: InterruptibleRegionDefinition? = null,
    ): ActionDefinition =
        actionDef(name = name, id = id, kind = ActivityNodeKind.Decision, partition = partition, showName = showName, region = region)

    /**
     * `merge` — V2.0.10 merge activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Merge`. The
     * renderer draws a diamond; the node merges alternative branches
     * (N incoming → 1 outgoing). V2.0.16: accepts an optional [partition].
     */
    fun mergeNode(
        name: String,
        id: String = name,
        partition: ActivityPartitionDefinition? = null,
        showName: Boolean = false,
        region: InterruptibleRegionDefinition? = null,
    ): ActionDefinition =
        actionDef(name = name, id = id, kind = ActivityNodeKind.Merge, partition = partition, showName = showName, region = region)

    /**
     * `fork` — V2.0.10 fork activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Fork`. The
     * renderer draws a synchronisation bar; the node splits into parallel
     * branches (1 incoming → N outgoing). V2.0.16: accepts an optional
     * [partition].
     */
    fun forkNode(
        name: String,
        id: String = name,
        partition: ActivityPartitionDefinition? = null,
        showName: Boolean = false,
        region: InterruptibleRegionDefinition? = null,
    ): ActionDefinition =
        actionDef(name = name, id = id, kind = ActivityNodeKind.Fork, partition = partition, showName = showName, region = region)

    /**
     * `join` — V2.0.10 join activity-node helper.
     *
     * Convenience wrapper around [actionDef] with `kind = Join`. The
     * renderer draws a synchronisation bar; the node synchronises parallel
     * branches (N incoming → 1 outgoing). V2.0.16: accepts an optional
     * [partition].
     */
    fun joinNode(
        name: String,
        id: String = name,
        partition: ActivityPartitionDefinition? = null,
        showName: Boolean = false,
        region: InterruptibleRegionDefinition? = null,
    ): ActionDefinition =
        actionDef(name = name, id = id, kind = ActivityNodeKind.Join, partition = partition, showName = showName, region = region)

    /**
     * `partition def Customer { … }` — V2.0.16 activity-partition (swimlane)
     * definition. Closes the V2.0.10 deferred-item list.
     *
     * Declares an [ActivityPartitionDefinition] and registers it on the
     * model's `definitions` list. The partition surfaces in the diagram as
     * a vertical lane with a header bar containing [name]; actions assigned
     * to the partition (via the `partition = …` argument on [actionDef] or
     * the pseudo-node helpers) render inside that lane.
     *
     * Optional [represents] points at the represented entity — typically a
     * [PartDefinition] id (`"Customer"`, `"OrderSystem"`). Read-only metadata
     * in the V2.0.16 MVP; surfaces as a tooltip / link in V2.x polish.
     *
     * Partitions live on the model (not on the diagram) so the bridge can
     * auto-include them in an `actDiagram(...)` projection as soon as any
     * action assigned to them is visible — identical philosophy to control /
     * object flows (V2.0.10).
     */
    fun activityPartition(
        name: String,
        id: String = name,
        represents: String? = null,
        isAbstract: Boolean = false,
        specializesId: String? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): ActivityPartitionDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ActivityPartitionDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                represents = represents,
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
            )
        definitions += def
        return def
    }

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
        interrupts: Boolean = false,
    ): ControlFlowUsage =
        controlFlowById(
            name = name,
            sourceNodeId = source.id,
            targetNodeId = target.id,
            guard = guard,
            id = id,
            interrupts = interrupts,
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
        interrupts: Boolean = false,
    ): ControlFlowUsage {
        val usage =
            ControlFlowUsage(
                id = id,
                name = name,
                qualifiedName = name,
                sourceNodeId = sourceNodeId,
                targetNodeId = targetNodeId,
                guard = guard,
                interrupts = interrupts,
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
        interrupts: Boolean = false,
        sourcePin: String? = null,
        targetPin: String? = null,
    ): ObjectFlowUsage =
        objectFlowById(
            name = name,
            sourceNodeId = source.id,
            targetNodeId = target.id,
            objectType = objectType,
            id = id,
            interrupts = interrupts,
            sourcePin = sourcePin,
            targetPin = targetPin,
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
        interrupts: Boolean = false,
        sourcePin: String? = null,
        targetPin: String? = null,
    ): ObjectFlowUsage {
        val usage =
            ObjectFlowUsage(
                id = id,
                name = name,
                qualifiedName = name,
                sourceNodeId = sourceNodeId,
                targetNodeId = targetNodeId,
                objectType = objectType,
                interrupts = interrupts,
                sourcePin = sourcePin,
                targetPin = targetPin,
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
        val builder = ActDiagramBuilder(this).apply(block)
        val diagram = ActDiagram(name = name, elementIds = builder.ids(), noteIds = builder.noteIds(), layout = builder.layoutSpec())
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
        specializesId: String? = null,
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
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
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
        val builder = SeqDiagramBuilder(this).apply(block)
        val diagram = SeqDiagram(name = name, elementIds = builder.ids(), noteIds = builder.noteIds(), layout = builder.layoutSpec())
        diagrams += diagram
        return diagram
    }

    // ── V2.0.15 SEQ Combined Fragments + Execution Specs ────────────────

    /**
     * `alt { credentials valid → 4..5; credentials invalid → … }` — V2.0.15
     * Combined-Fragment usage that wraps a range of messages in a sequence
     * diagram with an interaction operator.
     *
     * Registers the resulting [CombinedFragmentUsage] in [Sysml2Model.usages]
     * (via [registerUsage] — the V2.0.6 architecture bonus). The SVG renderer
     * reads fragments back from `model.usages` after the lifeline-head loop
     * and draws the dashed frame + operator-tag pentagon + per-operand
     * dividers directly. The bridge does NOT emit a LayoutGraph node/edge
     * for fragments — they are renderer-direct, same pattern as
     * [MessageUsage] in V2.0.11.
     *
     * V2.0.15 MVP: flat fragments only (no CF inside CF). The [operands]
     * list must be non-empty; the builder does not validate ordering or
     * seqNo-range overlap — that's the validator's job.
     *
     * Default [id] convention: `combinedFragment:<name>` — deterministic,
     * readable, collision-free for unique fragment names. Callers can
     * override the id when two fragments share a name (e.g. when reading
     * the model from an external source).
     */
    fun combinedFragment(
        name: String,
        operator: CombinedFragmentOperator,
        operands: List<CombinedFragmentOperand>,
        id: String = "combinedFragment:$name",
        parentFragmentId: String? = null,
        parentOperandIndex: Int? = null,
    ): CombinedFragmentUsage {
        val usage =
            CombinedFragmentUsage(
                id = id,
                name = name,
                qualifiedName = name,
                operator = operator,
                operands = operands,
                parentFragmentId = parentFragmentId,
                parentOperandIndex = parentOperandIndex,
            )
        registerUsage(usage)
        return usage
    }

    /**
     * C3 — block form of [combinedFragment] with **nested fragments**:
     *
     * ```
     * combinedFragment("retry", CombinedFragmentOperator.Loop) {
     *     operand(guard = "attempts < 3", start = 1, end = 5) {
     *         combinedFragment("cache", CombinedFragmentOperator.Alt) {
     *             operand(guard = "hit", start = 2, end = 2)
     *             operand(guard = "miss", start = 3, end = 4)
     *         }
     *     }
     * }
     * ```
     *
     * Children are registered with `parentFragmentId`/`parentOperandIndex` pointing at the enclosing
     * operand. Hard limits live in [Sysml2Limits]; deeper nesting is rendered flat and reported by the checker.
     */
    fun combinedFragment(
        name: String,
        operator: CombinedFragmentOperator,
        id: String = "combinedFragment:$name",
        parentFragmentId: String? = null,
        parentOperandIndex: Int? = null,
        block: CombinedFragmentBuilder.() -> Unit,
    ): CombinedFragmentUsage {
        val builder = CombinedFragmentBuilder(modelBuilder = this, fragmentId = id).apply(block)
        return combinedFragment(
            name = name,
            operator = operator,
            operands = builder.operands.toList(),
            id = id,
            parentFragmentId = parentFragmentId,
            parentOperandIndex = parentOperandIndex,
        )
    }

    /** `ref` frame over [lifelines] at row [seqNo], referencing the interaction named [refersTo] (C3). */
    fun interactionUse(
        name: String,
        refersTo: String,
        lifelines: List<LifelineDefinition>,
        seqNo: Int,
        id: String = "interactionUse:$name",
    ): InteractionUseUsage =
        interactionUseById(name = name, refersTo = refersTo, lifelineIds = lifelines.map { it.id }, seqNo = seqNo, id = id)

    /** Id-only variant of [interactionUse] (used by the DSL printer). */
    fun interactionUseById(
        name: String,
        refersTo: String,
        lifelineIds: List<String>,
        seqNo: Int,
        id: String = "interactionUse:$name",
    ): InteractionUseUsage {
        val usage = InteractionUseUsage(id = id, name = name, refersTo = refersTo, lifelineIds = lifelineIds, seqNo = seqNo)
        registerUsage(usage)
        return usage
    }

    /** Duration constraint `{expression}` on [lifeline] between rows [from] and [to] (C3). */
    fun durationConstraint(
        lifeline: LifelineDefinition,
        from: Int,
        to: Int,
        expression: String,
        id: String = "durationConstraint:${lifeline.id}-$from-$to",
        name: String = expression,
    ): DurationConstraintUsage =
        durationConstraintById(lifelineId = lifeline.id, from = from, to = to, expression = expression, id = id, name = name)

    /** Id-only variant of [durationConstraint]. */
    fun durationConstraintById(
        lifelineId: String,
        from: Int,
        to: Int,
        expression: String,
        id: String = "durationConstraint:$lifelineId-$from-$to",
        name: String = expression,
    ): DurationConstraintUsage {
        val usage =
            DurationConstraintUsage(id = id, name = name, lifelineId = lifelineId, fromSeqNo = from, toSeqNo = to, expression = expression)
        registerUsage(usage)
        return usage
    }

    /** Time observation (`name = now`) tick on [lifeline] at row [seqNo] (C3). */
    fun timeObservation(
        lifeline: LifelineDefinition,
        seqNo: Int,
        name: String,
        id: String = "timeObservation:${lifeline.id}-$seqNo",
    ): TimeObservationUsage = timeObservationById(lifelineId = lifeline.id, seqNo = seqNo, name = name, id = id)

    /** Id-only variant of [timeObservation]. */
    fun timeObservationById(
        lifelineId: String,
        seqNo: Int,
        name: String,
        id: String = "timeObservation:$lifelineId-$seqNo",
    ): TimeObservationUsage {
        val usage = TimeObservationUsage(id = id, name = name, lifelineId = lifelineId, seqNo = seqNo)
        registerUsage(usage)
        return usage
    }

    /** Found message: a message from outside the diagram arriving at [target] (C3). */
    fun foundMessage(
        label: String,
        target: LifelineDefinition,
        seqNo: Int,
        id: String = "message:found-${target.id}-$seqNo",
    ): MessageUsage =
        messageById(
            label = label,
            sourceLifelineId = target.id,
            targetLifelineId = target.id,
            seqNo = seqNo,
            kind = MessageKind.Found,
            id = id,
        )

    /** Lost message: a message from [source] that never arrives anywhere inside the diagram (C3). */
    fun lostMessage(
        label: String,
        source: LifelineDefinition,
        seqNo: Int,
        id: String = "message:lost-${source.id}-$seqNo",
    ): MessageUsage =
        messageById(
            label = label,
            sourceLifelineId = source.id,
            targetLifelineId = source.id,
            seqNo = seqNo,
            kind = MessageKind.Lost,
            id = id,
        )

    /**
     * Single-operand convenience overload for [combinedFragment] — typical
     * for `Opt` / `Loop` / `Break` / `Critical` operators where one operand
     * with one guard covers the whole frame range.
     *
     * Delegates to the list-form overload after packaging the single
     * operand. The default [id] mirrors the list-form convention so the
     * resulting [CombinedFragmentUsage.id] is deterministic across overload
     * choices for the same name.
     */
    fun combinedFragment(
        name: String,
        operator: CombinedFragmentOperator,
        startSeqNo: Int,
        endSeqNo: Int,
        guard: String? = null,
        id: String = "combinedFragment:$name",
    ): CombinedFragmentUsage =
        combinedFragment(
            name = name,
            operator = operator,
            operands = listOf(CombinedFragmentOperand(guard = guard, startSeqNo = startSeqNo, endSeqNo = endSeqNo)),
            id = id,
        )

    /**
     * `execution validate on AuthService 3..3` — V2.0.15 Execution-Specification
     * usage that places a thin vertical activation bar on a lifeline between
     * two sequence numbers (inclusive).
     *
     * Registers the resulting [ExecutionSpecificationUsage] in
     * [Sysml2Model.usages] (via [registerUsage]). The SVG renderer reads
     * exec-specs back from `model.usages` and draws the activation bar
     * before drawing message arrows, so messages appear visually on top of
     * the bar. The bridge does NOT emit a LayoutGraph node/edge for
     * exec-specs — they are renderer-direct.
     *
     * V2.0.15 MVP: no nested activations (overlapping exec-specs on the
     * same lifeline are V2.x). The builder does not validate range
     * ordering — that's the validator's job.
     *
     * Default [id] convention: `executionSpec:<lifelineId>-<startSeqNo>-<endSeqNo>`
     * — deterministic, readable, collision-free for unique
     * (lifelineId, startSeqNo, endSeqNo) triples.
     */
    fun executionSpec(
        name: String,
        lifeline: LifelineDefinition,
        startSeqNo: Int,
        endSeqNo: Int,
        id: String = "executionSpec:${lifeline.id}-$startSeqNo-$endSeqNo",
    ): ExecutionSpecificationUsage {
        val usage =
            ExecutionSpecificationUsage(
                id = id,
                name = name,
                qualifiedName = name,
                lifelineId = lifeline.id,
                startSeqNo = startSeqNo,
                endSeqNo = endSeqNo,
            )
        registerUsage(usage)
        return usage
    }

    // ── V2.0.12 PAR ──────────────────────────────────────────────────────

    /**
     * `constraint def NewtonsLaw { expression = "F = m * a"; … }` — V2.0.12
     * constraint-typed definition for the SysML 2 Parametric Diagram.
     *
     * Mirrors [partDef] and adds two V2.0.12-specific slots:
     *  - [expression] — the constraint body **as a raw string**, e.g.
     *    `"F = m * a"`. Raw-string MVP in V2.0.12; the typed
     *    constraint-expression AST is a separate cross-cutting V2.x wave
     *    (see [ConstraintDefinition] KDoc for the rationale).
     *  - [parameters] — list of [ConstraintParameter] pins on the
     *    constraint's edge. Each parameter has a name, optional type-id
     *    reference, and a direction; bindings reference parameters by the
     *    synthetic endpoint id `"<constraintId>::<parameterName>"`.
     *
     * Constraints are nodes in a [ParDiagram]; bindings between constraint
     * parameter pins and attribute references are captured separately via
     * [bind] / [bindById], which register a [BindingConnectorUsage] on the
     * model's `usages` list (V2.0.6 architecture bonus).
     */
    fun constraintDef(
        name: String,
        id: String = name,
        expression: String = "",
        parameters: List<ConstraintParameter> = emptyList(),
        isAbstract: Boolean = false,
        specializesId: String? = null,
        parent: ConstraintDefinition? = null,
        block: DefinitionBuilder.() -> Unit = {},
    ): ConstraintDefinition {
        val builder = DefinitionBuilder(parentId = id, modelBuilder = this).apply(block)
        val def =
            ConstraintDefinition(
                id = id,
                name = name,
                isAbstract = isAbstract,
                features = builder.features(),
                expression = expression,
                parameters = parameters,
                specializations = specializesId?.let { listOf(KermlSpecialization(specificId = id, generalId = it)) }.orEmpty(),
                ownerConstraintId = parent?.id,
            )
        definitions += def
        return def
    }

    /**
     * `bind name : NewtonsLaw::m to Vehicle::mass` — V2.0.12 binding-connector
     * usage between two endpoints (typically a constraint parameter pin and
     * an attribute reference).
     *
     * Registers the resulting [BindingConnectorUsage] in [Sysml2Model.usages]
     * (via [registerUsage] — the V2.0.6 architecture bonus). The bridge
     * picks bindings back up from `model.usages` when projecting a
     * [ParDiagram], so the surface does not need to declare them on the
     * diagram itself — see [ParDiagram] KDoc for the rationale (bindings
     * ARE the constraint topology, not a diagram-only assertion).
     *
     * Default [id] convention: `binding:<source>::<target>` — deterministic,
     * readable, collision-free for unique (source, target) endpoint pairs.
     * Callers can override the id when two distinct bindings connect the
     * same endpoint pair.
     *
     * Endpoint ids follow the synthetic
     * `"<elementId>::<member>"` convention — for a constraint parameter pin,
     * `"NewtonsLaw::m"`; for an attribute on a part, `"Vehicle::mass"`. The
     * bridge resolves both endpoints by **longest-prefix-match** against the
     * visible-element ids (same heuristic as V2.0.6 IBD's [ConnectionUsage]).
     */
    fun bind(
        name: String,
        source: String,
        target: String,
        id: String = "binding:$source::$target",
    ): BindingConnectorUsage {
        val usage =
            BindingConnectorUsage(
                id = id,
                name = name,
                qualifiedName = name,
                sourceEndId = source,
                targetEndId = target,
            )
        registerUsage(usage)
        return usage
    }

    /**
     * `parDiagram("Newton — F = m·a applied to Vehicle") { include(newton);
     * include(vehicle) }` — V2.0.12 Parametric Diagram, the closing entry of
     * the 8/8 SysML 2 diagram-type series.
     *
     * The block declares which [ConstraintDefinition]s + [PartDefinition]s
     * participate (`include(...)` / `includeById(...)`). Bindings are *not*
     * declared on the diagram — they live on the model via [bind] / [bindById]
     * and the bridge auto-includes them when both endpoints resolve via
     * longest-prefix-match to visible elements. See [ParDiagram] KDoc for the
     * rationale (bindings ARE the constraint topology, not a diagram-only
     * assertion).
     */
    fun parDiagram(
        name: String,
        block: ParDiagramBuilder.() -> Unit = {},
    ): ParDiagram {
        val builder = ParDiagramBuilder(this).apply(block)
        val diagram = ParDiagram(name = name, elementIds = builder.ids(), noteIds = builder.noteIds(), layout = builder.layoutSpec())
        diagrams += diagram
        return diagram
    }

    // ── Top-level usage-only DSL constructors (ADR-0017 Wave B) ─────────────

    /**
     * `actor reader : Reader` — top-level [ActorUsage] typed by an
     * [ActorDefinition]. Closes one of the 8 `Sysml2Usage` DSL-completeness
     * gaps from ADR-0017 Wave B: the type already existed in the metamodel
     * (for symmetry with [PartUsage]) but had no DSL constructor. Not
     * currently consumed by the renderer/bridge — [UcDiagram] renders
     * [ActorDefinition]s directly — this only closes the DSL-construction gap.
     */
    fun actorUsage(
        name: String,
        actor: ActorDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ActorUsage = actorUsageById(name = name, definitionId = actor.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [actorUsage] — for forward refs / id-only setups. */
    fun actorUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ActorUsage {
        val usage = ActorUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    /**
     * `usecase borrowBook : BorrowBook` — top-level [UseCaseUsage] typed by a
     * [UseCaseDefinition]. See [actorUsage] KDoc for the ADR-0017 Wave B
     * rationale.
     */
    fun useCaseUsage(
        name: String,
        useCase: UseCaseDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): UseCaseUsage = useCaseUsageById(name = name, definitionId = useCase.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [useCaseUsage] — for forward refs / id-only setups. */
    fun useCaseUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): UseCaseUsage {
        val usage = UseCaseUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    /**
     * `requirement topSpeed : TopSpeedRequirement` — top-level
     * [RequirementUsage] typed by a [RequirementDefinition]. See [actorUsage]
     * KDoc for the ADR-0017 Wave B rationale.
     */
    fun requirementUsage(
        name: String,
        requirement: RequirementDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): RequirementUsage = requirementUsageById(name = name, definitionId = requirement.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [requirementUsage] — for forward refs / id-only setups. */
    fun requirementUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): RequirementUsage {
        val usage =
            RequirementUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    /**
     * `state s1 : State1` — top-level [StateUsage] typed by a
     * [StateDefinition]. See [actorUsage] KDoc for the ADR-0017 Wave B
     * rationale.
     */
    fun stateUsage(
        name: String,
        state: StateDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): StateUsage = stateUsageById(name = name, definitionId = state.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [stateUsage] — for forward refs / id-only setups. */
    fun stateUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): StateUsage {
        val usage = StateUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    /**
     * `action act1 : Action1` — top-level [ActionUsage] typed by an
     * [ActionDefinition]. See [actorUsage] KDoc for the ADR-0017 Wave B
     * rationale.
     */
    fun actionUsage(
        name: String,
        action: ActionDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ActionUsage = actionUsageById(name = name, definitionId = action.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [actionUsage] — for forward refs / id-only setups. */
    fun actionUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ActionUsage {
        val usage = ActionUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    /**
     * `partition p : Customer` — top-level [ActivityPartitionUsage] typed by
     * an [ActivityPartitionDefinition]. See [actorUsage] KDoc for the
     * ADR-0017 Wave B rationale.
     */
    fun activityPartitionUsage(
        name: String,
        partition: ActivityPartitionDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ActivityPartitionUsage = activityPartitionUsageById(name = name, definitionId = partition.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [activityPartitionUsage] — for forward refs / id-only setups. */
    fun activityPartitionUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ActivityPartitionUsage {
        val usage =
            ActivityPartitionUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    /**
     * `lifeline browser : Browser` — top-level [LifelineUsage] typed by a
     * [LifelineDefinition]. See [actorUsage] KDoc for the ADR-0017 Wave B
     * rationale.
     */
    fun lifelineUsage(
        name: String,
        lifeline: LifelineDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): LifelineUsage = lifelineUsageById(name = name, definitionId = lifeline.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [lifelineUsage] — for forward refs / id-only setups. */
    fun lifelineUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): LifelineUsage {
        val usage = LifelineUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    /**
     * `constraint c1 : ConstraintDef` — top-level [ConstraintUsage] typed by
     * a [ConstraintDefinition]. See [actorUsage] KDoc for the ADR-0017 Wave B
     * rationale.
     */
    fun constraintUsage(
        name: String,
        constraint: ConstraintDefinition,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ConstraintUsage = constraintUsageById(name = name, definitionId = constraint.id, id = id, multiplicity = multiplicity)

    /** Id-only variant of [constraintUsage] — for forward refs / id-only setups. */
    fun constraintUsageById(
        name: String,
        definitionId: String,
        id: String = name,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): ConstraintUsage {
        val usage =
            ConstraintUsage(id = id, name = name, qualifiedName = name, definitionId = definitionId, multiplicity = multiplicity)
        registerUsage(usage)
        return usage
    }

    fun build(): Sysml2Model {
        val model =
            Sysml2Model(
                name = name,
                definitions = definitions.toList(),
                usages = usages.toList(),
                diagrams = diagrams.toList(),
                comments = comments.toList(),
                commentLinks = commentLinks.toList(),
            )
        // Checked here (not at comment()) so forward references via ids stay possible.
        val anchorable = model.allAnchorableIds()
        for (l in model.commentLinks) {
            require(l.link.targetId in anchorable) {
                "comment '${l.link.noteId}' anchors unknown element '${l.link.targetId}'"
            }
        }
        for (al in model.usages.filterIsInstance<AllocationUsage>()) {
            require(al.sourceId in anchorable) { "allocation '${al.name}' references unknown source '${al.sourceId}'" }
            require(al.targetId in anchorable) { "allocation '${al.name}' references unknown target '${al.targetId}'" }
        }
        val seenCommentIds = mutableSetOf<String>()
        for (c in model.comments) {
            require(seenCommentIds.add(c.id)) { "duplicate comment id '${c.id}'" }
            require(c.id !in anchorable) { "comment id '${c.id}' collides with a definition / usage id" }
        }
        val noteIds = seenCommentIds
        for (d in model.diagrams) {
            for (nid in d.noteIds) {
                require(nid in noteIds) { "diagram '${d.name}' lists unknown comment '$nid'" }
            }
        }
        return model
    }
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
    private val collectedConstraints = mutableListOf<dev.kuml.uml.UmlConstraint>()
    private var constraintCounter = 0

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
        collected += toFeature(usage = usage, typeId = typeId)
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
        collected += toFeature(usage = usage, typeId = typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a reference part-usage (`ref part tank : Tank`): aggregation in a BDD. */
    fun refPart(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    ): PartUsage {
        checkLen(name, typeId)
        val qn = "$parentId::$name"
        val usage =
            PartUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
                isReference = true,
            )
        collected += toFeature(usage = usage, typeId = typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /**
     * Declare a port-usage: `port inlet : Inlet`. [direction] is the drawn
     * direction (`in` / `out` / `inout`); [conjugated] marks a `~Inlet` port.
     */
    fun port(
        name: String,
        typeId: String,
        multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
        direction: PortDirection? = null,
        conjugated: Boolean = false,
    ): PortUsage {
        val qn = "$parentId::$name"
        val usage =
            PortUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = typeId,
                multiplicity = multiplicity,
                direction = direction,
                isConjugated = conjugated,
            )
        collected += toFeature(usage = usage, typeId = typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare a flow property inside a port definition: `in item fuel : Fuel`. */
    fun flowProperty(
        name: String,
        typeId: String,
        direction: PortDirection,
    ): FlowPropertyUsage {
        checkLen(name, typeId)
        val qn = "$parentId::$name"
        val usage =
            FlowPropertyUsage(id = qn, name = name, qualifiedName = qn, definitionId = typeId, direction = direction)
        collected += toFeature(usage = usage, typeId = typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Declare an action-usage owned by this definition: shown in the `actions` compartment of a BDD. */
    fun action(
        name: String,
        typeId: String,
    ): dev.kuml.sysml2.ActionUsage {
        checkLen(name, typeId)
        val qn = "$parentId::$name"
        val usage = dev.kuml.sysml2.ActionUsage(id = qn, name = name, qualifiedName = qn, definitionId = typeId)
        collected += toFeature(usage = usage, typeId = typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    private var flowCounter = 0

    /**
     * Declare an item flow: `flow of Fuel from tank.out to engine.in`. Endpoints
     * may be dotted paths relative to this definition (`engine.crank.out`) or
     * already qualified (`Owner::engine::in`). [via] names the carrying
     * connection (its simple name or full id); the flow is then drawn as a
     * mid-arrow on that connector.
     */
    fun flow(
        item: String,
        from: String,
        to: String,
        via: String? = null,
        name: String = "flow${++flowCounter}",
    ): FlowUsage {
        checkLen(name, item)
        val qn = "$parentId::$name"
        val viaId = via?.let { if (it.contains("::")) it else "$parentId::$it" }
        val usage =
            FlowUsage(
                id = qn,
                name = name,
                qualifiedName = qn,
                definitionId = item,
                sourceEndId = Sysml2PathResolver.toQualified(ownerId = parentId, dotted = from),
                targetEndId = Sysml2PathResolver.toQualified(ownerId = parentId, dotted = to),
                viaConnectionId = viaId,
            )
        collected += toFeature(usage = usage, typeId = item)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /** Overload taking the item type and the carrying connection as objects. */
    fun flow(
        item: Sysml2Definition,
        from: String,
        to: String,
        via: ConnectionUsage? = null,
        name: String = "flow${++flowCounter}",
    ): FlowUsage = flow(item = item.id, from = from, to = to, via = via?.id, name = name)

    private fun checkLen(vararg parts: String) {
        for (p in parts) {
            require(p.length <= Sysml2ModelBuilder.MAX_NAME_CHARS) {
                "name or id longer than ${Sysml2ModelBuilder.MAX_NAME_CHARS} characters"
            }
        }
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
                sourceEndId = Sysml2PathResolver.toQualified(ownerId = parentId, dotted = sourceEndId),
                targetEndId = Sysml2PathResolver.toQualified(ownerId = parentId, dotted = targetEndId),
            )
        collected += toFeature(usage = usage, typeId = typeId)
        modelBuilder.registerUsage(usage)
        return usage
    }

    /**
     * Declare an OCL invariant on the enclosing definition (V3.2.23).
     *
     * Only meaningful on definitions that carry a `constraints` field
     * (currently [PartDefinition] — the SysML 2 analog of a UML classifier).
     * Coexists with PAR [dev.kuml.sysml2.ConstraintDefinition] /
     * [dev.kuml.sysml2.constraint.Sysml2ConstraintChecker]: this is a classifier
     * invariant evaluated by `kuml-core-ocl`'s `OclValidator`, not a parametric
     * equation checked by the type-checker.
     *
     * @param name Constraint name, shown in violation messages.
     * @param body OCL expression body, e.g. `"self.mass->notEmpty()"`.
     */
    fun constraint(
        name: String,
        body: String,
    ): dev.kuml.uml.UmlConstraint {
        val constraintId = "${parentId}_inv_${++constraintCounter}"
        val c = dev.kuml.uml.UmlConstraint(id = constraintId, name = name, body = body)
        collectedConstraints += c
        return c
    }

    internal fun features(): List<dev.kuml.kerml.KermlFeature> = collected.toList()

    internal fun constraints(): List<dev.kuml.uml.UmlConstraint> = collectedConstraints.toList()

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
class BdDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
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
class IbdDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
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
class UcDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
    private val ids = mutableListOf<String>()
    private val associations = mutableListOf<UcAssociation>()
    private val includes = mutableListOf<UcInclude>()
    private val extends = mutableListOf<UcExtend>()
    private val subjects = mutableListOf<UcSubject>()
    private val actorGeneralizations = mutableListOf<UcActorGeneralization>()

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
        val assoc = associationById(actorId = actor.id, useCaseId = useCase.id)
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
    ): UcInclude = includeById(sourceId = source.id, targetId = target.id)

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
    ): UcExtend = extendById(sourceId = source.id, targetId = target.id)

    /** Id-only variant of the extend-relationship form — for forward refs. */
    fun extendById(
        sourceId: String,
        targetId: String,
    ): UcExtend {
        val ext = UcExtend(id = "extend:$sourceId::$targetId", sourceUseCaseId = sourceId, targetUseCaseId = targetId)
        extends += ext
        return ext
    }

    /**
     * C1: declare a system-boundary frame. Use cases added via `include(...)` inside
     * the block are drawn inside; actors stay outside. Id: `ucsubject:<sanitised name>`.
     */
    fun subject(
        name: String,
        block: UcSubjectScope.() -> Unit = {},
    ): UcSubject {
        val scope = UcSubjectScope().apply(block)
        return subjectById(id = "ucsubject:" + sanitizeFrameName(name), name = name, useCaseIds = scope.ids())
    }

    /** Id-only variant of [subject] (used by the DSL printer). */
    fun subjectById(
        id: String,
        name: String,
        useCaseIds: List<String>,
    ): UcSubject {
        val subject = UcSubject(id = id, name = name, useCaseIds = useCaseIds)
        subjects += subject
        return subject
    }

    /** C1: actor [specific] specialises actor [general] (hollow-triangle arrow). */
    fun generalize(
        specific: ActorDefinition,
        general: ActorDefinition,
    ): UcActorGeneralization = generalizeById(specificId = specific.id, generalId = general.id)

    /** Id-only variant of [generalize]. */
    fun generalizeById(
        specificId: String,
        generalId: String,
    ): UcActorGeneralization {
        val g = UcActorGeneralization(id = "generalization:$specificId::$generalId", specificId = specificId, generalId = generalId)
        actorGeneralizations += g
        return g
    }

    internal fun subjects(): List<UcSubject> = subjects.toList()

    internal fun actorGeneralizations(): List<UcActorGeneralization> = actorGeneralizations.toList()

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
class ReqDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
    private val ids = mutableListOf<String>()
    private val satisfies = mutableListOf<ReqSatisfy>()
    private val verifies = mutableListOf<ReqVerify>()
    private val derives = mutableListOf<ReqDerive>()
    private val containsEdges = mutableListOf<ReqContains>()
    private val refines = mutableListOf<ReqRefine>()
    private val traces = mutableListOf<ReqTrace>()
    private val copies = mutableListOf<ReqCopy>()
    private val groups = mutableListOf<RequirementGroup>()

    /** C1: `refine` edge — [source] refines the [requirement]. */
    fun refine(
        source: Sysml2Definition,
        requirement: RequirementDefinition,
    ): ReqRefine = refineById(sourceId = source.id, requirementId = requirement.id)

    /** Id-only variant of [refine]. */
    fun refineById(
        sourceId: String,
        requirementId: String,
    ): ReqRefine {
        val e = ReqRefine(id = "refine:$sourceId::$requirementId", sourceId = sourceId, requirementId = requirementId)
        refines += e
        return e
    }

    /** C1: free `trace` edge between two elements. */
    fun trace(
        source: Sysml2Definition,
        target: Sysml2Definition,
    ): ReqTrace = traceById(sourceId = source.id, targetId = target.id)

    /** Id-only variant of [trace]. */
    fun traceById(
        sourceId: String,
        targetId: String,
    ): ReqTrace {
        val e = ReqTrace(id = "trace:$sourceId::$targetId", sourceId = sourceId, targetId = targetId)
        traces += e
        return e
    }

    /** C1: `copy` edge — [source] copies the text of [target] (slave to master). */
    fun copy(
        source: RequirementDefinition,
        target: RequirementDefinition,
    ): ReqCopy = copyById(sourceRequirementId = source.id, targetRequirementId = target.id)

    /** Id-only variant of [copy]. */
    fun copyById(
        sourceRequirementId: String,
        targetRequirementId: String,
    ): ReqCopy {
        val e =
            ReqCopy(
                id = "copy:$sourceRequirementId::$targetRequirementId",
                sourceRequirementId = sourceRequirementId,
                targetRequirementId = targetRequirementId,
            )
        copies += e
        return e
    }

    /**
     * C1: requirement group (frame with tab). Members via `include(...)` inside the
     * block, nested groups via `group(...)`. Ids are deterministic: `group:<path>`.
     * Members are also added to the diagram's node list.
     */
    fun group(
        name: String,
        block: RequirementGroupScope.() -> Unit = {},
    ): RequirementGroup = registerGroup(parentPath = null, name = name, block = block)

    internal fun registerGroup(
        parentPath: String?,
        name: String,
        block: RequirementGroupScope.() -> Unit,
    ): RequirementGroup {
        val path = if (parentPath == null) name else "$parentPath/$name"
        val scope = RequirementGroupScope(owner = this, path = path).apply(block)
        val g = RequirementGroup(id = "group:$path", name = name, memberIds = scope.memberIds(), childGroupIds = scope.childIds())
        groups += g
        return g
    }

    /** Id-only variant of [group] (used by the DSL printer). */
    fun groupById(
        id: String,
        name: String,
        memberIds: List<String> = emptyList(),
        childGroupIds: List<String> = emptyList(),
    ): RequirementGroup {
        val g = RequirementGroup(id = id, name = name, memberIds = memberIds, childGroupIds = childGroupIds)
        groups += g
        return g
    }

    internal fun addNodeId(id: String) {
        if (id !in ids) ids += id
    }

    internal fun refines(): List<ReqRefine> = refines.toList()

    internal fun traces(): List<ReqTrace> = traces.toList()

    internal fun copies(): List<ReqCopy> = copies.toList()

    internal fun groups(): List<RequirementGroup> = groups.toList()

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
    ): ReqSatisfy = satisfyById(sourceId = source.id, requirementId = requirement.id)

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
    ): ReqVerify = verifyById(sourceId = source.id, requirementId = requirement.id)

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
    ): ReqDerive = deriveById(sourceRequirementId = source.id, targetRequirementId = target.id)

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
    ): ReqContains = containsById(parentRequirementId = parent.id, childRequirementId = child.id)

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
class StmDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
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
class ActDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
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
class SeqDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
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

/**
 * Scope for `parDiagram("…") { include(newton); include(vehicle) }` —
 * V2.0.12 Parametric Diagram, the closing entry of the 8/8 SysML 2
 * diagram-type series.
 *
 * Mirrors [BdDiagramBuilder] / [StmDiagramBuilder] / [ActDiagramBuilder] /
 * [SeqDiagramBuilder] (selects nodes only — no edges on the diagram).
 * Bindings are auto-included by the bridge from `Sysml2Model.usages`
 * whenever both endpoint ids resolve via longest-prefix-match to elements in
 * this builder's [ids] list. See [ParDiagram] KDoc for the rationale
 * (bindings ARE the constraint topology, not a diagram-only assertion).
 *
 * Includes BOTH constraint definitions and part definitions — parametric
 * diagrams routinely show the constrained parts so the constraint pins have
 * concrete attribute references to bind to.
 */
@Sysml2Dsl
class ParDiagramBuilder internal constructor(
    modelBuilder: Sysml2ModelBuilder,
) : Sysml2NoteBuilderBase(modelBuilder) {
    private val ids = mutableListOf<String>()

    /**
     * Add a [Sysml2Definition] (typically a [ConstraintDefinition] or a
     * [PartDefinition]) as a visible node in the PAR diagram.
     */
    fun include(definition: Sysml2Definition) {
        ids += definition.id
    }

    /** Add a definition by raw id — forward refs / id-only setups. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/** Guard argument of `transition(...)`: an expression (`String` overload) or free-text [Prose]. */
sealed interface Sysml2Guard {
    val text: String

    /** Free-text guard (documentation of intent; see [dev.kuml.sysml2.GuardKind.Prose]). */
    data class Prose(
        override val text: String,
    ) : Sysml2Guard
}

/** Scope of `subject("System") { include(useCase) }` in a `ucDiagram` (C1). */
@Sysml2Dsl
class UcSubjectScope internal constructor() {
    private val ids = mutableListOf<String>()

    /** Put a use case inside the system boundary. */
    fun include(useCase: UseCaseDefinition) {
        ids += useCase.id
    }

    /** Id-only variant of [include]. */
    fun includeById(id: String) {
        ids += id
    }

    internal fun ids(): List<String> = ids.toList()
}

/** Scope of `group("Name") { include(req); group("Sub") { … } }` in a `reqDiagram` (C1). */
@Sysml2Dsl
class RequirementGroupScope internal constructor(
    private val owner: ReqDiagramBuilder,
    private val path: String,
) {
    private val members = mutableListOf<String>()
    private val children = mutableListOf<String>()

    /** Add an element to the group (and to the diagram). */
    fun include(definition: Sysml2Definition) = includeById(definition.id)

    /** Id-only variant of [include]. */
    fun includeById(id: String) {
        members += id
        owner.addNodeId(id)
    }

    /** Nested group. */
    fun group(
        name: String,
        block: RequirementGroupScope.() -> Unit = {},
    ) {
        children += owner.registerGroup(parentPath = path, name = name, block = block).id
    }

    internal fun memberIds(): List<String> = members.toList()

    internal fun childIds(): List<String> = children.toList()
}

/** Replaces every character outside `[A-Za-z0-9_-]` with `_` — used for deterministic frame ids. */
internal fun sanitizeFrameName(name: String): String =
    name
        .map {
            if (it.isLetterOrDigit() ||
                it == '_' ||
                it == '-'
            ) {
                it
            } else {
                '_'
            }
        }.joinToString("")

/** C3 — builder for the block form of [Sysml2ModelBuilder.combinedFragment]. */
class CombinedFragmentBuilder internal constructor(
    private val modelBuilder: Sysml2ModelBuilder,
    private val fragmentId: String,
) {
    internal val operands: MutableList<CombinedFragmentOperand> = mutableListOf()

    /** Adds an operand covering rows [start]..[end]; the optional [block] declares fragments nested inside it. */
    fun operand(
        guard: String? = null,
        start: Int,
        end: Int,
        block: CombinedFragmentOperandBuilder.() -> Unit = {},
    ) {
        val index = operands.size
        operands += CombinedFragmentOperand(guard = guard, startSeqNo = start, endSeqNo = end)
        CombinedFragmentOperandBuilder(modelBuilder = modelBuilder, parentId = fragmentId, operandIndex = index).apply(block)
    }
}

/** C3 — scope inside one operand: fragments declared here become children of that operand. */
class CombinedFragmentOperandBuilder internal constructor(
    private val modelBuilder: Sysml2ModelBuilder,
    private val parentId: String,
    private val operandIndex: Int,
) {
    /** Nested combined fragment (block form). */
    fun combinedFragment(
        name: String,
        operator: CombinedFragmentOperator,
        id: String = "combinedFragment:$name",
        block: CombinedFragmentBuilder.() -> Unit,
    ): CombinedFragmentUsage =
        modelBuilder.combinedFragment(
            name = name,
            operator = operator,
            id = id,
            parentFragmentId = parentId,
            parentOperandIndex = operandIndex,
            block = block,
        )
}

/**
 * Endpoint id of the parameter pin [name] of this constraint, for use as a `bind(...)` endpoint
 * (`"<constraintId>::<name>"`). Throws when the constraint declares no such parameter, so a typo is
 * caught while the model is built instead of surfacing as a binding that silently ends at the box centre.
 */
fun ConstraintDefinition.pin(name: String): String {
    require(parameters.any { it.name == name }) {
        "Constraint '${this.name}' has no parameter '$name' (declared: ${parameters.joinToString { it.name }})."
    }
    return "$id::$name"
}
