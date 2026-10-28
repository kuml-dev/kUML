package dev.kuml.sysml2.dsl.print

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.sysml2.ACT_MAX_REGION_DEPTH
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
import dev.kuml.sysml2.CombinedFragmentUsage
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.ConnectionUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ConstraintParameter
import dev.kuml.sysml2.ConstraintParameterDirection
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
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.RequirementUsage
import dev.kuml.sysml2.STM_MAX_DEPTH
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StateUsage
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.TestCaseDefinition
import dev.kuml.sysml2.TimeObservationUsage
import dev.kuml.sysml2.TransitionTimer
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.UseCaseUsage
import dev.kuml.sysml2.durationLiteral

/**
 * Pretty-prints a [Sysml2Model] as a `*.kuml.kts` source string.
 *
 * Mirrors [dev.kuml.uml.dsl.print.UmlModelDslPrinter] and
 * [dev.kuml.c4.dsl.print.C4DslPrinter], but the SysML 2 DSL is architecturally
 * closer to the UML compiler dialect than to C4: every
 * [dev.kuml.sysml2.dsl.Sysml2ModelBuilder] definition function (`partDef`,
 * `attributeDef`, `portDef`, …) takes an explicit `id: String = name`
 * parameter, and every diagram / edge builder offers an `…ById(...)` string
 * overload — so ids round-trip exactly and cross-references are almost
 * always plain string ids, never object references.
 *
 * ## The three object-reference exceptions
 *
 * Exactly three DSL calls require an actual object, with no `…ById` sibling:
 * [dev.kuml.sysml2.dsl.Sysml2ModelBuilder.ibd] (`owner: PartDefinition`),
 * [dev.kuml.sysml2.dsl.Sysml2ModelBuilder.executionSpec]
 * (`lifeline: LifelineDefinition`), and
 * [dev.kuml.sysml2.dsl.Sysml2ModelBuilder.actionDef] (optional
 * `partition: ActivityPartitionDefinition?`). To keep those three always
 * resolvable, this printer binds **every** [PartDefinition],
 * [LifelineDefinition], and [ActivityPartitionDefinition] to a `val` —
 * printed *before* every other definition, specifically so an
 * [ActivityPartitionDefinition] referenced by an [ActionDefinition]'s
 * `partition = …` argument is always already declared, regardless of the
 * two definitions' original relative order in the model. Every other
 * definition type is printed unassigned, referenced purely by its `id`
 * string elsewhere.
 *
 * ## Nested usages vs. top-level ("Pattern A") usages
 *
 * [Sysml2Model.usages] is a flat mix of two shapes:
 *  - **Nested feature usages** ([AttributeUsage] / [PartUsage] / [PortUsage] /
 *    [ConnectionUsage]) created inside a definition's body via
 *    `attribute(...)` / `part(...)` / `port(...)` / `connect(...)`. These are
 *    found via each [Sysml2Definition]'s own `features: List<KermlFeature>`
 *    and printed *inside* that definition's block, in feature order.
 *  - **Top-level usages** created directly on the model builder — transitions,
 *    control/object flows, messages, bindings, combined fragments, execution
 *    specs. Printed as `…ById(...)` calls after all definitions. A usage is
 *    classified as "top-level" whenever its id does not appear in any
 *    definition's `features` list.
 *
 * ## Known non-round-tripping data
 *
 * - [AttributeUsage.defaultExpression] — stored as
 *   [dev.kuml.sysml2.units.UnitValue.toSpecForm]'s raw string
 *   (`"1500.0[kg]"`); there is no public parser back from that string to a
 *   [dev.kuml.sysml2.units.UnitValue], so `default = …` cannot be
 *   reconstructed. Flagged with a `// TODO` instead of guessed at. This is the
 *   one remaining gap after Wave B (ADR-0017) — see CHANGELOG for the three
 *   DSL-builder gaps closed in that wave (`isAbstract` on
 *   AttributeDefinition/PortDefinition/ConnectionDefinition, `specializesId`
 *   on all 12 definition types, and DSL constructors for the 8
 *   previously-missing `Sysml2Usage` subtypes). `IncludeUsage`/`ExtendUsage`
 *   remain intentionally unsupported by any top-level usage constructor —
 *   the diagram-level [UcDiagram] edges are the DSL-supported path for that
 *   relationship — and any instance of these (or any other still-unsupported
 *   `Sysml2Usage` subtype) found as a top-level usage is flagged with a
 *   `// TODO` rather than silently dropped.
 *
 * All 8 diagram kinds ([BdDiagram], [IbdDiagram], [UcDiagram], [ReqDiagram],
 * [StmDiagram], [dev.kuml.sysml2.ActDiagram], [SeqDiagram], [ParDiagram])
 * round-trip **exactly** — every one of them selects elements purely via
 * `includeById(...)` (plus, for [UcDiagram]/[ReqDiagram], their own
 * `…ById(...)` edge builders), so no builder-side approximation is needed.
 */
public object Sysml2DslPrinter {
    public fun print(model: Sysml2Model): String {
        val ctx = Ctx()
        val sb = StringBuilder()
        if (needsKermlMultiplicityImport(model)) {
            sb.appendLine("import dev.kuml.kerml.KermlMultiplicity")
        }
        durationUnitImports(model).forEach { sb.appendLine("import kotlin.time.Duration.Companion.$it") }
        sb.appendLine("sysml2Model(${quote(model.name)}) {")

        val (special, rest) =
            model.definitions.partition {
                it is PartDefinition ||
                    it is LifelineDefinition ||
                    it is ActivityPartitionDefinition ||
                    it is InterruptibleRegionDefinition
            }
        orderRegionsParentFirst(special).forEach { printDefinition(sb = sb, def = it, model = model, ctx = ctx, bindVar = true) }
        orderStatesParentFirst(rest).forEach { printDefinition(sb = sb, def = it, model = model, ctx = ctx, bindVar = false) }

        val consumedFeatureIds =
            model.definitions
                .flatMap { it.features }
                .map { it.id }
                .toSet()
        val topLevelUsages = model.usages.filter { it.id !in consumedFeatureIds }
        topLevelUsages.forEach { printTopLevelUsage(sb = sb, usage = it, ctx = ctx) }

        printComments(sb = sb, model = model)

        model.diagrams.forEach { printDiagram(sb = sb, d = it, ctx = ctx) }

        sb.appendLine("}")
        return sb.toString()
    }

    /**
     * Reorders the [InterruptibleRegionDefinition]s of [defs] so enclosing regions precede nested
     * ones (the DSL references the parent through its bound variable). Cycle-safe; other
     * definitions keep their slots.
     */
    private fun orderRegionsParentFirst(defs: List<Sysml2Definition>): List<Sysml2Definition> {
        val regions = defs.filterIsInstance<InterruptibleRegionDefinition>()
        if (regions.none { it.parentRegionId != null }) return defs
        val byId = LinkedHashMap<String, InterruptibleRegionDefinition>()
        regions.forEach { if (it.id !in byId) byId[it.id] = it }
        val emitted = ArrayList<InterruptibleRegionDefinition>()
        val emittedIds = HashSet<String>()
        for (r in regions) {
            val chain = ArrayList<InterruptibleRegionDefinition>()
            val seen = HashSet<String>()
            var cur: InterruptibleRegionDefinition? = r
            while (cur != null && seen.add(cur.id) && cur.id !in emittedIds && chain.size <= ACT_MAX_REGION_DEPTH + 1) {
                chain += cur
                cur = cur.parentRegionId?.let { byId[it] }
            }
            for (c in chain.asReversed()) if (emittedIds.add(c.id)) emitted += c
        }
        val remaining = regions.filter { r -> emitted.none { it === r } }
        val sorted = ArrayDeque<InterruptibleRegionDefinition>(emitted + remaining)
        return defs.map { if (it is InterruptibleRegionDefinition) sorted.removeFirst() else it }
    }

    /**
     * Reorders the [StateDefinition]s of [defs] so parents precede children
     * (cosmetic: the DSL references parents by id). Iterative and cycle-safe;
     * non-state definitions keep their slots.
     */
    private fun orderStatesParentFirst(defs: List<Sysml2Definition>): List<Sysml2Definition> {
        val states = defs.filterIsInstance<StateDefinition>()
        if (states.none { it.parentStateId != null }) return defs
        val byId = LinkedHashMap<String, StateDefinition>()
        states.forEach { if (it.id !in byId) byId[it.id] = it }
        val emitted = ArrayList<StateDefinition>()
        val emittedIds = HashSet<String>()
        for (st in states) {
            val chain = ArrayList<StateDefinition>()
            val seen = HashSet<String>()
            var cur: StateDefinition? = st
            while (cur != null && seen.add(cur.id) && cur.id !in emittedIds && chain.size <= STM_MAX_DEPTH + 1) {
                chain += cur
                cur = cur.parentStateId?.let { byId[it] }
            }
            for (c in chain.asReversed()) {
                if (emittedIds.add(c.id)) emitted += c
            }
            if (emitted.none { it === st } && emittedIds.add(st.id)) emitted += st
        }
        // Duplicate ids (malformed models) must still be printed exactly once each.
        val remaining = states.filter { s -> emitted.none { it === s } }
        val sorted = ArrayDeque<StateDefinition>(emitted + remaining)
        return defs.map { if (it is StateDefinition) sorted.removeFirst() else it }
    }

    // ── printer state ──────────────────────────────────────────────────────

    private class Ctx {
        val partDefVars: MutableMap<String, String> = mutableMapOf()
        val lifelineDefVars: MutableMap<String, String> = mutableMapOf()
        val partitionDefVars: MutableMap<String, String> = mutableMapOf()
        val regionDefVars: MutableMap<String, String> = mutableMapOf()
        private var counter = 0

        fun freshVar(): String = "s2v${counter++}"
    }

    // ── definitions ────────────────────────────────────────────────────────

    private fun printDefinition(
        sb: StringBuilder,
        def: Sysml2Definition,
        model: Sysml2Model,
        ctx: Ctx,
        bindVar: Boolean,
    ) {
        val varName =
            if (bindVar) {
                val v = ctx.freshVar()
                when (def) {
                    is PartDefinition -> ctx.partDefVars[def.id] = v
                    is LifelineDefinition -> ctx.lifelineDefVars[def.id] = v
                    is ActivityPartitionDefinition -> ctx.partitionDefVars[def.id] = v
                    is InterruptibleRegionDefinition -> ctx.regionDefVars[def.id] = v
                    else -> Unit
                }
                v
            } else {
                null
            }

        val todos = mutableListOf<String>()
        val args = mutableListOf("name = ${quote(def.name)}", "id = ${quote(def.id)}")

        when (def) {
            is PartDefinition -> {
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "partDef", args = args, todos = todos)
                printDefCall(sb = sb, fnName = "partDef", varName = varName, args = args, todos = todos) { body ->
                    printNestedFeatures(sb = body, def = def, model = model)
                    printPartConstraints(sb = body, def = def)
                }
            }
            is AttributeDefinition -> {
                if (def.isAbstract) args += "isAbstract = true"
                def.unit?.let { args += "unitSymbol = ${quote(it)}" }
                appendSpecializesArg(def = def, builderName = "attributeDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "attributeDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is PortDefinition -> {
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "portDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "portDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is ConnectionDefinition -> {
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "connectionDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "connectionDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is ActorDefinition -> {
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "actorDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "actorDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is UseCaseDefinition -> {
                if (def.isAbstract) args += "isAbstract = true"
                if (def.description.isNotEmpty()) args += "description = ${quote(def.description)}"
                appendSpecializesArg(def = def, builderName = "useCaseDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "useCaseDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is RequirementDefinition -> {
                if (def.reqId.isNotEmpty()) args += "reqId = ${quote(def.reqId)}"
                if (def.text.isNotEmpty()) args += "text = ${quote(def.text)}"
                def.subject?.let { args += "subject = ${quote(it)}" }
                def.priority?.let { args += "priority = RequirementPriority.${it.name}" }
                if (def.source.isNotEmpty()) args += "source = ${quote(def.source)}"
                def.status?.let { args += "status = RequirementStatus.${it.name}" }
                if (def.isAssumption) args += "isAssumption = true"
                def.subjectRef?.let { args += "subjectRef = ${quote(it)}" }
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "requirementDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "requirementDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is TestCaseDefinition -> {
                if (def.text.isNotEmpty()) args += "text = ${quote(def.text)}"
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "testCaseDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "testCaseDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is StateDefinition -> {
                val kind = def.effectiveKind
                val pseudoFn =
                    when (kind) {
                        StateKind.Choice -> "choice"
                        StateKind.Junction -> "junction"
                        StateKind.Fork -> "fork"
                        StateKind.Join -> "join"
                        StateKind.ShallowHistory, StateKind.DeepHistory -> "history"
                        else -> null
                    }
                if (kind == StateKind.DeepHistory) args += "deep = true"
                if (pseudoFn == null) {
                    if (def.isInitial) args += "isInitial = true"
                    if (def.isFinal) args += "isFinal = true"
                    def.entryAction?.let { args += "entryAction = ${quote(it)}" }
                    def.exitAction?.let { args += "exitAction = ${quote(it)}" }
                    def.doAction?.let { args += "doAction = ${quote(it)}" }
                    if (def.isAbstract) args += "isAbstract = true"
                    appendSpecializesArg(def = def, builderName = "stateDef", args = args, todos = todos)
                } else if (def.entryAction != null || def.exitAction != null || def.doAction != null || def.features.isNotEmpty()) {
                    todos +=
                        "Pseudo-state ${def.name} (id = ${def.id}) carries actions or features, which the DSL cannot express for it — dropped."
                }
                def.parentStateId?.let { args += "parentId = ${quote(it)}" }
                def.regionId?.let { args += "regionId = ${quote(it)}" }
                def.parentStateId?.let { sb.appendLine("    // in ${sanitizeForComment(it)}") }
                printDefCall(
                    sb = sb,
                    fnName = pseudoFn ?: "stateDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> if (pseudoFn == null) printNestedFeatures(sb = body, def = def, model = model) }
                for (r in def.regions) {
                    val rargs = mutableListOf("ownerId = ${quote(def.id)}")
                    r.name?.let { rargs += "name = ${quote(it)}" }
                    rargs += "id = ${quote(r.id)}"
                    sb.appendLine("    regionById(${rargs.joinToString(", ")})")
                }
            }
            is ActionDefinition -> {
                def.action?.let { args += "action = ${quote(it)}" }
                if (def.kind != ActivityNodeKind.Action) args += "kind = ActivityNodeKind.${def.kind.name}"
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "actionDef", args = args, todos = todos)
                if (def.partitionId != null) {
                    val partitionVar = ctx.partitionDefVars[def.partitionId]
                    if (partitionVar != null) {
                        args += "partition = $partitionVar"
                    } else {
                        todos +=
                            "ActionDefinition ${def.name} (id = ${def.id}) references partitionId '${def.partitionId}', " +
                            "which does not resolve to a printed ActivityPartitionDefinition — partition assignment lost."
                    }
                }
                if (def.pins.isNotEmpty()) args += "pins = listOf(${def.pins.joinToString(", ") { pinExpr(it) }})"
                def.signal?.let { args += "signal = ${quote(it)}" }
                def.timeExpression?.let { args += "timeExpression = ${quote(it)}" }
                def.calledActivityId?.let { args += "calledActivityId = ${quote(it)}" }
                if (def.showName) args += "showName = true"
                if (def.interruptibleRegionId != null) {
                    val regionVar = ctx.regionDefVars[def.interruptibleRegionId]
                    if (regionVar != null) {
                        args += "region = $regionVar"
                    } else {
                        todos +=
                            "ActionDefinition ${def.name} (id = ${def.id}) references interruptibleRegionId " +
                            "'${def.interruptibleRegionId}', which does not resolve to a printed " +
                            "InterruptibleRegionDefinition — region assignment lost."
                    }
                }
                printDefCall(
                    sb = sb,
                    fnName = "actionDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is InterruptibleRegionDefinition -> {
                if (!def.showLabel) args += "showLabel = false"
                if (def.parentRegionId != null) {
                    val parentVar = ctx.regionDefVars[def.parentRegionId]
                    if (parentVar != null) {
                        args += "parent = $parentVar"
                    } else {
                        todos +=
                            "InterruptibleRegionDefinition ${def.name} (id = ${def.id}) references parentRegionId " +
                            "'${def.parentRegionId}', which does not resolve to a printed region — nesting lost."
                    }
                }
                printDefCall(
                    sb = sb,
                    fnName = "interruptibleRegion",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { _ -> }
            }
            is ActivityPartitionDefinition -> {
                def.represents?.let { args += "represents = ${quote(it)}" }
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "activityPartition", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "activityPartition",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is LifelineDefinition -> {
                def.represents?.let { args += "represents = ${quote(it)}" }
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "lifelineDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "lifelineDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
            is ConstraintDefinition -> {
                if (def.expression.isNotEmpty()) args += "expression = ${quote(def.expression)}"
                if (def.parameters.isNotEmpty()) args += "parameters = listOf(${def.parameters.joinToString(", ") { paramExpr(it) }})"
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "constraintDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "constraintDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
            }
        }
    }

    private fun printDefCall(
        sb: StringBuilder,
        fnName: String,
        varName: String?,
        args: List<String>,
        todos: List<String>,
        buildBody: (StringBuilder) -> Unit,
    ) {
        todos.forEach { sb.appendLine("    // TODO: ${sanitizeForComment(it)}") }
        val body = StringBuilder()
        buildBody(body)
        val prefix = if (varName != null) "    val $varName = " else "    "
        if (body.isBlank()) {
            sb.appendLine("$prefix$fnName(${args.joinToString(", ")})")
        } else {
            sb.appendLine("$prefix$fnName(${args.joinToString(", ")}) {")
            sb.append(body)
            sb.appendLine("    }")
        }
    }

    /**
     * Emits `specializesId = "..."` into [args] when [def]'s `specializations`
     * list is exactly one self-specific [dev.kuml.kerml.KermlSpecialization]
     * (the only shape any of the 12 `…Def(specializesId = ...)` builders can
     * produce), otherwise appends a `// TODO` explanation to [todos].
     *
     * More than one specialization, or a specialization whose `specificId`
     * isn't [def]'s own id, is malformed-relative-to-the-DSL data — it can
     * only arise from hand-crafted/`.copy(...)`-mutated models, never from
     * normal DSL use (every `…Def(specializesId = ...)` builder call
     * constructs exactly one self-specific entry). Treated the same
     * defensive way the printer already treats a dangling [IbdDiagram] owner
     * id or a dangling [ExecutionSpecificationUsage] lifeline id: a `// TODO`
     * fallback rather than a crash.
     */
    private fun appendSpecializesArg(
        def: Sysml2Definition,
        builderName: String,
        args: MutableList<String>,
        todos: MutableList<String>,
    ) {
        if (def.specializations.isEmpty()) return
        val onlySelf = def.specializations.singleOrNull { it.specificId == def.id }
        if (onlySelf != null && def.specializations.size == 1) {
            args += "specializesId = ${quote(onlySelf.generalId)}"
        } else {
            todos +=
                "${def::class.simpleName} ${def.name} (id = ${def.id}) has ${def.specializations.size} specialization(s) that " +
                "cannot be fully reconstructed via $builderName(specializesId = ...) (only a single, self-specific " +
                "specialization is supported) — specialization data is lost."
        }
    }

    private fun pinExpr(p: ActionPin): String {
        val args = mutableListOf("name = ${quote(p.name)}")
        p.typeId?.let { args += "typeId = ${quote(it)}" }
        if (p.direction != PinDirection.Input) args += "direction = PinDirection.${p.direction.name}"
        if (p.side != PinSide.Auto) args += "side = PinSide.${p.side.name}"
        return "ActionPin(${args.joinToString(", ")})"
    }

    private fun paramExpr(p: ConstraintParameter): String {
        val args = mutableListOf("name = ${quote(p.name)}")
        p.typeId?.let { args += "typeId = ${quote(it)}" }
        if (p.direction != ConstraintParameterDirection.Inout) args += "direction = ConstraintParameterDirection.${p.direction.name}"
        return "ConstraintParameter(${args.joinToString(", ")})"
    }

    // ── nested features (attribute / part / port / connect / constraint) ──

    private fun printNestedFeatures(
        sb: StringBuilder,
        def: Sysml2Definition,
        model: Sysml2Model,
    ) {
        val indent = "        "
        for (feature in def.features) {
            when (val usage = model.usages.find { it.id == feature.id }) {
                is AttributeUsage -> {
                    val args = mutableListOf("name = ${quote(usage.name)}", "typeId = ${quote(usage.definitionId)}")
                    multiplicityExpr(usage.multiplicity)?.let { args += "multiplicity = $it" }
                    sb.appendLine("$indent attribute(${args.joinToString(", ")})")
                    if (usage.defaultExpression != null) {
                        sb.appendLine(
                            "$indent // TODO: AttributeUsage '${sanitizeForComment(usage.name)}' had defaultExpression = " +
                                "${quote(usage.defaultExpression)} — no public UnitValue parser exists to reconstruct a " +
                                "`default = ...` argument from the stored spec-form string; the default value is lost.",
                        )
                    }
                }
                is PartUsage -> {
                    val args = mutableListOf("name = ${quote(usage.name)}", "typeId = ${quote(usage.definitionId)}")
                    multiplicityExpr(usage.multiplicity)?.let { args += "multiplicity = $it" }
                    sb.appendLine("$indent ${if (usage.isReference) "refPart" else "part"}(${args.joinToString(", ")})")
                }
                is PortUsage -> {
                    val args = mutableListOf("name = ${quote(usage.name)}", "typeId = ${quote(usage.definitionId)}")
                    multiplicityExpr(usage.multiplicity)?.let { args += "multiplicity = $it" }
                    usage.direction?.let { args += "direction = PortDirection.${it.name}" }
                    if (usage.isConjugated) args += "conjugated = true"
                    sb.appendLine("$indent port(${args.joinToString(", ")})")
                }
                is FlowPropertyUsage -> {
                    val args =
                        mutableListOf(
                            "name = ${quote(usage.name)}",
                            "typeId = ${quote(usage.definitionId)}",
                            "direction = PortDirection.${usage.direction.name}",
                        )
                    sb.appendLine("$indent flowProperty(${args.joinToString(", ")})")
                }
                is ActionUsage -> {
                    sb.appendLine("$indent action(name = ${quote(usage.name)}, typeId = ${quote(usage.definitionId)})")
                }
                is FlowUsage -> {
                    val args =
                        mutableListOf(
                            "item = ${quote(usage.definitionId)}",
                            "from = ${quote(usage.sourceEndId)}",
                            "to = ${quote(usage.targetEndId)}",
                        )
                    usage.viaConnectionId?.let { args += "via = ${quote(it)}" }
                    args += "name = ${quote(usage.name)}"
                    sb.appendLine("$indent flow(${args.joinToString(", ")})")
                }
                is ConnectionUsage -> {
                    val args =
                        mutableListOf(
                            "name = ${quote(usage.name)}",
                            "typeId = ${quote(usage.definitionId)}",
                            "sourceEndId = ${quote(usage.sourceEndId)}",
                            "targetEndId = ${quote(usage.targetEndId)}",
                        )
                    multiplicityExpr(usage.multiplicity)?.let { args += "multiplicity = $it" }
                    sb.appendLine("$indent connect(${args.joinToString(", ")})")
                }
                else -> {
                    sb.appendLine(
                        "$indent // TODO: feature '${sanitizeForComment(feature.name)}' (id = ${quote(feature.id)}) could " +
                            "not be resolved to a known nested-usage type (AttributeUsage/PartUsage/PortUsage/" +
                            "ConnectionUsage) in model.usages — feature dropped.",
                    )
                }
            }
        }
    }

    private fun printPartConstraints(
        sb: StringBuilder,
        def: PartDefinition,
    ) {
        def.constraints.forEach { c ->
            sb.appendLine("        constraint(name = ${quote(c.name)}, body = ${quote(c.body)})")
        }
    }

    // ── top-level ("Pattern A") usages ──────────────────────────────────────

    private fun printTopLevelUsage(
        sb: StringBuilder,
        usage: Sysml2Usage,
        ctx: Ctx,
    ) {
        when (usage) {
            is TransitionUsage -> {
                val args =
                    mutableListOf(
                        "name = ${quote(usage.name)}",
                        "sourceStateId = ${quote(usage.sourceStateId)}",
                        "targetStateId = ${quote(usage.targetStateId)}",
                    )
                usage.trigger?.let { args += "trigger = ${quote(it)}" }
                usage.guard?.let {
                    args += if (usage.guardKind == GuardKind.Prose) "guard = prose(text = ${quote(it)})" else "guard = ${quote(it)}"
                }
                usage.effect?.let { args += "effect = ${quote(it)}" }
                usage.timer?.let {
                    args +=
                        when (it) {
                            is TransitionTimer.After -> "timer = after(duration = ${durationLiteral(it.duration)})"
                            is TransitionTimer.At -> "timer = at(iso = ${quote(it.instant.toString())})"
                        }
                }
                args += "id = ${quote(usage.id)}"
                sb.appendLine("    transitionById(${args.joinToString(", ")})")
            }
            is ControlFlowUsage -> {
                val args =
                    mutableListOf(
                        "name = ${quote(usage.name)}",
                        "sourceNodeId = ${quote(usage.sourceNodeId)}",
                        "targetNodeId = ${quote(usage.targetNodeId)}",
                    )
                usage.guard?.let { args += "guard = ${quote(it)}" }
                args += "id = ${quote(usage.id)}"
                if (usage.interrupts) args += "interrupts = true"
                sb.appendLine("    controlFlowById(${args.joinToString(", ")})")
            }
            is ObjectFlowUsage -> {
                val args =
                    mutableListOf(
                        "name = ${quote(usage.name)}",
                        "sourceNodeId = ${quote(usage.sourceNodeId)}",
                        "targetNodeId = ${quote(usage.targetNodeId)}",
                    )
                usage.objectType?.let { args += "objectType = ${quote(it)}" }
                args += "id = ${quote(usage.id)}"
                if (usage.interrupts) args += "interrupts = true"
                usage.sourcePin?.let { args += "sourcePin = ${quote(it)}" }
                usage.targetPin?.let { args += "targetPin = ${quote(it)}" }
                sb.appendLine("    objectFlowById(${args.joinToString(", ")})")
            }
            is ExceptionHandlerUsage -> {
                val args =
                    mutableListOf(
                        "protectedNodeId = ${quote(usage.protectedNodeId)}",
                        "handlerNodeId = ${quote(usage.handlerNodeId)}",
                        "type = ${quote(usage.exceptionType)}",
                    )
                usage.handlerPin?.let { args += "handlerPin = ${quote(it)}" }
                args += "id = ${quote(usage.id)}"
                sb.appendLine("    exceptionHandlerById(${args.joinToString(", ")})")
            }
            is MessageUsage -> {
                val args =
                    mutableListOf(
                        "label = ${quote(usage.messageLabel)}",
                        "sourceLifelineId = ${quote(usage.sourceLifelineId)}",
                        "targetLifelineId = ${quote(usage.targetLifelineId)}",
                        "seqNo = ${usage.seqNo}",
                    )
                if (usage.kind != MessageKind.Sync) args += "kind = MessageKind.${usage.kind.name}"
                args += "id = ${quote(usage.id)}"
                if (usage.name != usage.messageLabel) args += "name = ${quote(usage.name)}"
                sb.appendLine("    messageById(${args.joinToString(", ")})")
            }
            is BindingConnectorUsage -> {
                val args =
                    mutableListOf(
                        "name = ${quote(usage.name)}",
                        "source = ${quote(usage.sourceEndId)}",
                        "target = ${quote(usage.targetEndId)}",
                        "id = ${quote(usage.id)}",
                    )
                sb.appendLine("    bind(${args.joinToString(", ")})")
            }
            is CombinedFragmentUsage -> {
                val operandsExpr =
                    usage.operands.joinToString(", ") { op ->
                        val guardArg = if (op.guard != null) "guard = ${quote(op.guard)}, " else ""
                        "CombinedFragmentOperand($guardArg" + "startSeqNo = ${op.startSeqNo}, endSeqNo = ${op.endSeqNo})"
                    }
                val parentArgs =
                    if (usage.parentFragmentId != null) {
                        ", parentFragmentId = ${quote(usage.parentFragmentId)}" +
                            (usage.parentOperandIndex?.let { ", parentOperandIndex = $it" } ?: "")
                    } else {
                        ""
                    }
                sb.appendLine(
                    "    combinedFragment(name = ${quote(usage.name)}, operator = " +
                        "CombinedFragmentOperator.${usage.operator.name}, operands = listOf($operandsExpr), " +
                        "id = ${quote(usage.id)}$parentArgs)",
                )
            }
            is InteractionUseUsage -> {
                val ids = usage.lifelineIds.joinToString(", ") { quote(it) }
                sb.appendLine(
                    "    interactionUseById(name = ${quote(usage.name)}, refersTo = ${quote(usage.refersTo)}, " +
                        "lifelineIds = listOf($ids), seqNo = ${usage.seqNo}, id = ${quote(usage.id)})",
                )
            }
            is DurationConstraintUsage ->
                sb.appendLine(
                    "    durationConstraintById(lifelineId = ${quote(usage.lifelineId)}, from = ${usage.fromSeqNo}, " +
                        "to = ${usage.toSeqNo}, expression = ${quote(usage.expression)}, id = ${quote(usage.id)}" +
                        (if (usage.name != usage.expression) ", name = ${quote(usage.name)}" else "") + ")",
                )
            is TimeObservationUsage ->
                sb.appendLine(
                    "    timeObservationById(lifelineId = ${quote(usage.lifelineId)}, seqNo = ${usage.seqNo}, " +
                        "name = ${quote(usage.name)}, id = ${quote(usage.id)})",
                )
            is ExecutionSpecificationUsage -> {
                val lifelineVar = ctx.lifelineDefVars[usage.lifelineId]
                if (lifelineVar == null) {
                    sb.appendLine(
                        "    // TODO: ExecutionSpecificationUsage ${quote(usage.id)} not serialized — lifeline id " +
                            "'${sanitizeForComment(usage.lifelineId)}' does not resolve to a printed LifelineDefinition.",
                    )
                } else {
                    sb.appendLine(
                        "    executionSpec(name = ${quote(usage.name)}, lifeline = $lifelineVar, " +
                            "startSeqNo = ${usage.startSeqNo}, endSeqNo = ${usage.endSeqNo}, id = ${quote(usage.id)})",
                    )
                }
            }
            is AllocationUsage -> {
                sb.appendLine(
                    "    allocate(from = ${quote(usage.sourceId)}, to = ${quote(usage.targetId)}, " +
                        "name = ${quote(usage.name)}, id = ${quote(usage.id)})",
                )
            }
            is ActorUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "actorUsageById", usage = usage)
            is UseCaseUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "useCaseUsageById", usage = usage)
            is RequirementUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "requirementUsageById", usage = usage)
            is StateUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "stateUsageById", usage = usage)
            is ActionUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "actionUsageById", usage = usage)
            is ActivityPartitionUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "activityPartitionUsageById", usage = usage)
            is LifelineUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "lifelineUsageById", usage = usage)
            is ConstraintUsage -> printSimpleTopLevelUsage(sb = sb, fnName = "constraintUsageById", usage = usage)
            else -> {
                sb.appendLine(
                    "    // TODO: Sysml2Usage of type '${usage::class.simpleName}' (id = ${quote(usage.id)}) cannot be " +
                        "created via the current SysML 2 DSL — no matching …ById(...) builder function exists.",
                )
            }
        }
    }

    // ── comments ───────────────────────────────────────────────────────────

    /**
     * Model-level `comment(text, anchors…, id = …)`. Ids are printed explicitly so
     * the round trip keeps them stable; anchors are printed as id strings (no
     * handle mapping needed).
     */
    private fun printComments(
        sb: StringBuilder,
        model: Sysml2Model,
    ) {
        for (c in model.comments) {
            val anchors =
                model.commentLinks
                    .filter { it.link.noteId == c.id }
                    .joinToString("") { ", ${quote(it.link.targetId)}" }
            sb.appendLine("    comment(${quote(c.note.body)}$anchors, id = ${quote(c.id)})")
        }
    }

    // ── diagrams ───────────────────────────────────────────────────────────

    private fun printDiagram(
        sb: StringBuilder,
        d: Sysml2Diagram,
        ctx: Ctx,
    ) {
        val start = sb.length
        printDiagramBody(sb = sb, d = d, ctx = ctx)
        printLayoutInto(sb = sb, start = start, spec = d.layout, diagramName = d.name)
    }

    /**
     * Wave C3 — inserts a `layout { }` block (id form) just before the closing brace of the diagram that
     * was printed at [start]; a diagram printed without a block (`fn("name")`) is expanded into a block.
     */
    private fun printLayoutInto(
        sb: StringBuilder,
        start: Int,
        spec: dev.kuml.sysml2.Sysml2LayoutSpec?,
        diagramName: String,
    ) {
        if (spec == null || spec.isEmpty) return
        val text = sb.substring(start)
        if (text.startsWith("    //")) return // diagram was not serialized (TODO marker)
        val block = StringBuilder()
        block.appendLine("        layout {")
        spec.direction?.let { block.appendLine("            direction = $it") }
        spec.wrapColumns?.let { block.appendLine("            wrapColumns = $it") }
        spec.sameLayer.forEach { g -> block.appendLine("            sameLayerById(${g.joinToString(", ") { quote(it) }})") }
        if (spec.first.isNotEmpty()) block.appendLine("            firstById(${spec.first.joinToString(", ") { quote(it) }})")
        if (spec.last.isNotEmpty()) block.appendLine("            lastById(${spec.last.joinToString(", ") { quote(it) }})")
        block.appendLine("        }")
        val trimmed = text.trimEnd('\n')
        val newText =
            if (trimmed.endsWith("{")) {
                trimmed + "\n" + block + "    }\n"
            } else if (trimmed.lines().last().trim() == "}") {
                trimmed.removeSuffix("    }").trimEnd(' ') + block + "    }\n"
            } else {
                // single-line form `fn("name")` or `fn("name", owner = x)`: expand into a block
                trimmed + " {\n" + block + "    }\n"
            }
        sb.setLength(start)
        sb.append(newText)
    }

    private fun printDiagramBody(
        sb: StringBuilder,
        d: Sysml2Diagram,
        ctx: Ctx,
    ) {
        when (d) {
            is BdDiagram -> printSimpleIdDiagram(sb = sb, fnName = "bdd", name = d.name, elementIds = d.elementIds, noteIds = d.noteIds)
            is IbdDiagram -> printIbdDiagram(sb = sb, d = d, ctx = ctx)
            is UcDiagram -> printUcDiagram(sb = sb, d = d)
            is ReqDiagram -> printReqDiagram(sb = sb, d = d)
            is StmDiagram ->
                printSimpleIdDiagram(
                    sb = sb,
                    fnName = "stmDiagram",
                    name = d.name,
                    elementIds = d.elementIds,
                    noteIds = d.noteIds,
                )
            is ActDiagram ->
                printSimpleIdDiagram(
                    sb = sb,
                    fnName = "actDiagram",
                    name = d.name,
                    elementIds = d.elementIds,
                    noteIds = d.noteIds,
                )
            is SeqDiagram ->
                printSimpleIdDiagram(
                    sb = sb,
                    fnName = "seqDiagram",
                    name = d.name,
                    elementIds = d.elementIds,
                    noteIds = d.noteIds,
                )
            is ParDiagram ->
                printSimpleIdDiagram(
                    sb = sb,
                    fnName = "parDiagram",
                    name = d.name,
                    elementIds = d.elementIds,
                    noteIds = d.noteIds,
                )
        }
    }

    private fun printSimpleIdDiagram(
        sb: StringBuilder,
        fnName: String,
        name: String,
        elementIds: List<String>,
        noteIds: List<String>,
    ) {
        if (elementIds.isEmpty() && noteIds.isEmpty()) {
            sb.appendLine("    $fnName(${quote(name)})")
        } else {
            sb.appendLine("    $fnName(${quote(name)}) {")
            elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
            noteIds.forEach { sb.appendLine("        includeCommentById(${quote(it)})") }
            sb.appendLine("    }")
        }
    }

    private fun printIbdDiagram(
        sb: StringBuilder,
        d: IbdDiagram,
        ctx: Ctx,
    ) {
        val ownerVar = ctx.partDefVars[d.ownerId]
        if (ownerVar == null) {
            sb.appendLine(
                "    // TODO: IbdDiagram ${quote(d.name)} not serialized — owner id '${sanitizeForComment(d.ownerId)}' " +
                    "does not resolve to a printed PartDefinition.",
            )
            return
        }
        if (d.elementIds.isEmpty() && d.noteIds.isEmpty()) {
            sb.appendLine("    ibd(${quote(d.name)}, owner = $ownerVar)")
        } else {
            sb.appendLine("    ibd(${quote(d.name)}, owner = $ownerVar) {")
            d.elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
            d.noteIds.forEach { sb.appendLine("        includeCommentById(${quote(it)})") }
            sb.appendLine("    }")
        }
    }

    private fun printUcDiagram(
        sb: StringBuilder,
        d: UcDiagram,
    ) {
        val hasBody =
            d.elementIds.isNotEmpty() ||
                d.noteIds.isNotEmpty() ||
                d.associations.isNotEmpty() ||
                d.includes.isNotEmpty() ||
                d.extends.isNotEmpty() ||
                d.subjects.isNotEmpty() ||
                d.actorGeneralizations.isNotEmpty()
        if (!hasBody) {
            sb.appendLine("    ucDiagram(${quote(d.name)})")
            return
        }
        sb.appendLine("    ucDiagram(${quote(d.name)}) {")
        d.elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
        d.noteIds.forEach { sb.appendLine("        includeCommentById(${quote(it)})") }
        d.associations.forEach {
            sb.appendLine("        associationById(actorId = ${quote(it.actorId)}, useCaseId = ${quote(it.useCaseId)})")
        }
        d.includes.forEach {
            sb.appendLine("        includeById(sourceId = ${quote(it.sourceUseCaseId)}, targetId = ${quote(it.targetUseCaseId)})")
        }
        d.extends.forEach {
            sb.appendLine("        extendById(sourceId = ${quote(it.sourceUseCaseId)}, targetId = ${quote(it.targetUseCaseId)})")
        }
        d.actorGeneralizations.forEach {
            sb.appendLine("        generalizeById(specificId = ${quote(it.specificId)}, generalId = ${quote(it.generalId)})")
        }
        d.subjects.forEach {
            sb.appendLine(
                "        subjectById(id = ${quote(it.id)}, name = ${quote(it.name)}, " +
                    "useCaseIds = listOf(${it.useCaseIds.joinToString(", ") { id -> quote(id) }}))",
            )
        }
        sb.appendLine("    }")
    }

    private fun printReqDiagram(
        sb: StringBuilder,
        d: ReqDiagram,
    ) {
        val hasBody =
            d.elementIds.isNotEmpty() ||
                d.noteIds.isNotEmpty() ||
                d.satisfies.isNotEmpty() ||
                d.verifies.isNotEmpty() ||
                d.derives.isNotEmpty() ||
                d.contains.isNotEmpty() ||
                d.refines.isNotEmpty() ||
                d.traces.isNotEmpty() ||
                d.copies.isNotEmpty() ||
                d.groups.isNotEmpty()
        if (!hasBody) {
            sb.appendLine("    reqDiagram(${quote(d.name)})")
            return
        }
        sb.appendLine("    reqDiagram(${quote(d.name)}) {")
        d.elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
        d.noteIds.forEach { sb.appendLine("        includeCommentById(${quote(it)})") }
        d.satisfies.forEach {
            sb.appendLine("        satisfyById(sourceId = ${quote(it.sourceId)}, requirementId = ${quote(it.requirementId)})")
        }
        d.verifies.forEach {
            sb.appendLine("        verifyById(sourceId = ${quote(it.sourceId)}, requirementId = ${quote(it.requirementId)})")
        }
        d.derives.forEach {
            sb.appendLine(
                "        deriveById(sourceRequirementId = ${quote(it.sourceRequirementId)}, " +
                    "targetRequirementId = ${quote(it.targetRequirementId)})",
            )
        }
        d.contains.forEach {
            sb.appendLine(
                "        containsById(parentRequirementId = ${quote(it.parentRequirementId)}, " +
                    "childRequirementId = ${quote(it.childRequirementId)})",
            )
        }
        d.refines.forEach {
            sb.appendLine("        refineById(sourceId = ${quote(it.sourceId)}, requirementId = ${quote(it.requirementId)})")
        }
        d.traces.forEach {
            sb.appendLine("        traceById(sourceId = ${quote(it.sourceId)}, targetId = ${quote(it.targetId)})")
        }
        d.copies.forEach {
            sb.appendLine(
                "        copyById(sourceRequirementId = ${quote(it.sourceRequirementId)}, " +
                    "targetRequirementId = ${quote(it.targetRequirementId)})",
            )
        }
        d.groups.forEach {
            sb.appendLine(
                "        groupById(id = ${quote(it.id)}, name = ${quote(it.name)}, " +
                    "memberIds = listOf(${it.memberIds.joinToString(", ") { id -> quote(id) }}), " +
                    "childGroupIds = listOf(${it.childGroupIds.joinToString(", ") { id -> quote(id) }}))",
            )
        }
        sb.appendLine("    }")
    }

    /**
     * Shared printer for the 8 ADR-0017 Wave B top-level usage types
     * ([ActorUsage] / [UseCaseUsage] / [RequirementUsage] / [StateUsage] /
     * [ActionUsage] / [ActivityPartitionUsage] / [LifelineUsage] /
     * [ConstraintUsage]) — they're all structurally identical (`name`,
     * `definitionId`, optional `multiplicity`, `id`), differing only in
     * their [dev.kuml.sysml2.dsl.Sysml2ModelBuilder]-side `…UsageById(...)`
     * function name.
     */
    private fun printSimpleTopLevelUsage(
        sb: StringBuilder,
        fnName: String,
        usage: Sysml2Usage,
    ) {
        val args = mutableListOf("name = ${quote(usage.name)}", "definitionId = ${quote(usage.definitionId)}")
        multiplicityExpr(usage.multiplicity)?.let { args += "multiplicity = $it" }
        args += "id = ${quote(usage.id)}"
        sb.appendLine("    $fnName(${args.joinToString(", ")})")
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun multiplicityExpr(m: KermlMultiplicity): String? {
        if (m == KermlMultiplicity.EXACTLY_ONE) return null
        return when (m) {
            KermlMultiplicity.OPTIONAL -> "KermlMultiplicity.OPTIONAL"
            KermlMultiplicity.ZERO_OR_MORE -> "KermlMultiplicity.ZERO_OR_MORE"
            KermlMultiplicity.ONE_OR_MORE -> "KermlMultiplicity.ONE_OR_MORE"
            else -> "KermlMultiplicity(lower = ${m.lower}, upper = ${m.upper?.toString() ?: "null"})"
        }
    }

    /** Units of `after(<n>.<unit>)` literals the printed script needs imported (sorted, distinct). */
    private fun durationUnitImports(model: Sysml2Model): List<String> =
        model.usages
            .filterIsInstance<TransitionUsage>()
            .mapNotNull { (it.timer as? TransitionTimer.After)?.duration }
            .map { durationLiteral(it).substringAfter('.') }
            .distinct()
            .sorted()

    private fun needsKermlMultiplicityImport(model: Sysml2Model): Boolean =
        model.usages.any { it.multiplicity != KermlMultiplicity.EXACTLY_ONE }

    private fun quote(s: String): String =
        buildString {
            append('"')
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }

    /**
     * Sanitizes a raw, unquoted text fragment (e.g. a definition/usage `name` or an id) for safe
     * embedding in a single-line Kotlin `//` comment emitted as diagnostic output
     * (`// TODO: …`).
     *
     * Unlike [quote], the fragments this is applied to are printed *outside* any string literal —
     * directly as bare text after `//`. `Sysml2Definition.name`/`.id` (and the analogous usage
     * fields) are unvalidated `String`s, so they may contain line-terminating characters. Without
     * sanitization, a name/id containing `\n` (or `\r`, or a Unicode line separator) followed by
     * Kotlin code would end the comment early and cause the remainder to be printed as live,
     * uncommented code in the generated `*.kuml.kts` script — a comment-injection vulnerability
     * that both hides malicious code from a human reviewer and could be picked up by an
     * automated compile step. Every character that any tool could plausibly treat as a line
     * break is replaced with a visible, non-breaking escape sequence.
     */
    private fun sanitizeForComment(s: String): String =
        buildString {
            for (ch in s) {
                when (ch.code) {
                    '\n'.code -> append("\\n")
                    '\r'.code -> append("\\r")
                    0x0085 -> append("\\u0085") // NEL
                    0x2028 -> append("\\u2028") // LINE SEPARATOR
                    0x2029 -> append("\\u2029") // PARAGRAPH SEPARATOR
                    else -> append(ch)
                }
            }
        }
}
