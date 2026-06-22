package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.MessageFlow
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.bpmn.model.TaskType
import dev.kuml.io.latex.escapeLatex

/**
 * Renders BPMN models as LaTeX/TikZ source — V3.1.8.
 *
 * Uses BPMN-specific TikZ styles (`kuml-bpmn-*`) that must be present in the
 * picture preamble (emitted by [dev.kuml.io.latex.KumlLatexRenderer] via
 * [appendBpmnTikzStyles]).
 *
 * Layout strategy: flow nodes are positioned sequentially left-to-right using
 * `right of=<prevId>` with `node distance=50pt and 80pt`. This produces a
 * readable linearised diagram without requiring a layout engine. The SVG
 * renderer uses the full kUML layout bridge; the LaTeX renderer deliberately
 * opts for this simpler approach to stay self-contained for export/document use.
 *
 * TikZ node names are sanitised via [sanitizeTikzId] — any character outside
 * `[A-Za-z0-9_]` is replaced by `_`.
 */
internal object BpmnLatexRenderer {
    // ── Process diagram ───────────────────────────────────────────────────────

    /**
     * Renders a [ProcessDiagram] view: all flow nodes and sequence flows of the
     * referenced process as TikZ nodes and draw-paths.
     */
    fun render(
        model: BpmnModel,
        diagram: ProcessDiagram,
    ): String {
        val process =
            model.processes.find { it.id == diagram.processId }
                ?: model.processes.firstOrNull()
                ?: return ""

        return buildString {
            appendLine("""\begin{tikzpicture}[node distance=50pt and 80pt,>=stealth]""")
            appendLine()

            val nodeIds = process.flowNodes.map { it.id }
            process.flowNodes.forEachIndexed { idx, node ->
                val prevId = if (idx == 0) null else nodeIds[idx - 1]
                appendFlowNode(node, prevId)
            }

            if (process.sequenceFlows.isNotEmpty()) {
                appendLine()
                process.sequenceFlows.forEach { flow -> appendSequenceFlow(flow) }
            }

            appendLine()
            appendLine("""\end{tikzpicture}""")
        }
    }

    // ── Collaboration diagram ─────────────────────────────────────────────────

    /**
     * Renders a [CollaborationDiagram] view: all participant pools and message
     * flows of the referenced collaboration as TikZ nodes and draw-paths.
     */
    fun render(
        model: BpmnModel,
        diagram: CollaborationDiagram,
    ): String {
        val collab =
            model.collaborations.find { it.id == diagram.collaborationId }
                ?: model.collaborations.firstOrNull()
                ?: return ""

        return buildString {
            appendLine("""\begin{tikzpicture}[node distance=50pt and 80pt,>=stealth]""")
            appendLine()
            appendLine("""  % Collaboration: ${escapeLatex(model.name)}""")
            appendLine()

            appendCollaborationPools(collab)

            if (collab.messageFlows.isNotEmpty()) {
                appendLine()
                collab.messageFlows.forEach { mf -> appendMessageFlow(mf) }
            }

            appendLine()
            appendLine("""\end{tikzpicture}""")
        }
    }

    // ── Pool rendering ────────────────────────────────────────────────────────

    private fun StringBuilder.appendCollaborationPools(collab: BpmnCollaboration) {
        collab.participants.forEachIndexed { pIdx, participant ->
            val poolId = sanitizeTikzId(participant.id)
            val poolLabel = escapeLatex(participant.name ?: participant.id)
            val yOffset = if (pIdx == 0) "0" else "${-pIdx * 100}pt"
            appendLine("""  % Pool: $poolLabel""")
            appendLine("""  \node[kuml-bpmn-pool] ($poolId) at (0,$yOffset) {};""")
            val rotatedLabel = """\rotatebox{90}{$poolLabel}"""
            appendLine("""  \node[kuml-bpmn-pool-header,minimum height=80pt,anchor=west] (ph_$poolId) at ($poolId.west) {$rotatedLabel};""")

            // If participant references a process, render its nodes inside the pool
            if (participant.processRef != null) {
                appendLine("""  % (inner process nodes omitted — see ProcessDiagram for process ${participant.processRef})""")
            }

            if (participant.lanes.isNotEmpty()) {
                participant.lanes.forEachIndexed { lIdx, lane ->
                    val laneId = sanitizeTikzId(lane.id)
                    val laneLabel = escapeLatex(lane.name ?: lane.id)
                    appendLine("""  \node[kuml-bpmn-lane,anchor=north west] ($laneId) at ($poolId.north west) {};""")
                    appendLine(
                        """  \node[kuml-bpmn-lane-header,anchor=west,minimum height=60pt] """ +
                            """(lh_$laneId) at ($laneId.west) {\rotatebox{90}{$laneLabel}};""",
                    )
                }
            }
        }
    }

    // ── Flow node rendering ───────────────────────────────────────────────────

    private fun StringBuilder.appendFlowNode(
        node: BpmnFlowNode,
        prevId: String?,
    ) {
        val nodeId = sanitizeTikzId(node.id)
        val label = escapeLatex(node.name ?: "")
        val pos = if (prevId != null) "right of=${sanitizeTikzId(prevId)}" else "at (0,0)"

        when (node) {
            is BpmnEvent -> appendEvent(node, nodeId, label, pos)
            is BpmnGateway -> appendGateway(node, nodeId, label, pos)
            is BpmnTask -> appendTask(node, nodeId, label, pos)
            is BpmnSubProcess -> appendSubProcess(node, nodeId, label, pos)
            is BpmnCallActivity -> appendCallActivity(node, nodeId, label, pos)
        }
    }

    private fun StringBuilder.appendEvent(
        event: BpmnEvent,
        nodeId: String,
        label: String,
        pos: String,
    ) {
        val style =
            when {
                event.attachedToRef != null -> "kuml-bpmn-boundary"
                event.position == EventPosition.START -> "kuml-bpmn-start"
                event.position == EventPosition.END -> "kuml-bpmn-end"
                else -> "kuml-bpmn-intermediate"
            }
        val symbol = eventSymbol(event.definition)
        appendLine("""  \node[$style] ($nodeId) [$pos] {$symbol};""")
        if (label.isNotBlank()) {
            appendLine("""  \node[kuml-bpmn-label,below=4pt of $nodeId] {$label};""")
        }
    }

    private fun eventSymbol(def: EventDefinition): String =
        when (def) {
            EventDefinition.NONE -> ""
            EventDefinition.MESSAGE -> "\$\\bowtie\$"
            EventDefinition.TIMER -> "\$\\odot\$"
            EventDefinition.ERROR -> "\$\\mathsf{E}\$"
            EventDefinition.SIGNAL -> "\$\\triangle\$"
            EventDefinition.TERMINATE -> "\$\\bullet\$"
            EventDefinition.ESCALATION -> "\$\\uparrow\$"
            EventDefinition.COMPENSATION -> "\$\\Leftarrow\$"
            EventDefinition.CONDITIONAL -> "\$\\equiv\$"
            EventDefinition.LINK -> "\$\\rightarrow\$"
            EventDefinition.CANCEL -> "\$\\times\$"
            EventDefinition.MULTIPLE -> "\$\\star\$"
            EventDefinition.PARALLEL_MULTIPLE -> "\$+\$"
        }

    private fun StringBuilder.appendGateway(
        gw: BpmnGateway,
        nodeId: String,
        label: String,
        pos: String,
    ) {
        val symbol =
            when (gw.gatewayType) {
                GatewayType.EXCLUSIVE -> "\$\\times\$"
                GatewayType.INCLUSIVE -> "\$\\bigcirc\$"
                GatewayType.PARALLEL -> "\$+\$"
                GatewayType.EVENT_BASED -> "\$\\odot\$"
                GatewayType.COMPLEX -> "\$*\$"
            }
        appendLine("""  \node[kuml-bpmn-gateway] ($nodeId) [$pos] {$symbol};""")
        if (label.isNotBlank()) {
            appendLine("""  \node[kuml-bpmn-label,below=4pt of $nodeId] {$label};""")
        }
    }

    private fun StringBuilder.appendTask(
        task: BpmnTask,
        nodeId: String,
        label: String,
        pos: String,
    ) {
        val typePrefix =
            when (task.taskType) {
                TaskType.USER -> "{\\tiny User}~"
                TaskType.SERVICE -> "{\\tiny Service}~"
                TaskType.SEND -> "{\\tiny Send}~"
                TaskType.RECEIVE -> "{\\tiny Receive}~"
                TaskType.MANUAL -> "{\\tiny Manual}~"
                TaskType.SCRIPT -> "{\\tiny Script}~"
                TaskType.BUSINESS_RULE -> "{\\tiny BR}~"
                TaskType.NONE -> ""
            }
        appendLine("""  \node[kuml-bpmn-task] ($nodeId) [$pos] {$typePrefix$label};""")
    }

    private fun StringBuilder.appendSubProcess(
        sp: BpmnSubProcess,
        nodeId: String,
        label: String,
        pos: String,
    ) {
        appendLine("""  \node[kuml-bpmn-subprocess] ($nodeId) [$pos] {$label\\\$[+]\$};""")
    }

    private fun StringBuilder.appendCallActivity(
        ca: BpmnCallActivity,
        nodeId: String,
        label: String,
        pos: String,
    ) {
        appendLine("""  \node[kuml-bpmn-callactivity] ($nodeId) [$pos] {$label};""")
    }

    // ── Sequence flow rendering ───────────────────────────────────────────────

    private fun StringBuilder.appendSequenceFlow(flow: SequenceFlow) {
        val src = sanitizeTikzId(flow.sourceRef)
        val tgt = sanitizeTikzId(flow.targetRef)
        val labelPart =
            flow.name
                ?.takeIf { it.isNotBlank() }
                ?.let { " node[kuml-bpmn-label,midway,above] {${escapeLatex(it)}}" }
                ?: ""
        val condPart =
            flow.conditionExpression
                ?.takeIf { it.isNotBlank() }
                ?.let { " node[kuml-bpmn-label,near start,sloped] {${escapeLatex(it)}}" }
                ?: ""
        appendLine("""  \draw[kuml-bpmn-flow] ($src) --$labelPart$condPart ($tgt);""")
    }

    // ── Message flow rendering ────────────────────────────────────────────────

    private fun StringBuilder.appendMessageFlow(mf: MessageFlow) {
        val src = sanitizeTikzId(mf.sourceRef)
        val tgt = sanitizeTikzId(mf.targetRef)
        val labelPart =
            mf.name
                ?.takeIf { it.isNotBlank() }
                ?.let { " node[kuml-bpmn-label,midway,above] {${escapeLatex(it)}}" }
                ?: ""
        appendLine("""  \draw[kuml-bpmn-msgflow] ($src) --$labelPart ($tgt);""")
    }

    // ── TikZ style block ──────────────────────────────────────────────────────

    /**
     * Emits the BPMN-specific TikZ styles into a `\tikzset{…}` block.
     *
     * Called from [dev.kuml.io.latex.KumlLatexRenderer.appendTikzStyles] once
     * per picture so the styles are available when BPMN nodes are drawn.
     */
    internal fun appendBpmnTikzStyles(
        sb: StringBuilder,
        indent: String,
    ) {
        sb.apply {
            // ── Events (circles) ──────────────────────────────────────────────
            appendLine(
                "$indent  kuml-bpmn-event/.style={circle,minimum size=28pt,draw=black,line width=1pt," +
                    "font=\\footnotesize,align=center,inner sep=2pt},",
            )
            appendLine("$indent  kuml-bpmn-start/.style={kuml-bpmn-event,fill=white},")
            appendLine("$indent  kuml-bpmn-end/.style={kuml-bpmn-event,line width=3pt},")
            appendLine(
                "$indent  kuml-bpmn-intermediate/.style={kuml-bpmn-event,double,double distance=2pt},",
            )
            appendLine(
                "$indent  kuml-bpmn-boundary/.style={kuml-bpmn-event,dashed},",
            )
            // ── Gateways (diamonds) ───────────────────────────────────────────
            appendLine(
                "$indent  kuml-bpmn-gateway/.style={diamond,minimum size=36pt,draw=black,line width=1pt," +
                    "aspect=1.4,font=\\footnotesize,align=center,inner sep=0pt},",
            )
            // ── Tasks (rounded rectangles) ────────────────────────────────────
            appendLine(
                "$indent  kuml-bpmn-task/.style={rectangle,rounded corners=5pt,minimum width=80pt," +
                    "minimum height=40pt,draw=black,line width=1pt,font=\\footnotesize," +
                    "align=center,text width=70pt,inner sep=4pt},",
            )
            appendLine(
                "$indent  kuml-bpmn-subprocess/.style={kuml-bpmn-task,double,double distance=2pt},",
            )
            appendLine(
                "$indent  kuml-bpmn-callactivity/.style={kuml-bpmn-task,line width=3pt},",
            )
            // ── Pools and Lanes (swimlanes) ───────────────────────────────────
            appendLine(
                "$indent  kuml-bpmn-pool/.style={rectangle,draw=black,line width=1.5pt," +
                    "minimum width=200pt,minimum height=80pt,align=left},",
            )
            appendLine(
                "$indent  kuml-bpmn-pool-header/.style={rectangle,draw=black,line width=1pt," +
                    "fill=gray!15,minimum width=25pt,font=\\footnotesize\\bfseries,align=center},",
            )
            appendLine(
                "$indent  kuml-bpmn-lane/.style={rectangle,draw=black,line width=0.7pt," +
                    "fill=white,minimum width=175pt,minimum height=70pt,align=left},",
            )
            appendLine(
                "$indent  kuml-bpmn-lane-header/.style={rectangle,draw=black,line width=0.5pt," +
                    "fill=gray!8,minimum width=20pt,font=\\scriptsize,align=center},",
            )
            // ── Edges ─────────────────────────────────────────────────────────
            appendLine(
                "$indent  kuml-bpmn-flow/.style={->,>=stealth,draw=black,line width=0.8pt},",
            )
            appendLine(
                "$indent  kuml-bpmn-msgflow/.style={->,>=open triangle 45,draw=black," +
                    "line width=0.8pt,dashed},",
            )
            // ── Labels ────────────────────────────────────────────────────────
            appendLine("$indent  kuml-bpmn-label/.style={font=\\scriptsize,align=center},")
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Sanitises an element ID into a valid TikZ node name.
     *
     * TikZ node names must consist of alphanumeric characters and underscores.
     * Hyphens and other characters that appear in BPMN element IDs are replaced
     * by `_`.
     */
    internal fun sanitizeTikzId(id: String): String = id.replace(Regex("[^A-Za-z0-9_]"), "_")
}
