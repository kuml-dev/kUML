package dev.kuml.layout.bridge

import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Size
import dev.kuml.layout.Spacing
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.DurationConstraintUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2LayoutFlow
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TimeObservationUsage
import dev.kuml.sysml2.UcDiagram

/**
 * Zentrale ELK-Hints für alle SysML-2-Diagrammtypen (C3-2).
 *
 * Bisher bauten nur die CLI (REQ/STM/ACT) eigene Spacing-Hints; Desktop, Asciidoc, Gradle, Web,
 * kuml-ai und die Vault-Tests nutzten [LayoutHints.DEFAULT]. Mit dieser einen Funktion rendern alle
 * Pipelines dieselben Abstände. Die Werte für REQ/STM/ACT sind wörtlich die der CLI.
 *
 * - **SEQ:** [LayoutHints.preserveNodeOrder] — ohne diesen Hint vertauscht ELK unterschiedlich breite
 *   Lifelines (kantenlose Komponenten werden nach Größe gepackt). Zusätzlich normalisiert
 *   [normalizeSeqLayout] die Reihenfolge nach dem Layout (Doppelsicherung, wirkt auch bei der Grid-Engine).
 */
public fun Sysml2LayoutBridge.layoutHintsFor(
    @Suppress("UNUSED_PARAMETER") model: Sysml2Model,
    diagram: Sysml2Diagram,
): LayoutHints = withLayoutDirection(diagram = diagram, hints = baseHints(diagram))

private fun baseHints(diagram: Sysml2Diagram): LayoutHints =
    when (diagram) {
        is SeqDiagram -> LayoutHints.DEFAULT.copy(preserveNodeOrder = true)
        // V2.0.8+: Stereotyp-Labels auf jeder REQ-Kante brauchen mehr Platz als die 40-px-Defaults.
        is ReqDiagram ->
            LayoutHints.DEFAULT.copy(
                spacing = LayoutHints.DEFAULT.spacing.copy(nodeToNode = 80f, edgeToEdge = 20f, layerToLayer = 100f),
            )
        // V2.0.44: viele Kantenlabels (`event [guard] / action`) um gemeinsame Zustände.
        is StmDiagram ->
            LayoutHints.DEFAULT.copy(spacing = Spacing(nodeToNode = 80f, edgeToEdge = 28f, groupPadding = 24f))
        // V2.0.45: Pin-Labels und Kantenlabels brauchen Platz.
        is ActDiagram ->
            LayoutHints.DEFAULT.copy(spacing = LayoutHints.DEFAULT.spacing.copy(nodeToNode = 100f, layerToLayer = 100f))
        is BdDiagram, is IbdDiagram, is UcDiagram, is ParDiagram -> LayoutHints.DEFAULT
    }

/** C3 — a `layout { direction = … }` hint overrides the flow direction (not for SEQ: lifelines are left to right). */
private fun withLayoutDirection(
    diagram: Sysml2Diagram,
    hints: LayoutHints,
): LayoutHints {
    val flow = diagram.layout?.direction
    if (flow == null || diagram is SeqDiagram) return hints
    return hints.copy(
        direction =
            when (flow) {
                Sysml2LayoutFlow.Down -> LayoutDirection.TopToBottom
                Sysml2LayoutFlow.Up -> LayoutDirection.BottomToTop
                Sysml2LayoutFlow.Right -> LayoutDirection.LeftToRight
                Sysml2LayoutFlow.Left -> LayoutDirection.RightToLeft
            },
    )
}

/**
 * Verteilt die Lifelines eines SEQ-Layouts in der Reihenfolge von [SeqDiagram.elementIds] von links
 * nach rechts neu (C3-2) und schafft Platz für Zeit-Beschriftungen rechts der Lifelines (C3-6).
 *
 * - Liegt die Reihenfolge schon richtig und reicht der Platz, kommt [layoutResult] unverändert zurück
 *   — bestehende Bilder bleiben pixelgleich.
 * - y bleibt, der Abstand ist mindestens der größte vorhandene ELK-Abstand bzw. [MIN_SEQ_LIFELINE_GAP].
 * - Duration-Constraints und Time-Observations schreiben Text rechts der Lifeline-Achse; der Abstand zur
 *   nächsten Achse wird so vergrößert, dass der Text nicht in die Nachbar-Lifeline läuft, und rechts der
 *   letzten Lifeline wird Canvas-Breite reserviert.
 */
public fun Sysml2LayoutBridge.normalizeSeqLayout(
    model: Sysml2Model,
    diagram: SeqDiagram,
    layoutResult: LayoutResult,
): LayoutResult {
    val ordered = diagram.elementIds.map { NodeId(it) }.filter { it in layoutResult.nodes }
    if (ordered.isEmpty()) return layoutResult
    val rightNeeds = seqSideTextNeeds(model = model, ordered = ordered)
    val byX =
        ordered.sortedBy {
            layoutResult.nodes
                .getValue(it)
                .bounds.origin.x
        }

    fun centre(id: NodeId): Float =
        layoutResult.nodes
            .getValue(id)
            .bounds
            .let { it.origin.x + it.size.width / 2f }

    // Is the spacing already sufficient for all side texts (centre distance and right canvas margin)?
    val orderOk = byX == ordered
    val spacingOk =
        ordered.zipWithNext().all { (a, b) -> centre(b) - centre(a) >= (rightNeeds[a] ?: 0f) + MIN_SIDE_TEXT_CLEARANCE } &&
            ordered.last().let { last ->
                val b = layoutResult.nodes.getValue(last).bounds
                layoutResult.canvas.width >= b.origin.x + b.size.width / 2f + (rightNeeds[last] ?: 0f)
            }
    if (orderOk && spacingOk) return layoutResult
    if (ordered.size < 2 && spacingOk) return layoutResult

    val sortedByX =
        layoutResult.nodes.entries
            .filter { it.key in ordered }
            .sortedBy { it.value.bounds.origin.x }
    var gap = MIN_SEQ_LIFELINE_GAP
    for (i in 0 until sortedByX.size - 1) {
        val a = sortedByX[i].value.bounds
        val b = sortedByX[i + 1].value.bounds
        gap = maxOf(gap, b.origin.x - (a.origin.x + a.size.width))
    }
    val left = sortedByX.minOf { it.value.bounds.origin.x }
    val oldRight = sortedByX.maxOf { it.value.bounds.origin.x + it.value.bounds.size.width }
    val rightMargin = (layoutResult.canvas.width - oldRight).coerceAtLeast(0f)

    val newNodes = LinkedHashMap(layoutResult.nodes)
    var x = left
    var previousCentre = Float.NEGATIVE_INFINITY
    var previousNeed = 0f
    for (id in ordered) {
        val layout = layoutResult.nodes.getValue(id)
        val w = layout.bounds.size.width
        // keep at least `gap` between boxes AND enough centre distance for the previous lifeline's side text
        val minCentre =
            if (previousCentre ==
                Float.NEGATIVE_INFINITY
            ) {
                Float.NEGATIVE_INFINITY
            } else {
                previousCentre + previousNeed + MIN_SIDE_TEXT_CLEARANCE
            }
        if (x + w / 2f < minCentre) x = minCentre - w / 2f
        newNodes[id] = layout.copy(bounds = layout.bounds.copy(origin = Point(x = x, y = layout.bounds.origin.y)))
        previousCentre = x + w / 2f
        previousNeed = rightNeeds[id] ?: 0f
        x += w + gap
    }
    val lastId = ordered.last()
    val lastBounds = newNodes.getValue(lastId).bounds
    val newRight =
        maxOf(
            lastBounds.origin.x + lastBounds.size.width,
            lastBounds.origin.x + lastBounds.size.width / 2f + (rightNeeds[lastId] ?: 0f),
        )
    val canvas = Size(width = maxOf(layoutResult.canvas.width, newRight + rightMargin), height = layoutResult.canvas.height)
    return layoutResult.copy(canvas = canvas, nodes = newNodes)
}

/** Width of the widest duration-constraint / time-observation text to the right of each lifeline's axis. */
private fun seqSideTextNeeds(
    model: Sysml2Model,
    ordered: List<NodeId>,
): Map<NodeId, Float> {
    val visible = ordered.map { it.value }.toSet()
    val needs = HashMap<NodeId, Float>()
    for (u in model.usages) {
        when (u) {
            is DurationConstraintUsage ->
                if (u.lifelineId in visible) {
                    // axis + activation bar half width + 14 gap + hook/text offset + text
                    val w = 5f + 14f + 8f + u.expression.take(Sysml2Limits.TIME_EXPR).length * SEQ_CHAR_W + 2f * SEQ_CHAR_W
                    needs[NodeId(u.lifelineId)] = maxOf(needs[NodeId(u.lifelineId)] ?: 0f, w)
                }
            is TimeObservationUsage ->
                if (u.lifelineId in visible) {
                    val w = 10f + (u.name.take(Sysml2Limits.TIME_EXPR).length + 6) * SEQ_CHAR_W
                    needs[NodeId(u.lifelineId)] = maxOf(needs[NodeId(u.lifelineId)] ?: 0f, w)
                }
            else -> Unit
        }
    }
    return needs
}

private const val MIN_SEQ_LIFELINE_GAP: Float = 40f
private const val MIN_SIDE_TEXT_CLEARANCE: Float = 12f
private const val SEQ_CHAR_W: Float = 6.5f
