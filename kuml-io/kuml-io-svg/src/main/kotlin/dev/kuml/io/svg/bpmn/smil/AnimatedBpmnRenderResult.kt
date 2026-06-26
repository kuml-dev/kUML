package dev.kuml.io.svg.bpmn.smil

/**
 * Result of a BPMN render call from [BpmnSmilRenderer].
 *
 * When [hasAnimation] is `false`, [svg] is byte-identical to the output of
 * `KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)` — no SMIL or
 * token circles have been injected.
 *
 * When [hasAnimation] is `true`, [svg] contains injected `<animateMotion>` elements
 * and token `<circle>` elements in addition to the static diagram content.
 *
 * @param svg The SVG string.
 * @param hasAnimation `true` iff SMIL animations were successfully injected.
 *
 * V3.1.30 — BPMN SMIL Renderer
 */
public data class AnimatedBpmnRenderResult(
    val svg: String,
    val hasAnimation: Boolean,
)
