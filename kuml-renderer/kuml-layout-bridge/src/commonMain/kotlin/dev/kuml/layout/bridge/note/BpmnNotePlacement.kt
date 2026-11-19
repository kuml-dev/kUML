package dev.kuml.layout.bridge.note

import dev.kuml.bpmn.model.BpmnAssociation
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnLane
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.BpmnTextAnnotation
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.isHint
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size

/**
 * Note placement for BPMN process and collaboration diagrams (Nacharbeit 103). BPMN text annotations are ordinary ELK
 * nodes joined to their anchors by association edges. An annotation that carries a side hint (ABOVE/BELOW/LEFT/RIGHT)
 * is taken out of the ELK result and placed by [NotePlacer] against the finished layout, with the strict lane rules:
 * inside a lane (or pool), or fully outside all pools. Since Nacharbeit 120 the note first looks for a free position
 * inside its home lane, the innermost lane or pool that contains the anchor; only when none exists it may land in
 * another lane or outside, and `KUML-NOTE-W004` says so. A hint that no free position satisfies falls back to the
 * default rules and is reported as `KUML-NOTE-W004`. Choreography and conversation diagrams draw no
 * notes and are not handled here.
 *
 * Annotations without a hint (and NEAR hints) and layouts without a hinted annotation stay byte-identical: the
 * layout that was passed in is returned.
 */
public object BpmnNotePlacement {
    /** Width of the title band of a pool (matches the SVG renderer). */
    public const val POOL_BAND_PX: Float = 30f

    /** Width of the title band of a lane (matches the SVG renderer). */
    public const val LANE_BAND_PX: Float = 24f

    /** Extra height below an event or gateway that its external label blocks. */
    public const val LABEL_EXTENSION_PX: Float = 28f

    private const val LABEL_CHAR_PX: Float = 6.5f

    /** Places the hinted text annotations of a process [diagram]; returns [layout] unchanged when there is nothing to do. */
    public fun placeProcess(
        diagram: KumlDiagram,
        layout: LayoutResult,
        config: NotePlacementConfig = NotePlacementConfig(),
    ): NotePlacementResult {
        val subprocesses = diagram.elements.filterIsInstance<BpmnSubProcess>().filter { it.expanded }
        val frames =
            subprocesses.mapNotNull { sp ->
                layout.groups.entries
                    .firstOrNull { it.key.value == sp.id }
                    ?.let { sp.id to it.value.bounds }
            }
        // Outermost expanded sub-processes only: a note lies inside one of them or fully outside all.
        val containers =
            frames
                .filter { (id, r) -> frames.none { (otherId, o) -> otherId != id && contains(outer = o, inner = r) } }
                .map { (id, r) -> LaneRect(id = id, rect = r) }
        return place(
            annotations = diagram.elements.filterIsInstance<BpmnTextAnnotation>(),
            associations = diagram.elements.filterIsInstance<BpmnAssociation>(),
            layout = layout,
            containers = containers,
            anchorExtras = emptyMap(),
            bands = emptyMap(),
            labelled = labelledNodes(diagram.elements.filterIsInstance<BpmnFlowNode>()),
            config = config,
        )
    }

    /** Places the hinted text annotations of the collaboration of [diagram]; returns [layout] unchanged when there is nothing to do. */
    public fun placeCollaboration(
        model: BpmnModel,
        diagram: CollaborationDiagram,
        layout: LayoutResult,
        config: NotePlacementConfig = NotePlacementConfig(),
    ): NotePlacementResult {
        val collaboration = model.collaborations.firstOrNull { it.id == diagram.collaborationId }
        if (collaboration == null) return NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        val processes =
            collaboration.participants.mapNotNull { p ->
                p.processRef?.let { ref -> model.processes.firstOrNull { it.id == ref } }
            }
        val annotations = collaboration.textAnnotations + processes.flatMap { it.textAnnotations }
        val associations = collaboration.associations + processes.flatMap { it.associations }

        val bands = LinkedHashMap<String, TitleBand>()
        val leafIds = mutableListOf<String>()
        val participantRects = LinkedHashMap<String, AnchorGeometry>()

        fun walk(
            lanes: List<BpmnLane>,
            horizontal: Boolean,
        ) {
            for (lane in lanes) {
                bands[lane.id] = TitleBand(width = LANE_BAND_PX, horizontal = horizontal)
                if (lane.childLanes.isEmpty()) leafIds += lane.id else walk(lanes = lane.childLanes, horizontal = horizontal)
            }
        }
        for (p in collaboration.participants) {
            bands[p.id] = TitleBand(width = POOL_BAND_PX, horizontal = p.horizontal)
            layout.groups.entries
                .firstOrNull { it.key.value == p.id }
                ?.let { participantRects[p.id] = AnchorGeometry.Around(it.value.bounds) }
            if (p.lanes.isEmpty()) leafIds += p.id else walk(lanes = p.lanes, horizontal = p.horizontal)
        }
        val containers =
            leafIds.mapNotNull { id ->
                val band = bands.getValue(id)
                layout.groups.entries
                    .firstOrNull { it.key.value == id }
                    ?.let { LaneRect(id = id, rect = band.inner(it.value.bounds)) }
            }
        return place(
            annotations = annotations,
            associations = associations,
            layout = layout,
            containers = containers,
            anchorExtras = participantRects,
            bands = bands,
            labelled = labelledNodes(processes.flatMap { it.flowNodes }),
            config = config,
        )
    }

    /** The obstacle model of a BPMN layout: nodes (events and gateways with their label), group frames, title bands, edges. */
    public fun obstaclesOf(
        layout: LayoutResult,
        bands: Map<String, TitleBand>,
        labelled: Map<String, String> = emptyMap(),
    ): ObstacleIndex =
        Sysml2NotePlacement.obstaclesOf(
            layout = layout,
            nodeObstacle = { id, r -> nodeObstacle(id = id, r = r, labelled = labelled) },
            groupTitle = { id, b ->
                val band = bands[id]
                if (band == null) {
                    Rect(
                        origin = b.origin,
                        size = Size(width = b.size.width, height = minOf(Sysml2NotePlacement.COMPOSITE_TITLE_HEIGHT, b.size.height)),
                    )
                } else {
                    band.outer(b)
                }
            },
        )

    private fun nodeObstacle(
        id: String,
        r: Rect,
        labelled: Map<String, String>,
    ): Rect {
        val name = labelled[id] ?: return r
        val w = maxOf(r.size.width, name.length * LABEL_CHAR_PX)
        val cx = r.origin.x + r.size.width / 2f
        return Rect(origin = Point(x = cx - w / 2f, y = r.origin.y), size = Size(width = w, height = r.size.height + LABEL_EXTENSION_PX))
    }

    private fun labelledNodes(nodes: List<BpmnFlowNode>): Map<String, String> =
        buildMap {
            for (n in nodes) {
                val name =
                    when (n) {
                        is BpmnEvent -> n.name
                        is BpmnGateway -> n.name
                        else -> null
                    }
                if (!name.isNullOrBlank()) put(n.id, name)
            }
        }

    /**
     * Nacharbeit 120: per hinted note the rectangle of its home lane: the innermost container (smallest area, ties to
     * the first in [containers]) that contains the centre of the note's first anchor, boundary inclusive. A note whose
     * anchor lies in no container gets no entry and stays unrestricted; so does a note anchored on a pool or lane
     * itself (it has no "own" lane and may sit outside).
     */
    internal fun homeLanesOf(
        inputs: List<NotePlacementInput>,
        containers: List<LaneRect>,
        layout: LayoutResult,
        anchorExtras: Map<String, AnchorGeometry>,
        bands: Map<String, TitleBand> = emptyMap(),
    ): Map<String, Rect> {
        if (containers.isEmpty()) return emptyMap()
        val frames = containers.map { it.id }.toSet() + anchorExtras.keys + bands.keys
        val resolver = AnchorGeometryResolver(layout = layout, extras = anchorExtras)
        val out = LinkedHashMap<String, Rect>()
        for (input in inputs) {
            val first = input.anchors.firstOrNull { resolver.canResolve(it.targetId) } ?: continue
            if (first.targetId in frames) continue
            val geometry = resolver.resolve(first.targetId) ?: continue
            val c = centreOf(geometry)
            val home =
                containers
                    .filter { it.rect.contains(c) }
                    .minByOrNull { it.rect.size.width * it.rect.size.height }
            if (home != null) out[input.noteId] = home.rect
        }
        return out
    }

    private fun centreOf(g: AnchorGeometry): Point =
        when (g) {
            is AnchorGeometry.At -> g.p
            is AnchorGeometry.Port -> Point(x = g.box.cx, y = g.box.cy)
            is AnchorGeometry.Stem -> Point(x = g.x, y = (g.top + g.bottom) / 2f)
            is AnchorGeometry.Along -> polylineMidpoint(g.pts)
            is AnchorGeometry.Around -> Point(x = g.r.cx, y = g.r.cy)
            is AnchorGeometry.Ellipse -> Point(x = g.cx, y = g.cy)
        }

    private fun contains(
        outer: Rect,
        inner: Rect,
    ): Boolean =
        outer.left <= inner.left &&
            outer.top <= inner.top &&
            outer.right >= inner.right &&
            outer.bottom >= inner.bottom &&
            (outer.size.width > inner.size.width || outer.size.height > inner.size.height)

    @Suppress("LongParameterList")
    internal fun place(
        annotations: List<BpmnTextAnnotation>,
        associations: List<BpmnAssociation>,
        layout: LayoutResult,
        containers: List<LaneRect>,
        anchorExtras: Map<String, AnchorGeometry>,
        bands: Map<String, TitleBand>,
        labelled: Map<String, String>,
        config: NotePlacementConfig,
    ): NotePlacementResult {
        val none = NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        val inLayout =
            layout.nodes.keys
                .map { it.value }
                .toSet()
        val hinted = annotations.filter { it.note.placement.isHint() && it.id in inLayout }
        if (hinted.isEmpty()) return none
        val hintedIds = hinted.map { it.id }.toSet()
        val linksOfHinted = associations.filter { it.link.noteId in hintedIds }.map { it.id }.toSet()

        // The layout without the hinted notes and their association lines: what the placer measures against.
        val stripped =
            layout.copy(
                nodes = layout.nodes.filterKeys { it.value !in hintedIds },
                edges = layout.edges.filterKeys { it.value !in linksOfHinted },
            )
        val inputs =
            hinted.mapNotNull { a ->
                val resolver = AnchorGeometryResolver(layout = stripped, extras = anchorExtras)
                val anchors =
                    associations
                        .filter { it.link.noteId == a.id && resolver.canResolve(it.link.targetId) }
                        .map { NoteAnchorRef(linkId = it.id, targetId = it.link.targetId) }
                if (anchors.isEmpty()) {
                    null
                } else {
                    NotePlacementInput(
                        noteId = a.id,
                        body = a.note.body,
                        kind = a.note.kind,
                        placement = a.note.placement,
                        anchors = anchors,
                    )
                }
            }
        if (inputs.isEmpty()) return none

        val taken = inputs.map { it.noteId }.toSet()
        val takenLinks = associations.filter { it.link.noteId in taken }.map { it.id }.toSet()
        val base =
            layout.copy(
                nodes = layout.nodes.filterKeys { it.value !in taken },
                edges = layout.edges.filterKeys { it.value !in takenLinks },
            )
        val policy =
            StrictNotePolicy(
                lanes = containers,
                homeLanes =
                    homeLanesOf(
                        inputs = inputs,
                        containers = containers,
                        layout = base,
                        anchorExtras = anchorExtras,
                        bands = bands,
                    ),
                hardLabelsAndTitles = true,
                flow = FlowAxis.NONE,
                laneInset = 16f,
                laneOuterGap = 24f,
            ).toConfig(config)
        // Pool phantom nodes (0 x 0, id = pool id) are anchor stand-ins, not obstacles.
        val drawn = base.copy(nodes = base.nodes.filterValues { it.bounds.size.width > 0f || it.bounds.size.height > 0f })
        val placed =
            NotePlacer.place(
                layout = base,
                notes = inputs,
                resolver = AnchorGeometryResolver(layout = base, extras = anchorExtras),
                obstacles = obstaclesOf(layout = drawn, bands = bands, labelled = labelled),
                config = policy,
            )
        if (placed.hintNotHonoured.isNotEmpty()) {
            val lines = annotations.associate { it.id to it.note.sourceLine }
            val misses = placed.hintNotHonoured.map { it.copy(sourceLine = lines[it.noteId]) }
            NotePlacementMissCollector.report(misses)
            return placed.copy(hintNotHonoured = misses)
        }
        return placed
    }
}

/** The title band of a pool or lane: [width] px wide at the left edge ([horizontal]) or at the top of a vertical pool. */
public data class TitleBand(
    val width: Float,
    val horizontal: Boolean,
) {
    /** The band rectangle inside [b]. */
    internal fun outer(b: Rect): Rect =
        if (horizontal) {
            Rect(origin = b.origin, size = Size(width = minOf(width, b.size.width), height = b.size.height))
        } else {
            Rect(origin = b.origin, size = Size(width = b.size.width, height = minOf(width, b.size.height)))
        }

    /** [b] without the band. */
    internal fun inner(b: Rect): Rect =
        if (horizontal) {
            Rect(
                origin = Point(x = b.origin.x + width, y = b.origin.y),
                size = Size(width = maxOf(0f, b.size.width - width), height = b.size.height),
            )
        } else {
            Rect(
                origin = Point(x = b.origin.x, y = b.origin.y + width),
                size =
                    Size(
                        width = b.size.width,
                        height =
                            maxOf(
                                0f,
                                b.size.height - width,
                            ),
                    ),
            )
        }
}
