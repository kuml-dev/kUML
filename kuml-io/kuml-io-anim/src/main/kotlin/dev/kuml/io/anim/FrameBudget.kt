package dev.kuml.io.anim

/**
 * Computes the effective frame count and per-frame interval given a timeline duration,
 * nominal fps, and the configurable caps in [AnimRenderOptions].
 *
 * DoS guards applied (in order):
 * 1. `totalDurationMs` is clamped to [AnimRenderOptions.maxDurationMs].
 * 2. If `fps * clampedDuration / 1000 > maxFrames`, fps is reduced to 12.
 * 3. Frame count is then clamped again to [AnimRenderOptions.maxFrames].
 *
 * @property frameCount Number of frames to sample (≥ 1).
 * @property intervalMs Per-frame interval in milliseconds.
 * @property effectiveFps The fps actually used after any auto-reduction.
 * @property clampedDurationMs The duration used after applying the max cap.
 */
public data class FrameBudget(
    val frameCount: Int,
    val intervalMs: Long,
    val effectiveFps: Int,
    val clampedDurationMs: Long,
) {
    public companion object {
        private const val FPS_REDUCED = 12
        private const val FPS_DEFAULT = 25

        /**
         * Compute a [FrameBudget] from [totalDurationMs] and [options].
         *
         * @param totalDurationMs Raw timeline duration in milliseconds (from [dev.kuml.render.smil.SmilTimeline.totalDurationMs]).
         * @param options Export options governing caps and nominal fps.
         */
        public fun compute(
            totalDurationMs: Long,
            options: AnimRenderOptions,
        ): FrameBudget {
            // 1. Clamp duration
            val clamped = totalDurationMs.coerceAtMost(options.maxDurationMs).coerceAtLeast(1L)

            // 2. Select fps — reduce to FPS_REDUCED if budget at nominal fps exceeds maxFrames
            val nominalFps = options.fps
            val nominalCount = (nominalFps.toLong() * clamped / 1000L).coerceAtLeast(1L)
            val effectiveFps =
                if (nominalCount > options.maxFrames) FPS_REDUCED else nominalFps

            // 3. Compute interval and final frame count
            val intervalMs = (1000L / effectiveFps).coerceAtLeast(1L)
            val rawCount = (clamped / intervalMs).coerceAtLeast(1L)
            val frameCount = rawCount.coerceAtMost(options.maxFrames.toLong()).toInt()

            return FrameBudget(
                frameCount = frameCount,
                intervalMs = intervalMs,
                effectiveFps = effectiveFps,
                clampedDurationMs = clamped,
            )
        }
    }
}
