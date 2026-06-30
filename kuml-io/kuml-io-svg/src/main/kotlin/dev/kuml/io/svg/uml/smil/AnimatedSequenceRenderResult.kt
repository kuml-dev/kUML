package dev.kuml.io.svg.uml.smil

/**
 * Result of [SequenceSmilRenderer.render].
 *
 * @param svg The SVG string — either the static base SVG (when [hasAnimation] is false)
 *   or the SMIL-animated SVG with message-dot elements injected (when [hasAnimation] is true).
 * @param hasAnimation True when at least one [dev.kuml.runtime.TraceEntry.MessageSent] entry
 *   produced an animatable motion path. False when the trace is null/empty or no paths were
 *   resolved (output is byte-identical to the static renderer).
 *
 * V3.2 — UML Sequence Diagram SMIL Animation
 */
public data class AnimatedSequenceRenderResult(
    val svg: String,
    val hasAnimation: Boolean,
)
