package dev.kuml.render.smil

/**
 * A single SMIL animation element targeting a specific SVG element.
 *
 * All subtypes carry [elementId] (the target SVG element's `id` attribute),
 * [beginMs] (absolute begin time in milliseconds), and [durationMs].
 *
 * Note: [SmilAnimation.Set] is not a Kotlin reserved word when qualified; reference it
 * as `SmilAnimation.Set` to avoid shadowing [kotlin.collections.Set].
 *
 * ADR-0014: `<animateColor>` is explicitly excluded — use [Fill] which emits
 * `<animate attributeName="fill" .../>` instead.
 *
 * ## Security: attribute-name validation
 *
 * [Animate.attribute] and [Set.attribute] are interpolated directly into the emitted XML
 * `attributeName` value. To prevent XML attribute-name injection, both subtypes validate
 * the supplied name against [ALLOWED_ATTRIBUTE_NAMES] at construction time.
 */
public sealed class SmilAnimation {
    public abstract val elementId: String
    public abstract val beginMs: Long
    public abstract val durationMs: Long

    public companion object {
        /**
         * Allowlist of SVG presentation attributes permitted as `attributeName` values in
         * `<animate>` and `<set>` elements.
         *
         * Only attributes that kUML actually emits today are listed here. Extend this set
         * when new animation targets are added — never remove entries without a deprecation
         * cycle. The set is case-sensitive and matches the SVG spec attribute names exactly.
         */
        public val ALLOWED_ATTRIBUTE_NAMES: kotlin.collections.Set<String> =
            setOf(
                "opacity",
                "fill",
                "fill-opacity",
                "stroke",
                "stroke-opacity",
                "stroke-width",
                "visibility",
                "display",
                "color",
                "font-size",
                "font-weight",
                "transform",
                "x",
                "y",
                "cx",
                "cy",
                "r",
                "rx",
                "ry",
                "width",
                "height",
                "d",
                "viewBox",
            )

        internal fun requireValidAttributeName(attribute: String) {
            require(attribute in ALLOWED_ATTRIBUTE_NAMES) {
                "Illegal attributeName '$attribute': not in ALLOWED_ATTRIBUTE_NAMES. " +
                    "Add the attribute to SmilAnimation.ALLOWED_ATTRIBUTE_NAMES if it is a " +
                    "legitimate SVG presentation attribute."
            }
        }

        /**
         * Sentinel value for [Animate.repeatCount] / [AnimateTransform.repeatCount] /
         * [AnimateMotion.repeatCount] indicating an indefinitely repeating animation.
         *
         * Corresponds to SMIL `repeatCount="indefinite"`. When used in a [SmilTimeline],
         * [dev.kuml.io.anim.SmilTimelineFrameSampler] will cycle the animation at the period
         * boundary rather than freezing at the final value.
         * [dev.kuml.io.anim.BatikFrameSampler] handles indefinite repeat automatically via the
         * Batik animation engine when the emitted SVG carries `repeatCount="indefinite"`.
         */
        public const val REPEAT_INDEFINITE: Int = 0

        /**
         * Play the animation exactly once, then freeze at the end value (default).
         * Corresponds to SMIL `fill="freeze"` with no explicit `repeatCount`.
         */
        public const val REPEAT_ONCE: Int = 1
    }

    /**
     * Generic attribute animation: `<animate attributeName=[attribute] from=[from] to=[to] .../>`.
     *
     * @param repeatCount Number of times to repeat: 1 = play once (default, freeze at end),
     *   [REPEAT_INDEFINITE] (0) = loop forever. Values > 1 play the animation that many times.
     * @throws IllegalArgumentException if [attribute] is not in [ALLOWED_ATTRIBUTE_NAMES].
     */
    public data class Animate(
        override val elementId: String,
        val attribute: String,
        val from: String,
        val to: String,
        override val beginMs: Long,
        override val durationMs: Long,
        val repeatCount: Int = REPEAT_ONCE,
    ) : SmilAnimation() {
        init {
            requireValidAttributeName(attribute)
            require(repeatCount >= 0) { "repeatCount must be >= 0 (use 0 for indefinite), got $repeatCount" }
        }
    }

    /**
     * Transform animation: `<animateTransform type=[type.svgToken] from=[from] to=[to] .../>`.
     *
     * @param repeatCount Number of times to repeat: 1 = play once (default), [REPEAT_INDEFINITE] = loop forever.
     */
    public data class AnimateTransform(
        override val elementId: String,
        val type: TransformType,
        val from: String,
        val to: String,
        override val beginMs: Long,
        override val durationMs: Long,
        val repeatCount: Int = REPEAT_ONCE,
    ) : SmilAnimation() {
        init {
            require(repeatCount >= 0) { "repeatCount must be >= 0 (use 0 for indefinite), got $repeatCount" }
        }
    }

    /**
     * Motion animation along an SVG path: `<animateMotion path=[path] .../>`.
     *
     * [path] is the SVG path `d` attribute string. When the path is not available
     * (no resolver supplied), the builder skips emitting this animation.
     *
     * @param repeatCount Number of times to repeat: 1 = play once (default), [REPEAT_INDEFINITE] = loop forever.
     */
    public data class AnimateMotion(
        override val elementId: String,
        val path: String,
        override val beginMs: Long,
        override val durationMs: Long,
        val repeatCount: Int = REPEAT_ONCE,
    ) : SmilAnimation() {
        init {
            require(repeatCount >= 0) { "repeatCount must be >= 0 (use 0 for indefinite), got $repeatCount" }
        }
    }

    /**
     * Discrete property set: `<set attributeName=[attribute] to=[to] .../>`.
     *
     * Named `Set` (not a Kotlin keyword here); always reference as `SmilAnimation.Set`.
     *
     * @throws IllegalArgumentException if [attribute] is not in [ALLOWED_ATTRIBUTE_NAMES].
     */
    public data class Set(
        override val elementId: String,
        val attribute: String,
        val to: String,
        override val beginMs: Long,
        override val durationMs: Long,
    ) : SmilAnimation() {
        init {
            requireValidAttributeName(attribute)
        }
    }

    /**
     * Fill-colour highlight animation.
     *
     * Emits `<animate attributeName="fill" from="[fromColor]" to="[color]" .../>`.
     * Never emits `<animateColor>` (deprecated per ADR-0014).
     *
     * [fromColor] is the original fill to restore from after the animation completes.
     * When `null`, the browser derives the starting value from the element's current
     * attribute state, which can produce flicker during repeated replay. Callers should
     * supply the SVG element's pre-highlight fill colour whenever it is known
     * (e.g. `"white"`, `"none"`, the theme background colour).
     */
    public data class Fill(
        override val elementId: String,
        val color: String,
        override val beginMs: Long,
        override val durationMs: Long,
        val fromColor: String? = null,
    ) : SmilAnimation()
}
