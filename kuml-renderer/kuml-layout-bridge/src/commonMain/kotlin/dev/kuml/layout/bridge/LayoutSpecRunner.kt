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

/**
 * Which `layout { }` options a diagram family honours (PAL wave 2B). Coarse on purpose: layered
 * (ELK) diagrams honour all options, diagrams drawn by a dedicated grid/time-line layout honour none
 * and report [LayoutCodes.NO_EFFECT] for every option that was set.
 */
public enum class LayoutDiagramKind(
    public val effective: Set<LayoutOption>,
) {
    /** Layered (ELK) layout: all options apply. */
    LAYERED(LayoutOption.entries.toSet()),

    /** Sequence/timing/grid layouts: the spec has no effect. */
    FIXED(emptySet()),
}

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
    ): LayoutGraph = graph.withSpec(spec)

    /** Options of [spec] that are set but not honoured by [kind]. */
    public fun ignoredOptions(
        spec: LayoutSpec?,
        kind: LayoutDiagramKind,
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
            }
        return set.filter { it !in kind.effective }
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
        kind: LayoutDiagramKind = LayoutDiagramKind.LAYERED,
    ): SpecLayoutOutcome {
        val findings = mutableListOf<LayoutSpecFinding>()
        ignoredOptions(spec = spec, kind = kind).forEach {
            findings +=
                LayoutSpecFinding(
                    code = LayoutCodes.NO_EFFECT,
                    message = "layout option '${it.name.lowercase()}' has no effect on this diagram type.",
                )
        }
        if (kind == LayoutDiagramKind.FIXED) {
            return SpecLayoutOutcome(result = engine.layout(graph = graph, hints = hints), findings = findings)
        }
        val checked = sanitize(spec = spec, findings = findings)
        checkIds(spec = checked, graph = graph, findings = findings)
        val effectiveHints = hintsWith(base = hints, spec = checked)
        val first = engine.layout(graph = graphWith(graph = graph, spec = checked), hints = effectiveHints)
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
                    engine.layout(
                        graph = graphWith(graph = graph, spec = (checked ?: LayoutSpec()).copy(wrapColumns = c)),
                        hints = effectiveHints,
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
    ) {
        if (spec == null) return
        val known = graph.nodes.map { it.id.value }.toSet()

        fun unknown(
            option: String,
            ids: List<String>,
        ) {
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
        spec.last.intersect(spec.first.toSet()).filter { it in known }.forEach {
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
