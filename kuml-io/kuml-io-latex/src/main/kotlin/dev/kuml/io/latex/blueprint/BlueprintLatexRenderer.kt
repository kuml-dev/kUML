package dev.kuml.io.latex.blueprint

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCm
import dev.kuml.io.latex.note.NoteTikz
import dev.kuml.io.latex.note.NoteTikzSpec
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.NoteFrameStyle
import kotlin.math.max

/**
 * Renders a User-Journey / Service-Blueprint model as LaTeX/TikZ source
 * (V3.1.26).
 *
 * Mirrors the SVG renderer's structure exactly: the blueprint is a strict table
 * (phases = columns, layers = rows), so the renderer computes coordinates here
 * directly — no layout engine. Coordinates use a TikZ unit of 1cm per grid step
 * with the canonical column/row geometry; Y grows downward in the picture by
 * negating row indices.
 *
 * Output (per [render]) is a bare `\begin{tikzpicture}…\end{tikzpicture}` block;
 * the surrounding `\tikzset{…}` styles and standalone preamble are emitted by
 * [dev.kuml.io.latex.KumlLatexRenderer].
 *
 * TikZ libraries required (added to the standalone preamble by the host
 * renderer): `shapes.geometric` (diamond/hexagon touchpoints), `plotmarks`
 * (emotion-curve markers).
 */
internal object BlueprintLatexRenderer {
    private const val COL_W = 3.6 // cm per phase column
    private const val ROW_H = 2.0 // cm per layer band
    private const val EMOTION_H = 1.8 // cm emotion band height
    private const val STEP_H = ROW_H - 0.6 // cm minimum step box height (see kuml-bp-step)
    private const val STEP_STACK_PITCH = 0.4 // cm between stacked steps of one cell
    private const val MIN_ROUTE_GAP = 0.06 // cm
    private const val EXIT_START_CM = 0.05
    private const val MAX_EXIT_STEP_CM = 0.04
    private const val EXIT_SPAN_CM = 0.15

    // Lanes below a band: clearance to the separator line / next boxes and minimum distance between two lanes.
    internal const val LANE_CLEARANCE_CM = 0.05
    internal const val LANE_PITCH_CM = 0.05

    // The last vertical of a route runs right of the box edge; right of the last column it must clear the line label,
    // which ends at the grid's right edge (box edge + 0.25 cm for the minimum box width).
    private const val RIGHT_RUN_START_CM = 0.05
    private const val RIGHT_RUN_STEP_CM = 0.05
    private const val RIGHT_RUN_MAX_LANES = 6
    private const val LAST_COLUMN_RUN_START_CM = 0.28
    private const val LAST_COLUMN_RUN_STEP_CM = 0.03
    private const val LAST_COLUMN_MAX_LANES = 4

    /**
     * Height in cm below the band bottom of lane [lane] (0-based) among [lanes] links in a strip of [gap] cm. Lanes keep
     * [LANE_CLEARANCE_CM] to the separator line and the next boxes and at least [LANE_PITCH_CM] to each other; when the
     * strip is too narrow for that, the surplus links share lanes (modulo).
     */
    internal fun laneOffsetCm(
        gap: Double,
        lanes: Int,
        lane: Int,
    ): Double {
        val usable = gap - 2 * LANE_CLEARANCE_CM
        val capacity = if (usable <= 0.0) 1 else (usable / LANE_PITCH_CM + 1e-9).toInt() + 1
        val used = minOf(maxOf(lanes, 1), capacity)
        if (used == 1) return gap / 2
        return LANE_CLEARANCE_CM + (lane % used) * usable / (used - 1)
    }

    /** Where the note line of a step runs: band index, step south edge above the band bottom, free strip height. */
    private data class StepRoute(
        val band: Int,
        val southAboveBandBottom: Double,
        val gap: Double,
        /** Distance of the anchor above the south edge for a box partly covered by a stacked box; null: east centre. */
        val endLift: Double?,
        val lastColumn: Boolean,
    )

    /** Emits the blueprint-specific TikZ styles into the shared `\tikzset` block. */
    fun appendBlueprintTikzStyles(
        sb: StringBuilder,
        indent: String,
    ) {
        sb.appendLine("$indent  kuml-bp-band/.style={draw=none, rectangle, minimum height=${ROW_H}cm, font=\\sffamily},")
        sb.appendLine(
            "$indent  kuml-bp-step/.style={draw, rounded corners=2pt, fill=white, " +
                "minimum width=${COL_W - 0.5}cm, minimum height=${ROW_H - 0.6}cm, " +
                "align=center, font=\\sffamily\\small},",
        )
        sb.appendLine("$indent  kuml-bp-label/.style={font=\\sffamily\\bfseries, anchor=west},")
        sb.appendLine("$indent  kuml-bp-phase/.style={font=\\sffamily\\bfseries, anchor=south},")
        sb.appendLine("$indent  kuml-bp-line-interaction/.style={draw=blue!40!black, line width=0.8pt},")
        sb.appendLine("$indent  kuml-bp-line-visibility/.style={draw=blue!40!black, line width=0.8pt, dashed},")
        sb.appendLine("$indent  kuml-bp-line-internal/.style={draw=blue!40!black, line width=0.8pt, dotted},")
        sb.appendLine("$indent  kuml-bp-line-label/.style={font=\\sffamily\\itshape\\footnotesize, anchor=south east},")
        sb.appendLine("$indent  kuml-bp-emotion/.style={draw=orange!80!black, line width=1pt, mark=*, mark size=2pt},")
    }

    fun render(
        model: BlueprintModel,
        diagram: BlueprintDiagram,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visibleLayers =
            when (diagram) {
                is JourneyDiagram -> diagram.visibleLayers
                is BlueprintDiagramFull -> diagram.visibleLayers
            }
        val showEmotion =
            when (diagram) {
                is JourneyDiagram -> diagram.showEmotionCurve
                is BlueprintDiagramFull -> diagram.showEmotionCurve
            }
        val showLines: Set<BlueprintLine> =
            when (diagram) {
                is BlueprintDiagramFull -> diagram.showLines
                is JourneyDiagram -> emptySet()
            }

        val effectiveLayers =
            when (diagram) {
                is JourneyDiagram -> visibleLayers.filter { it in model.activeLayers() }.toSet()
                is BlueprintDiagramFull -> visibleLayers
            }.ifEmpty { setOf(BlueprintLayer.CUSTOMER_ACTIONS) }

        val layers = BlueprintLayer.entries.filter { it in effectiveLayers }
        val phases = model.orderedPhases()

        // Y of the top of the grid (below the optional emotion band).
        val gridTop = if (showEmotion) -(EMOTION_H + 0.6) else 0.0

        fun colX(i: Int) = COL_W * i + COL_W / 2.0

        fun bandTopY(layerIdx: Int) = gridTop - ROW_H * layerIdx

        fun bandCenterY(layerIdx: Int) = bandTopY(layerIdx) - ROW_H / 2.0

        val hasNotes = model.notes.isNotEmpty()
        // Names of the drawn steps (only given when notes exist, so the output without notes is unchanged).
        val stepNames = mutableMapOf<String, String>()
        val stepRoutes = mutableMapOf<String, StepRoute>()

        return buildString {
            appendLine("""\begin{tikzpicture}[>=stealth]""")
            if (hasNotes) {
                NoteTikz.appendNoteStyles(sb = this, indent = options.indent, kinds = model.notes.map { it.kind })
            }
            appendLine()

            // ── phase headers ──
            phases.forEachIndexed { i, phase ->
                appendLine(
                    """  \node[kuml-bp-phase] at (${fmt(colX(i))}, 0.3) {${escapeLatex(phase.name ?: phase.id)}};""",
                )
            }
            appendLine()

            // ── layer swimlane labels (left of grid) ──
            layers.forEachIndexed { li, layer ->
                appendLine(
                    """  \node[kuml-bp-label] at (-1.4, ${fmt(bandCenterY(li))}) {${escapeLatex(diagram.labels.layer(layer))}};""",
                )
            }
            appendLine()

            // ── emotion curve ──
            if (showEmotion) {
                val pts =
                    model.emotionCurve().mapIndexedNotNull { i, (_, s) ->
                        s?.let {
                            val frac = (it.value + 2) / 4.0 // 0..1
                            val y = -(EMOTION_H * (1.0 - frac)) - 0.3
                            colX(i) to y
                        }
                    }
                if (pts.size >= 2) {
                    val coords = pts.joinToString(" ") { (x, y) -> "(${fmt(x)},${fmt(y)})" }
                    appendLine("""  \draw[kuml-bp-emotion] plot coordinates {$coords};""")
                    appendLine()
                }
            }

            // ── step cells ──
            layers.forEachIndexed { li, layer ->
                phases.forEachIndexed { pi, phase ->
                    val steps = model.stepsIn(phaseId = phase.id, layer = layer)
                    steps.forEachIndexed { si, step ->
                        // stack multiple steps slightly within the band
                        val yOff = if (steps.size > 1) (si - (steps.size - 1) / 2.0) * 0.4 else 0.0
                        val actor = step.actorRef?.let { ref -> model.actors.firstOrNull { it.id == ref } }
                        val roleSuffix = actor?.let { """\\\\{\footnotesize[${escapeLatex(it.role.name)}]}""" } ?: ""
                        val nameOpt =
                            if (hasNotes) {
                                val name = "kumlbpstep${model.steps.indexOf(step)}"
                                stepNames[step.id] = name
                                // Routes run in the free strip just below this band (below the separator line and
                                // its label, above the boxes of the next band), never inside a box or on a line.
                                val nextStack =
                                    layers.getOrNull(li + 1)?.let { next ->
                                        phases.maxOf { model.stepsIn(phaseId = it.id, layer = next).size }
                                    } ?: 1
                                stepRoutes[step.id] =
                                    StepRoute(
                                        band = li,
                                        southAboveBandBottom = (ROW_H - STEP_H) / 2.0 + yOff,
                                        gap = max((ROW_H - STEP_H) / 2.0 - STEP_STACK_PITCH * (nextStack - 1) / 2.0, MIN_ROUTE_GAP),
                                        // A box under a later (higher, drawn on top) box of its cell shows only the
                                        // strip of one pitch at its bottom: anchor in the middle of that strip.
                                        endLift = if (si < steps.size - 1) STEP_STACK_PITCH / 2.0 else null,
                                        lastColumn = pi == phases.size - 1,
                                    )
                                " ($name)"
                            } else {
                                ""
                            }
                        appendLine(
                            """  \node[kuml-bp-step]$nameOpt at (${fmt(colX(pi))}, ${fmt(bandCenterY(li) + yOff)}) """ +
                                """{${escapeLatex(step.name ?: step.id)}$roleSuffix};""",
                        )
                    }
                }
            }
            appendLine()

            // ── separator lines (full view only) ──
            if (showLines.isNotEmpty()) {
                val contentLeft = 0.0
                val contentRight = COL_W * phases.size
                renderLines(
                    sb = this,
                    lines = showLines,
                    labels = diagram.labels,
                    layers = layers,
                    left = contentLeft,
                    right = contentRight,
                    bandTopY = ::bandTopY,
                )
            }

            // ── connections ──
            model.connections.forEach { conn ->
                val src =
                    cellCenter(
                        model = model,
                        layers = layers,
                        phases = phases,
                        elementId = conn.sourceRef,
                        colX = ::colX,
                        bandCenterY = ::bandCenterY,
                    )
                val dst =
                    cellCenter(
                        model = model,
                        layers = layers,
                        phases = phases,
                        elementId = conn.targetRef,
                        colX = ::colX,
                        bandCenterY = ::bandCenterY,
                    )
                if (src != null && dst != null) {
                    val dash = if (conn.style == dev.kuml.blueprint.model.ConnectionStyle.DASHED) "[->, dashed]" else "[->]"
                    appendLine(
                        """  \draw$dash (${fmt(src.first)},${fmt(src.second)}) -- (${fmt(dst.first)},${fmt(dst.second)});""",
                    )
                }
            }

            if (hasNotes) {
                appendNotes(
                    model = model,
                    stepNames = stepNames,
                    stepRoutes = stepRoutes,
                    gridTop = gridTop,
                    columns = phases.size,
                    options = options,
                )
            }

            appendLine()
            appendLine("""\end{tikzpicture}""")
        }
    }

    /**
     * Notes in a column right of the grid (stacked downwards from the grid top, declaration order), a dashed line with
     * a dot to the east edge of every drawn step they are linked to. Lines and dots come first; the notes follow
     * and sit on top. A link to anything that is not a drawn step gets a constant comment, never the id.
     */
    private fun StringBuilder.appendNotes(
        model: BlueprintModel,
        stepNames: Map<String, String>,
        stepRoutes: Map<String, StepRoute>,
        gridTop: Double,
        columns: Int,
        options: LatexRenderOptions,
    ) {
        val ind = options.indent
        val placed = BlueprintNoteTikz.place(model.notes)
        val x0 = COL_W * columns + BlueprintNoteTikz.COLUMN_GAP_CM
        appendLine()
        appendLine("$ind% notes")
        // Every drawn link gets its own exit column in the gap and its own lane in the strip below its band, so no
        // two dashed lines ever lie on top of each other.
        val drawn = model.noteLinks.filter { stepRoutes.containsKey(it.targetId) }
        val perBand = drawn.groupingBy { stepRoutes.getValue(it.targetId).band }.eachCount()
        val laneOfBand = mutableMapOf<Int, Int>()
        val exitStep = minOf(MAX_EXIT_STEP_CM, EXIT_SPAN_CM / maxOf(drawn.size, 1))
        var linkIndex = 0
        for (pl in placed) {
            val y = gridTop - (pl.yPt + pl.size.height / 2f) / BlueprintNoteTikz.PT_PER_CM
            for (link in model.noteLinks.filter { it.noteId == pl.note.id }) {
                val name = stepNames[link.targetId]
                val route = stepRoutes[link.targetId]
                if (name == null || route == null) {
                    appendLine("$ind${BlueprintNoteTikz.OMITTED_ANCHOR}")
                } else {
                    val lane = laneOfBand.merge(route.band, 1, Int::plus)!! - 1
                    val n = perBand.getValue(route.band)
                    val runDx =
                        if (route.lastColumn) {
                            LAST_COLUMN_RUN_START_CM + LAST_COLUMN_RUN_STEP_CM * minOf(lane, LAST_COLUMN_MAX_LANES)
                        } else {
                            RIGHT_RUN_START_CM + RIGHT_RUN_STEP_CM * minOf(lane, RIGHT_RUN_MAX_LANES)
                        }
                    NoteTikz.renderAnchorCmRoutedToEast(
                        fromX = x0,
                        fromY = y,
                        toName = name,
                        options = options,
                        out = this,
                        leaveCm = EXIT_START_CM + linkIndex * exitStep,
                        routeDy = route.southAboveBandBottom + laneOffsetCm(gap = route.gap, lanes = n, lane = lane),
                        routeDx = runDx,
                        endLiftCm = route.endLift,
                    )
                    NoteTikz.renderAnchorDotEast(toName = name, options = options, out = this, liftCm = route.endLift)
                    linkIndex++
                }
            }
        }
        appendLine("$ind\\begin{scope}[shift={(${fmtCm(x0)},${fmtCm(gridTop)})}]")
        for (pl in placed) {
            NoteTikz.renderNote(
                spec =
                    NoteTikzSpec(
                        id = "kumlbpnote${pl.k}",
                        body = pl.note.body,
                        kind = pl.note.kind,
                        bounds = Rect(origin = Point(x = 0f, y = pl.yPt), size = pl.size),
                        frame = NoteFrameStyle.DOG_EAR,
                    ),
                options = options,
                out = this,
            )
        }
        appendLine("$ind\\end{scope}")
        if (placed.size < model.notes.size) appendLine("$ind${BlueprintNoteTikz.OMITTED_NOTE}")
    }

    private fun renderLines(
        sb: StringBuilder,
        lines: Set<BlueprintLine>,
        labels: dev.kuml.blueprint.model.BlueprintLabels,
        layers: List<BlueprintLayer>,
        left: Double,
        right: Double,
        bandTopY: (Int) -> Double,
    ) {
        data class Boundary(
            val line: BlueprintLine,
            val upper: BlueprintLayer,
            val lower: BlueprintLayer,
            val style: String,
        )

        val boundaries =
            listOf(
                Boundary(
                    BlueprintLine.INTERACTION,
                    BlueprintLayer.CUSTOMER_ACTIONS,
                    BlueprintLayer.FRONTSTAGE,
                    "kuml-bp-line-interaction",
                ),
                Boundary(
                    BlueprintLine.VISIBILITY,
                    BlueprintLayer.FRONTSTAGE,
                    BlueprintLayer.BACKSTAGE,
                    "kuml-bp-line-visibility",
                ),
                Boundary(
                    BlueprintLine.INTERNAL_INTERACTION,
                    BlueprintLayer.BACKSTAGE,
                    BlueprintLayer.SUPPORT_PROCESSES,
                    "kuml-bp-line-internal",
                ),
            )
        boundaries.forEach { bd ->
            if (bd.line !in lines) return@forEach
            val upperIdx = layers.indexOf(bd.upper)
            if (upperIdx < 0 || bd.lower !in layers) return@forEach
            val y = bandTopY(upperIdx) - ROW_H // bottom edge of the upper band
            sb.appendLine("""  \draw[${bd.style}] (${fmt(left)},${fmt(y)}) -- (${fmt(right)},${fmt(y)});""")
            sb.appendLine("""  \node[kuml-bp-line-label] at (${fmt(right)},${fmt(y)}) {${escapeLatex(labels.line(bd.line))}};""")
        }
    }

    private fun cellCenter(
        model: BlueprintModel,
        layers: List<BlueprintLayer>,
        phases: List<dev.kuml.blueprint.model.Phase>,
        elementId: String,
        colX: (Int) -> Double,
        bandCenterY: (Int) -> Double,
    ): Pair<Double, Double>? {
        val step =
            model.steps.firstOrNull { it.id == elementId }
                ?: model.touchpoints
                    .firstOrNull { it.id == elementId }
                    ?.let { tp -> model.steps.firstOrNull { tp.id in it.touchpointRefs } }
                ?: return null
        val pi = phases.indexOfFirst { it.id == step.phaseRef }
        val li = layers.indexOf(step.layer)
        if (pi < 0 || li < 0) return null
        return colX(pi) to bandCenterY(li)
    }

    private fun fmt(v: Double): String = fmtCm(v)

    @Suppress("unused")
    private fun sentimentY(s: Sentiment): Double = (s.value + 2) / 4.0
}
