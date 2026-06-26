package dev.kuml.render.smil

/**
 * Emits SMIL animation elements and injects them into an SVG string.
 *
 * Injection strategy: all SMIL elements are appended immediately before the last
 * `</svg>` tag in the SVG string. If no `</svg>` is found the fragment is appended
 * to the end of the string (no data loss).
 *
 * ADR-0014 compliance: `<animateColor>` is never emitted. [SmilAnimation.Fill] emits
 * `<animate attributeName="fill" .../>` instead.
 *
 * ## Speed-scaling contract
 *
 * Speed scaling is the **sole responsibility of [SmilTimeline.scaledBy]**. This emitter
 * always emits timings exactly as stored in the [SmilTimeline] — it does **not** accept a
 * speed parameter. Callers who need playback at a non-1× speed must pre-scale the timeline:
 *
 * ```kotlin
 * val scaled = timeline.scaledBy(SpeedFactor(2.0))
 * val svg = emitter.inject(svg, scaled)
 * ```
 *
 * This single-path contract prevents accidental double-scaling (which would occur if a
 * pre-scaled timeline were also passed through a speed-aware emitter).
 *
 * ## inject() stripping behaviour
 *
 * [inject] with [StaticSnapshotMode.ANIMATED] (the default) does **not** strip pre-existing
 * SMIL elements. Existing animations in the SVG are preserved. Only [StaticSnapshotMode.STRIPPED]
 * removes all SMIL elements and suppresses injection of new ones.
 */
public class SmilEmitter {
    private companion object {
        /** Regex matching SMIL elements in both self-closing and paired form. */
        private val SMIL_ELEMENT_REGEX =
            Regex(
                """<(?:animate|animateTransform|animateMotion|set)(?:\s[^>]*)?>(?:</(?:animate|animateTransform|animateMotion|set)>)?""",
                RegexOption.IGNORE_CASE,
            )

        private const val SVG_CLOSE = "</svg>"
        private const val FILL_FREEZE = "freeze"
    }

    /**
     * Inject SMIL animations from [timeline] into [svg].
     *
     * In [StaticSnapshotMode.ANIMATED] (default) the existing SVG content is left intact and
     * the new SMIL fragment is appended before the closing `</svg>` tag. Pre-existing SMIL
     * elements in the SVG are **preserved** — calling inject() twice accumulates animations.
     *
     * In [StaticSnapshotMode.STRIPPED] all existing SMIL elements are removed and no new ones
     * are injected, returning a static snapshot.
     *
     * Speed scaling must be applied to [timeline] before calling this method via
     * [SmilTimeline.scaledBy]. Timings are emitted as-is.
     *
     * @param svg the SVG string to augment.
     * @param timeline the animation timeline to inject (pre-scaled if non-1× speed is needed).
     * @param staticSnapshot controls stripping behaviour; defaults to [StaticSnapshotMode.ANIMATED].
     * @return the modified SVG string.
     */
    public fun inject(
        svg: String,
        timeline: SmilTimeline,
        staticSnapshot: StaticSnapshotMode = StaticSnapshotMode.ANIMATED,
    ): String {
        if (staticSnapshot == StaticSnapshotMode.STRIPPED) return stripSmil(svg)
        if (timeline.animations.isEmpty()) return svg

        val fragment = renderElements(timeline)
        val closeIndex = svg.lastIndexOf(SVG_CLOSE)
        return if (closeIndex >= 0) {
            svg.substring(0, closeIndex) + fragment + SVG_CLOSE
        } else {
            svg + fragment
        }
    }

    /**
     * Render all animations in [timeline] as a SMIL XML fragment string.
     *
     * Timings are emitted exactly as stored — no speed scaling is applied here.
     * Pre-scale the timeline with [SmilTimeline.scaledBy] before calling this method
     * if non-1× playback speed is required.
     *
     * @param timeline the animation timeline to render.
     * @return the concatenated SMIL element strings (newline-separated).
     */
    public fun renderElements(timeline: SmilTimeline): String =
        buildString {
            for (anim in timeline.animations) {
                append('\n')
                append(renderElement(anim))
            }
            if (timeline.animations.isNotEmpty()) append('\n')
        }

    // ── private rendering helpers ─────────────────────────────────────────────

    private fun renderElement(anim: SmilAnimation): String {
        val begin = anim.beginMs
        val dur = anim.durationMs
        val href = SmilXml.attr("href", "#${anim.elementId}")
        val beginAttr = SmilXml.attr("begin", "${begin}ms")
        val durAttr = SmilXml.attr("dur", "${dur}ms")
        val fillAttr = SmilXml.attr("fill", FILL_FREEZE)

        return when (anim) {
            is SmilAnimation.Animate -> {
                val attrName = SmilXml.attr("attributeName", anim.attribute)
                val from = SmilXml.attr("from", anim.from)
                val to = SmilXml.attr("to", anim.to)
                "<animate $href $attrName $from $to $beginAttr $durAttr $fillAttr/>"
            }
            is SmilAnimation.AnimateTransform -> {
                val attrName = SmilXml.attr("attributeName", "transform")
                val type = SmilXml.attr("type", anim.type.svgToken)
                val from = SmilXml.attr("from", anim.from)
                val to = SmilXml.attr("to", anim.to)
                "<animateTransform $href $attrName $type $from $to $beginAttr $durAttr $fillAttr/>"
            }
            is SmilAnimation.AnimateMotion -> {
                val path = SmilXml.attr("path", anim.path)
                "<animateMotion $href $path $beginAttr $durAttr $fillAttr/>"
            }
            is SmilAnimation.Set -> {
                val attrName = SmilXml.attr("attributeName", anim.attribute)
                val to = SmilXml.attr("to", anim.to)
                "<set $href $attrName $to $beginAttr $durAttr/>"
            }
            is SmilAnimation.Fill -> {
                // ADR-0014: emit <animate attributeName="fill"> — NEVER <animateColor>
                val attrName = SmilXml.attr("attributeName", "fill")
                val from = anim.fromColor?.let { SmilXml.attr("from", it) + " " } ?: ""
                val to = SmilXml.attr("to", anim.color)
                "<animate $href $attrName ${from}$to $beginAttr $durAttr $fillAttr/>"
            }
        }
    }

    /**
     * Remove all SMIL animation elements from [svg].
     *
     * Handles both self-closing (`<animate .../>`) and paired (`<animate ...></animate>`)
     * forms. The operation is idempotent — calling it on an already-clean SVG is a no-op.
     */
    private fun stripSmil(svg: String): String = SMIL_ELEMENT_REGEX.replace(svg, "")
}
