package dev.kuml.io.svg.activity.smil

import dev.kuml.render.smil.SmilAnimation
import dev.kuml.render.smil.SmilTimeline
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlActivityEdge

/**
 * A token circle element to inject into the SVG.
 *
 * Each circle is invisible (opacity 0) by default and revealed only during motion.
 */
internal data class ActivityTokenCircle(
    val id: String,
    val color: String,
)

/**
 * Builds a [SmilTimeline] from [TraceFile] Activity trace entries.
 *
 * Trace entries handled:
 * - [TraceEntry.TokenPlaced]: Injects a token circle + [SmilAnimation.AnimateMotion] along the
 *   resolved flow path + opacity reveal animation.
 * - [TraceEntry.TokenConsumed]: [SmilAnimation.Set] opacity=0 on the travelling token.
 * - [TraceEntry.ForkSplit]: [SmilAnimation.Fill] highlight on the fork node.
 * - [TraceEntry.JoinReached]: [SmilAnimation.Fill] highlight on the join node.
 * - [TraceEntry.DecisionTaken]: [SmilAnimation.Fill] highlight on the decision node.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
internal object ActivityTokenTimelineBuilder {
    /** Milliseconds per animation step at 1× speed. */
    private const val STEP_MS: Long = 1_000L

    /** Duration of the token opacity reveal/hide (ms). */
    private const val OPACITY_DUR_MS: Long = 50L

    /** Duration of the fork/join/decision fill highlight (ms). */
    private const val NODE_HIGHLIGHT_MS: Long = 400L

    /**
     * Builds the animation timeline and the list of token circle descriptors.
     *
     * @param edges The activity edges in the diagram.
     * @param trace The trace file.
     * @param edgePaths Map from [UmlActivityEdge.id] to SVG path `d`-string.
     * @param context Animation tuning parameters.
     * @return Pair of (pre-scaled [SmilTimeline], list of [ActivityTokenCircle] elements to inject).
     * @throws IllegalArgumentException if the trace exceeds [ActivityAnimationContext.MAX_ANIMATIONS].
     */
    fun build(
        edges: List<UmlActivityEdge>,
        trace: TraceFile,
        edgePaths: Map<String, String>,
        context: ActivityAnimationContext,
    ): Pair<SmilTimeline, List<ActivityTokenCircle>> {
        // Build flow index for O(1) edge lookup
        val flowIndex = ActivityFlowPathResolver.buildFlowIndex(edges)

        // Filter relevant Activity entries in seqNo order
        val actEntries =
            trace.entries
                .filter {
                    it is TraceEntry.TokenPlaced ||
                        it is TraceEntry.TokenConsumed ||
                        it is TraceEntry.ForkSplit ||
                        it is TraceEntry.JoinReached ||
                        it is TraceEntry.DecisionTaken
                }.sortedBy { it.seqNo }

        // Guard: cap total animations
        require(actEntries.size <= ActivityAnimationContext.MAX_ANIMATIONS) {
            "Activity trace has ${actEntries.size} entries which exceeds the maximum of " +
                "${ActivityAnimationContext.MAX_ANIMATIONS}. Reduce trace length or increase the cap."
        }

        val animations = mutableListOf<SmilAnimation>()
        val circles = mutableListOf<ActivityTokenCircle>()

        // Track visited node ids in order for motion leg resolution
        val visitedNodes = mutableListOf<String>()
        // Track per-token circle ids (by token clock or seqNo)
        var circleSeq = 0
        // Map from nodeId to the current token circle id travelling to that node
        val activeTokenCircles = mutableMapOf<String, String>()

        var stepIndex = 0L

        for ((idx, entry) in actEntries.withIndex()) {
            val beginMs = stepIndex * STEP_MS

            when (entry) {
                is TraceEntry.TokenPlaced -> {
                    val nodeId = entry.nodeId
                    visitedNodes += nodeId

                    // Check if there's a resolvable edge from the previous node to this one
                    if (visitedNodes.size >= 2) {
                        val prevNodeId = visitedNodes[visitedNodes.size - 2]
                        val edge = flowIndex["$prevNodeId->$nodeId"]
                        if (edge != null) {
                            val pathD = edgePaths[edge.id]
                            if (pathD != null) {
                                val circleId = "kuml-act-token-${circleSeq++}"
                                circles += ActivityTokenCircle(id = circleId, color = context.tokenColor)
                                activeTokenCircles[nodeId] = circleId

                                val motionBeginMs = beginMs
                                // Show token
                                animations +=
                                    SmilAnimation.Animate(
                                        elementId = circleId,
                                        attribute = "opacity",
                                        from = "0",
                                        to = "1",
                                        beginMs = motionBeginMs,
                                        durationMs = OPACITY_DUR_MS,
                                    )
                                // Animate motion along flow edge path
                                animations +=
                                    SmilAnimation.AnimateMotion(
                                        elementId = circleId,
                                        path = pathD,
                                        beginMs = motionBeginMs,
                                        durationMs = STEP_MS / 2,
                                    )
                                // Hide after motion
                                animations +=
                                    SmilAnimation.Animate(
                                        elementId = circleId,
                                        attribute = "opacity",
                                        from = "1",
                                        to = "0",
                                        beginMs = motionBeginMs + STEP_MS / 2,
                                        durationMs = OPACITY_DUR_MS,
                                    )
                            }
                        }
                    }
                    stepIndex++
                }

                is TraceEntry.TokenConsumed -> {
                    val nodeId = entry.nodeId
                    val circleId = activeTokenCircles[nodeId]
                    if (circleId != null) {
                        // Immediately hide token circle when consumed
                        animations +=
                            SmilAnimation.Set(
                                elementId = circleId,
                                attribute = "opacity",
                                to = "0",
                                beginMs = beginMs,
                                durationMs = OPACITY_DUR_MS,
                            )
                        activeTokenCircles.remove(nodeId)
                    }
                    stepIndex++
                }

                is TraceEntry.ForkSplit -> {
                    visitedNodes += entry.nodeId
                    animations +=
                        SmilAnimation.Fill(
                            elementId = sanitizeId(entry.nodeId),
                            color = context.highlightColor,
                            beginMs = beginMs,
                            durationMs = NODE_HIGHLIGHT_MS,
                            fromColor = "white",
                        )
                    stepIndex++
                }

                is TraceEntry.JoinReached -> {
                    visitedNodes += entry.nodeId
                    animations +=
                        SmilAnimation.Fill(
                            elementId = sanitizeId(entry.nodeId),
                            color = context.highlightColor,
                            beginMs = beginMs,
                            durationMs = NODE_HIGHLIGHT_MS,
                            fromColor = "white",
                        )
                    stepIndex++
                }

                is TraceEntry.DecisionTaken -> {
                    visitedNodes += entry.nodeId
                    animations +=
                        SmilAnimation.Fill(
                            elementId = sanitizeId(entry.nodeId),
                            color = context.highlightColor,
                            beginMs = beginMs,
                            durationMs = NODE_HIGHLIGHT_MS,
                            fromColor = "white",
                        )
                    stepIndex++
                }

                else -> Unit
            }
        }

        val rawTimeline = SmilTimeline(animations)
        val scaledTimeline = rawTimeline.scaledBy(context.speedFactor)
        return Pair(scaledTimeline, circles)
    }

    internal fun sanitizeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9\\-_]"), "_")
}
