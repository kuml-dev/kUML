package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.core.model.layout.LayoutFlow
import dev.kuml.core.model.layout.LayoutLimits
import dev.kuml.core.model.layout.LayoutOption
import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutDirection
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
    ): List<LayoutSpecFinding> {
        if (spec == null) return emptyList()
        val findings = noEffectFindings(spec = spec, kind = kind, diagramName = diagramName).toMutableList()
        val checked = sanitize(spec = spec, findings = findings)
        checkIdSet(spec = checked, known = knownIds, findings = findings)
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
        val outcome = layoutInner(engine = engine, graph = graph, hints = hints, spec = spec, kind = kind, diagramName = diagramName)
        // Wave 2E-a: every requirement diagram (grid or legacy) gets spread endpoints and nudged parallel segments.
        return if (kind ==
            SpecDiagramKind.SYSML2_REQ
        ) {
            outcome.copy(result = ReqEdgeBands.apply(result = outcome.result, graph = graph))
        } else {
            outcome
        }
    }

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
        val effectiveHints = hintsWith(base = hints, spec = checked)
        if (kind == SpecDiagramKind.SYSML2_REQ && checked != null && ReqGroupGrid.applies(graph = graph, spec = checked)) {
            // Wave 2E-a: group grid. The aspect iteration below is skipped, the grid chooses its own column count.
            val gridResult =
                ReqGroupGrid.layout(engine = engine, graph = graph, hints = effectiveHints, spec = checked, findings = findings)
            val horizontalFlow =
                effectiveHints.direction == LayoutDirection.LeftToRight || effectiveHints.direction == LayoutDirection.RightToLeft
            reportUnreached(
                result = gridResult,
                target = checked.aspectRatio,
                maxWidth = checked.maxWidth,
                horizontal = horizontalFlow,
                explicit = checked.aspectRatio != null || checked.maxWidth != null,
                bothSides = checked.aspectRatio != null,
                findings = findings,
            )
            return SpecLayoutOutcome(result = gridResult, findings = findings)
        }
        val alignedGroups =
            if (kind == SpecDiagramKind.SYSML2_STM || kind == SpecDiagramKind.UML_STATE) {
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
        val engineGraph = if (internalEdgeIds.isEmpty()) graph else graph.copy(edges = graph.edges.filterNot { it.id in internalEdgeIds })

        fun aligned(result: LayoutResult): LayoutResult {
            val centred =
                result.alignSameLayerCenters(
                    groups = alignedGroups,
                    direction = effectiveHints.direction,
                    graph = graph,
                    internalEdges = internalEdgeIds,
                )
            return if (kind == SpecDiagramKind.SYSML2_STM) {
                centred.pinInitialsToTargets(
                    graph = graph,
                    direction = effectiveHints.direction,
                    pseudoSize = Sysml2LayoutBridge.STM_PSEUDO_SIZE,
                )
            } else {
                centred
            }
        }

        val first = aligned(engine.layout(graph = graphWith(graph = engineGraph, spec = checked), hints = effectiveHints))
        val explicit = checked?.aspectRatio != null || checked?.maxWidth != null
        if (!explicit && !effectiveHints.autoAspect) return SpecLayoutOutcome(result = first, findings = findings)

        val horizontal = effectiveHints.direction == LayoutDirection.LeftToRight || effectiveHints.direction == LayoutDirection.RightToLeft
        val w = first.canvas.width.toDouble()
        val h = first.canvas.height.toDouble()
        if (w <= 0.0 || h <= 0.0) return SpecLayoutOutcome(result = first, findings = findings)
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
        if (!tooWide && !offTarget) return SpecLayoutOutcome(result = first, findings = findings)

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
        reportUnreached(
            result = best,
            target = target,
            maxWidth = maxWidth,
            horizontal = horizontal,
            explicit = explicit,
            bothSides = checked?.aspectRatio != null,
            findings = findings,
        )
        return SpecLayoutOutcome(result = best, findings = findings)
    }

    /**
     * An implicit target only corrects the over-stretched side (too wide when vertical, too tall when
     * horizontal). An explicit `aspectRatio` is missed on either side ([bothSides]).
     */
    private fun isOffTarget(
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
