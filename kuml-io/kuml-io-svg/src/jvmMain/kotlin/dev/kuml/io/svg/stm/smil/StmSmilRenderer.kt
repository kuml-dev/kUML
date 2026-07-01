package dev.kuml.io.svg.stm.smil

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Rect
import dev.kuml.render.smil.SmilEmitter
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlStateMachine

/**
 * Renders a UML State Machine diagram as an optionally animated SVG string.
 *
 * ## Static mode (default)
 *
 * When [trace] is `null`, empty, or contains no STM trace entries that produce animations,
 * the output is **byte-identical** to `KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)`.
 * No SMIL elements or overlay rects/paths are injected.
 *
 * ## Animated mode
 *
 * When a [trace] is supplied with valid STM entries:
 *
 * 1. The base SVG is rendered via [KumlSvgRenderer.toSvg].
 * 2. Overlay `<rect>` elements (one per visited state) and `<path>` elements (one per
 *    fired transition with a resolved layout edge) are injected before `</svg>`.
 * 3. A [dev.kuml.render.smil.SmilTimeline] is built and injected via [SmilEmitter].
 *
 * ## Security
 *
 * [StmAnimationContext.highlightColor] and [StmAnimationContext.normalColor] are validated
 * in [StmAnimationContext.init] against a CSS-color allowlist.
 *
 * ## SMIL strategy
 *
 * Because the base SVG renderer does not place stable `id` attributes on state `<rect>`
 * elements or transition `<path>` elements that SMIL can target, this renderer injects
 * its own overlay elements with stable ids (e.g. `smil-stm-hl-<vertexId>`) and animates
 * those overlays instead.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
public object StmSmilRenderer {
    /**
     * Renders a UML State Machine diagram to SVG, optionally injecting SMIL animations.
     *
     * @param diagram The [KumlDiagram] to render.
     * @param stateMachine The [UmlStateMachine] whose vertices and transitions are animated.
     * @param layoutResult The computed layout result.
     * @param theme Visual theme. Defaults to [PlainTheme].
     * @param options Renderer options. Defaults to [SvgRenderOptions.DEFAULT].
     * @param trace Optional [TraceFile] containing STM trace entries. When `null` or
     *   when no animatable entries are found, falls back to static rendering.
     * @param context Animation tuning parameters. Defaults to [StmAnimationContext.DEFAULT].
     * @return [AnimatedStmRenderResult] with the SVG string and animation flag.
     */
    public fun render(
        diagram: KumlDiagram,
        stateMachine: UmlStateMachine,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
        trace: TraceFile? = null,
        context: StmAnimationContext = StmAnimationContext.DEFAULT,
    ): AnimatedStmRenderResult {
        // Always render the static base SVG first
        val baseSvg = KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)

        // Static path: null trace
        if (trace == null) return AnimatedStmRenderResult(svg = baseSvg, hasAnimation = false)

        // Static path: empty trace
        if (trace.entries.isEmpty()) return AnimatedStmRenderResult(svg = baseSvg, hasAnimation = false)

        // Build node bounds map — shift by padding to match rendered SVG coordinates
        val padding = options.paddingPx
        val nodeBounds: Map<String, Rect> =
            layoutResult.nodes.entries.associate { (nodeId, nodeLayout) ->
                val b = nodeLayout.bounds
                nodeId.value to
                    Rect(
                        origin = dev.kuml.layout.Point(b.origin.x + padding, b.origin.y + padding),
                        size = b.size,
                    )
            }

        // Build transition path map
        val transitionPaths =
            StmTransitionPathResolver.buildTransitionPaths(
                transitions = stateMachine.transitions,
                layoutResult = layoutResult,
                padding = padding,
            )

        // Build timeline + overlays
        val (timeline, stateOverlays, transitionOverlays) =
            StmStateTimelineBuilder.build(
                stateMachine = stateMachine,
                trace = trace,
                transitionPaths = transitionPaths,
                nodeBounds = nodeBounds,
                context = context,
            )

        // Static path: no animations produced
        if (timeline.animations.isEmpty()) return AnimatedStmRenderResult(svg = baseSvg, hasAnimation = false)

        // Inject overlay elements before </svg>
        val svgWithOverlays = injectOverlays(baseSvg, stateOverlays, transitionOverlays)

        // Inject SMIL animations
        val animatedSvg = SmilEmitter().inject(svgWithOverlays, timeline)

        return AnimatedStmRenderResult(svg = animatedSvg, hasAnimation = true, timeline = timeline)
    }

    /**
     * Variant for callers that already have a rendered base SVG (e.g. the SysML2 STM pipeline
     * which uses a SysML2-specific renderer). Injects STM SMIL animations into [baseSvg]
     * without re-rendering via [KumlSvgRenderer].
     *
     * When [trace] is `null`, empty, or produces no animations, returns [baseSvg] unchanged
     * with [AnimatedStmRenderResult.hasAnimation] = `false`.
     *
     * @param baseSvg Pre-rendered SVG string to inject animations into.
     * @param stateMachine The [UmlStateMachine] whose vertices and transitions are animated.
     * @param layoutResult The computed layout result (must match [baseSvg]).
     * @param options Renderer options (only [SvgRenderOptions.paddingPx] is used). Defaults to [SvgRenderOptions.DEFAULT].
     * @param trace Optional [TraceFile] containing STM trace entries.
     * @param context Animation tuning parameters. Defaults to [StmAnimationContext.DEFAULT].
     */
    public fun renderWithBaseSvg(
        baseSvg: String,
        stateMachine: UmlStateMachine,
        layoutResult: LayoutResult,
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
        trace: TraceFile? = null,
        context: StmAnimationContext = StmAnimationContext.DEFAULT,
    ): AnimatedStmRenderResult {
        if (trace == null) return AnimatedStmRenderResult(svg = baseSvg, hasAnimation = false)
        if (trace.entries.isEmpty()) return AnimatedStmRenderResult(svg = baseSvg, hasAnimation = false)

        val padding = options.paddingPx
        val nodeBounds: Map<String, Rect> =
            layoutResult.nodes.entries.associate { (nodeId, nodeLayout) ->
                val b = nodeLayout.bounds
                nodeId.value to
                    Rect(
                        origin = dev.kuml.layout.Point(b.origin.x + padding, b.origin.y + padding),
                        size = b.size,
                    )
            }

        val transitionPaths =
            StmTransitionPathResolver.buildTransitionPaths(
                transitions = stateMachine.transitions,
                layoutResult = layoutResult,
                padding = padding,
            )

        val (timeline, stateOverlays, transitionOverlays) =
            StmStateTimelineBuilder.build(
                stateMachine = stateMachine,
                trace = trace,
                transitionPaths = transitionPaths,
                nodeBounds = nodeBounds,
                context = context,
            )

        if (timeline.animations.isEmpty()) return AnimatedStmRenderResult(svg = baseSvg, hasAnimation = false)

        val svgWithOverlays = injectOverlays(baseSvg, stateOverlays, transitionOverlays)
        val animatedSvg = SmilEmitter().inject(svgWithOverlays, timeline)
        return AnimatedStmRenderResult(svg = animatedSvg, hasAnimation = true, timeline = timeline)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Injects state highlight `<rect>` and transition `<path>` overlay elements
     * immediately before the last `</svg>` in [svg].
     */
    private fun injectOverlays(
        svg: String,
        stateOverlays: List<StmOverlay>,
        transitionOverlays: List<StmTransitionOverlay>,
    ): String {
        if (stateOverlays.isEmpty() && transitionOverlays.isEmpty()) return svg

        val fragment =
            buildString {
                for (overlay in stateOverlays) {
                    append(
                        "\n<rect id=\"${xmlEscapeAttr(overlay.id)}\" " +
                            "x=\"${overlay.x}\" y=\"${overlay.y}\" " +
                            "width=\"${overlay.width}\" height=\"${overlay.height}\" " +
                            "fill=\"none\" opacity=\"0\" rx=\"4\"/>",
                    )
                }
                for (t in transitionOverlays) {
                    append(
                        "\n<path id=\"${xmlEscapeAttr(t.id)}\" " +
                            "d=\"${xmlEscapeAttr(t.pathD)}\" " +
                            "fill=\"none\" stroke=\"#333\" stroke-width=\"1.5\" opacity=\"0.6\"/>",
                    )
                }
            }

        val closeIndex = svg.lastIndexOf("</svg>")
        return if (closeIndex >= 0) {
            svg.substring(0, closeIndex) + fragment + "\n</svg>"
        } else {
            svg + fragment
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
