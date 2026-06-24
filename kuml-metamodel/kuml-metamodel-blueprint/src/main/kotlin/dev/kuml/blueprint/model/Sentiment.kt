package dev.kuml.blueprint.model

import kotlinx.serialization.Serializable

/**
 * Discrete 5-level sentiment scale, normalised to [-2..+2]. The integer
 * [value] gives a deterministic Y-position for the emotion curve.
 *
 * V3.1.21
 */
@Serializable
enum class Sentiment(
    val value: Int,
) {
    VERY_NEGATIVE(-2),
    NEGATIVE(-1),
    NEUTRAL(0),
    POSITIVE(1),
    VERY_POSITIVE(2),
    ;

    companion object {
        /** Maps an arbitrary int to the nearest sentiment, clamped to [-2..+2]. */
        fun of(v: Int): Sentiment {
            val clamped = v.coerceIn(-2, 2)
            return entries.firstOrNull { it.value == clamped } ?: NEUTRAL
        }
    }
}
