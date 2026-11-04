package dev.kuml.blueprint.dsl.print

import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLabels
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.ConnectionStyle
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.TouchpointSymbol
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.note.NoteKind

/**
 * Pretty-prints a [BlueprintModel] as a `*.kuml.kts` source string (`blueprint(name) { … }`).
 *
 * The Blueprint builder allocates deterministic ids (`actor_0`, `phase_1`, `step_3`, …) from the
 * declaration order, so a model built through the DSL round-trips with full data-class equality as
 * long as the printer re-declares everything in the same order: actors, channels, touchpoints,
 * phases (each with its steps in model order), connections, diagrams, notes.
 *
 * Steps are referenced by variable. A step that a connection or note points to is predeclared as
 * `lateinit var stepN: String` and assigned inside its `phase { … }` block (the lambda-scope trick
 * the C4 printer uses); a reference that cannot be resolved falls back to the quoted id string.
 *
 * Notes are always printed with an explicit `id = …`, and `kind = NoteKind.X` only when the kind is
 * not [NoteKind.NOTE]. Note markup is part of the text; the only escaping is [quote].
 *
 * Not printed, because the DSL has no way to set them (each gets a `// TODO:` comment instead):
 * `metadata` on any element, diagram `elementIds`, diagram `layout`, a `null` step name (printed as
 * the empty string) and a phase `order` that differs from its position.
 */
object BlueprintDslPrinter {
    fun print(model: BlueprintModel): String {
        val sb = StringBuilder()
        val todos = mutableListOf<String>()
        sb.appendLine("blueprint(name = ${quote(model.name)}) {")

        // Reference variables for everything a later statement can point to.
        val refVars = mutableMapOf<String, String>()
        model.actors.forEachIndexed { i, a ->
            refVars[a.id] = "actor$i"
            sb.append("    val actor$i = actor(${quote(a.name ?: "")}")
            if (a.role != ActorRole.CUSTOMER) sb.append(", role = ActorRole.${a.role.name}")
            a.description?.let { sb.append(", description = ${quote(it)}") }
            sb.appendLine(")")
            if (a.name == null) todos += "actor '${commentSafe(a.id)}' has a null name (printed as empty)."
            metadataTodo(todos = todos, kind = "actor", id = a.id, metadata = a.metadata)
        }
        model.channels.forEachIndexed { i, c ->
            refVars[c.id] = "channel$i"
            sb.append("    val channel$i = channel(${quote(c.name ?: "")}")
            if (c.kind != ChannelKind.OTHER) sb.append(", kind = ChannelKind.${c.kind.name}")
            sb.appendLine(")")
            if (c.name == null) todos += "channel '${commentSafe(c.id)}' has a null name (printed as empty)."
            metadataTodo(todos = todos, kind = "channel", id = c.id, metadata = c.metadata)
        }
        model.touchpoints.forEachIndexed { i, t ->
            refVars[t.id] = "touchpoint$i"
            sb.append("    val touchpoint$i = touchpoint(${quote(t.name ?: "")}")
            t.channelRef?.let { sb.append(", channel = ${ref(id = it, refVars = refVars)}") }
            if (t.symbol != TouchpointSymbol.CIRCLE) sb.append(", symbol = TouchpointSymbol.${t.symbol.name}")
            sb.appendLine(")")
            if (t.name == null) todos += "touchpoint '${commentSafe(t.id)}' has a null name (printed as empty)."
            metadataTodo(todos = todos, kind = "touchpoint", id = t.id, metadata = t.metadata)
        }

        // Steps that something points to get a lateinit var, assigned inside their phase block.
        val referencedStepIds =
            buildSet {
                model.connections.forEach {
                    add(it.sourceRef)
                    add(it.targetRef)
                }
                model.noteLinks.forEach { add(it.targetId) }
            }
        val phaseIds = model.phases.map { it.id }.toSet()
        model.steps.forEachIndexed { i, s ->
            if (s.id in referencedStepIds && s.phaseRef in phaseIds) {
                refVars[s.id] = "step$i"
                sb.appendLine("    lateinit var step$i: String")
            }
        }

        val ordered = model.orderedPhases()
        ordered.forEachIndexed { index, phase ->
            if (phase.order != index) todos += "phase '${commentSafe(phase.id)}' has order ${phase.order} (printed at position $index)."
            if (phase.name == null) todos += "phase '${commentSafe(phase.id)}' has a null name (printed as empty)."
            metadataTodo(todos = todos, kind = "phase", id = phase.id, metadata = phase.metadata)
            val phaseSteps = model.steps.filter { it.phaseRef == phase.id }
            if (phaseSteps.isEmpty()) {
                sb.appendLine("    phase(${quote(phase.name ?: "")}) {}")
            } else {
                sb.appendLine("    phase(${quote(phase.name ?: "")}) {")
                for (s in phaseSteps) printStep(sb = sb, s = s, refVars = refVars, todos = todos)
                sb.appendLine("    }")
            }
        }
        model.steps.filter { it.phaseRef !in phaseIds }.forEach {
            todos += "step '${commentSafe(it.id)}' not serialized — phase '${commentSafe(it.phaseRef)}' does not exist."
        }

        for (c in model.connections) {
            val style = if (c.style != ConnectionStyle.SOLID) ", style = ConnectionStyle.${c.style.name}" else ""
            sb.appendLine(
                "    connection(from = ${ref(
                    id = c.sourceRef,
                    refVars = refVars,
                )}, to = ${ref(id = c.targetRef, refVars = refVars)}$style)",
            )
            if (c.name != null) todos += "connection '${commentSafe(c.id)}' has a name, which the DSL cannot set."
            metadataTodo(todos = todos, kind = "connection", id = c.id, metadata = c.metadata)
        }

        for (d in model.diagrams) {
            when (d) {
                is JourneyDiagram -> {
                    val args = mutableListOf("name = ${quote(d.name)}")
                    if (d.visibleLayers != setOf(BlueprintLayer.CUSTOMER_ACTIONS)) args += "layers = ${layerSet(d.visibleLayers)}"
                    if (!d.showEmotionCurve) args += "emotionCurve = false"
                    labelsArg(d.labels)?.let { args += "labels = $it" }
                    sb.appendLine("    journeyDiagram(${args.joinToString(", ")})")
                }
                is BlueprintDiagramFull -> {
                    val args = mutableListOf("name = ${quote(d.name)}")
                    if (d.visibleLayers != BlueprintLayer.entries.toSet()) args += "layers = ${layerSet(d.visibleLayers)}"
                    if (d.showLines != BlueprintLine.entries.toSet()) args += "lines = ${lineSet(d.showLines)}"
                    if (d.showEmotionCurve) args += "emotionCurve = true"
                    labelsArg(d.labels)?.let { args += "labels = $it" }
                    sb.appendLine("    blueprintDiagram(${args.joinToString(", ")})")
                }
            }
            if (d.layout != null) todos += "diagram ${quote(d.name)} layout hints are not serialized."
            if (d.elementIds.isNotEmpty()) todos += "diagram ${quote(d.name)} elementIds are not serialized."
        }

        for (note in model.notes) {
            val args = mutableListOf(quote(note.body))
            model.noteLinks.filter { it.noteId == note.id }.forEach { args += ref(id = it.targetId, refVars = refVars) }
            args += "id = ${quote(note.id)}"
            if (note.kind != NoteKind.NOTE) args += "kind = NoteKind.${note.kind.name}"
            sb.appendLine("    note(${args.joinToString(", ")})")
        }

        metadataTodo(todos = todos, kind = "model", id = model.name, metadata = model.metadata)
        for (t in todos) sb.appendLine("    // TODO: $t")
        sb.appendLine("}")
        return sb.toString()
    }

    private fun printStep(
        sb: StringBuilder,
        s: JourneyStep,
        refVars: Map<String, String>,
        todos: MutableList<String>,
    ) {
        val assigned = refVars[s.id]?.takeIf { it.startsWith("step") }
        val prefix = if (assigned != null) "        $assigned = " else "        "
        val name = quote(s.name ?: "")
        if (s.name == null) todos += "step '${commentSafe(s.id)}' has a null name (printed as empty)."
        metadataTodo(todos = todos, kind = "step", id = s.id, metadata = s.metadata)
        val tps =
            if (s.touchpointRefs.isEmpty()) {
                null
            } else {
                "listOf(${s.touchpointRefs.joinToString(
                    ", ",
                ) { ref(id = it, refVars = refVars) }})"
            }
        val actor = s.actorRef?.let { ref(id = it, refVars = refVars) }
        val shortcut =
            when (s.layer) {
                BlueprintLayer.CUSTOMER_ACTIONS -> if (s.sentiment != null) "customer" else null
                BlueprintLayer.FRONTSTAGE -> if (s.sentiment == null && s.painPoint == null) "frontstage" else null
                BlueprintLayer.BACKSTAGE -> if (s.sentiment == null && s.painPoint == null && tps == null) "backstage" else null
                BlueprintLayer.SUPPORT_PROCESSES -> if (s.sentiment == null && s.painPoint == null && tps == null) "support" else null
            }
        val args = mutableListOf(name)
        if (shortcut == null) {
            if (s.layer != BlueprintLayer.CUSTOMER_ACTIONS) args += "layer = BlueprintLayer.${s.layer.name}"
        } else if (shortcut == "customer") {
            args += "sentiment = Sentiment.${s.sentiment!!.name}"
        }
        tps?.let { args += "touchpoints = $it" }
        actor?.let { args += "actor = $it" }
        if (shortcut == null) s.sentiment?.let { args += "sentiment = Sentiment.${it.name}" }
        if (shortcut == null || shortcut == "customer") s.painPoint?.let { args += "pain = ${quote(it)}" }
        s.opportunity?.let { args += "opportunity = ${quote(it)}" }
        sb.appendLine("$prefix${shortcut ?: "step"}(${args.joinToString(", ")})")
    }

    private fun ref(
        id: String,
        refVars: Map<String, String>,
    ): String = refVars[id] ?: quote(id)

    private fun layerSet(layers: Set<BlueprintLayer>): String =
        if (layers.isEmpty()) {
            "emptySet()"
        } else {
            "setOf(${layers.sortedBy { it.ordinal }.joinToString(", ") { "BlueprintLayer.${it.name}" }})"
        }

    private fun lineSet(lines: Set<BlueprintLine>): String =
        if (lines.isEmpty()) {
            "emptySet()"
        } else {
            "setOf(${lines.sortedBy { it.ordinal }.joinToString(", ") { "BlueprintLine.${it.name}" }})"
        }

    /** The `labels = …` argument, or null for the default English labels. */
    private fun labelsArg(labels: BlueprintLabels): String? {
        val d = BlueprintLabels.ENGLISH
        if (labels == d) return null
        if (labels == BlueprintLabels.GERMAN) return "BlueprintLabels.GERMAN"
        val parts = mutableListOf<String>()
        if (labels.customerActions != d.customerActions) parts += "customerActions = ${quote(labels.customerActions)}"
        if (labels.frontstage != d.frontstage) parts += "frontstage = ${quote(labels.frontstage)}"
        if (labels.backstage != d.backstage) parts += "backstage = ${quote(labels.backstage)}"
        if (labels.supportProcesses != d.supportProcesses) parts += "supportProcesses = ${quote(labels.supportProcesses)}"
        if (labels.lineOfInteraction != d.lineOfInteraction) parts += "lineOfInteraction = ${quote(labels.lineOfInteraction)}"
        if (labels.lineOfVisibility != d.lineOfVisibility) parts += "lineOfVisibility = ${quote(labels.lineOfVisibility)}"
        if (labels.lineOfInternalInteraction != d.lineOfInternalInteraction) {
            parts += "lineOfInternalInteraction = ${quote(labels.lineOfInternalInteraction)}"
        }
        if (labels.painPrefix != d.painPrefix) parts += "painPrefix = ${quote(labels.painPrefix)}"
        if (labels.opportunityPrefix != d.opportunityPrefix) parts += "opportunityPrefix = ${quote(labels.opportunityPrefix)}"
        return "BlueprintLabels(${parts.joinToString(", ")})"
    }

    private fun metadataTodo(
        todos: MutableList<String>,
        kind: String,
        id: String,
        metadata: Map<String, KumlMetaValue>,
    ) {
        if (metadata.isNotEmpty()) todos += "$kind '${commentSafe(id)}' metadata is not serialized (the DSL cannot set it)."
    }

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

    /** Keeps an id on one line inside a `//` comment (comment-injection guard). */
    private fun commentSafe(s: String): String =
        buildString {
            for (ch in s) {
                when (ch.code) {
                    '\n'.code -> append("\\n")
                    '\r'.code -> append("\\r")
                    0x0085 -> append("\\u0085")
                    0x2028 -> append("\\u2028")
                    0x2029 -> append("\\u2029")
                    else -> append(ch)
                }
            }
        }
}
