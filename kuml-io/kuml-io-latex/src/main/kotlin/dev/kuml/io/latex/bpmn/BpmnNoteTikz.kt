package dev.kuml.io.latex.bpmn

import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnCollaboration
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.core.model.note.NoteKind
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.core.model.note.isHint
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
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import dev.kuml.layout.bridge.note.PlacementHintMiss
import kotlin.math.abs

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

    /** The sides a process-view note can be asked to sit on besides the default tier above the flow. */
    private enum class Side { BELOW, LEFT, RIGHT }

    /** One placed process-view note: where it goes, in the local frame of its shift, and its anchor coordinate. */
    private class Placement(
        val item: Item,
        val shift: String,
        val rect: Rect,
        val anchorX: Float,
        val anchorY: Float,
    )

    /** Gap between the flow and the first side column (and between stacked notes of a side). */
    const val NOTE_SIDE_GAP = 16f

    private fun sideOf(placement: NotePlacement?): Side? =
        when (placement) {
            NotePlacement.BELOW -> Side.BELOW
            NotePlacement.LEFT -> Side.LEFT
            NotePlacement.RIGHT -> Side.RIGHT
            else -> null
        }

    private fun missOf(
        item: Item,
        placed: NotePlacement = NotePlacement.NEAR,
    ): PlacementHintMiss =
        PlacementHintMiss(
            noteId = item.annotation.id,
            wanted = item.annotation.note.placement ?: NotePlacement.NEAR,
            placed = placed,
            sourceLine = item.annotation.note.sourceLine,
        )

    /**
     * Annotations of a process view (all of them; the process view draws every flow node).
     *
     * The view has no absolute geometry, so a placement hint is honoured with TikZ anchors outside the flow:
     * ABOVE (and no hint, NEAR) is the tier above the flow, BELOW a mirrored tier under it, LEFT and RIGHT columns
     * beside it, vertically centred on the first drawn anchor. All of them are outside the bounding box of the
     * flow, so they cannot cover a node. A hint that cannot be honoured (no drawn anchor, or a stack cut by the
     * size limit) is reported as `KUML-NOTE-W004` through [NotePlacementMissCollector].
     */
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
        val misses = mutableListOf<PlacementHintMiss>()

        // Anchor: first association of the note whose target is a drawn flow node.
        fun anchorOf(a: BpmnTextAnnotation): String? =
            process.associations
                .firstOrNull { it.link.noteId == a.id && it.link.targetId in drawn }
                ?.link
                ?.targetId

        // A side hint needs a drawn anchor; without one the note stays in the default tier and the hint is reported.
        val sideItems = LinkedHashMap<Side, MutableList<Item>>()
        val aboveItems = mutableListOf<Item>()
        for (item in items) {
            val side = sideOf(item.annotation.note.placement)
            when {
                side == null -> aboveItems += item
                anchorOf(item.annotation) == null -> {
                    misses += missOf(item = item)
                    aboveItems += item
                }
                else -> sideItems.getOrPut(side) { mutableListOf() } += item
            }
        }

        sb.appendLine()
        sb.appendLine("$ind% text annotations")
        if (process.flowNodes.isEmpty()) {
            sb.appendLine("$ind\\coordinate (${p}base) at (0pt,0pt);")
        } else {
            sb.appendLine("$ind\\coordinate (${p}base) at (current bounding box.north);")
        }
        // The side bases are taken before any note is drawn, so a note never moves the base of another side.
        for (side in Side.entries) {
            if (side !in sideItems) continue
            val anchor =
                when (side) {
                    Side.BELOW -> "south"
                    Side.LEFT -> "west"
                    Side.RIGHT -> "east"
                }
            sb.appendLine("$ind\\coordinate (${p}${baseSuffix(side)}) at (current bounding box.$anchor);")
        }

        fun shiftOf(item: Item): String {
            val target = anchorOf(item.annotation)
            return if (target == null) "(${p}base)" else "(${BpmnLatexRenderer.sanitizeTikzId(target)}.center |- ${p}base)"
        }

        // Floors: floor k sits above the floors below it; stop at the height limit.
        val placements = mutableListOf<Placement>()
        var bottom = NOTE_BASE_GAP
        var truncated = false
        for ((index, item) in aboveItems.withIndex()) {
            if (bottom + item.size.height > MAX_STACK_PT) {
                truncated = true
                for (rest in aboveItems.drop(index)) {
                    if (rest.annotation.note.placement
                            .isHint()
                    ) {
                        misses += missOf(item = rest)
                    }
                }
                break
            }
            val w = item.size.width
            val h = item.size.height
            placements +=
                Placement(
                    item = item,
                    shift = shiftOf(item),
                    rect = Rect(origin = Point(x = -w / 2f, y = -(bottom + h)), size = Size(width = w, height = h)),
                    anchorX = 0f,
                    anchorY = bottom,
                )
            bottom += h + NOTE_GAP
        }
        for ((side, list) in sideItems) {
            var off = if (side == Side.BELOW) NOTE_BASE_GAP else NOTE_SIDE_GAP
            for ((index, item) in list.withIndex()) {
                val w = item.size.width
                val h = item.size.height
                val extent = if (side == Side.BELOW) h else w
                if (off + extent > MAX_STACK_PT) {
                    truncated = true
                    for (rest in list.drop(index)) misses += missOf(item = rest)
                    break
                }
                val target = BpmnLatexRenderer.sanitizeTikzId(anchorOf(item.annotation)!!)
                placements +=
                    when (side) {
                        Side.BELOW ->
                            Placement(
                                item = item,
                                shift = "($target.center |- ${p}${baseSuffix(side)})",
                                rect = Rect(origin = Point(x = -w / 2f, y = off), size = Size(width = w, height = h)),
                                anchorX = 0f,
                                anchorY = -off,
                            )
                        Side.RIGHT ->
                            Placement(
                                item = item,
                                shift = "(${p}${baseSuffix(side)} |- $target.center)",
                                rect = Rect(origin = Point(x = off, y = -h / 2f), size = Size(width = w, height = h)),
                                anchorX = off,
                                anchorY = 0f,
                            )
                        Side.LEFT ->
                            Placement(
                                item = item,
                                shift = "(${p}${baseSuffix(side)} |- $target.center)",
                                rect = Rect(origin = Point(x = -off - w, y = -h / 2f), size = Size(width = w, height = h)),
                                anchorX = -off,
                                anchorY = 0f,
                            )
                    }
                off += extent + NOTE_GAP
            }
        }

        // Pass A: anchor points (coordinates only), (the association lines are drawn after the notes).
        for (pl in placements) {
            sb.appendLine("$ind\\begin{scope}[shift={${pl.shift}}]")
            val ax = if (pl.anchorX == 0f) "0pt" else "${fmtCoord(pl.anchorX)}pt"
            sb.appendLine("$ind  \\coordinate (${p}a${pl.item.k}) at ($ax,${fmtCoord(pl.anchorY)}pt);")
            sb.appendLine("$ind\\end{scope}")
        }

        for (pl in placements) {
            sb.appendLine("$ind\\begin{scope}[shift={${pl.shift}}]")
            NoteTikz.renderNote(
                spec =
                    NoteTikzSpec(
                        id = "$p${pl.item.k}",
                        body = pl.item.annotation.note.body,
                        kind = pl.item.annotation.note.kind,
                        bounds = pl.rect,
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
        for (pl in placements) {
            appendAssociations(sb = sb, associations = process.associations, item = pl.item, drawn = drawn, p = p, options = options)
        }
        if (truncated) sb.appendLine("$ind$OMITTED_NOTE")
        NotePlacementMissCollector.report(misses)
    }

    private fun baseSuffix(side: Side): String =
        when (side) {
            Side.BELOW -> "bs"
            Side.LEFT -> "bw"
            Side.RIGHT -> "be"
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

        // Hinted notes with a drawn flow node as anchor are placed by the SVG rule on an estimated layout; every other
        // note keeps the default rows below the pools (they are fixed obstacles for the hinted ones).
        val candidates =
            all.filter {
                it.annotation.note.placement
                    .isHint() &&
                    linksOf(it).any { l -> l.link.targetId in drawn }
            }
        val absolute = LinkedHashMap<Item, Rect>()
        if (candidates.isNotEmpty()) {
            val candidateIds = candidates.map { it.k }.toSet()
            val rowItems = items.filter { it.k !in candidateIds }
            val (rowPositions, _) = rowLayout(items = rowItems, maxStack = MAX_STACK_PT - plan.height)
            val obstacles =
                rowPositions.associate { r ->
                    "$p${r.item.k}" to
                        Rect(origin = Point(x = r.x, y = plan.height + r.rowTop), size = r.item.size)
                }
            val layout =
                BpmnNoteLayoutBridge.syntheticLayout(
                    plan = plan,
                    obstacles = obstacles,
                    hinted = candidates.map { BpmnNoteLayoutBridge.Input(id = it.annotation.id, size = it.size) },
                )
            val result = BpmnNoteLayoutBridge.place(model = model, collab = collab, layout = layout)
            for (c in candidates) {
                val rect = BpmnNoteLayoutBridge.placedRect(result = result, id = c.annotation.id) ?: continue
                absolute[c] = rect
            }
        }
        val rowsFor = items.filter { it !in absolute.keys }

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
        // The base is the south west of the pools, which reach plan.height below the origin.
        val (positions, truncated) = rowLayout(items = rowsFor, maxStack = MAX_STACK_PT - plan.height)
        for (pos in positions) {
            val item = pos.item
            NoteTikz.renderNote(
                spec =
                    NoteTikzSpec(
                        id = "$p${item.k}",
                        body = item.annotation.note.body,
                        kind = item.annotation.note.kind,
                        bounds = Rect(origin = Point(x = pos.x, y = pos.rowTop), size = item.size),
                        frame = NoteFrameStyle.BRACKET,
                    ),
                options = options,
                out = sb,
            )
            // Anchor at the top centre of the note; the named invisible note node is anchored north west.
            sb.appendLine("$ind\\coordinate (${p}a${item.k}) at (${tikzId(NodeId("$p${item.k}"))}.north);")
        }
        sb.appendLine("$ind\\end{scope}")
        for (pos in positions) {
            appendCollaborationLines(
                sb = sb,
                item = pos.item,
                links = linksOf(pos.item),
                drawn = drawn,
                p = p,
                options = options,
            )
        }

        // Hinted notes at their absolute plan position (the pools start at the origin, y grows downwards in the plan).
        val misses = mutableListOf<PlacementHintMiss>()
        var omittedAbsolute = false
        for ((item, rect) in absolute) {
            val far =
                listOf(rect.origin.x, rect.origin.y, rect.origin.x + rect.size.width, rect.origin.y + rect.size.height)
                    .maxOf { abs(it) }
            if (far > MAX_STACK_PT) {
                omittedAbsolute = true
                misses += missOf(item = item)
                continue
            }
            NoteTikz.renderNote(
                spec =
                    NoteTikzSpec(
                        id = "$p${item.k}",
                        body = item.annotation.note.body,
                        kind = item.annotation.note.kind,
                        bounds = rect,
                        frame = NoteFrameStyle.BRACKET,
                    ),
                options = options,
                out = sb,
            )
            val side = anchorSide(rect = rect, target = linksOf(item).firstNotNullOf { drawn[it.link.targetId] }, plan = plan)
            sb.appendLine("$ind\\coordinate (${p}a${item.k}) at (${tikzId(NodeId("$p${item.k}"))}.$side);")
            appendCollaborationLines(sb = sb, item = item, links = linksOf(item), drawn = drawn, p = p, options = options)
        }
        if (truncated || omittedAbsolute) sb.appendLine("$ind$OMITTED_NOTE")
        NotePlacementMissCollector.report(misses)
    }

    private class RowPos(
        val item: Item,
        val x: Float,
        val rowTop: Float,
    )

    /** The default rows below the pools: [items] left to right, wrapping at [ROW_MAX_W]; stops at [maxStack]. */
    private fun rowLayout(
        items: List<Item>,
        maxStack: Float,
    ): Pair<List<RowPos>, Boolean> {
        var x = 0f
        var rowTop = NOTE_BASE_GAP
        var rowH = 0f
        val out = mutableListOf<RowPos>()
        for (item in items) {
            val w = item.size.width
            val h = item.size.height
            if (x > 0f && x + w > ROW_MAX_W) {
                rowTop += rowH + NOTE_GAP
                x = 0f
                rowH = 0f
            }
            if (rowTop + h > maxStack) return out to true
            out += RowPos(item = item, x = x, rowTop = rowTop)
            x += w + NOTE_GAP
            rowH = maxOf(rowH, h)
        }
        return out to false
    }

    /** The note side facing [target]: vertical when the target is more above/below than beside the note. */
    private fun anchorSide(
        rect: Rect,
        target: BpmnCollaborationLayout.PlacedNode,
        plan: BpmnCollaborationLayout.Plan,
    ): String {
        val ty = plan.targets[target.name]?.centerY ?: target.y
        val dx = target.x - (rect.origin.x + rect.size.width / 2f)
        val dy = ty - (rect.origin.y + rect.size.height / 2f)
        return if (abs(dy) >= abs(dx)) {
            if (dy > 0f) "south" else "north"
        } else {
            if (dx > 0f) "east" else "west"
        }
    }

    private fun appendCollaborationLines(
        sb: StringBuilder,
        item: Item,
        links: List<BpmnAssociation>,
        drawn: Map<String, BpmnCollaborationLayout.PlacedNode>,
        p: String,
        options: LatexRenderOptions,
    ) {
        for (assoc in links) {
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
}
