package dev.kuml.render.smil

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile

/**
 * Builds a [SmilTimeline] from a [TraceFile].
 *
 * Timing is derived from entry ordinal index × [BuildOptions.stepMs] — trace timestamps
 * are ISO-8601 strings and not reliable for numeric timing; this approach is deterministic
 * and flavour-independent.
 *
 * The builder detects STM vs Activity entries locally without depending on
 * `kuml-runtime-trace` (which would create a heavier dependency and potential cycles).
 * STM entries: [TraceEntry.StateEntered], [TraceEntry.TransitionFired].
 * Activity entries: [TraceEntry.TokenPlaced], [TraceEntry.TokenConsumed].
 *
 * ## ADR-0015: TransitionFired uses Animate(opacity) instead of AnimateTransform
 *
 * The original specification proposed emitting [SmilAnimation.AnimateTransform] (scale) for
 * [TraceEntry.TransitionFired] to animate the arrow element representing the fired transition.
 *
 * **Decision**: the implementation emits two [SmilAnimation.Animate] elements targeting the
 * `opacity` attribute instead (a dim-then-restore opacity pulse: 1 → 0.3, then 0.3 → 1,
 * each occupying half of [BuildOptions.stepMs]).
 *
 * **Rationale**: `<animateTransform type="scale">` is visually ineffective on SVG `<path>`
 * and `<line>` elements. The scale origin defaults to the element's coordinate-system origin
 * (typically `(0, 0)`), not the path centroid, so the element either does not move visually
 * or moves off-screen. An opacity flash is universally supported, clearly visible, and
 * matches the intended "highlight this transition fired" semantics without requiring
 * centroid computation.
 *
 * **Impact on callers**: callers that branched on [SmilAnimation.AnimateTransform] for
 * `TransitionFired` events will not receive that type; they will receive two
 * [SmilAnimation.Animate] entries with `attribute = "opacity"`. The sealed class still
 * includes [SmilAnimation.AnimateTransform] for use by other builder paths (e.g. explicit
 * transform animations requested by layout post-processors).
 */
public class SmilTimelineBuilder {
    /**
     * Build a [SmilTimeline] from [traceFile] using [options].
     *
     * Returns [SmilTimeline.EMPTY] when [traceFile] contains no entries.
     */
    public fun build(
        traceFile: TraceFile,
        options: BuildOptions = BuildOptions(),
    ): SmilTimeline {
        if (traceFile.entries.isEmpty()) return SmilTimeline.EMPTY

        val animations = mutableListOf<SmilAnimation>()
        traceFile.entries.forEachIndexed { index, entry ->
            val beginMs = index.toLong() * options.stepMs
            val durationMs = options.stepMs
            when (entry) {
                is TraceEntry.StateEntered ->
                    animations +=
                        SmilAnimation.Fill(
                            elementId = entry.vertexId,
                            color = options.highlightColor,
                            beginMs = beginMs,
                            durationMs = durationMs,
                        )
                is TraceEntry.TransitionFired -> {
                    // Emit an opacity pulse on the transition/arrow element.
                    // AnimateTransform SCALE is semantically wrong for path/line elements
                    // (the scale origin is at the element origin, not the path centroid —
                    // scale animations produce no visible effect on SVG paths).
                    // An opacity flash from 1 → 0.3 → 1 is achieved via two chained Animate
                    // elements: dim phase (1→0.3) followed by restore phase (0.3→1),
                    // each occupying half of stepMs.
                    animations +=
                        SmilAnimation.Animate(
                            elementId = entry.transitionId,
                            attribute = "opacity",
                            from = "1",
                            to = "0.3",
                            beginMs = beginMs,
                            durationMs = durationMs / 2,
                        )
                    animations +=
                        SmilAnimation.Animate(
                            elementId = entry.transitionId,
                            attribute = "opacity",
                            from = "0.3",
                            to = "1",
                            beginMs = beginMs + durationMs / 2,
                            durationMs = durationMs / 2,
                        )
                }
                is TraceEntry.TokenPlaced -> {
                    val path = options.pathResolver(entry.nodeId)
                    if (path != null) {
                        animations +=
                            SmilAnimation.AnimateMotion(
                                elementId = options.tokenElementIdPrefix + entry.nodeId,
                                path = path,
                                beginMs = beginMs,
                                durationMs = durationMs,
                            )
                    }
                    // when pathResolver returns null, skip — no AnimateMotion with empty path
                }
                is TraceEntry.TokenConsumed -> {
                    // Token consumed: matched to the advance along the edge.
                    // When a path is available, emit a second AnimateMotion to "complete" the move.
                    val path = options.pathResolver(entry.nodeId)
                    if (path != null) {
                        animations +=
                            SmilAnimation.AnimateMotion(
                                elementId = options.tokenElementIdPrefix + entry.nodeId + "-consumed",
                                path = path,
                                beginMs = beginMs,
                                durationMs = durationMs,
                            )
                    }
                }
                else -> { /* other entry types are not animated in this wave */ }
            }
            require(animations.size <= options.maxAnimations) {
                "Animation count exceeded maxAnimations limit of ${options.maxAnimations}. " +
                    "The trace file contains too many entries. Increase BuildOptions.maxAnimations " +
                    "or reduce the trace file size."
            }
        }

        return if (animations.isEmpty()) SmilTimeline.EMPTY else SmilTimeline(animations)
    }
}
