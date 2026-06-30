package dev.kuml.io.svg.activity.smil

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutResult
import dev.kuml.render.smil.SmilEmitter
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlActivityEdge

/**
 * Renders a UML Activity diagram as an optionally animated SVG string.
 *
 * ## Static mode (default)
 *
 * When [trace] is `null`, empty, or contains no animatable entries, the output is
 * **byte-identical** to `KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)`.
 * No SMIL elements or token circles are injected.
 *
 * ## Animated mode
 *
 * When a [trace] is supplied with valid Activity token entries:
 *
 * 1. The base SVG is rendered via [KumlSvgRenderer.toSvg].
 * 2. Token `<circle>` elements are injected before `</svg>` for each motion leg.
 * 3. A [dev.kuml.render.smil.SmilTimeline] is built and injected via [SmilEmitter].
 *
 * The resulting SVG contains:
 * - `<animateMotion>` elements driving token circles along activity edges.
 * - `<animate attributeName="opacity">` for token visibility (show/hide per leg).
 * - `<animate attributeName="fill">` for fork/join/decision node highlights.
 * - `<set attributeName="opacity">` for consumed token hiding.
 *
 * ## Security
 *
 * [ActivityAnimationContext.tokenColor] and [ActivityAnimationContext.highlightColor] are
 * validated in [ActivityAnimationContext.init] against a CSS-color allowlist.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
public object ActivitySmilRenderer {
    /**
     * Renders a UML Activity diagram to SVG, optionally injecting SMIL token animations.
     *
     * @param diagram The [KumlDiagram] to render.
     * @param activityEdges The list of activity edges in the diagram (used for path resolution).
     * @param layoutResult The computed layout result.
     * @param theme Visual theme. Defaults to [PlainTheme].
     * @param options Renderer options. Defaults to [SvgRenderOptions.DEFAULT].
     * @param trace Optional [TraceFile] containing activity trace entries. When `null` or
     *   when no animatable entries are found, falls back to static rendering.
     * @param context Animation tuning parameters. Defaults to [ActivityAnimationContext.DEFAULT].
     * @return [AnimatedActivityRenderResult] with the SVG string and animation flag.
     */
    public fun render(
        diagram: KumlDiagram,
        activityEdges: List<UmlActivityEdge>,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
        trace: TraceFile? = null,
        context: ActivityAnimationContext = ActivityAnimationContext.DEFAULT,
    ): AnimatedActivityRenderResult {
        // Always render the static base SVG first
        val baseSvg = KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)

        // Static path: null trace
        if (trace == null) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)

        // Static path: empty trace
        if (trace.entries.isEmpty()) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)

        if (activityEdges.isEmpty()) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)

        // Build edge paths
        val edgePaths =
            ActivityFlowPathResolver.buildEdgePaths(
                edges = activityEdges,
                layoutResult = layoutResult,
                padding = options.paddingPx,
            )

        // Build timeline + circles
        val (timeline, circles) =
            ActivityTokenTimelineBuilder.build(
                edges = activityEdges,
                trace = trace,
                edgePaths = edgePaths,
                context = context,
            )

        // Static path: no animations produced
        if (timeline.animations.isEmpty()) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)

        // Inject token circle elements before </svg>
        val svgWithCircles = injectCircles(baseSvg, circles)

        // Inject SMIL animations
        val animatedSvg = SmilEmitter().inject(svgWithCircles, timeline)

        return AnimatedActivityRenderResult(svg = animatedSvg, hasAnimation = true, timeline = timeline)
    }

    /**
     * Variant for callers that already have a rendered base SVG (e.g. the SysML2 ACT pipeline
     * which uses a SysML2-specific renderer). Injects Activity SMIL token animations into
     * [baseSvg] without re-rendering via [KumlSvgRenderer].
     *
     * When [trace] is `null`, empty, or produces no animations, returns [baseSvg] unchanged
     * with [AnimatedActivityRenderResult.hasAnimation] = `false`.
     *
     * @param baseSvg Pre-rendered SVG string to inject animations into.
     * @param activityEdges The list of activity edges (used for path resolution).
     * @param layoutResult The computed layout result (must match [baseSvg]).
     * @param options Renderer options (only [SvgRenderOptions.paddingPx] is used). Defaults to [SvgRenderOptions.DEFAULT].
     * @param trace Optional [TraceFile] containing activity trace entries.
     * @param context Animation tuning parameters. Defaults to [ActivityAnimationContext.DEFAULT].
     */
    public fun renderWithBaseSvg(
        baseSvg: String,
        activityEdges: List<UmlActivityEdge>,
        layoutResult: LayoutResult,
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
        trace: TraceFile? = null,
        context: ActivityAnimationContext = ActivityAnimationContext.DEFAULT,
    ): AnimatedActivityRenderResult {
        if (trace == null) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)
        if (trace.entries.isEmpty()) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)
        if (activityEdges.isEmpty()) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)

        val edgePaths =
            ActivityFlowPathResolver.buildEdgePaths(
                edges = activityEdges,
                layoutResult = layoutResult,
                padding = options.paddingPx,
            )

        val (timeline, circles) =
            ActivityTokenTimelineBuilder.build(
                edges = activityEdges,
                trace = trace,
                edgePaths = edgePaths,
                context = context,
            )

        if (timeline.animations.isEmpty()) return AnimatedActivityRenderResult(svg = baseSvg, hasAnimation = false)

        val svgWithCircles = injectCircles(baseSvg, circles)
        val animatedSvg = SmilEmitter().inject(svgWithCircles, timeline)
        return AnimatedActivityRenderResult(svg = animatedSvg, hasAnimation = true, timeline = timeline)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun injectCircles(
        svg: String,
        circles: List<ActivityTokenCircle>,
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

    private fun xmlEscapeAttr(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
