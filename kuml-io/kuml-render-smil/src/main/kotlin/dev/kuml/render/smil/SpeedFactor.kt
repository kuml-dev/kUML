package dev.kuml.render.smil

import kotlin.math.roundToLong

/**
 * Dimensionless speed multiplier for SMIL animation playback.
 *
 * A value greater than 1.0 makes animations faster (compresses timeline durations).
 * A value less than 1.0 slows animations down. Default is [DEFAULT] = 1.0 (no scaling).
 *
 * @param value must be strictly positive.
 */
@JvmInline
public value class SpeedFactor(
    public val value: Double,
) {
    init {
        require(value > 0.0) { "SpeedFactor.value must be > 0.0, was $value" }
    }

    /**
     * Scale a duration/begin offset in milliseconds by this factor.
     *
     * Faster speed (>1.0) divides the time, compressing the animation.
     */
    public fun scaleMillis(ms: Long): Long = (ms / value).roundToLong()

    public companion object {
        /** Identity factor — animations play at authored speed. */
        public val DEFAULT: SpeedFactor = SpeedFactor(1.0)
    }
}
