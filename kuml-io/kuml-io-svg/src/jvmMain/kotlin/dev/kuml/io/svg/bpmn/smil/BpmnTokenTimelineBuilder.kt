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
 * - [SmilAnimation.Fill] — gateway diamond highlights on activation (amber).
 * - [SmilAnimation.Fill] — task rect fills light-blue on execution, plus stroke-width pulse.
 * - [SmilAnimation.Fill] — start event fills light-green, end event fills light-red on visit.
 * - [SmilAnimation.Animate] stroke-width — task rect border pulse (retained alongside fill).
 * - [SmilAnimation.Animate] opacity — start/end events dim on visit (retained alongside fill).
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
                    // Reset gateway diamond back to white after the highlight fades
                    val gatewayReset =
                        SmilAnimation.Fill(
                            elementId = "${xmlEscapeId(nodeId)}-diamond",
                            color = "white",
                            beginMs = beginMs + GATEWAY_HIGHLIGHT_MS,
                            durationMs = GATEWAY_HIGHLIGHT_MS,
                            fromColor = context.highlightColor,
                        )
                    animations += gatewayReset
                }
                is BpmnTask -> {
                    // Task execution: fill highlight (light blue) on the "-box" rect + stroke-width
                    // pulse (0 → 4 → 0) on a dedicated transparent "-box-pulse" overlay rect.
                    // Fill must NOT target the parent <g> (it would not override the child <rect>'s
                    // own fill). Stroke-width is animated on the overlay because SMIL <animate> on
                    // stroke-width is browser-inconsistent when the rect carries an inline
                    // stroke-width presentation attribute (Chrome/Safari ignore it).
                    val boxId = "${xmlEscapeId(nodeId)}-box"
                    val pulseId = "$boxId-pulse"
                    // Fill covers the full pulse duration (on + off = 2 × TASK_PULSE_MS).
                    val fillOn =
                        SmilAnimation.Fill(
                            elementId = boxId,
                            color = context.taskHighlightColor,
                            beginMs = beginMs,
                            durationMs = TASK_PULSE_MS * 2,
                            fromColor = "white",
                        )
                    val fillOff =
                        SmilAnimation.Fill(
                            elementId = boxId,
                            color = "white",
                            beginMs = beginMs + TASK_PULSE_MS * 2,
                            durationMs = TASK_PULSE_MS,
                            fromColor = context.taskHighlightColor,
                        )
                    val pulseOn =
                        SmilAnimation.Animate(
                            elementId = pulseId,
                            attribute = "stroke-width",
                            from = "0",
                            to = "4",
                            beginMs = beginMs,
                            durationMs = TASK_PULSE_MS,
                        )
                    val pulseOff =
                        SmilAnimation.Animate(
                            elementId = pulseId,
                            attribute = "stroke-width",
                            from = "4",
                            to = "0",
                            beginMs = beginMs + TASK_PULSE_MS,
                            durationMs = TASK_PULSE_MS,
                        )
                    animations += fillOn
                    animations += fillOff
                    animations += pulseOn
                    animations += pulseOff
                }
                is BpmnEvent -> {
                    // Start/End events: type-specific fill highlight + opacity dim (both retained).
                    // Start events fill light-green, end events fill light-red.
                    val fillColor =
                        when (element.position) {
                            EventPosition.START -> context.startEventColor
                            EventPosition.END -> context.endEventColor
                            else -> null
                        }
                    if (fillColor != null) {
                        // Fill targets the "-circle" <circle> id, NOT the parent <g>:
                        // the circle carries its own fill="white" which the group fill cannot override.
                        // The opacity dim below stays on the <g> so the whole event group fades.
                        val circleId = "${xmlEscapeId(nodeId)}-circle"
                        val fillOn =
                            SmilAnimation.Fill(
                                elementId = circleId,
                                color = fillColor,
                                beginMs = beginMs,
                                durationMs = EVENT_DIM_MS,
                                fromColor = "white",
                            )
                        val fillOff =
                            SmilAnimation.Fill(
                                elementId = circleId,
                                color = "white",
                                beginMs = beginMs + EVENT_DIM_MS,
                                durationMs = EVENT_DIM_MS,
                                fromColor = fillColor,
                            )
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
                        animations += fillOn
                        animations += fillOff
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
        // Loop the sequence `context.loopCount` times with `context.loopGapMs` pause between passes.
        // loopGapMs is applied BEFORE speed scaling so the gap compresses consistently with content.
        // LOOP_INFINITE is capped at LOOP_PRACTICAL_MAX to keep the SVG size manageable while
        // still providing ~23 minutes of seamless animation (effectively infinite for presentations).
        val effectiveLoopCount =
            if (context.loopCount == BpmnAnimationContext.LOOP_INFINITE) {
                BpmnAnimationContext.LOOP_PRACTICAL_MAX
            } else {
                context.loopCount
            }
        val scaledTimeline =
            if (effectiveLoopCount <= 1 || rawTimeline.animations.isEmpty()) {
                rawTimeline.scaledBy(context.speedFactor)
            } else {
                val onePassMs = rawTimeline.totalDurationMs + context.loopGapMs
                val loopedAnimations = rawTimeline.animations.toMutableList()
                for (i in 1 until effectiveLoopCount) {
                    loopedAnimations += rawTimeline.shiftedBy(i * onePassMs).animations
                }
                SmilTimeline(loopedAnimations).scaledBy(context.speedFactor)
            }
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
