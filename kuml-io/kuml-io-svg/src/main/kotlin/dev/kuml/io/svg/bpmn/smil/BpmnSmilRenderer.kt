package dev.kuml.io.svg.bpmn.smil

import dev.kuml.bpmn.model.BpmnFlowNode
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutResult
import dev.kuml.render.smil.SmilEmitter
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.runtime.TraceFile

/**
 * Renders a BPMN [KumlDiagram] as an optionally animated SVG string.
 *
 * ## Static mode (default)
 *
 * When [trace] is `null`, empty, or contains no [dev.kuml.runtime.TraceEntry.TokenPlaced]
 * entries that map to a known [dev.kuml.bpmn.model.SequenceFlow] in the diagram, the
 * output is **byte-identical** to `KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)`.
 * No SMIL elements or token circles are injected.
 *
 * ## Animated mode
 *
 * When a [trace] is supplied with valid token entries:
 *
 * 1. The base SVG is rendered via [KumlSvgRenderer.toSvg].
 * 2. A token `<circle>` element is injected before `</svg>` for each motion leg.
 * 3. A [dev.kuml.render.smil.SmilTimeline] is built and injected via [SmilEmitter].
 *
 * The resulting SVG contains:
 * - `<animateMotion>` elements driving token circles along SequenceFlow paths.
 * - `<animate attributeName="opacity">` for token visibility (show/hide per leg).
 * - `<animate attributeName="fill">` (via [dev.kuml.render.smil.SmilAnimation.Fill])
 *   for gateway diamond highlights.
 * - `<animate attributeName="stroke-width">` pulse for task rects.
 * - `<animate attributeName="opacity">` dim for start/end event circles.
 *
 * ## Security
 *
 * [BpmnAnimationContext.tokenColor] and [BpmnAnimationContext.highlightColor] are
 * validated in [BpmnAnimationContext.init] against a CSS-color allowlist. Injection
 * payloads (e.g. `"/><script>`) are rejected with [IllegalArgumentException].
 *
 * ## Usage
 *
 * ```kotlin
 * val result = BpmnSmilRenderer.render(diagram, layoutResult, trace = myTrace)
 * if (result.hasAnimation) {
 *     File("animated.svg").writeText(result.svg)
 * } else {
 *     File("static.svg").writeText(result.svg)
 * }
 * ```
 *
 * V3.1.30 — BPMN SMIL Renderer
 */
public object BpmnSmilRenderer {
    /**
     * Renders a BPMN diagram to SVG, optionally injecting SMIL token animations.
     *
     * @param diagram The BPMN [KumlDiagram] (must be of type [dev.kuml.core.model.DiagramType.BPMN_PROCESS]).
     * @param layoutResult The computed layout result.
     * @param theme Visual theme. Defaults to [PlainTheme].
     * @param options Renderer options. Defaults to [SvgRenderOptions.DEFAULT].
     * @param trace Optional [TraceFile] containing token-flow entries. When `null` or
     *   when no animatable entries are found, falls back to static rendering.
     * @param context Animation tuning parameters. Defaults to [BpmnAnimationContext.DEFAULT].
     * @return [AnimatedBpmnRenderResult] with the SVG string and animation flag.
     */
    public fun render(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
        trace: TraceFile? = null,
        context: BpmnAnimationContext = BpmnAnimationContext.DEFAULT,
    ): AnimatedBpmnRenderResult {
        // Render the static base SVG (always needed)
        val baseSvg = KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)

        // Static path: null trace → byte-identical output
        if (trace == null) {
            return AnimatedBpmnRenderResult(svg = baseSvg, hasAnimation = false)
        }

        // Static path: empty trace → byte-identical output
        if (trace.entries.isEmpty()) {
            return AnimatedBpmnRenderResult(svg = baseSvg, hasAnimation = false)
        }

        // Reconstruct a minimal BpmnProcess from diagram elements.
        // KumlDiagram.elements is populated by BpmnProcess.renderableElements() which
        // expands flowNodes + sequenceFlows + dataObjects in-place — the BpmnProcess
        // wrapper itself is NOT included. We reconstruct a process for animation purposes
        // using the sequence flows and flow nodes present in diagram.elements.
        val flows = diagram.elements.filterIsInstance<SequenceFlow>()
        val flowNodes = diagram.elements.filterIsInstance<BpmnFlowNode>()
        if (flows.isEmpty() && flowNodes.isEmpty()) {
            return AnimatedBpmnRenderResult(svg = baseSvg, hasAnimation = false)
        }
        val process =
            BpmnProcess(
                id = "_smil_virtual_",
                name = null,
                flowNodes = flowNodes,
                sequenceFlows = flows,
            )

        // Build edge paths (requires a process with sequence flows in the layout)
        val edgePaths =
            BpmnFlowPathResolver.buildEdgePaths(
                process = process,
                layoutResult = layoutResult,
                padding = options.paddingPx,
            )

        // Build timeline + circles
        val (timeline, circles) =
            BpmnTokenTimelineBuilder.build(
                process = process,
                trace = trace,
                edgePaths = edgePaths,
                context = context,
            )

        // Static path: no animations produced → byte-identical output
        if (timeline.animations.isEmpty()) {
            return AnimatedBpmnRenderResult(svg = baseSvg, hasAnimation = false)
        }

        // Inject token circle elements before </svg>
        val svgWithCircles = injectCircles(baseSvg, circles)

        // Inject SMIL animations via SmilEmitter
        val animatedSvg = SmilEmitter().inject(svgWithCircles, timeline)

        return AnimatedBpmnRenderResult(svg = animatedSvg, hasAnimation = true)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Injects token circle `<circle>` elements into [svg] immediately before the last `</svg>`.
     *
     * Each circle has:
     * - `id` attribute for SMIL [animateMotion] targeting via `xlink:href="#id"`.
     * - `r=6` (radius), `cx=0 cy=0` (position driven by animateMotion).
     * - `fill` set to [TokenCircle.color].
     * - `opacity=0` so circles are invisible before their animation begins.
     */
    private fun injectCircles(
        svg: String,
        circles: List<TokenCircle>,
    ): String {
        if (circles.isEmpty()) return svg
        val circlesXml =
            buildString {
                for (circle in circles) {
                    append(
                        "\n<circle id=\"${xmlEscapeAttr(circle.id)}\" r=\"6\" cx=\"0\" cy=\"0\" " +
                            "fill=\"${xmlEscapeAttr(circle.color)}\" opacity=\"0\"/>",
                    )
                }
            }
        val closeIndex = svg.lastIndexOf("</svg>")
        return if (closeIndex >= 0) {
            svg.substring(0, closeIndex) + circlesXml + "\n</svg>"
        } else {
            svg + circlesXml
        }
    }

    /**
     * Escapes XML attribute values by replacing `"`, `<`, `>`, `&`, `'` with
     * their XML entity equivalents.
     *
     * Used when interpolating [TokenCircle.id] and [TokenCircle.color] into raw SVG
     * attribute strings. This prevents attribute injection if (despite the allowlist
     * checks) an unsafe value were to reach this point.
     */
    private fun xmlEscapeAttr(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
