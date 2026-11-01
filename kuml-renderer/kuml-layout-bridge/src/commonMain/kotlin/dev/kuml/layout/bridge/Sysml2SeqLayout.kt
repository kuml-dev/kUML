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
            // Wave 2E-a: the corridor between layers is 12 px so parallel edges sit closer to their cards. The design
            // note asked for edgeToEdge = 10; 20 meets its minimum and keeps existing renders from getting worse.
            LayoutHints.DEFAULT.copy(
                spacing =
                    LayoutHints.DEFAULT.spacing.copy(
                        nodeToNode = 80f,
                        edgeToEdge = 20f,
                        layerToLayer = 100f,
                        edgeToEdgeBetweenLayers = 12f,
                    ),
            )
        // V2.0.44: viele Kantenlabels (`event [guard] / action`) um gemeinsame Zustände.
        is StmDiagram ->
            LayoutHints.DEFAULT.copy(
                spacing = Spacing(nodeToNode = 80f, edgeToEdge = 28f, groupPadding = 24f, edgeToEdgeBetweenLayers = 16f),
            )
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
): LayoutResult = seqVerticalMargins(normalizeSeqOrderAndGaps(model = model, diagram = diagram, layoutResult = layoutResult))

/** Wave 2F: space above the lifeline heads and below the lifeline ends, on top of the 16 px canvas padding (px). */
internal const val SEQ_VERTICAL_MARGIN = 8f

/**
 * Wave 2F (S5): the engine centres the lifelines vertically, which left 130 to 230 px of blank space above and below.
 * The lifelines are moved to [SEQ_VERTICAL_MARGIN] from the top and the canvas ends [SEQ_VERTICAL_MARGIN] below the
 * lowest lifeline end. Messages, fragments and notes are all derived from the lifeline boxes, so they follow.
 */
internal fun seqVerticalMargins(result: LayoutResult): LayoutResult {
    if (result.nodes.isEmpty()) return result
    val top = result.nodes.values.minOf { it.bounds.origin.y }
    val bottom = result.nodes.values.maxOf { it.bounds.origin.y + it.bounds.size.height }
    val dy = SEQ_VERTICAL_MARGIN - top
    if (kotlin.math.abs(dy) < 0.01f && kotlin.math.abs(result.canvas.height - (bottom + SEQ_VERTICAL_MARGIN)) < 0.01f) return result
    val nodes =
        result.nodes.mapValues { (_, n) ->
            n.copy(bounds = n.bounds.copy(origin = n.bounds.origin.copy(y = n.bounds.origin.y + dy)))
        }
    return result.copy(nodes = nodes, canvas = result.canvas.copy(height = bottom + dy + SEQ_VERTICAL_MARGIN))
}

private fun Sysml2LayoutBridge.normalizeSeqOrderAndGaps(
    model: Sysml2Model,
    diagram: SeqDiagram,
    layoutResult: LayoutResult,
): LayoutResult {
    val ordered = diagram.elementIds.map { NodeId(it) }.filter { it in layoutResult.nodes }
    if (ordered.isEmpty()) return layoutResult
    val needs = seqSideTextNeeds(model = model, ordered = ordered)
    return seqLeftCanvasShift(
        needs = needs,
        ordered = ordered,
        result = seqSpaceLifelines(needs = needs, ordered = ordered, layoutResult = layoutResult),
    )
}

/**
 * Wave 2F: text that stands LEFT of the leftmost lifeline (a duration constraint bracket, which is drawn 12 px left of
 * the axis) would start at a negative x. The whole diagram moves right by the missing room and the canvas widens by
 * the same amount. Idempotent: once the room exists, nothing moves.
 */
private fun seqLeftCanvasShift(
    needs: SeqSideNeeds,
    ordered: List<NodeId>,
    result: LayoutResult,
): LayoutResult {
    val first =
        ordered.minByOrNull {
            result.nodes
                .getValue(it)
                .bounds.origin.x
        } ?: return result
    val b = result.nodes.getValue(first).bounds
    val missing = (needs.left[first] ?: 0f) - (b.origin.x + b.size.width / 2f)
    if (missing <= 0.01f) return result
    return result.copy(
        nodes =
            result.nodes.mapValues { (_, n) ->
                n.copy(
                    bounds =
                        n.bounds.copy(
                            origin =
                                n.bounds.origin.copy(
                                    x =
                                        n.bounds.origin.x + missing,
                                ),
                        ),
                )
            },
        canvas = result.canvas.copy(width = result.canvas.width + missing),
    )
}

private fun seqSpaceLifelines(
    needs: SeqSideNeeds,
    ordered: List<NodeId>,
    layoutResult: LayoutResult,
): LayoutResult {
    val rightNeeds = needs.right
    val leftNeeds = needs.left
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
        ordered.zipWithNext().all { (a, b) ->
            centre(b) - centre(a) >=
                (rightNeeds[a] ?: 0f) + (leftNeeds[b] ?: 0f) + MIN_SIDE_TEXT_CLEARANCE
        } &&
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
                previousCentre + previousNeed + (leftNeeds[id] ?: 0f) + MIN_SIDE_TEXT_CLEARANCE
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

/** Width of the widest side text per lifeline axis, to the left and to the right. */
private data class SeqSideNeeds(
    val left: Map<NodeId, Float>,
    val right: Map<NodeId, Float>,
)

/**
 * Widest duration-constraint / time-observation / self-message text on each side of a lifeline's axis. Mirrors the
 * renderer: the duration constraint bracket and its text stand LEFT of the axis, a time observation label stands left
 * unless the lifeline is the leftmost one or a message in the same row runs to its left (then it is on the right),
 * a self-message label stands right of its loop.
 */
private fun seqSideTextNeeds(
    model: Sysml2Model,
    ordered: List<NodeId>,
): SeqSideNeeds {
    val visible = ordered.map { it.value }.toSet()
    val indexOf = ordered.mapIndexed { i, id -> id.value to i }.toMap()
    val left = HashMap<NodeId, Float>()
    val right = HashMap<NodeId, Float>()

    fun add(
        into: HashMap<NodeId, Float>,
        id: String,
        w: Float,
    ) {
        into[NodeId(id)] = maxOf(into[NodeId(id)] ?: 0f, w)
    }
    val messages = model.usages.filterIsInstance<dev.kuml.sysml2.MessageUsage>()
    for (u in model.usages) {
        when (u) {
            is DurationConstraintUsage ->
                if (u.lifelineId in visible) {
                    // axis + activation bar half width + 12 gap + 8 hook/text offset + text
                    add(left, u.lifelineId, 5f + 12f + 8f + u.expression.take(Sysml2Limits.TIME_EXPR).length * SEQ_CHAR_W + 2f * SEQ_CHAR_W)
                }
            is TimeObservationUsage ->
                if (u.lifelineId in visible) {
                    val shown = u.label?.takeIf { it.isNotBlank() }?.let { "${u.name} = $it" } ?: u.name
                    val w = 10f + (shown.take(Sysml2Limits.TIME_EXPR).length + 2) * SEQ_CHAR_W
                    val myIndex = indexOf.getValue(u.lifelineId)
                    val leftBlocked =
                        messages.any { m ->
                            m.seqNo == u.seqNo &&
                                (m.sourceLifelineId == u.lifelineId || m.targetLifelineId == u.lifelineId) &&
                                m.sourceLifelineId != m.targetLifelineId &&
                                listOf(m.sourceLifelineId, m.targetLifelineId).filter { it != u.lifelineId }.any { other ->
                                    (indexOf[other] ?: Int.MAX_VALUE) < myIndex
                                }
                        }
                    if (myIndex > 0 && !leftBlocked) add(left, u.lifelineId, w) else add(right, u.lifelineId, w)
                }
            is dev.kuml.sysml2.MessageUsage ->
                // Wave 2F: a self message has a 32 px loop and its label right of it.
                if (u.sourceLifelineId == u.targetLifelineId &&
                    u.kind != dev.kuml.sysml2.MessageKind.Found &&
                    u.kind != dev.kuml.sysml2.MessageKind.Lost &&
                    u.sourceLifelineId in visible
                ) {
                    add(right, u.sourceLifelineId, 32f + 4f + u.messageLabel.length * SEQ_CHAR_W)
                }
            else -> Unit
        }
    }
    return SeqSideNeeds(left = left, right = right)
}

private const val MIN_SEQ_LIFELINE_GAP: Float = 40f
private const val MIN_SIDE_TEXT_CLEARANCE: Float = 12f
private const val SEQ_CHAR_W: Float = 6.5f
