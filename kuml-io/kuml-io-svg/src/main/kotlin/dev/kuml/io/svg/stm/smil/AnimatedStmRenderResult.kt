package dev.kuml.io.svg.stm.smil

import dev.kuml.render.smil.SmilTimeline

/**
 * Result of a [StmSmilRenderer.render] call.
 *
 * When [hasAnimation] is `false` the [svg] is byte-identical to the output of
 * `KumlSvgRenderer.toSvg(...)` — no SMIL elements have been injected.
 *
 * [timeline] holds the [SmilTimeline] used to inject SMIL animations. When
 * [hasAnimation] is `false`, [timeline] is [SmilTimeline.EMPTY].
 * Callers that need the timeline for APNG/WebP export (via `kuml-io-anim`) can
 * read it from this field without re-building it.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 * V3.2 — Added timeline field for APNG/WebP export
 */
public data class AnimatedStmRenderResult(
    val svg: String,
    val hasAnimation: Boolean,
    val timeline: SmilTimeline = SmilTimeline.EMPTY,
)
