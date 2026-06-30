package dev.kuml.io.svg.uml.smil

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.io.svg.uml.UML_SEQ_FRAGMENT_PADDING
import dev.kuml.layout.LayoutResult
import dev.kuml.render.smil.SmilEmitter
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.runtime.TraceFile
import dev.kuml.uml.UmlInteraction

/**
 * Renders a UML Sequence Diagram [KumlDiagram] as an optionally animated SVG string.
 *
 * ## Static mode (default)
 *
 * When [trace] is `null`, empty, or contains no [dev.kuml.runtime.TraceEntry.MessageSent]
 * entries that resolve to a known lifeline layout, the output is **byte-identical** to
 * `KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)`. No SMIL elements or
 * message-dot circles are injected.
 *
 * ## Animated mode
 *
 * When a [trace] is supplied with valid MessageSent entries:
 *
 * 1. The base SVG is rendered via [KumlSvgRenderer.toSvg].
 * 2. A `<circle>` element is injected before `</svg>` for each animated message.
 * 3. A [dev.kuml.render.smil.SmilTimeline] is built and injected via [SmilEmitter].
 *
 * The resulting SVG contains:
 * - `<animateMotion>` elements driving message-dot circles along horizontal arrow paths.
 * - `<animate attributeName="opacity">` for dot visibility (fade-in / fade-out per message).
 *
 * ## Coordinate system
 *
 * The renderer replicates the **padding-shift logic** from `KumlSvgRenderer.renderUmlSequence`
 * exactly: when the interaction contains combined fragments, the effective padding is raised to
 * `max(paddingPx, UML_SEQ_FRAGMENT_PADDING + 4)`. This ensures the dot coordinates match the
 * painted arrow positions pixel-exactly.
 *
 * ## Self-messages
 *
 * Self-messages (`from == to`) are skipped in V3.2 because their path is L-shaped and
 * cannot be expressed as a straight horizontal `<animateMotion>`. A future version may
 * animate along the L-path (`M cx,y L cx+24,y L cx+24,y+16 L cx,y+16`).
 *
 * ## Security
 *
 * [SequenceAnimationContext.dotColor] is validated in [SequenceAnimationContext.init] against
 * a CSS-color allowlist. Injection payloads (e.g. `"/><script>`) are rejected with
 * [IllegalArgumentException].
 *
 * V3.2 — UML Sequence Diagram SMIL Animation
 */
public object SequenceSmilRenderer {
    /**
     * Renders a UML Sequence Diagram to SVG, optionally injecting SMIL message-dot animations.
     *
     * @param diagram The [KumlDiagram] (must contain a [UmlInteraction] element).
     * @param layoutResult The computed layout result.
     * @param theme Visual theme. Defaults to [PlainTheme].
     * @param options Renderer options. Defaults to [SvgRenderOptions.DEFAULT].
     * @param trace Optional [TraceFile] containing [dev.kuml.runtime.TraceEntry.MessageSent] entries.
     *   When `null` or when no animatable entries are found, falls back to static rendering.
     * @param context Animation tuning parameters. Defaults to [SequenceAnimationContext.DEFAULT].
     * @return [AnimatedSequenceRenderResult] with the SVG string and animation flag.
     */
    public fun render(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
        trace: TraceFile? = null,
        context: SequenceAnimationContext = SequenceAnimationContext.DEFAULT,
    ): AnimatedSequenceRenderResult {
        // Always render the static base SVG first
        val baseSvg = KumlSvgRenderer.toSvg(diagram, layoutResult, theme, options)

        // Static path: null or empty trace
        if (trace == null || trace.entries.isEmpty()) {
            return AnimatedSequenceRenderResult(svg = baseSvg, hasAnimation = false)
        }

        // Extract the UmlInteraction from the diagram
        val interaction =
            diagram.elements.filterIsInstance<UmlInteraction>().firstOrNull()
                ?: return AnimatedSequenceRenderResult(svg = baseSvg, hasAnimation = false)

        // Replicate the effective-padding logic from KumlSvgRenderer.renderUmlSequence.
        // When fragments are present, the padding is raised so the fragment frame fits.
        // The shifted lifeline layouts must use the SAME padding as the static renderer,
        // otherwise dots are offset from the painted arrow lines.
        val effectivePadding =
            if (interaction.fragments.isNotEmpty()) {
                maxOf(options.paddingPx, UML_SEQ_FRAGMENT_PADDING + 4f)
            } else {
                options.paddingPx
            }

        // Build the shifted-layouts map for lifelines only (mirrors renderUmlSequence step 1)
        val shiftedLayouts =
            buildMap {
                for ((nodeId, nodeLayout) in layoutResult.nodes) {
                    // Only include nodes that correspond to a lifeline in this interaction
                    if (interaction.lifelines.none { it.id == nodeId.value }) continue
                    put(
                        nodeId,
                        nodeLayout.copy(
                            bounds =
                                nodeLayout.bounds.copy(
                                    origin =
                                        nodeLayout.bounds.origin.copy(
                                            x = nodeLayout.bounds.origin.x + effectivePadding,
                                            y = nodeLayout.bounds.origin.y + effectivePadding,
                                        ),
                                ),
                        ),
                    )
                }
            }

        // Build timeline + dots
        val (timeline, dots) =
            SequenceMessageTimelineBuilder.build(
                interaction = interaction,
                shiftedLayouts = shiftedLayouts,
                trace = trace,
                context = context,
            )

        // Static path: no animations produced
        if (timeline.animations.isEmpty()) {
            return AnimatedSequenceRenderResult(svg = baseSvg, hasAnimation = false)
        }

        // Inject dot circle elements before </svg>
        val svgWithDots = injectDots(baseSvg, dots)

        // Inject SMIL animations via SmilEmitter
        val animatedSvg = SmilEmitter().inject(svgWithDots, timeline)

        return AnimatedSequenceRenderResult(svg = animatedSvg, hasAnimation = true, timeline = timeline)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Injects message-dot `<circle>` elements into [svg] immediately before the last `</svg>`.
     *
     * Each circle has:
     * - `id` attribute for SMIL [animateMotion] targeting.
     * - `r=6` (radius), `cx=0 cy=0` (position driven by animateMotion).
     * - `fill` set to [MessageDot.color].
     * - `opacity=0` so circles are invisible before their animation begins.
     *
     * Dot ids use only safe ASCII characters (`kuml-seq-dot-N`) so no sanitization is needed,
     * but [xmlEscapeAttr] is applied defensively.
     */
    private fun injectDots(
        svg: String,
        dots: List<MessageDot>,
    ): String {
        if (dots.isEmpty()) return svg
        val dotsXml =
            buildString {
                for (dot in dots) {
                    append(
                        "\n<circle id=\"${xmlEscapeAttr(dot.id)}\" r=\"6\" cx=\"0\" cy=\"0\" " +
                            "fill=\"${xmlEscapeAttr(dot.color)}\" opacity=\"0\"/>",
                    )
                }
            }
        val closeIndex = svg.lastIndexOf("</svg>")
        return if (closeIndex >= 0) {
            svg.substring(0, closeIndex) + dotsXml + "\n</svg>"
        } else {
            svg + dotsXml
        }
    }

    /**
     * Escapes XML attribute values by replacing `"`, `<`, `>`, `&`, `'` with
     * their XML entity equivalents.
     */
    private fun xmlEscapeAttr(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
