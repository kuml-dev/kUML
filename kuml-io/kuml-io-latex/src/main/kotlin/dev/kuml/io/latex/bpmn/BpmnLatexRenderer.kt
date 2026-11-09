package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnCallActivity
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
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.commentSafe
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCoord
import dev.kuml.io.latex.note.NoteTikz

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
 * A collaboration view is laid out with absolute coordinates by [BpmnCollaborationLayout]: equal-width pools, one
 * band per leaf lane, and the flow nodes of every referenced process inside their pool (so message flows can end
 * at them). No TikZ library beyond the shared preamble is needed.
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
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val process =
            model.processes.find { it.id == diagram.processId }
                ?: model.processes.firstOrNull()
                ?: return ""

        return buildString {
            appendLine("""\begin{tikzpicture}[node distance=50pt and 80pt,>=stealth]""")
            if (process.textAnnotations.isNotEmpty()) {
                NoteTikz.appendNoteStyles(
                    sb = this,
                    indent = options.indent,
                    kinds = BpmnNoteTikz.noteKinds(process.textAnnotations),
                    association = true,
                )
            }
            appendLine()

            val nodeIds = process.flowNodes.map { it.id }
            process.flowNodes.forEachIndexed { idx, node ->
                val prevId = if (idx == 0) null else nodeIds[idx - 1]
                appendFlowNode(node = node, prevId = prevId)
            }

            if (process.sequenceFlows.isNotEmpty()) {
                appendLine()
                process.sequenceFlows.forEach { flow -> appendSequenceFlow(flow) }
            }

            BpmnNoteTikz.appendProcessNotes(sb = this, process = process, options = options)

            appendLine()
            appendLine("""\end{tikzpicture}""")
        }
    }

    // ── Collaboration diagram ─────────────────────────────────────────────────

    /**
     * Renders a [CollaborationDiagram] view: all participant pools with their lanes, the flow nodes and sequence
     * flows of the referenced processes, and the message flows of the collaboration as TikZ nodes and draw-paths.
     */
    fun render(
        model: BpmnModel,
        diagram: CollaborationDiagram,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val collab =
            model.collaborations.find { it.id == diagram.collaborationId }
                ?: model.collaborations.firstOrNull()
                ?: return ""

        return buildString {
            appendLine("""\begin{tikzpicture}[node distance=50pt and 80pt,>=stealth]""")
            if (collab.messageFlows.isNotEmpty()) {
                // Local to this picture; see MESSAGE_FLOW_STYLE_OVERRIDE.
                appendLine(MESSAGE_FLOW_STYLE_OVERRIDE)
            }
            val annotations = BpmnNoteTikz.collaborationAnnotations(model = model, collab = collab)
            if (annotations.isNotEmpty()) {
                NoteTikz.appendNoteStyles(
                    sb = this,
                    indent = options.indent,
                    kinds = BpmnNoteTikz.noteKinds(annotations),
                    association = true,
                )
            }
            appendLine()
            appendLine("""  % Collaboration: ${commentSafe(model.name)}""")
            appendLine()

            val plan = BpmnCollaborationLayout.plan(model = model, collab = collab)
            appendCollaborationPools(plan)

            if (collab.messageFlows.isNotEmpty()) {
                appendLine()
                collab.messageFlows.forEach { mf -> appendMessageFlow(mf = mf, targets = plan.targets) }
            }

            BpmnNoteTikz.appendCollaborationNotes(sb = this, model = model, collab = collab, options = options)

            appendLine()
            appendLine("""\end{tikzpicture}""")
        }
    }

    // ── Pool rendering ────────────────────────────────────────────────────────

    /** A TeX length in pt without trailing zeros (`-0` becomes `0`). */
    private fun pt(v: Float): String {
        val text = fmtCoord(v).trimEnd('0').trimEnd('.')
        return "${if (text == "-0" || text.isEmpty()) "0" else text}pt"
    }

    /** TikZ y of a layout y: the layout grows downwards, TikZ upwards. */
    private fun ty(v: Float): String = pt(-v)

    private fun StringBuilder.appendCollaborationPools(plan: BpmnCollaborationLayout.Plan) {
        for (pool in plan.pools) {
            val participant = pool.participant
            val poolLabel = escapeLatex(participant.name ?: participant.id)
            appendLine("""  % Pool: ${commentSafe(participant.name ?: participant.id)}""")
            appendLine(
                """  \node[kuml-bpmn-pool,anchor=north west,minimum width=${pt(plan.width)},minimum height=${pt(pool.height)}] """ +
                    """(${pool.name}) at (0pt,${ty(pool.top)}) {};""",
            )
            appendLine(
                """  \node[kuml-bpmn-pool-header,anchor=north west,minimum height=${pt(pool.height)}] """ +
                    """(${pool.headerName}) at (${pool.name}.north west) {\rotatebox{90}{$poolLabel}};""",
            )

            for (lane in pool.lanes) {
                val laneLabel = escapeLatex(lane.lane.name ?: lane.lane.id)
                val x = BpmnCollaborationLayout.POOL_HEADER_W + BpmnCollaborationLayout.LANE_HEADER_W * lane.depth
                appendLine(
                    """  \node[kuml-bpmn-lane,anchor=north west,minimum width=${pt(plan.width - x)},minimum height=${pt(lane.height)}] """ +
                        """(${lane.name}) at (${pt(x)},${ty(pool.top + lane.top)}) {};""",
                )
                appendLine(
                    """  \node[kuml-bpmn-lane-header,anchor=north west,minimum height=${pt(lane.height)}] """ +
                        """(${lane.headerName}) at (${lane.name}.north west) {\rotatebox{90}{$laneLabel}};""",
                )
            }

            for (placed in pool.nodes) {
                appendFlowNodeAt(node = placed.node, nodeId = placed.name, pos = "at (${pt(placed.x)},${ty(pool.top + placed.y)})")
            }
            if (pool.omittedNodes) appendLine(OMITTED_NODES)

            val process = pool.process
            if (process != null) {
                val drawn = pool.nodes.map { it.node.id }.toSet()
                process.sequenceFlows
                    .filter { it.sourceRef in drawn && it.targetRef in drawn }
                    .forEach { flow -> appendSequenceFlow(flow) }
            }
        }
        if (plan.omittedPools) appendLine(OMITTED_POOLS)
    }

    // ── Flow node rendering ───────────────────────────────────────────────────

    private fun StringBuilder.appendFlowNode(
        node: BpmnFlowNode,
        prevId: String?,
    ) {
        // `at (0,0)` is a node position, not an option: it must stand outside the option brackets.
        val pos = if (prevId != null) "[right of=${sanitizeTikzId(prevId)}]" else "at (0,0)"
        appendFlowNodeAt(node = node, nodeId = sanitizeTikzId(node.id), pos = pos)
    }

    private fun StringBuilder.appendFlowNodeAt(
        node: BpmnFlowNode,
        nodeId: String,
        pos: String,
    ) {
        val label = escapeLatex(node.name ?: "")
        when (node) {
            is BpmnEvent -> appendEvent(event = node, nodeId = nodeId, label = label, pos = pos)
            is BpmnGateway -> appendGateway(gw = node, nodeId = nodeId, label = label, pos = pos)
            is BpmnTask -> appendTask(task = node, nodeId = nodeId, label = label, pos = pos)
            is BpmnSubProcess -> appendSubProcess(sp = node, nodeId = nodeId, label = label, pos = pos)
            is BpmnCallActivity -> appendCallActivity(ca = node, nodeId = nodeId, label = label, pos = pos)
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
        appendLine("""  \node[$style] ($nodeId) $pos {$symbol};""")
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
        appendLine("""  \node[kuml-bpmn-gateway] ($nodeId) $pos {$symbol};""")
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
        appendLine("""  \node[kuml-bpmn-task] ($nodeId) $pos {$label};""")
        appendTaskTypeMarker(taskType = task.taskType, nodeId = nodeId)
    }

    /**
     * Task-type marker in the top-left corner of the task, 4pt from the edges: one capital letter, or a small
     * gear for a service task. NONE draws nothing. `nodeId` is the sanitised TikZ name, never a raw id.
     */
    private fun StringBuilder.appendTaskTypeMarker(
        taskType: TaskType,
        nodeId: String,
    ) {
        val letter =
            when (taskType) {
                TaskType.USER -> "U"
                TaskType.SEND -> "S"
                TaskType.RECEIVE -> "R"
                TaskType.MANUAL -> "M"
                TaskType.SCRIPT -> "\\#"
                TaskType.BUSINESS_RULE -> "B"
                TaskType.SERVICE, TaskType.NONE -> null
            }
        when {
            letter != null ->
                appendLine(
                    """  \node[kuml-bpmn-tasktype,anchor=north west] at ([xshift=4pt,yshift=-4pt]$nodeId.north west) {$letter};""",
                )
            taskType == TaskType.SERVICE -> {
                appendLine("""  \begin{scope}[shift={([xshift=7pt,yshift=-7pt]$nodeId.north west)}]""")
                appendLine("""    \draw[line width=0.4pt] (0pt,0pt) circle (2.2pt);""")
                appendLine("""    \foreach \a in {0,45,...,315} \draw[line width=0.4pt] (\a:2.2pt) -- (\a:3.2pt);""")
                appendLine("""  \end{scope}""")
            }
            else -> Unit
        }
    }

    private fun StringBuilder.appendSubProcess(
        sp: BpmnSubProcess,
        nodeId: String,
        label: String,
        pos: String,
    ) {
        appendLine("""  \node[kuml-bpmn-subprocess] ($nodeId) $pos {$label\\\$[+]\$};""")
    }

    private fun StringBuilder.appendCallActivity(
        ca: BpmnCallActivity,
        nodeId: String,
        label: String,
        pos: String,
    ) {
        appendLine("""  \node[kuml-bpmn-callactivity] ($nodeId) $pos {$label};""")
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
                ?.let { " node[kuml-bpmn-label,near start,sloped,$EDGE_LABEL_BACKDROP] {${escapeLatex(it)}}" }
                ?: ""
        appendLine("""  \draw[kuml-bpmn-flow] ($src) --$labelPart$condPart ($tgt);""")
    }

    /**
     * Redefines `kuml-bpmn-msgflow` inside a collaboration picture with `arrows.meta` tips: an open
     * circle at the source and an open triangle at the target (sizes scaled from the SVG renderer).
     * The shared preamble still carries the obsolete `open triangle 45` definition, which TikZ
     * resolves only when used, so this later definition wins at every `\draw[kuml-bpmn-msgflow]`.
     * The text is a constant; no user input reaches it. Needs the `arrows.meta` library.
     */
    private const val MESSAGE_FLOW_STYLE_OVERRIDE =
        "  \\tikzset{kuml-bpmn-msgflow/.style={{Circle[open,length=4.5pt]}-{Triangle[open,length=5pt,width=3.75pt]}," +
            "draw=black,line width=0.8pt,dashed}}"

    /**
     * Opaque white backdrop for labels that sit on a line (see Nacharbeit 15 and the C4 renderer,
     * which uses the same literal). Constant text; no user input reaches it.
     */
    private const val EDGE_LABEL_BACKDROP = "fill=white, inner sep=1.5pt"

    /**
     * Placement of message-flow labels: beside the line, not on it. Pools are stacked, so a
     * message flow is always vertical and only 20pt long; after the circle (4.5pt) and the triangle
     * (5pt) only 10.5pt of line remain, so a label on the line would collide with the tips.
     */
    private const val MESSAGE_FLOW_LABEL_PLACEMENT = "midway,right=3pt"

    private const val OMITTED_NODES = "  % some flow nodes omitted: empty or duplicate name, or picture width limit reached"
    private const val OMITTED_POOLS = "  % some pools omitted: picture height limit reached"

    // ── Message flow rendering ────────────────────────────────────────────────

    private fun StringBuilder.appendMessageFlow(
        mf: MessageFlow,
        targets: Map<String, BpmnCollaborationLayout.Target>,
    ) {
        val src = sanitizeTikzId(mf.sourceRef)
        val tgt = sanitizeTikzId(mf.targetRef)
        val labelPart =
            mf.name
                ?.takeIf { it.isNotBlank() }
                ?.let { " node[kuml-bpmn-label,$MESSAGE_FLOW_LABEL_PLACEMENT] {${escapeLatex(it)}}" }
                ?: ""
        val from = targets[src]
        val to = targets[tgt]
        // An endpoint that is not drawn is written as it is: the figure then fails the source check instead of
        // silently losing the flow. Pool-to-pool flows keep the plain centre-to-border line.
        val container = BpmnCollaborationLayout.TargetKind.NODE
        val bothContainers = from != null && to != null && from.kind != container && to.kind != container
        if (from == null || to == null || bothContainers) {
            appendLine("""  \draw[kuml-bpmn-msgflow] ($src) --$labelPart ($tgt);""")
            return
        }
        val targetBelow = to.centerY > from.centerY
        val start = messageFlowPoint(name = src, own = from, other = tgt, otherTarget = to, towardsBelow = targetBelow)
        val end = messageFlowPoint(name = tgt, own = to, other = src, otherTarget = from, towardsBelow = !targetBelow)
        appendLine("""  \draw[kuml-bpmn-msgflow] $start --$labelPart $end;""")
    }

    /**
     * The point where a message flow meets [name]. A node is met at its south/north anchor; a pool or lane is met on
     * its facing border, straight above/below the node at the other end, so that the line stays vertical.
     */
    private fun messageFlowPoint(
        name: String,
        own: BpmnCollaborationLayout.Target,
        other: String,
        otherTarget: BpmnCollaborationLayout.Target,
        towardsBelow: Boolean,
    ): String {
        val side = if (towardsBelow) "south" else "north"
        return if (own.kind == BpmnCollaborationLayout.TargetKind.NODE) {
            "($name.$side)"
        } else {
            val border = if (otherTarget.centerY > own.centerY) "south" else "north"
            val otherSide = if (border == "south") "north" else "south"
            "($other.$otherSide |- $name.$border)"
        }
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
                    "align=center,text width=70pt,inner xsep=4pt,inner ysep=10pt},",
            )
            // Task-type marker (letter or gear) in the top-left corner; the 10pt top padding above keeps it clear of the label.
            appendLine(
                "$indent  kuml-bpmn-tasktype/.style={inner sep=0pt,font=\\sffamily\\bfseries\\fontsize{6}{6}\\selectfont},",
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
            // Obsolete: the tip `open triangle 45` no longer exists in current TikZ. TikZ resolves `>=` only
            // when a path uses the style, so this unused definition is harmless; the collaboration picture
            // overrides it locally (MESSAGE_FLOW_STYLE_OVERRIDE). Removing it globally would change every
            // golden and is left for the next golden regeneration.
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
