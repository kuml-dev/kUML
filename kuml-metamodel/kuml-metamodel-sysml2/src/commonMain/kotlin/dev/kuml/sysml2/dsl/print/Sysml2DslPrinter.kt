package dev.kuml.sysml2.dsl.print

import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActionPin
import dev.kuml.sysml2.ActionUsage
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.sysml2.ActivityPartitionUsage
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.ActorUsage
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
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.LifelineUsage
import dev.kuml.sysml2.MessageKind
import dev.kuml.sysml2.MessageUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.RequirementUsage
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateUsage
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.Sysml2Usage
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.UseCaseUsage

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
        sb.appendLine("sysml2Model(${quote(model.name)}) {")

        val (special, rest) =
            model.definitions.partition {
                it is PartDefinition ||
                    it is LifelineDefinition ||
                    it is ActivityPartitionDefinition
            }
        special.forEach { printDefinition(sb = sb, def = it, model = model, ctx = ctx, bindVar = true) }
        rest.forEach { printDefinition(sb = sb, def = it, model = model, ctx = ctx, bindVar = false) }

        val consumedFeatureIds =
            model.definitions
                .flatMap { it.features }
                .map { it.id }
                .toSet()
        val topLevelUsages = model.usages.filter { it.id !in consumedFeatureIds }
        topLevelUsages.forEach { printTopLevelUsage(sb = sb, usage = it, ctx = ctx) }

        model.diagrams.forEach { printDiagram(sb = sb, d = it, ctx = ctx) }

        sb.appendLine("}")
        return sb.toString()
    }

    // ── printer state ──────────────────────────────────────────────────────

    private class Ctx {
        val partDefVars: MutableMap<String, String> = mutableMapOf()
        val lifelineDefVars: MutableMap<String, String> = mutableMapOf()
        val partitionDefVars: MutableMap<String, String> = mutableMapOf()
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
            is StateDefinition -> {
                if (def.isInitial) args += "isInitial = true"
                if (def.isFinal) args += "isFinal = true"
                def.entryAction?.let { args += "entryAction = ${quote(it)}" }
                def.exitAction?.let { args += "exitAction = ${quote(it)}" }
                def.doAction?.let { args += "doAction = ${quote(it)}" }
                if (def.isAbstract) args += "isAbstract = true"
                appendSpecializesArg(def = def, builderName = "stateDef", args = args, todos = todos)
                printDefCall(
                    sb = sb,
                    fnName = "stateDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
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
                printDefCall(
                    sb = sb,
                    fnName = "actionDef",
                    varName = varName,
                    args = args,
                    todos = todos,
                ) { body -> printNestedFeatures(sb = body, def = def, model = model) }
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
                    sb.appendLine("$indent part(${args.joinToString(", ")})")
                }
                is PortUsage -> {
                    val args = mutableListOf("name = ${quote(usage.name)}", "typeId = ${quote(usage.definitionId)}")
                    multiplicityExpr(usage.multiplicity)?.let { args += "multiplicity = $it" }
                    sb.appendLine("$indent port(${args.joinToString(", ")})")
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
                usage.guard?.let { args += "guard = ${quote(it)}" }
                usage.effect?.let { args += "effect = ${quote(it)}" }
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
                sb.appendLine("    objectFlowById(${args.joinToString(", ")})")
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
                sb.appendLine(
                    "    combinedFragment(name = ${quote(usage.name)}, operator = " +
                        "CombinedFragmentOperator.${usage.operator.name}, operands = listOf($operandsExpr), " +
                        "id = ${quote(usage.id)})",
                )
            }
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

    // ── diagrams ───────────────────────────────────────────────────────────

    private fun printDiagram(
        sb: StringBuilder,
        d: Sysml2Diagram,
        ctx: Ctx,
    ) {
        when (d) {
            is BdDiagram -> printSimpleIdDiagram(sb = sb, fnName = "bdd", name = d.name, elementIds = d.elementIds)
            is IbdDiagram -> printIbdDiagram(sb = sb, d = d, ctx = ctx)
            is UcDiagram -> printUcDiagram(sb = sb, d = d)
            is ReqDiagram -> printReqDiagram(sb = sb, d = d)
            is StmDiagram -> printSimpleIdDiagram(sb = sb, fnName = "stmDiagram", name = d.name, elementIds = d.elementIds)
            is ActDiagram -> printSimpleIdDiagram(sb = sb, fnName = "actDiagram", name = d.name, elementIds = d.elementIds)
            is SeqDiagram -> printSimpleIdDiagram(sb = sb, fnName = "seqDiagram", name = d.name, elementIds = d.elementIds)
            is ParDiagram -> printSimpleIdDiagram(sb = sb, fnName = "parDiagram", name = d.name, elementIds = d.elementIds)
        }
    }

    private fun printSimpleIdDiagram(
        sb: StringBuilder,
        fnName: String,
        name: String,
        elementIds: List<String>,
    ) {
        if (elementIds.isEmpty()) {
            sb.appendLine("    $fnName(${quote(name)})")
        } else {
            sb.appendLine("    $fnName(${quote(name)}) {")
            elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
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
        if (d.elementIds.isEmpty()) {
            sb.appendLine("    ibd(${quote(d.name)}, owner = $ownerVar)")
        } else {
            sb.appendLine("    ibd(${quote(d.name)}, owner = $ownerVar) {")
            d.elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
            sb.appendLine("    }")
        }
    }

    private fun printUcDiagram(
        sb: StringBuilder,
        d: UcDiagram,
    ) {
        val hasBody = d.elementIds.isNotEmpty() || d.associations.isNotEmpty() || d.includes.isNotEmpty() || d.extends.isNotEmpty()
        if (!hasBody) {
            sb.appendLine("    ucDiagram(${quote(d.name)})")
            return
        }
        sb.appendLine("    ucDiagram(${quote(d.name)}) {")
        d.elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
        d.associations.forEach {
            sb.appendLine("        associationById(actorId = ${quote(it.actorId)}, useCaseId = ${quote(it.useCaseId)})")
        }
        d.includes.forEach {
            sb.appendLine("        includeById(sourceId = ${quote(it.sourceUseCaseId)}, targetId = ${quote(it.targetUseCaseId)})")
        }
        d.extends.forEach {
            sb.appendLine("        extendById(sourceId = ${quote(it.sourceUseCaseId)}, targetId = ${quote(it.targetUseCaseId)})")
        }
        sb.appendLine("    }")
    }

    private fun printReqDiagram(
        sb: StringBuilder,
        d: ReqDiagram,
    ) {
        val hasBody =
            d.elementIds.isNotEmpty() ||
                d.satisfies.isNotEmpty() ||
                d.verifies.isNotEmpty() ||
                d.derives.isNotEmpty() ||
                d.contains.isNotEmpty()
        if (!hasBody) {
            sb.appendLine("    reqDiagram(${quote(d.name)})")
            return
        }
        sb.appendLine("    reqDiagram(${quote(d.name)}) {")
        d.elementIds.forEach { sb.appendLine("        includeById(${quote(it)})") }
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
