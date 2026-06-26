package dev.kuml.render.smil

/**
 * An ordered list of [SmilAnimation] entries representing a complete animation timeline
 * derived from a kUML [dev.kuml.runtime.TraceFile].
 *
 * The timeline is speed-neutral: all timing values are in absolute milliseconds at 1× speed.
 * Speed scaling is applied at emit time by [SmilEmitter].
 *
 * @param animations the ordered list of animations; may be empty (see [EMPTY]).
 */
public data class SmilTimeline(
    val animations: List<SmilAnimation>,
) {
    /** Total animation duration: the maximum of (begin + duration) across all entries. */
    public val totalDurationMs: Long
        get() = animations.maxOfOrNull { it.beginMs + it.durationMs } ?: 0L

    /**
     * Returns a new timeline with all begin times shifted by [offsetMs].
     * Useful for compositing multiple timelines sequentially.
     */
    public fun shiftedBy(offsetMs: Long): SmilTimeline =
        SmilTimeline(
            animations.map { anim ->
                val shifted: SmilAnimation =
                    when (anim) {
                        is SmilAnimation.Animate ->
                            anim.copy(beginMs = anim.beginMs + offsetMs)
                        is SmilAnimation.AnimateTransform ->
                            anim.copy(beginMs = anim.beginMs + offsetMs)
                        is SmilAnimation.AnimateMotion ->
                            anim.copy(beginMs = anim.beginMs + offsetMs)
                        is SmilAnimation.Set ->
                            anim.copy(beginMs = anim.beginMs + offsetMs)
                        is SmilAnimation.Fill ->
                            anim.copy(beginMs = anim.beginMs + offsetMs)
                    }
                shifted
            },
        )

    /**
     * Returns a new timeline with all timings scaled by [speed].
     *
     * A speed > 1.0 compresses the timeline (faster playback).
     */
    public fun scaledBy(speed: SpeedFactor): SmilTimeline =
        SmilTimeline(
            animations.map { anim ->
                val scaled: SmilAnimation =
                    when (anim) {
                        is SmilAnimation.Animate ->
                            anim.copy(
                                beginMs = speed.scaleMillis(anim.beginMs),
                                durationMs = speed.scaleMillis(anim.durationMs),
                            )
                        is SmilAnimation.AnimateTransform ->
                            anim.copy(
                                beginMs = speed.scaleMillis(anim.beginMs),
                                durationMs = speed.scaleMillis(anim.durationMs),
                            )
                        is SmilAnimation.AnimateMotion ->
                            anim.copy(
                                beginMs = speed.scaleMillis(anim.beginMs),
                                durationMs = speed.scaleMillis(anim.durationMs),
                            )
                        is SmilAnimation.Set ->
                            anim.copy(
                                beginMs = speed.scaleMillis(anim.beginMs),
                                durationMs = speed.scaleMillis(anim.durationMs),
                            )
                        is SmilAnimation.Fill ->
                            anim.copy(
                                beginMs = speed.scaleMillis(anim.beginMs),
                                durationMs = speed.scaleMillis(anim.durationMs),
                            )
                    }
                scaled
            },
        )

    public companion object {
        /** An empty timeline containing no animations. */
        public val EMPTY: SmilTimeline = SmilTimeline(emptyList())
    }
}
