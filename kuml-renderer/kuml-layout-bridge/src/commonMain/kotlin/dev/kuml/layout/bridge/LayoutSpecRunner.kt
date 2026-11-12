package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.core.model.layout.LayoutOption
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** A diagnostic produced while applying a [LayoutSpec]. */
public data class LayoutSpecFinding(
    val code: String,
    val message: String,
    val isError: Boolean = false,
)

/** Layout result plus the diagnostics of the spec application. */
public data class SpecLayoutOutcome(
    val result: LayoutResult,
    val findings: List<LayoutSpecFinding>,
)

/**
 * Applies a diagram's [LayoutSpec] around a layout engine call: flow direction, layer hints and the
 * `maxWidth` / `aspectRatio` iteration (at most [LayoutLimits.MAX_ITERATIONS] layout runs).
 */
public object LayoutSpecRunner {
    /** Maps the spec's flow direction onto [base]. */
    public fun hintsWith(
        base: LayoutHints,
        spec: LayoutSpec?,
    ): LayoutHints {
        val flow = spec?.direction ?: return base
        return base.copy(
            direction =
                when (flow) {
                    LayoutFlow.Down -> LayoutDirection.TopToBottom
                    LayoutFlow.Up -> LayoutDirection.BottomToTop
                    LayoutFlow.Right -> LayoutDirection.LeftToRight
                    LayoutFlow.Left -> LayoutDirection.RightToLeft
                },
        )
    }

    /** Applies wrapColumns / sameLayer / first / last of [spec] to [graph] as layer hints. */
    public fun graphWith(
        graph: LayoutGraph,
        spec: LayoutSpec?,
    ): LayoutGraph = graph.withSpec(spec = spec)

    /** Options of [spec] that are set but not honoured by [kind]. */
    public fun ignoredOptions(
        spec: LayoutSpec?,
        kind: SpecDiagramKind,
    ): List<LayoutOption> {
        if (spec == null) return emptyList()
        val set =
            buildList {
                if (spec.direction != null) add(LayoutOption.DIRECTION)
                if (spec.wrapColumns != null) add(LayoutOption.WRAP_COLUMNS)
                if (spec.sameLayer.isNotEmpty()) add(LayoutOption.SAME_LAYER)
                if (spec.first.isNotEmpty()) add(LayoutOption.FIRST)
                if (spec.last.isNotEmpty()) add(LayoutOption.LAST)
                if (spec.maxWidth != null) add(LayoutOption.MAX_WIDTH)
                if (spec.aspectRatio != null) add(LayoutOption.ASPECT_RATIO)
                if (spec.labelMaxWidth != null) add(LayoutOption.LABEL_MAX_WIDTH)
                if (spec.showSubjectEdges != null) add(LayoutOption.SHOW_SUBJECT_EDGES)
                if (spec.actorSide != null) add(LayoutOption.ACTOR_SIDE)
            }
        val effective = LayoutEffectMatrix.effectiveFor(kind)
        return set.filter { it !in effective }
    }

    /** One `KUML-LAYOUT-W001` per ignored option (never per element); nothing for an empty block. */
    public fun noEffectFindings(
        spec: LayoutSpec?,
        kind: SpecDiagramKind,
        diagramName: String?,
    ): List<LayoutSpecFinding> {
        val where =
            if (diagramName !=
                null
            ) {
                "diagram '$diagramName' (${kind.name.lowercase()})"
            } else {
                "this diagram type (${kind.name.lowercase()})"
            }
        return ignoredOptions(spec = spec, kind = kind).map {
            LayoutSpecFinding(
                code = LayoutCodes.NO_EFFECT,
                message = "layout option '${it.name.lowercase()}' has no effect on $where.",
            )
        }
    }

    /**
     * Static check of [spec] without running a layout: W001 for options the diagram art ignores, and
     * W004 ([LayoutCodes.UNKNOWN_ID]) / range findings for ids not in [knownIds] (a null [knownIds] means the
     * diagram's ids could not be determined, so unknown ids are not reported). Used by `kuml validate`.
     */
    public fun validate(
        kind: SpecDiagramKind,
        spec: LayoutSpec?,
        knownIds: Set<String>?,
        diagramName: String? = null,
        edgeDegrees: Map<String, EdgeDegree>? = null,
    ): List<LayoutSpecFinding> {
        if (spec == null) return emptyList()
        val findings = noEffectFindings(spec = spec, kind = kind, diagramName = diagramName).toMutableList()
        val checked = sanitize(spec = spec, findings = findings)
        checkIdSet(spec = checked, known = knownIds, findings = findings)
        findings += layerPinFindings(kind = kind, spec = checked, degrees = edgeDegrees)
        return findings
    }

    /**
     * Runs [engine] on [graph] with [spec] applied. Values outside [LayoutLimits] are dropped and
     * reported ([LayoutCodes.OUT_OF_RANGE]); an unreachable `maxWidth` / `aspectRatio` is reported
     * ([LayoutCodes.MAX_WIDTH_UNREACHED] / [LayoutCodes.ASPECT_UNREACHED]).
     */
    public fun layout(
        engine: KumlLayoutEngine,
        graph: LayoutGraph,
        hints: LayoutHints,
        spec: LayoutSpec?,
        kind: SpecDiagramKind = SpecDiagramKind.UML_CLASS,
        diagramName: String? = null,
    ): SpecLayoutOutcome {
        val pinFindings = layerPinFindings(kind = kind, spec = spec, degrees = spec?.let { LayerPinCheck.edgeDegrees(graph) })
        if (kind == SpecDiagramKind.UML_CLASS) {
            val umlOutcome = layoutUmlClass(engine = engine, graph = graph, hints = hints, spec = spec, diagramName = diagramName)
            return umlOutcome.copy(findings = umlOutcome.findings + pinFindings)
        }
        val outcome =
            layoutInner(engine = engine, graph = graph, hints = hints, spec = spec, kind = kind, diagramName = diagramName)
                .let { it.copy(findings = it.findings + pinFindings) }
        // Wave 2E-a: every requirement diagram (grid or legacy) gets spread endpoints and nudged parallel segments.
        return when (kind) {
            SpecDiagramKind.SYSML2_REQ -> outcome.copy(result = ReqEdgeBands.apply(result = outcome.result, graph = graph))
            else -> outcome
        }
    }

    /**
     * Wave 2G-a: UML layout with the auto wrap (more than [AUTO_WRAP_THRESHOLD] nodes in a layer wrap into
     * `ceil(sqrt(n))` columns unless `wrapColumns`, `aspectRatio` or `maxWidth` is given), the grid of wide generalisation
     * fans ([UmlWrapGrid]) and the band of edgeless classifiers ([UmlEdgelessBand]).
     */
    private fun layoutUmlClass(
        engine: KumlLayoutEngine,
        graph: LayoutGraph,
        hints: LayoutHints,
        spec: LayoutSpec?,
        diagramName: String?,
    ): SpecLayoutOutcome {
        val split = UmlEdgelessBand.split(graph)
        val engineGraph = split?.first ?: graph
        // Nacharbeit 85: the engine sees generalisations general -> specific so the general class is laid out first (on top).
        val oriented = reverseGeneralizations(engineGraph)
        val effective = withAutoWrap(spec = spec, graph = oriented)
        val outcome =
            layoutInner(
                engine = engine,
                graph = oriented,
                hints = hints,
                spec = effective,
                kind = SpecDiagramKind.UML_CLASS,
                diagramName = diagramName,
            )
        var result = restoreGeneralizations(result = outcome.result, original = engineGraph)
        val columns = effective?.wrapColumns?.takeIf { it in 1..LayoutLimits.WRAP_COLUMNS_MAX }
        if (columns != null) {
            val direction = hintsWith(base = hints, spec = sanitize(spec = effective, findings = mutableListOf())).direction
            result = UmlWrapGrid.apply(result = result, graph = engineGraph, direction = direction, columns = columns)
        }
        if (split != null) result = UmlEdgelessBand.place(result = result, band = split.second)
        return outcome.copy(result = result)
    }

    /**
     * Nacharbeit 85: the copy of [graph] in which every UML generalisation (`gen::` id) runs general -> specific, so the engine
     * layers the general class above its specialisations. Other edges (and self-loops) keep their orientation.
     */
    internal fun reverseGeneralizations(graph: LayoutGraph): LayoutGraph {
        if (graph.edges.none { isGeneralization(it) }) return graph
        return graph.copy(
            edges = graph.edges.map { if (isGeneralization(it)) it.copy(source = it.target, target = it.source) else it },
        )
    }

    /** Turns the routes of the generalisations in [result] back to specific -> general, the orientation of [original]. */
    internal fun restoreGeneralizations(
        result: LayoutResult,
        original: LayoutGraph,
    ): LayoutResult {
        val flipped =
            original.edges
                .filter { isGeneralization(it) }
                .map { it.id }
                .toSet()
        if (flipped.isEmpty()) return result
        return result.copy(edges = result.edges.mapValues { (id, route) -> if (id in flipped) reverseRoute(route) else route })
    }

    private fun isGeneralization(edge: LayoutEdge): Boolean =
        edge.id.value.startsWith(GENERALIZATION_PREFIX) && edge.source.nodeId != edge.target.nodeId

    private fun reverseRoute(route: EdgeRoute): EdgeRoute =
        when (route) {
            is EdgeRoute.Direct -> route.copy(source = route.target, target = route.source)
            is EdgeRoute.OrthogonalRounded ->
                route.copy(
                    source = route.target,
                    target = route.source,
                    waypoints = route.waypoints.reversed(),
                )
            is EdgeRoute.TreeRounded -> route.copy(source = route.target, target = route.source, waypoints = route.waypoints.reversed())
            is EdgeRoute.Bezier -> route.copy(source = route.target, target = route.source, controlPoints = route.controlPoints.reversed())
        }

    /** Sets `wrapColumns = ceil(sqrt(n))` when the widest layer holds more than [AUTO_WRAP_THRESHOLD] nodes and nothing steers the width. */
    private fun withAutoWrap(
        spec: LayoutSpec?,
        graph: LayoutGraph,
    ): LayoutSpec? {
        if (spec?.wrapColumns != null || spec?.aspectRatio != null || spec?.maxWidth != null) return spec
        if (graph.nodes.size <= AUTO_WRAP_THRESHOLD || graph.edges.isEmpty()) return spec
        val layers =
            wrapLayers(
                nodes = graph.nodes.map { it.id.value },
                edges = graph.edges.map { it.source.nodeId.value to it.target.nodeId.value },
                columns = null,
            )
        val widest =
            layers.values
                .groupingBy { it }
                .eachCount()
                .values
                .maxOrNull() ?: 0
        if (widest <= AUTO_WRAP_THRESHOLD) return spec
        val columns = UmlEdgelessBand.autoColumns(nodes = widest).coerceIn(2, LayoutLimits.WRAP_COLUMNS_MAX)
        return (spec ?: LayoutSpec()).copy(wrapColumns = columns)
    }

    private const val GENERALIZATION_PREFIX = "gen::"
    private const val AUTO_WRAP_THRESHOLD = 6

    private fun layoutInner(
        engine: KumlLayoutEngine,
        graph: LayoutGraph,
        hints: LayoutHints,
        spec: LayoutSpec?,
        kind: SpecDiagramKind,
        diagramName: String?,
    ): SpecLayoutOutcome {
        val findings = mutableListOf<LayoutSpecFinding>()
        findings += noEffectFindings(spec = spec, kind = kind, diagramName = diagramName)
        if (LayoutEffectMatrix.effectiveFor(kind).isEmpty()) {
            return SpecLayoutOutcome(result = engine.layout(graph = graph, hints = hints), findings = findings)
        }
        val checked = sanitize(spec = spec, findings = findings)
        checkIds(spec = checked, graph = graph, findings = findings, withGroups = kind == SpecDiagramKind.SYSML2_REQ)
        for (m in graph.ignoredMemberships) {
            findings +=
                LayoutSpecFinding(
                    code = LayoutCodes.DUPLICATE_GROUP_MEMBERSHIP,
                    message =
                        "group: '${m.nodeId.value}' is a member of '${m.keptGroup.value}' and '${m.ignoredGroup.value}'; " +
                            "it stands in '${m.keptGroup.value}' only (the first group in declaration order).",
                )
        }
        val effectiveHints = hintsWith(base = hints, spec = checked)
        if (kind == SpecDiagramKind.SYSML2_REQ && ReqGroupGrid.applies(graph = graph, spec = checked)) {
            val gridSpec = checked ?: LayoutSpec()
            // Wave 2E-a: group grid. The aspect iteration below is skipped, the grid chooses its own column count.
            val gridResult =
                ReqGroupGrid.layout(engine = engine, graph = graph, hints = effectiveHints, spec = gridSpec, findings = findings)
            val horizontalFlow =
                effectiveHints.direction == LayoutDirection.LeftToRight || effectiveHints.direction == LayoutDirection.RightToLeft
            reportUnreached(
                result = gridResult,
                target = gridSpec.aspectRatio,
                maxWidth = gridSpec.maxWidth,
                horizontal = horizontalFlow,
                explicit = gridSpec.aspectRatio != null || gridSpec.maxWidth != null,
                bothSides = gridSpec.aspectRatio != null,
                findings = findings,
            )
            return SpecLayoutOutcome(result = gridResult, findings = findings)
        }
        if (kind == SpecDiagramKind.SYSML2_UC && UcFrameGrid.applies(graph)) {
            // Wave 2E-b: subject frame grid with actors beside the frame. The aspect iteration is skipped.
            val ucSpec = checked ?: LayoutSpec()
            val gridResult = UcFrameGrid.layout(engine = engine, graph = graph, hints = effectiveHints, spec = ucSpec)
            reportUnreached(
                result = gridResult,
                target = ucSpec.aspectRatio ?: LayoutLimits.GRID_DEFAULT_ASPECT,
                maxWidth = ucSpec.maxWidth,
                horizontal = false,
                explicit = ucSpec.aspectRatio != null || ucSpec.maxWidth != null || ucSpec.wrapColumns == null,
                bothSides = false,
                findings = findings,
            )
            return SpecLayoutOutcome(result = gridResult, findings = findings)
        }
        val alignedGroups =
            if (kind == SpecDiagramKind.SYSML2_STM || kind == SpecDiagramKind.UML_STATE || kind == SpecDiagramKind.SYSML2_BDD) {
                checked
                    ?.sameLayer
                    .orEmpty()
                    .take(LayoutLimits.GROUPS)
                    .map { it.take(LayoutLimits.IDS_PER_GROUP) }
                    .filter { it.size >= 2 }
            } else {
                emptyList()
            }

        // Edges between two members of one sameLayer group cannot be routed by the layered engine (the members share a
        // layer), and they would pull the members apart. They are withheld from the engine and drawn by `aligned`.
        val internalEdgeIds =
            graph.edges
                .filter { e ->
                    e.source.nodeId != e.target.nodeId &&
                        alignedGroups.any { g -> e.source.nodeId.value in g && e.target.nodeId.value in g }
                }.map { it.id }
                .toSet()
        val withoutInternal =
            if (internalEdgeIds.isEmpty()) {
                graph
            } else {
                graph.copy(
                    edges = graph.edges.filterNot { it.id in internalEdgeIds },
                )
            }
        val engineGraph =
            if (kind == SpecDiagramKind.SYSML2_STM || kind == SpecDiagramKind.UML_STATE) {
                withoutInternal.withForwardEdgePriority()
            } else {
                withoutInternal
            }

        fun aligned(
            result: LayoutResult,
            direction: LayoutDirection = effectiveHints.direction,
        ): LayoutResult {
            val centred =
                result.alignSameLayerCenters(
                    groups = alignedGroups,
                    direction = direction,
                    graph = graph,
                    internalEdges = internalEdgeIds,
                )
            return if (kind == SpecDiagramKind.SYSML2_STM || kind == SpecDiagramKind.UML_STATE) {
                centred
                    .pinInitialsToTargets(graph = graph, direction = direction)
                    .avoidCompositeHeaderBand(graph = graph, direction = direction)
            } else {
                centred
            }
        }

        // Wave 2E-b: generalisation fans of a block definition diagram are drawn as a bus. It is applied once, to the
        // candidate that was chosen, with that candidate's flow direction (a rotated run, Nacharbeit 60, gets a matching
        // bus). The candidates themselves are compared before the bus, so existing choices do not change.
        fun finished(
            result: LayoutResult,
            direction: LayoutDirection,
        ): LayoutResult =
            if (kind == SpecDiagramKind.SYSML2_BDD) {
                val bus = BddGeneralizationBus.apply(result = result, graph = graph, direction = direction)
                BddAllocationDocking.apply(result = bus, graph = graph, direction = direction)
            } else {
                result
            }

        val first = aligned(engine.layout(graph = graphWith(graph = engineGraph, spec = checked), hints = effectiveHints))
        val explicit = checked?.aspectRatio != null || checked?.maxWidth != null
        if (!explicit && !effectiveHints.autoAspect) {
            return SpecLayoutOutcome(result = finished(result = first, direction = effectiveHints.direction), findings = findings)
        }

        val horizontal = effectiveHints.direction == LayoutDirection.LeftToRight || effectiveHints.direction == LayoutDirection.RightToLeft
        val w = first.canvas.width.toDouble()
        val h = first.canvas.height.toDouble()
        if (w <= 0.0 || h <= 0.0) {
            return SpecLayoutOutcome(result = finished(result = first, direction = effectiveHints.direction), findings = findings)
        }
        val ratio = w / h
        val target =
            checked?.aspectRatio
                ?: when {
                    !horizontal && ratio > LayoutLimits.IMPLICIT_FRAME -> LayoutLimits.IMPLICIT_FRAME
                    horizontal && ratio < 1.0 / LayoutLimits.IMPLICIT_FRAME -> 1.0 / LayoutLimits.IMPLICIT_FRAME
                    else -> null
                }
        val maxWidth = checked?.maxWidth
        val tooWide = maxWidth != null && w > maxWidth
        val offTarget =
            target != null &&
                isOffTarget(ratio = ratio, target = target, horizontal = horizontal, bothSides = checked?.aspectRatio != null)
        if (!tooWide && !offTarget) {
            return SpecLayoutOutcome(result = finished(result = first, direction = effectiveHints.direction), findings = findings)
        }

        // The widest layer drives the wrap: nodes per layer without any wrapping.
        val layers =
            wrapLayers(
                nodes = graph.nodes.map { it.id.value },
                edges = graph.edges.map { it.source.nodeId.value to it.target.nodeId.value },
                columns = null,
            )
        val m =
            layers.values
                .groupingBy { it }
                .eachCount()
                .values
                .maxOrNull() ?: 1
        var best = first
        if (m > 1) {
            var c = m
            if (target != null) {
                c = ceil(m * sqrt(if (horizontal) ratio / target else target / ratio)).toInt()
            }
            if (maxWidth != null && !horizontal) {
                c = minOf(c, maxOf(1, floor(m * maxWidth / w).toInt()))
            }
            checked?.wrapColumns?.let { c = minOf(c, it) }
            c = c.coerceIn(1, minOf(m, LayoutLimits.WRAP_COLUMNS_MAX))
            if (c < m) {
                val second =
                    aligned(
                        engine.layout(
                            graph = graphWith(graph = engineGraph, spec = (checked ?: LayoutSpec()).copy(wrapColumns = c)),
                            hints = effectiveHints,
                        ),
                    )
                if (isBetter(candidate = second, current = best, target = target, maxWidth = maxWidth, horizontal = horizontal)) {
                    best =
                        second
                }
            }
        }
        var bestHorizontal = horizontal
        // A chain or a cycle has one state per layer, so wrapping cannot reach an explicit target. When the author
        // gave no direction, one more run with the orthogonal flow direction may land closer (Nacharbeit 58/60).
        val rotates =
            kind == SpecDiagramKind.SYSML2_STM || kind == SpecDiagramKind.UML_STATE || kind == SpecDiagramKind.SYSML2_BDD
        if (rotates && explicit && checked.direction == null) {
            val bestW = best.canvas.width.toDouble()
            val bestH = best.canvas.height.toDouble()
            val missed =
                (maxWidth != null && bestW > maxWidth) ||
                    (
                        target != null &&
                            bestH > 0.0 &&
                            isOffTarget(
                                ratio = bestW / bestH,
                                target = target,
                                horizontal = horizontal,
                                bothSides =
                                    checked.aspectRatio != null,
                            )
                    )
            if (missed) {
                val rotated = orthogonal(effectiveHints.direction)
                val candidate =
                    aligned(
                        result =
                            engine.layout(
                                graph = graphWith(graph = engineGraph, spec = checked),
                                hints = effectiveHints.copy(direction = rotated),
                            ),
                        direction = rotated,
                    )
                if (closerToTarget(candidate = candidate, current = best, target = target, maxWidth = maxWidth)) {
                    best = candidate
                    bestHorizontal = !horizontal
                }
            }
        }
        val chosenDirection = if (bestHorizontal == horizontal) effectiveHints.direction else orthogonal(effectiveHints.direction)
        val chosen = finished(result = best, direction = chosenDirection)
        reportUnreached(
            result = chosen,
            target = target,
            maxWidth = maxWidth,
            horizontal = bestHorizontal,
            explicit = explicit,
            bothSides = checked?.aspectRatio != null,
            findings = findings,
        )
        return SpecLayoutOutcome(result = chosen, findings = findings)
    }

    /** The flow direction turned by a quarter: vertical flows become horizontal and the other way round. */
    private fun orthogonal(direction: LayoutDirection): LayoutDirection =
        when (direction) {
            LayoutDirection.TopToBottom -> LayoutDirection.LeftToRight
            LayoutDirection.BottomToTop -> LayoutDirection.RightToLeft
            LayoutDirection.LeftToRight -> LayoutDirection.TopToBottom
            LayoutDirection.RightToLeft -> LayoutDirection.BottomToTop
        }

    /**
     * Whether [candidate] is clearly closer to the explicit target than [current], whatever the flow direction of
     * either run. Unlike [isBetter] this does not depend on the direction (a maxWidth is a plain width limit here).
     */
    private fun closerToTarget(
        candidate: LayoutResult,
        current: LayoutResult,
        target: Double?,
        maxWidth: Int?,
    ): Boolean {
        val cw = candidate.canvas.width.toDouble()
        val ch = candidate.canvas.height.toDouble()
        if (cw <= 0.0 || ch <= 0.0) return false
        if (maxWidth != null) return cw < current.canvas.width
        if (target == null) return false

        fun dist(r: LayoutResult) = kotlin.math.abs(kotlin.math.ln((r.canvas.width.toDouble() / r.canvas.height.toDouble()) / target))
        return dist(candidate) < dist(current) * 0.9
    }

    /**
     * An implicit target only corrects the over-stretched side (too wide when vertical, too tall when
     * horizontal). An explicit `aspectRatio` is missed on either side ([bothSides]).
     */
    internal fun isOffTarget(
        ratio: Double,
        target: Double,
        horizontal: Boolean,
        bothSides: Boolean,
    ): Boolean {
        val tooWide = ratio > target * (1 + LayoutLimits.ASPECT_TOLERANCE)
        val tooTall = ratio < target / (1 + LayoutLimits.ASPECT_TOLERANCE)
        return if (bothSides) {
            tooWide || tooTall
        } else if (horizontal) {
            tooTall
        } else {
            tooWide
        }
    }

    /** W008 for pins that contradict the node's edges; nothing when the diagram kind ignores first/last (W001 says so). */
    private fun layerPinFindings(
        kind: SpecDiagramKind,
        spec: LayoutSpec?,
        degrees: Map<String, EdgeDegree>?,
    ): List<LayoutSpecFinding> {
        val effective = LayoutEffectMatrix.effectiveFor(kind)
        val pinned =
            spec?.copy(
                first = if (LayoutOption.FIRST in effective) spec.first else emptyList(),
                last = if (LayoutOption.LAST in effective) spec.last else emptyList(),
            )
        return LayerPinCheck.findings(spec = pinned, degrees = degrees)
    }

    /** Reports unknown ids ([LayoutCodes.UNKNOWN_ID]), exceeded id/group limits and first/last conflicts. */
    private fun checkIds(
        spec: LayoutSpec?,
        graph: LayoutGraph,
        findings: MutableList<LayoutSpecFinding>,
        withGroups: Boolean = false,
    ) = checkIdSet(
        spec = spec,
        known = (graph.nodes.map { it.id.value } + if (withGroups) graph.groups.map { it.id.value } else emptyList()).toSet(),
        findings = findings,
    )

    private fun checkIdSet(
        spec: LayoutSpec?,
        known: Set<String>?,
        findings: MutableList<LayoutSpecFinding>,
    ) {
        if (spec == null) return

        fun unknown(
            option: String,
            ids: List<String>,
        ) {
            if (known == null) return
            ids.filter { it !in known }.distinct().forEach {
                findings +=
                    LayoutSpecFinding(
                        code = LayoutCodes.UNKNOWN_ID,
                        message = "layout.$option: element '$it' is not in this diagram; ignored.",
                    )
            }
        }

        fun tooMany(
            option: String,
            count: Int,
            max: Int,
            what: String,
        ) {
            if (count > max) {
                findings +=
                    LayoutSpecFinding(
                        code = LayoutCodes.OUT_OF_RANGE,
                        message = "layout.$option has $count $what (maximum $max); the rest is ignored.",
                        isError = true,
                    )
            }
        }
        tooMany(option = "sameLayer", count = spec.sameLayer.size, max = LayoutLimits.GROUPS, what = "groups")
        spec.sameLayer.take(LayoutLimits.GROUPS).forEach {
            tooMany(option = "sameLayer", count = it.size, max = LayoutLimits.IDS_PER_GROUP, what = "ids in one group")
            unknown(option = "sameLayer", ids = it.take(LayoutLimits.IDS_PER_GROUP))
        }
        tooMany(option = "first", count = spec.first.size, max = LayoutLimits.IDS_PER_GROUP, what = "ids")
        tooMany(option = "last", count = spec.last.size, max = LayoutLimits.IDS_PER_GROUP, what = "ids")
        unknown(option = "first", ids = spec.first.take(LayoutLimits.IDS_PER_GROUP))
        unknown(option = "last", ids = spec.last.take(LayoutLimits.IDS_PER_GROUP))
        spec.last.intersect(spec.first.toSet()).filter { known == null || it in known }.forEach {
            findings +=
                LayoutSpecFinding(
                    code = LayoutCodes.NO_EFFECT,
                    message = "layout.last: element '$it' is also in first; it stays in the first layer.",
                )
        }
    }

    private fun isBetter(
        candidate: LayoutResult,
        current: LayoutResult,
        target: Double?,
        maxWidth: Int?,
        horizontal: Boolean,
    ): Boolean {
        val cw = candidate.canvas.width.toDouble()
        val ch = candidate.canvas.height.toDouble()
        if (cw <= 0.0 || ch <= 0.0) return false
        if (maxWidth != null && !horizontal) return cw < current.canvas.width
        if (target == null) return false

        fun dist(r: LayoutResult) = kotlin.math.abs(kotlin.math.ln((r.canvas.width.toDouble() / r.canvas.height.toDouble()) / target))
        return dist(candidate) < dist(current) * 0.9
    }

    private fun reportUnreached(
        result: LayoutResult,
        target: Double?,
        maxWidth: Int?,
        horizontal: Boolean,
        explicit: Boolean,
        bothSides: Boolean,
        findings: MutableList<LayoutSpecFinding>,
    ) {
        val w = result.canvas.width.toDouble()
        val h = result.canvas.height.toDouble()
        if (maxWidth != null && w > maxWidth) {
            findings +=
                LayoutSpecFinding(
                    code = LayoutCodes.MAX_WIDTH_UNREACHED,
                    message = "layout.maxWidth = $maxWidth could not be reached (actual width ${w.toInt()} px).",
                )
        }
        if (explicit && target != null && h > 0.0) {
            val r = w / h
            if (isOffTarget(ratio = r, target = target, horizontal = horizontal, bothSides = bothSides)) {
                findings +=
                    LayoutSpecFinding(
                        code = LayoutCodes.ASPECT_UNREACHED,
                        message = "layout.aspectRatio = $target could not be reached (actual ${(
                            kotlin.math.round(
                                r * 100,
                            ) / 100
                        )}).",
                    )
            }
        }
    }

    private fun sanitize(
        spec: LayoutSpec?,
        findings: MutableList<LayoutSpecFinding>,
    ): LayoutSpec? {
        if (spec == null) return null
        var out = spec
        spec.wrapColumns?.let {
            if (it !in 1..LayoutLimits.WRAP_COLUMNS_MAX) {
                findings +=
                    LayoutSpecFinding(
                        code = LayoutCodes.OUT_OF_RANGE,
                        message = "layout.wrapColumns = $it is outside 1..${LayoutLimits.WRAP_COLUMNS_MAX}; ignored.",
                        isError = true,
                    )
                out = out!!.copy(wrapColumns = null)
            }
        }
        spec.maxWidth?.let {
            if (it !in LayoutLimits.MAX_WIDTH_MIN..LayoutLimits.MAX_WIDTH_MAX) {
                findings +=
                    LayoutSpecFinding(
                        code = LayoutCodes.OUT_OF_RANGE,
                        message = "layout.maxWidth = $it is outside ${LayoutLimits.MAX_WIDTH_MIN}..${LayoutLimits.MAX_WIDTH_MAX}; ignored.",
                        isError = true,
                    )
                out = out!!.copy(maxWidth = null)
            }
        }
        spec.labelMaxWidth?.let {
            if (it !in LayoutLimits.LABEL_MAX_WIDTH_MIN..LayoutLimits.LABEL_MAX_WIDTH_MAX) {
                findings +=
                    LayoutSpecFinding(
                        code = LayoutCodes.OUT_OF_RANGE,
                        message =
                            "layout.labelMaxWidth = $it is outside " +
                                "${LayoutLimits.LABEL_MAX_WIDTH_MIN}..${LayoutLimits.LABEL_MAX_WIDTH_MAX}; ignored.",
                        isError = true,
                    )
                out = out!!.copy(labelMaxWidth = null)
            }
        }
        spec.aspectRatio?.let {
            if (!it.isFinite() || it < LayoutLimits.ASPECT_MIN || it > LayoutLimits.ASPECT_MAX) {
                findings +=
                    LayoutSpecFinding(
                        code = LayoutCodes.OUT_OF_RANGE,
                        message = "layout.aspectRatio is outside ${LayoutLimits.ASPECT_MIN}..${LayoutLimits.ASPECT_MAX}; ignored.",
                        isError = true,
                    )
                out = out!!.copy(aspectRatio = null)
            }
        }
        return out
    }
}

/**
 * Nacharbeit 71: `layout.aspectRatio` report for an activity diagram, measured on the FINAL image (swimlane columns,
 * note columns and label overhang included) instead of the engine canvas the runner sees before the activity
 * post-pass. Returns `null` when the target is met within [LayoutLimits.ASPECT_TOLERANCE] on either side.
 * [hasNoteColumns] adds a hint, because notes beside the swimlanes widen the diagram.
 */
public fun actAspectFinding(
    canvasWidth: Double,
    canvasHeight: Double,
    target: Double,
    hasNoteColumns: Boolean,
): LayoutSpecFinding? =
    aspectFindingFor(
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        target = target,
        hint = if (hasNoteColumns) " (note columns beside the swimlanes widen the diagram)" else "",
    )

/**
 * Nacharbeit 73: `layout.aspectRatio` report for a state machine (SysML 2 STM or UML state), measured on the FINAL
 * image (engine canvas plus the renderer padding) instead of the engine canvas the runner sees. Same tolerance as
 * [actAspectFinding]; at most one finding. [directionExplicit] adds a hint, because an explicit `direction` stops the
 * flow from rotating towards the target.
 */
public fun stmAspectFinding(
    canvasWidth: Double,
    canvasHeight: Double,
    target: Double,
    directionExplicit: Boolean,
): LayoutSpecFinding? =
    aspectFindingFor(
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        target = target,
        hint = if (directionExplicit) " (direction is set explicitly; remove it to allow the flow to rotate)" else "",
    )

/**
 * Nacharbeit 83: `layout.aspectRatio` report for a SysML 2 block definition diagram, measured on the FINAL image
 * (engine canvas plus label overhang plus the renderer padding) instead of the engine canvas the runner sees. Same
 * tolerance as [actAspectFinding]; at most one finding. [directionExplicit] adds a hint, because an explicit
 * `direction` stops the flow from rotating towards the target.
 */
public fun bddAspectFinding(
    canvasWidth: Double,
    canvasHeight: Double,
    target: Double,
    directionExplicit: Boolean,
): LayoutSpecFinding? =
    aspectFindingFor(
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        target = target,
        hint = if (directionExplicit) " (direction is set explicitly; remove it to allow the flow to rotate)" else "",
    )

/**
 * Nacharbeit 74: `layout.aspectRatio` report for a SysML 2 use case diagram, measured on the FINAL image (engine canvas
 * plus label overhang plus the renderer padding) and in both directions. Same tolerance as [actAspectFinding]; at most
 * one finding. [wrapColumnsExplicit] adds a hint, because an explicit `wrapColumns` takes precedence over the aspect
 * ratio when the subject frame chooses its columns.
 */
public fun ucAspectFinding(
    canvasWidth: Double,
    canvasHeight: Double,
    target: Double,
    wrapColumnsExplicit: Boolean,
): LayoutSpecFinding? =
    aspectFindingFor(
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        target = target,
        hint = if (wrapColumnsExplicit) " (wrapColumns is set explicitly; remove it to let aspectRatio choose the columns)" else "",
    )

private fun aspectFindingFor(
    canvasWidth: Double,
    canvasHeight: Double,
    target: Double,
    hint: String,
): LayoutSpecFinding? {
    if (canvasWidth <= 0.0 || canvasHeight <= 0.0 || target <= 0.0) return null
    val ratio = canvasWidth / canvasHeight
    if (!LayoutSpecRunner.isOffTarget(ratio = ratio, target = target, horizontal = false, bothSides = true)) return null
    return LayoutSpecFinding(
        code = LayoutCodes.ASPECT_UNREACHED,
        message = "layout.aspectRatio = $target could not be reached (actual ${kotlin.math.round(ratio * 100) / 100})$hint.",
    )
}
