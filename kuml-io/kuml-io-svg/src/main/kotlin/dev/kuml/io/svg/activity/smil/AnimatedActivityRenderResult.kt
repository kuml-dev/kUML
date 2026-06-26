package dev.kuml.io.svg.activity.smil

/**
 * Result of an [ActivitySmilRenderer.render] call.
 *
 * When [hasAnimation] is `false` the [svg] is byte-identical to the output of
 * `KumlSvgRenderer.toSvg(...)` — no SMIL elements have been injected.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
public data class AnimatedActivityRenderResult(
    val svg: String,
    val hasAnimation: Boolean,
)
