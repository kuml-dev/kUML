package dev.kuml.io.svg.uml.smil

import dev.kuml.render.smil.SmilTimeline

/**
 * Result of [SequenceSmilRenderer.render].
 *
 * @param svg The SVG string — either the static base SVG (when [hasAnimation] is false)
 *   or the SMIL-animated SVG with message-dot elements injected (when [hasAnimation] is true).
 * @param hasAnimation True when at least one [dev.kuml.runtime.TraceEntry.MessageSent] entry
 *   produced an animatable motion path. False when the trace is null/empty or no paths were
 *   resolved (output is byte-identical to the static renderer).
 * @param timeline The [SmilTimeline] used to inject animations. [SmilTimeline.EMPTY] when
 *   [hasAnimation] is false. Used by `kuml-io-anim` for APNG/WebP export.
 *
 * V3.2 — UML Sequence Diagram SMIL Animation
 * V3.2 — Added timeline field for APNG/WebP export
 */
public data class AnimatedSequenceRenderResult(
    val svg: String,
    val hasAnimation: Boolean,
    val timeline: SmilTimeline = SmilTimeline.EMPTY,
)
