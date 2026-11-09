package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.fmtCoord
import dev.kuml.io.latex.note.NoteTikz
import dev.kuml.io.latex.note.NoteTikzAnchor
import dev.kuml.io.latex.note.NoteTikzSpec
import dev.kuml.io.latex.tikzId
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.NoteFrameStyle
import dev.kuml.layout.bridge.NoteLayout

/**
 * TikZ output of BPMN text annotations (open bracket, dotted association) for the LaTeX renderer.
 *
 * The placement is a simple LaTeX-side rule, not the SVG note router: in a process view every annotation gets one
 * floor above the flow-node row (floors stack upwards in declaration order), centred over its first drawn target.
 * In a collaboration view the annotations form rows below the pools. TikZ computes the base line from the real
 * bounding box (`current bounding box`), so long task names and edge labels are never covered.
 *
 * Nothing user-controlled reaches the output except escaped note text: coordinate and node names are built from a
 * reserved prefix and the annotation index, never from an id, and the constant comments name no id.
 */
internal object BpmnNoteTikz {
    const val NOTE_BASE_GAP = 16f
    const val NOTE_GAP = 12f

    /** TeX stops at 16383.99pt; stay well below it so a model with 200 long notes cannot break the compile. */
    const val MAX_STACK_PT = 12000f
    const val ROW_MAX_W = 600f

    internal const val OMITTED_ANCHOR = "% note anchor omitted: target not drawn in this view"
    internal const val OMITTED_NOTE = "% note omitted: picture height limit reached"

    /** A name prefix no flow node name starts with (neither directly nor behind the `n_` of a note node). */
    fun prefixFor(reserved: Set<String>): String {
        var p = "kumlnote"
        while (reserved.any { it.startsWith(p) || it.startsWith("n_$p") }) p += "x"
        return p
    }

    private class Item(
        val k: Int,
        val annotation: BpmnTextAnnotation,
        val size: Size,
    )

    private fun items(annotations: List<BpmnTextAnnotation>): List<Item> =
        annotations.mapIndexed { k, a ->
            Item(k = k, annotation = a, size = NoteLayout.sizeOf(body = a.note.body, frame = NoteFrameStyle.BRACKET, kind = a.note.kind))
        }

    /** Annotations shown in a collaboration view: its own plus those of the referenced processes (as in the SVG output). */
    fun collaborationAnnotations(
        model: BpmnModel,
        collab: BpmnCollaboration,
    ): List<BpmnTextAnnotation> =
        collab.textAnnotations +
            collab.participants
                .mapNotNull { pt -> pt.processRef?.let { ref -> model.processes.firstOrNull { it.id == ref } } }
                .flatMap { it.textAnnotations }

    fun noteKinds(annotations: List<BpmnTextAnnotation>): List<NoteKind> = annotations.map { it.note.kind }

    /** Annotations of a process view (all of them; the process view draws every flow node). */
    fun appendProcessNotes(
        sb: StringBuilder,
        process: BpmnProcess,
        options: LatexRenderOptions,
    ) {
        val annotations = process.textAnnotations
        if (annotations.isEmpty()) return
        val ind = options.indent
        // A node whose id sanitises to nothing has no usable TikZ name and counts as not drawn.
        val drawn =
            process.flowNodes
                .map { it.id }
                .filter { BpmnLatexRenderer.sanitizeTikzId(it).isNotEmpty() }
                .toSet()
        val reserved = process.flowNodes.map { BpmnLatexRenderer.sanitizeTikzId(it.id) }.toSet()
        val p = prefixFor(reserved)
        val items = items(annotations)

        // Anchor: first association of the note whose target is a drawn flow node.
        fun anchorOf(a: BpmnTextAnnotation): String? =
            process.associations
                .firstOrNull { it.link.noteId == a.id && it.link.targetId in drawn }
                ?.link
                ?.targetId

        sb.appendLine()
        sb.appendLine("$ind% text annotations")
        if (process.flowNodes.isEmpty()) {
            sb.appendLine("$ind\\coordinate (${p}base) at (0pt,0pt);")
        } else {
            sb.appendLine("$ind\\coordinate (${p}base) at (current bounding box.north);")
        }

        // Floors: floor k sits above the floors below it; stop at the height limit.
        val placed = mutableListOf<Pair<Item, Float>>() // item to bottom (pt above the base)
        var bottom = NOTE_BASE_GAP
        var truncated = false
        for (item in items) {
            if (bottom + item.size.height > MAX_STACK_PT) {
                truncated = true
                break
            }
            placed += item to bottom
            bottom += item.size.height + NOTE_GAP
        }

        fun shiftOf(item: Item): String {
            val target = anchorOf(item.annotation)
            return if (target == null) "(${p}base)" else "(${BpmnLatexRenderer.sanitizeTikzId(target)}.center |- ${p}base)"
        }

        // Pass A: anchor points (coordinates only), (the association lines are drawn after the notes).
        for ((item, y) in placed) {
            sb.appendLine("$ind\\begin{scope}[shift={${shiftOf(item)}}]")
            sb.appendLine("$ind  \\coordinate (${p}a${item.k}) at (0pt,${fmtCoord(y)}pt);")
            sb.appendLine("$ind\\end{scope}")
        }

        for ((item, y) in placed) {
            sb.appendLine("$ind\\begin{scope}[shift={${shiftOf(item)}}]")
            val w = item.size.width
            val h = item.size.height
            NoteTikz.renderNote(
                spec =
                    NoteTikzSpec(
                        id = "$p${item.k}",
                        body = item.annotation.note.body,
                        kind = item.annotation.note.kind,
                        bounds = Rect(origin = Point(x = -w / 2f, y = -(y + h)), size = Size(width = w, height = h)),
                        frame = NoteFrameStyle.BRACKET,
                    ),
                options = options,
                out = sb,
            )
            sb.appendLine("$ind\\end{scope}")
        }
        // Association lines last: notes are wider than the node spacing, and a white note area drawn on top would
        // hide the line of a higher floor and make it look attached to the wrong note. A line that crosses a lower
        // note stays visible and continuous from its own note to its own target.
        for ((item, _) in placed) {
            appendAssociations(sb = sb, associations = process.associations, item = item, drawn = drawn, p = p, options = options)
        }
        if (truncated) sb.appendLine("$ind$OMITTED_NOTE")
    }

    private fun appendAssociations(
        sb: StringBuilder,
        associations: List<BpmnAssociation>,
        item: Item,
        drawn: Set<String>,
        p: String,
        options: LatexRenderOptions,
    ) {
        val links = associations.filter { it.link.noteId == item.annotation.id }
        for (assoc in links) {
            if (assoc.link.targetId in drawn) {
                NoteTikz.renderAnchorNamed(
                    fromName = "${p}a${item.k}",
                    toName = BpmnLatexRenderer.sanitizeTikzId(assoc.link.targetId),
                    style = NoteTikzAnchor.DOTTED,
                    options = options,
                    out = sb,
                )
            } else {
                sb.appendLine("${options.indent}$OMITTED_ANCHOR")
            }
        }
    }

    /**
     * Annotations of a collaboration view: rows below the pools. The rows follow the x position of each note's
     * first drawn target (notes without a drawn target come last, in declaration order), and a dotted association
     * line runs from the top edge of the note to every association target that is drawn in this view. A target
     * outside the view (or a note cut by the height limit) gets a constant comment instead.
     */
    fun appendCollaborationNotes(
        sb: StringBuilder,
        model: BpmnModel,
        collab: BpmnCollaboration,
        options: LatexRenderOptions,
    ) {
        val referenced = collab.participants.mapNotNull { pt -> pt.processRef?.let { ref -> model.processes.firstOrNull { it.id == ref } } }
        val annotations = collaborationAnnotations(model = model, collab = collab)
        if (annotations.isEmpty()) return
        val associations = collab.associations + referenced.flatMap { it.associations }
        val ind = options.indent
        val plan = BpmnCollaborationLayout.plan(model = model, collab = collab)
        // Pools, lanes and flow nodes are all named TikZ nodes now; the note prefix must collide with none of them.
        val reserved = plan.definedNames
        val p = prefixFor(reserved)
        // Drawn flow nodes by id: first occurrence wins; a node without a usable TikZ name is never in the plan.
        val drawn = LinkedHashMap<String, BpmnCollaborationLayout.PlacedNode>()
        for (pool in plan.pools) for (placed in pool.nodes) drawn.putIfAbsent(placed.node.id, placed)

        fun linksOf(item: Item) = associations.filter { it.link.noteId == item.annotation.id }

        fun firstTargetX(item: Item): Float? = linksOf(item).firstNotNullOfOrNull { drawn[it.link.targetId]?.x }
        val all = items(annotations)
        // Stable sort: notes with a drawn target by its x, the others behind them in declaration order.
        val items = all.filter { firstTargetX(it) != null }.sortedBy { firstTargetX(it) } + all.filter { firstTargetX(it) == null }

        sb.appendLine()
        sb.appendLine("$ind% text annotations")
        if (collab.participants.isEmpty()) {
            sb.appendLine("$ind\\coordinate (${p}base) at (0pt,0pt);")
        } else {
            sb.appendLine("$ind\\coordinate (${p}base) at (current bounding box.south west);")
        }
        for (item in all) {
            for (assoc in linksOf(item)) {
                if (assoc.link.targetId !in drawn) sb.appendLine("$ind$OMITTED_ANCHOR")
            }
        }
        sb.appendLine("$ind\\begin{scope}[shift={(${p}base)}]")
        var x = 0f
        var rowTop = NOTE_BASE_GAP
        var rowH = 0f
        var truncated = false
        val shown = mutableListOf<Item>()
        // The base is the south west of the pools, which reach plan.height below the origin.
        val maxStack = MAX_STACK_PT - plan.height
        for (item in items) {
            val w = item.size.width
            val h = item.size.height
            if (x > 0f && x + w > ROW_MAX_W) {
                rowTop += rowH + NOTE_GAP
                x = 0f
                rowH = 0f
            }
            if (rowTop + h > maxStack) {
                truncated = true
                break
            }
            NoteTikz.renderNote(
                spec =
                    NoteTikzSpec(
                        id = "$p${item.k}",
                        body = item.annotation.note.body,
                        kind = item.annotation.note.kind,
                        bounds = Rect(origin = Point(x = x, y = rowTop), size = Size(width = w, height = h)),
                        frame = NoteFrameStyle.BRACKET,
                    ),
                options = options,
                out = sb,
            )
            // Anchor at the top centre of the note; the named invisible note node is anchored north west.
            sb.appendLine("$ind\\coordinate (${p}a${item.k}) at (${tikzId(NodeId("$p${item.k}"))}.north);")
            shown += item
            x += w + NOTE_GAP
            rowH = maxOf(rowH, h)
        }
        sb.appendLine("$ind\\end{scope}")
        for (item in shown) {
            for (assoc in linksOf(item)) {
                val target = drawn[assoc.link.targetId] ?: continue
                NoteTikz.renderAnchorNamed(
                    fromName = "${p}a${item.k}",
                    toName = target.name,
                    style = NoteTikzAnchor.DOTTED,
                    options = options,
                    out = sb,
                )
            }
        }
        if (truncated) sb.appendLine("$ind$OMITTED_NOTE")
    }
}
