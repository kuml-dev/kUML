package dev.kuml.io.anim

/**
 * Thrown when an animated export cannot be completed.
 *
 * The [message] is always actionable — it explains what is missing and how to
 * fix the problem (e.g. install `img2webp` for WebP export).
 */
public class AnimEncoderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
