package dev.kuml.io.svg.bpmn.smil

import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnTask
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.render.smil.SmilAnimation
import dev.kuml.render.smil.SmilTimeline
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile

/**
 * A token circle to inject into the SVG.
 *
 * Each circle is invisible (opacity 0) by default and revealed only during its
 * motion leg via a companion [SmilAnimation.Animate] on the `opacity` attribute.
 *
 * @param id Unique SVG `id` attribute for the circle element.
 * @param color CSS color string for the `fill` attribute.
 */
internal data class TokenCircle(
    val id: String,
    val color: String,
)

/**
 * Builds a [SmilTimeline] from [TraceFile] token entries for a BPMN process.
 *
 * The builder walks [TraceFile.entries] and, for each consecutive pair of
 * [TraceEntry.TokenPlaced] / [TraceEntry.TokenConsumed] nodeId pairs that are
 * connected by a [dev.kuml.bpmn.model.SequenceFlow], emits:
 *
 * - [SmilAnimation.AnimateMotion] — token circle travels along the edge path.
 * - [SmilAnimation.Animate] opacity — circle fades in/out for its travel leg.
 * - [SmilAnimation.Fill] — gateway diamond highlights on activation.
 * - [SmilAnimation.Animate] stroke-width — task rect pulses on execution.
 * - [SmilAnimation.Animate] opacity — start/end events dim on visit.
 *
 * Only [TraceEntry.TokenPlaced] and [TraceEntry.TokenConsumed] entries are
 * meaningful for BPMN animation. Other entry types (STM-style StateEntered,
 * TransitionFired, etc.) are silently ignored.
 *
 * V3.1.30 — BPMN SMIL Renderer
 */
internal object BpmnTokenTimelineBuilder {
    /** Milliseconds per animation step at 1× speed (before speedFactor scaling). */
    private const val STEP_MS: Long = 1_000L

    /** Opacity animation duration for the token circle showing/hiding (ms). */
    private const val OPACITY_DUR_MS: Long = 50L

    /** Duration of the gateway fill highlight (ms). */
    private const val GATEWAY_HIGHLIGHT_MS: Long = 400L

    /** Duration of the task stroke-width pulse (ms). */
    private const val TASK_PULSE_MS: Long = 300L

    /** Duration of the start/end event opacity dim (ms). */
    private const val EVENT_DIM_MS: Long = 400L

    /**
     * Builds the animation timeline and the list of token circle descriptors.
     *
     * @param process The BPMN process whose elements are animated.
     * @param trace The trace file containing token entries.
     * @param edgePaths Map from [dev.kuml.bpmn.model.SequenceFlow.id] to SVG path `d`-string.
     * @param context Animation tuning parameters.
     * @return Pair of (pre-scaled [SmilTimeline], list of [TokenCircle] elements to inject).
     * @throws IllegalArgumentException if the trace exceeds [BpmnAnimationContext.MAX_ANIMATIONS].
     */
    fun build(
        process: BpmnProcess,
        trace: TraceFile,
        edgePaths: Map<String, String>,
        context: BpmnAnimationContext,
    ): Pair<SmilTimeline, List<TokenCircle>> {
        // Build flat element index for node type classification.
        val elementIndex = buildElementIndex(process)

        // Build flow lookup index once — avoids O(N*M) cost of collectAllFlows() per iteration.
        val flowIndex = BpmnFlowPathResolver.buildFlowIndex(process)

        // Extract token-relevant entries in seqNo order.
        // TokenPlaced and TokenConsumed are the primary token-movement events.
        // DecisionTaken, ForkSplit, and JoinReached are gateway-activation events emitted by
        // BPMN-native runtimes. A gateway that fires via DecisionTaken may never emit a
        // TokenPlaced at the gateway node itself, so we must treat DecisionTaken/ForkSplit/
        // JoinReached as a gateway visit (nodeId visit) to ensure the gateway highlight
        // animation is emitted even when no TokenPlaced entry exists for the gateway.
        val tokenEntries =
            trace.entries
                .filter {
                    it is TraceEntry.TokenPlaced ||
                        it is TraceEntry.TokenConsumed ||
                        it is TraceEntry.DecisionTaken ||
                        it is TraceEntry.ForkSplit ||
                        it is TraceEntry.JoinReached
                }.sortedBy { it.seqNo }

        val animations = mutableListOf<SmilAnimation>()
        val circles = mutableListOf<TokenCircle>()

        // Walk consecutive pairs of token entries to find motion legs.
        // A motion leg is: TokenConsumed(nodeA) immediately followed by TokenPlaced(nodeB),
        // or equivalently consecutive nodeIds in the trace mapped to a SequenceFlow.
        // Simpler approach: collect all visited nodeIds in order, then for each adjacent pair
        // (a, b) where a SequenceFlow a->b exists, emit a motion animation.
        val visitedNodes = mutableListOf<String>()
        for (entry in tokenEntries) {
            when (entry) {
                is TraceEntry.TokenPlaced -> visitedNodes += entry.nodeId
                is TraceEntry.TokenConsumed -> { /* consumed node: already recorded on placement */ }
                // Gateway-activation entries: record the gateway node as visited so the
                // highlight animation is emitted even when no TokenPlaced targets the gateway.
                is TraceEntry.DecisionTaken -> visitedNodes += entry.nodeId
                is TraceEntry.ForkSplit -> visitedNodes += entry.nodeId
                is TraceEntry.JoinReached -> visitedNodes += entry.nodeId
                else -> Unit // never reached due to filter above
            }
        }

        // Guard: cap total animations
        val stepCount = visitedNodes.size
        if (stepCount > BpmnAnimationContext.MAX_ANIMATIONS) {
            throw IllegalArgumentException(
                "BPMN trace has $stepCount token-placement steps which exceeds the maximum " +
                    "of ${BpmnAnimationContext.MAX_ANIMATIONS}. Reduce trace length or increase the cap.",
            )
        }

        // Process adjacent node pairs to emit animations
        var circleSeq = 0
        var stepIndex = 0

        // Emit node-visit animations for each visited node
        for ((idx, nodeId) in visitedNodes.withIndex()) {
            val beginMs = stepIndex * STEP_MS
            val element = elementIndex[nodeId]

            when (element) {
                is BpmnGateway -> {
                    // Gateway activation: highlight fill on the inner diamond polygon.
                    // The <g> wrapper carries the gateway id; animating fill on a <g> does not
                    // propagate to child <polygon> fills in conforming SMIL renderers. Target the
                    // "-diamond" child polygon id that BpmnGatewaySvg places on the <polygon>.
                    val gatewayAnim =
                        SmilAnimation.Fill(
                            elementId = "${xmlEscapeId(nodeId)}-diamond",
                            color = context.highlightColor,
                            beginMs = beginMs,
                            durationMs = GATEWAY_HIGHLIGHT_MS,
                            fromColor = "white",
                        )
                    animations += gatewayAnim
                }
                is BpmnTask -> {
                    // Task execution: stroke-width pulse (1.5 → 4 → 1.5)
                    val pulseOn =
                        SmilAnimation.Animate(
                            elementId = xmlEscapeId(nodeId),
                            attribute = "stroke-width",
                            from = "1.5",
                            to = "4",
                            beginMs = beginMs,
                            durationMs = TASK_PULSE_MS,
                        )
                    val pulseOff =
                        SmilAnimation.Animate(
                            elementId = xmlEscapeId(nodeId),
                            attribute = "stroke-width",
                            from = "4",
                            to = "1.5",
                            beginMs = beginMs + TASK_PULSE_MS,
                            durationMs = TASK_PULSE_MS,
                        )
                    animations += pulseOn
                    animations += pulseOff
                }
                is BpmnEvent -> {
                    // Start/End events: opacity dim
                    if (element.position == EventPosition.START || element.position == EventPosition.END) {
                        val dimDown =
                            SmilAnimation.Animate(
                                elementId = xmlEscapeId(nodeId),
                                attribute = "opacity",
                                from = "1",
                                to = "0.3",
                                beginMs = beginMs,
                                durationMs = EVENT_DIM_MS,
                            )
                        val dimUp =
                            SmilAnimation.Animate(
                                elementId = xmlEscapeId(nodeId),
                                attribute = "opacity",
                                from = "0.3",
                                to = "1",
                                beginMs = beginMs + EVENT_DIM_MS,
                                durationMs = EVENT_DIM_MS,
                            )
                        animations += dimDown
                        animations += dimUp
                    }
                }
                else -> Unit // DataObject, SubProcess, CallActivity, null — no visit animation
            }

            // Emit motion animation for the edge from visitedNodes[idx] to visitedNodes[idx+1]
            if (idx + 1 < visitedNodes.size) {
                val nextNodeId = visitedNodes[idx + 1]
                val flow = flowIndex["$nodeId->$nextNodeId"]
                if (flow != null) {
                    val pathD = edgePaths[flow.id]
                    if (pathD != null) {
                        val circleId = "kuml-token-circle-${circleSeq++}"
                        val motionBeginMs = beginMs + STEP_MS / 2
                        circles += TokenCircle(id = circleId, color = context.tokenColor)

                        // Show token circle during motion
                        val showCircle =
                            SmilAnimation.Animate(
                                elementId = circleId,
                                attribute = "opacity",
                                from = "0",
                                to = "1",
                                beginMs = motionBeginMs,
                                durationMs = OPACITY_DUR_MS,
                            )
                        // Animate motion along flow path
                        val motionAnim =
                            SmilAnimation.AnimateMotion(
                                elementId = circleId,
                                path = pathD,
                                beginMs = motionBeginMs,
                                durationMs = STEP_MS / 2,
                            )
                        // Hide token circle after motion
                        val hideCircle =
                            SmilAnimation.Animate(
                                elementId = circleId,
                                attribute = "opacity",
                                from = "1",
                                to = "0",
                                beginMs = motionBeginMs + STEP_MS / 2,
                                durationMs = OPACITY_DUR_MS,
                            )
                        animations += showCircle
                        animations += motionAnim
                        animations += hideCircle
                    }
                }
            }

            stepIndex++
        }

        val rawTimeline = SmilTimeline(animations)
        val scaledTimeline = rawTimeline.scaledBy(context.speedFactor)
        return Pair(scaledTimeline, circles)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun buildElementIndex(process: BpmnProcess): Map<String, Any> {
        val index = mutableMapOf<String, Any>()
        for (node in process.flowNodes) {
            index[node.id] = node
            if (node is dev.kuml.bpmn.model.BpmnSubProcess && node.expanded) {
                for (inner in node.flowElementNodes) {
                    index[inner.id] = inner
                }
            }
        }
        return index
    }

    /**
     * Converts a BPMN element id to a safe SVG id by replacing non-alphanumeric
     * characters (except hyphens and underscores) with underscores.
     *
     * This matches the id sanitization used in [dev.kuml.io.svg.bpmn.edge.renderBpmnSequenceFlow]
     * for marker ids, and is applied here to the `elementId` used as xlink:href targets
     * in SMIL animations. SVG ids must not contain XML special chars like `<`, `>`, `"`, `&`.
     */
    internal fun xmlEscapeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9\\-_]"), "_")
}
