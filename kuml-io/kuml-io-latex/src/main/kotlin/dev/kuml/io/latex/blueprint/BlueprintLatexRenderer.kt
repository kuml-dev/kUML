package dev.kuml.io.latex.blueprint

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.io.latex.escapeLatex

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

        return buildString {
            appendLine("""\begin{tikzpicture}[>=stealth]""")
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
                    """  \node[kuml-bp-label] at (-1.4, ${fmt(bandCenterY(li))}) {${escapeLatex(layerLabel(layer))}};""",
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
                    val steps = model.stepsIn(phase.id, layer)
                    steps.forEachIndexed { si, step ->
                        // stack multiple steps slightly within the band
                        val yOff = if (steps.size > 1) (si - (steps.size - 1) / 2.0) * 0.4 else 0.0
                        val actor = step.actorRef?.let { ref -> model.actors.firstOrNull { it.id == ref } }
                        val roleSuffix = actor?.let { """\\\\{\footnotesize[${escapeLatex(it.role.name)}]}""" } ?: ""
                        appendLine(
                            """  \node[kuml-bp-step] at (${fmt(colX(pi))}, ${fmt(bandCenterY(li) + yOff)}) """ +
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
                renderLines(this, showLines, layers, contentLeft, contentRight, ::bandTopY)
            }

            // ── connections ──
            model.connections.forEach { conn ->
                val src = cellCenter(model, layers, phases, conn.sourceRef, ::colX, ::bandCenterY)
                val dst = cellCenter(model, layers, phases, conn.targetRef, ::colX, ::bandCenterY)
                if (src != null && dst != null) {
                    val dash = if (conn.style == dev.kuml.blueprint.model.ConnectionStyle.DASHED) "[->, dashed]" else "[->]"
                    appendLine(
                        """  \draw$dash (${fmt(src.first)},${fmt(src.second)}) -- (${fmt(dst.first)},${fmt(dst.second)});""",
                    )
                }
            }

            appendLine()
            appendLine("""\end{tikzpicture}""")
        }
    }

    private fun renderLines(
        sb: StringBuilder,
        lines: Set<BlueprintLine>,
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
            val caption: String,
        )

        val boundaries =
            listOf(
                Boundary(
                    BlueprintLine.INTERACTION,
                    BlueprintLayer.CUSTOMER_ACTIONS,
                    BlueprintLayer.FRONTSTAGE,
                    "kuml-bp-line-interaction",
                    "Line of Interaction",
                ),
                Boundary(
                    BlueprintLine.VISIBILITY,
                    BlueprintLayer.FRONTSTAGE,
                    BlueprintLayer.BACKSTAGE,
                    "kuml-bp-line-visibility",
                    "Line of Visibility",
                ),
                Boundary(
                    BlueprintLine.INTERNAL_INTERACTION,
                    BlueprintLayer.BACKSTAGE,
                    BlueprintLayer.SUPPORT_PROCESSES,
                    "kuml-bp-line-internal",
                    "Line of Internal Interaction",
                ),
            )
        boundaries.forEach { bd ->
            if (bd.line !in lines) return@forEach
            val upperIdx = layers.indexOf(bd.upper)
            if (upperIdx < 0 || bd.lower !in layers) return@forEach
            val y = bandTopY(upperIdx) - ROW_H // bottom edge of the upper band
            sb.appendLine("""  \draw[${bd.style}] (${fmt(left)},${fmt(y)}) -- (${fmt(right)},${fmt(y)});""")
            sb.appendLine("""  \node[kuml-bp-line-label] at (${fmt(right)},${fmt(y)}) {${escapeLatex(bd.caption)}};""")
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

    private fun layerLabel(layer: BlueprintLayer): String =
        when (layer) {
            BlueprintLayer.CUSTOMER_ACTIONS -> "Customer Actions"
            BlueprintLayer.FRONTSTAGE -> "Frontstage"
            BlueprintLayer.BACKSTAGE -> "Backstage"
            BlueprintLayer.SUPPORT_PROCESSES -> "Support Processes"
        }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, r)
    }

    @Suppress("unused")
    private fun sentimentY(s: Sentiment): Double = (s.value + 2) / 4.0
}
