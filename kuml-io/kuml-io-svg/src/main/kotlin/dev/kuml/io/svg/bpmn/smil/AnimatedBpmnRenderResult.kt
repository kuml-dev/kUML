package dev.kuml.io.svg.bpmn.smil

import dev.kuml.render.smil.SmilTimeline

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
 * [timeline] holds the [SmilTimeline] used to inject SMIL animations. When
 * [hasAnimation] is `false`, [timeline] is [SmilTimeline.EMPTY].
 *
 * @param svg The SVG string.
 * @param hasAnimation `true` iff SMIL animations were successfully injected.
 * @param timeline The SMIL timeline. [SmilTimeline.EMPTY] when not animated.
 *
 * V3.1.30 — BPMN SMIL Renderer
 * V3.2 — Added timeline field for APNG/WebP export
 */
public data class AnimatedBpmnRenderResult(
    val svg: String,
    val hasAnimation: Boolean,
    val timeline: SmilTimeline = SmilTimeline.EMPTY,
)
