package dev.kuml.io.svg.stm.smil

import dev.kuml.layout.Rect
import dev.kuml.render.smil.SmilAnimation
import dev.kuml.render.smil.SmilTimeline
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlStateMachine

/**
 * A highlight overlay rect to inject into the SVG before the closing `</svg>` tag.
 *
 * Each rect is invisible by default (opacity=0) and targeted by SMIL animations.
 */
internal data class StmOverlay(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * A transition pulse overlay path to inject into the SVG.
 */
internal data class StmTransitionOverlay(
    val id: String,
    val pathD: String,
)

/**
 * Builds a [SmilTimeline] from [TraceFile] STM trace entries for a UML state machine.
 *
 * Trace entries handled:
 * - [TraceEntry.StateEntered]: Fill animation on a highlight overlay rect
 *   (highlightColor → normalColor, freeze).
 * - [TraceEntry.TransitionFired]: Two stroke-width pulse animations on a transition
 *   overlay path (`smil-stm-trans-<transitionId>`).
 * - [TraceEntry.Terminated]: Set opacity=0.5 on the final state's overlay rect.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
internal object StmStateTimelineBuilder {
    /** Milliseconds per animation step at 1× speed. */
    private const val STEP_MS: Long = 1_000L

    /** Duration of the state fill highlight (ms). */
    private const val STATE_HIGHLIGHT_MS: Long = 600L

    /** Duration of the transition stroke-width pulse (ms). */
    private const val TRANSITION_PULSE_MS: Long = 300L

    /** Duration of the terminated opacity set (ms). */
    private const val TERMINATED_DIM_MS: Long = 500L

    /**
     * Builds the animation timeline plus lists of overlay elements to inject.
     *
     * @param stateMachine The state machine whose vertices are animated.
     * @param trace The trace file containing STM entries.
     * @param transitionPaths Map from [dev.kuml.uml.UmlTransition.id] to SVG path `d`-string.
     * @param nodeBounds Map from vertex id to its bounds rect (already shifted by padding).
     * @param context Animation tuning parameters.
     * @return Triple of (pre-scaled timeline, state overlays, transition overlays).
     * @throws IllegalArgumentException if the trace exceeds [StmAnimationContext.MAX_ANIMATIONS].
     */
    fun build(
        stateMachine: UmlStateMachine,
        trace: TraceFile,
        transitionPaths: Map<String, String>,
        nodeBounds: Map<String, Rect>,
        context: StmAnimationContext,
    ): Triple<SmilTimeline, List<StmOverlay>, List<StmTransitionOverlay>> {
        // Filter relevant STM entries in seqNo order
        val stmEntries =
            trace.entries
                .filter {
                    it is TraceEntry.StateEntered ||
                        it is TraceEntry.TransitionFired ||
                        it is TraceEntry.Terminated
                }.sortedBy { it.seqNo }

        // Guard: cap total animations
        require(stmEntries.size <= StmAnimationContext.MAX_ANIMATIONS) {
            "STM trace has ${stmEntries.size} entries which exceeds the maximum of " +
                "${StmAnimationContext.MAX_ANIMATIONS}. Reduce trace length or increase the cap."
        }

        val animations = mutableListOf<SmilAnimation>()
        // Collect overlay rects (one per unique state that is visited)
        val stateOverlayMap = mutableMapOf<String, StmOverlay>()
        // Collect transition path overlays
        val transitionOverlayMap = mutableMapOf<String, StmTransitionOverlay>()

        var stepIndex = 0L

        for (entry in stmEntries) {
            val beginMs = stepIndex * STEP_MS

            when (entry) {
                is TraceEntry.StateEntered -> {
                    val vertexId = entry.vertexId
                    val bounds = nodeBounds[vertexId]
                    if (bounds != null) {
                        val overlayId = "smil-stm-hl-${sanitizeId(vertexId)}"
                        // Register overlay rect if not already done
                        if (!stateOverlayMap.containsKey(overlayId)) {
                            stateOverlayMap[overlayId] =
                                StmOverlay(
                                    id = overlayId,
                                    x = bounds.origin.x,
                                    y = bounds.origin.y,
                                    width = bounds.size.width,
                                    height = bounds.size.height,
                                )
                        }
                        // Animate: highlight → normal
                        val fillAnim =
                            SmilAnimation.Fill(
                                elementId = overlayId,
                                color = context.highlightColor,
                                beginMs = beginMs,
                                durationMs = STATE_HIGHLIGHT_MS,
                                fromColor = context.normalColor,
                            )
                        // Restore opacity on the overlay so it becomes visible during highlight
                        val showOverlay =
                            SmilAnimation.Animate(
                                elementId = overlayId,
                                attribute = "opacity",
                                from = "0",
                                to = "0.4",
                                beginMs = beginMs,
                                durationMs = 50L,
                            )
                        val hideOverlay =
                            SmilAnimation.Animate(
                                elementId = overlayId,
                                attribute = "opacity",
                                from = "0.4",
                                to = "0",
                                beginMs = beginMs + STATE_HIGHLIGHT_MS,
                                durationMs = 200L,
                            )
                        animations += showOverlay
                        animations += fillAnim
                        animations += hideOverlay
                    }
                    stepIndex++
                }

                is TraceEntry.TransitionFired -> {
                    val transId = entry.transitionId
                    val pathD = transitionPaths[transId]
                    if (pathD != null) {
                        val overlayId = "smil-stm-trans-${sanitizeId(transId)}"
                        if (!transitionOverlayMap.containsKey(overlayId)) {
                            transitionOverlayMap[overlayId] =
                                StmTransitionOverlay(id = overlayId, pathD = pathD)
                        }
                        // Two SMIL <animate> elements pulse the stroke-width of the transition overlay path.
                        // A stroke-width pulse was chosen over <animateTransform> because it is simpler,
                        // avoids coordinate-space issues with rotated paths, and produces a clear visual
                        // signal without moving the arrow geometry.
                        val pulseOn =
                            SmilAnimation.Animate(
                                elementId = overlayId,
                                attribute = "stroke-width",
                                from = "1.5",
                                to = "4",
                                beginMs = beginMs,
                                durationMs = TRANSITION_PULSE_MS,
                            )
                        val pulseOff =
                            SmilAnimation.Animate(
                                elementId = overlayId,
                                attribute = "stroke-width",
                                from = "4",
                                to = "1.5",
                                beginMs = beginMs + TRANSITION_PULSE_MS,
                                durationMs = TRANSITION_PULSE_MS,
                            )
                        animations += pulseOn
                        animations += pulseOff
                    }
                    stepIndex++
                }

                is TraceEntry.Terminated -> {
                    val finalId = entry.finalVertexId
                    val bounds = nodeBounds[finalId]
                    if (bounds != null) {
                        val overlayId = "smil-stm-hl-${sanitizeId(finalId)}"
                        if (!stateOverlayMap.containsKey(overlayId)) {
                            stateOverlayMap[overlayId] =
                                StmOverlay(
                                    id = overlayId,
                                    x = bounds.origin.x,
                                    y = bounds.origin.y,
                                    width = bounds.size.width,
                                    height = bounds.size.height,
                                )
                        }
                        // Set opacity=0.5 on final state overlay (permanent dim)
                        val dimOverlay =
                            SmilAnimation.Animate(
                                elementId = overlayId,
                                attribute = "opacity",
                                from = "0",
                                to = "0.5",
                                beginMs = beginMs,
                                durationMs = TERMINATED_DIM_MS,
                            )
                        animations += dimOverlay
                    }
                    stepIndex++
                }

                else -> Unit // handled by filter above; else branch satisfies exhaustive when
            }
        }

        val rawTimeline = SmilTimeline(animations)
        val scaledTimeline = rawTimeline.scaledBy(context.speedFactor)
        return Triple(scaledTimeline, stateOverlayMap.values.toList(), transitionOverlayMap.values.toList())
    }

    /**
     * Sanitizes an id for use in an SVG id attribute by replacing non-alphanumeric
     * characters (except hyphens and underscores) with underscores.
     */
    internal fun sanitizeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9\\-_]"), "_")
}
