package dev.kuml.layout.bridge.bpmn

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ChoreographySequenceFlow
import dev.kuml.bpmn.model.ChoreographyTask
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.GroupId
import dev.kuml.layout.GroupLayout
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SizeProvider

/**
 * Deterministisches Custom-Grid-Layout für BPMN-Choreography-Diagramme.
 *
 * Umgeht ELK bewusst (analog zu `BlueprintLayoutBridge`): eine Choreography ist eine
 * lineare/verzweigende Sequenz von Zwei-Parteien-Interaktionen, kein freier Graph —
 * Teilnehmer-Spuren (Y) × Sequenz-Schritte (X) bilden ein striktes Raster. Liefert
 * ein Standard-[LayoutResult], damit die bestehenden `BpmnChoreoSvg`-Symbol-Renderer
 * es unverändert konsumieren.
 *
 * Algorithmus (siehe V3.2.2-Plan):
 * 1. Sequenz-Rang je Element (längster Pfad ab Start-Events, Rückkanten/Loops
 *    werden per DFS erkannt und beim Ranking ignoriert) → X-Spalte.
 * 2. Teilnehmer-Spur je Teilnehmer (erste Erwähnung, Initiator zuerst je Task) → Y-Zeile.
 * 3. Tasks spannen die Spuren ihrer zwei Teilnehmer auf; Gateways/Events sitzen auf
 *    einer "Spine"-Spur (Median der anliegenden Teilnehmer-Spuren).
 * 4. Kanten: direkt bei gleicher Spur + benachbarter Spalte, sonst orthogonal mit
 *    einem vertikalen Versatz auf halber Spaltenbreite. Rückkanten (Loops) werden
 *    unterhalb aller Spuren geroutet.
 *
 * V3.2.2 — BPMN Choreography SVG-Renderer (custom grid layout, kein ELK)
 */
public object ChoreographyGridLayout {
    public val ENGINE_ID: LayoutEngineId = LayoutEngineId("bpmn-choreo-grid")

    // ── Grid-Konstanten (px) ─────────────────────────────────────────────────
    public const val TASK_W: Float = 160f
    public const val TASK_MIN_H: Float = 80f
    public const val LANE_HEIGHT: Float = 44f
    public const val COL_GAP: Float = 60f
    public const val EVENT_D: Float = 36f
    public const val GATEWAY_D: Float = 50f
    public const val MARGIN: Float = 24f
    public const val LOOP_BACKEDGE_RESERVE: Float = 48f
    public const val CORNER_RADIUS: Float = 6f

    /**
     * Berechnet das vollständige [LayoutResult] für [diagram] gegen [model].
     *
     * @param model Das BPMN-Modell mit allen Choreographien.
     * @param diagram Das [ChoreographyDiagram], das Choreography + Element-Filter festlegt.
     * @param sizeProvider Optionaler inhalts-bewusster SizeProvider (Task-Breite aus Namenlänge).
     * @return Ein deterministisches [LayoutResult]; leeres Ergebnis, wenn die Choreography fehlt.
     */
    public fun layout(
        model: BpmnModel,
        diagram: ChoreographyDiagram,
        sizeProvider: SizeProvider? = null,
    ): LayoutResult {
        val emptyResult =
            LayoutResult(
                engineId = ENGINE_ID,
                seed = null,
                canvas = Size(MARGIN * 2, MARGIN * 2),
                nodes = emptyMap(),
                edges = emptyMap(),
                groups = emptyMap<GroupId, GroupLayout>(),
            )

        val choreography =
            model.choreographies.firstOrNull { it.id == diagram.choreographyId }
                ?: return emptyResult

        val filterIds: Set<String>? = diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()

        val tasks = choreography.tasks.filter { filterIds == null || it.id in filterIds }
        val gateways = choreography.gateways.filter { filterIds == null || it.id in filterIds }
        val events = choreography.events.filter { filterIds == null || it.id in filterIds }

        val elementIds: List<String> = tasks.map { it.id } + gateways.map { it.id } + events.map { it.id }
        val elementIdSet = elementIds.toSet()

        val flows =
            choreography.sequenceFlows.filter { flow ->
                (filterIds == null || flow.id in filterIds) &&
                    flow.sourceRef in elementIdSet &&
                    flow.targetRef in elementIdSet
            }

        if (elementIds.isEmpty()) return emptyResult

        // STEP A/B — Sequenz-Ranking (Spalten)
        val (ranks, backEdgeIds) = computeSequenceRanks(elementIds, flows)

        // STEP C — Teilnehmer-Spuren
        val laneIndex: Map<String, Int> = assignParticipantLanes(tasks)
        val laneCount = maxOf(1, laneIndex.size)
        val spineLane = (laneCount - 1) / 2

        val kindById: Map<String, String> =
            tasks.associate { it.id to "ChoreographyTask" } +
                gateways.associate { it.id to "ChoreographyGateway" } +
                events.associate { it.id to "ChoreographyEvent" }

        val taskById: Map<String, ChoreographyTask> = tasks.associateBy { it.id }

        // STEP D — Zellen → absolute Geometrie
        fun columnX(rank: Int): Float = MARGIN + rank * (TASK_W + COL_GAP)

        fun laneY(lane: Int): Float = MARGIN + lane * LANE_HEIGHT

        val bounds = mutableMapOf<String, Rect>()
        for (id in elementIds) {
            val rank = ranks.getValue(id)
            val kind = kindById.getValue(id)
            when (kind) {
                "ChoreographyTask" -> {
                    val task = taskById.getValue(id)
                    val lanes = task.participants.mapNotNull { laneIndex[it] }
                    val topLane = lanes.minOrNull() ?: spineLane
                    val bottomLane = lanes.maxOrNull() ?: spineLane
                    val size = sizeProvider?.sizeOf(id, kind) ?: Size(TASK_W, TASK_MIN_H)
                    val x = columnX(rank)
                    val y = laneY(topLane)
                    val spanH = laneY(bottomLane) + LANE_HEIGHT - y
                    val h = maxOf(TASK_MIN_H, spanH, size.height)
                    val w = maxOf(TASK_W, size.width)
                    bounds[id] = Rect(Point(x, y), Size(w, h))
                }

                "ChoreographyGateway" -> {
                    val size = sizeProvider?.sizeOf(id, kind) ?: Size(GATEWAY_D, GATEWAY_D)
                    val x = columnX(rank) + (TASK_W - size.width) / 2f
                    val y = laneY(spineLane) + (LANE_HEIGHT - size.height) / 2f
                    bounds[id] = Rect(Point(x, y), size)
                }

                else -> {
                    val size = sizeProvider?.sizeOf(id, kind) ?: Size(EVENT_D, EVENT_D)
                    val x = columnX(rank) + (TASK_W - size.width) / 2f
                    val y = laneY(spineLane) + (LANE_HEIGHT - size.height) / 2f
                    bounds[id] = Rect(Point(x, y), size)
                }
            }
        }

        val maxRank = ranks.values.maxOrNull() ?: 0
        val canvasWidth = columnX(maxRank) + TASK_W + MARGIN
        val loopReserve = if (backEdgeIds.isNotEmpty()) LOOP_BACKEDGE_RESERVE else 0f
        val canvasHeight = laneY(laneCount - 1) + LANE_HEIGHT + MARGIN + loopReserve

        // STEP E — Kanten-Routing
        val edges = mutableMapOf<EdgeId, EdgeRoute>()
        for (flow in flows) {
            val source = bounds[flow.sourceRef] ?: continue
            val target = bounds[flow.targetRef] ?: continue
            val isBackEdge = flow.id in backEdgeIds
            edges[EdgeId(flow.id)] = routeFlow(source, target, isBackEdge, canvasHeight)
        }

        val nodes: Map<NodeId, NodeLayout> =
            bounds.mapKeys { NodeId(it.key) }.mapValues { NodeLayout(bounds = it.value) }

        return LayoutResult(
            engineId = ENGINE_ID,
            seed = null,
            canvas = Size(canvasWidth, canvasHeight),
            nodes = nodes,
            edges = edges,
            groups = emptyMap(),
        )
    }

    /**
     * Topologisches Longest-Path-Ranking. Rückkanten (bei Loops) werden per DFS
     * anhand des Aufruf-Stacks erkannt und beim Ranking ignoriert, damit Zyklen
     * nicht zu unendlicher Rekursion / NaN-Rängen führen.
     *
     * @return Paar aus (Rang je Element-ID, Menge der Flow-IDs, die Rückkanten sind).
     */
    private fun computeSequenceRanks(
        elementIds: List<String>,
        flows: List<ChoreographySequenceFlow>,
    ): Pair<Map<String, Int>, Set<String>> {
        val outgoing: Map<String, List<ChoreographySequenceFlow>> = flows.groupBy { it.sourceRef }
        val backEdgeIds = mutableSetOf<String>()

        // DFS zur Rückkanten-Erkennung (grau = im aktuellen Pfad, schwarz = fertig).
        val visitState = mutableMapOf<String, Int>() // 0=unbesucht, 1=im Pfad, 2=fertig

        fun detectBackEdges(node: String) {
            visitState[node] = 1
            for (flow in outgoing[node].orEmpty()) {
                val target = flow.targetRef
                when (visitState[target] ?: 0) {
                    1 -> backEdgeIds.add(flow.id) // Rückkante: Ziel liegt im aktuellen Pfad
                    0 -> detectBackEdges(target)
                    else -> Unit
                }
            }
            visitState[node] = 2
        }
        for (id in elementIds) {
            if ((visitState[id] ?: 0) == 0) detectBackEdges(id)
        }

        // Longest-path-Ranking über den azyklischen Restgraphen (ohne Rückkanten).
        val forwardOutgoing: Map<String, List<String>> =
            flows.filter { it.id !in backEdgeIds }.groupBy({ it.sourceRef }, { it.targetRef })
        val incomingCount: MutableMap<String, Int> = elementIds.associateWith { 0 }.toMutableMap()
        for ((_, targets) in forwardOutgoing) {
            for (t in targets) incomingCount[t] = (incomingCount[t] ?: 0) + 1
        }

        val ranks = mutableMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        // Startknoten = keine eingehenden Vorwärtskanten (typischerweise Start-Events).
        for (id in elementIds) if ((incomingCount[id] ?: 0) == 0) queue.add(id)
        if (queue.isEmpty() && elementIds.isNotEmpty()) queue.add(elementIds.first())

        for (id in elementIds) ranks[id] = 0
        val remainingIncoming = incomingCount.toMutableMap()
        val processed = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node in processed) continue
            processed.add(node)
            for (t in forwardOutgoing[node].orEmpty()) {
                ranks[t] = maxOf(ranks[t] ?: 0, (ranks[node] ?: 0) + 1)
                remainingIncoming[t] = (remainingIncoming[t] ?: 1) - 1
                if ((remainingIncoming[t] ?: 0) <= 0 && t !in processed) queue.add(t)
            }
        }
        // Alle noch nicht erreichten Elemente (isolierte Knoten) bleiben bei Rang 0.
        for (id in elementIds) if (id !in processed) ranks[id] = ranks[id] ?: 0

        return ranks to backEdgeIds
    }

    /** Teilnehmer → Spur-Index in Erst-Erwähnungs-Reihenfolge (Initiator zuerst je Task). */
    private fun assignParticipantLanes(tasks: List<ChoreographyTask>): Map<String, Int> {
        val order = LinkedHashSet<String>()
        for (task in tasks) {
            order.add(task.initiatingParticipant)
            for (p in task.participants) order.add(p)
        }
        return order.withIndex().associate { (i, p) -> p to i }
    }

    /** Orthogonale/direkte Kanten-Route zwischen zwei platzierten Knoten. */
    private fun routeFlow(
        source: Rect,
        target: Rect,
        isBackEdge: Boolean,
        canvasHeight: Float,
    ): EdgeRoute {
        val sourcePort = Point(source.origin.x + source.size.width, source.origin.y + source.size.height / 2f)
        val targetPort = Point(target.origin.x, target.origin.y + target.size.height / 2f)

        if (isBackEdge) {
            val loopY = canvasHeight - LOOP_BACKEDGE_RESERVE / 2f
            val sourceBottom = Point(source.origin.x + source.size.width / 2f, source.origin.y + source.size.height)
            val targetBottom = Point(target.origin.x + target.size.width / 2f, target.origin.y + target.size.height)
            return EdgeRoute.OrthogonalRounded(
                source = sourceBottom,
                target = targetBottom,
                waypoints = listOf(Point(sourceBottom.x, loopY), Point(targetBottom.x, loopY)),
                cornerRadiusPx = CORNER_RADIUS,
            )
        }

        val sameLaneBand = sourcePort.y == targetPort.y
        val adjacentColumns = target.origin.x - (source.origin.x + source.size.width) <= COL_GAP + 1f
        return if (sameLaneBand && adjacentColumns) {
            EdgeRoute.Direct(source = sourcePort, target = targetPort)
        } else {
            val midX = (sourcePort.x + targetPort.x) / 2f
            EdgeRoute.OrthogonalRounded(
                source = sourcePort,
                target = targetPort,
                waypoints = listOf(Point(midX, sourcePort.y), Point(midX, targetPort.y)),
                cornerRadiusPx = CORNER_RADIUS,
            )
        }
    }
}
