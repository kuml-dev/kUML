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
    }

    /**
     * Generic attribute animation: `<animate attributeName=[attribute] from=[from] to=[to] .../>`.
     *
     * @throws IllegalArgumentException if [attribute] is not in [ALLOWED_ATTRIBUTE_NAMES].
     */
    public data class Animate(
        override val elementId: String,
        val attribute: String,
        val from: String,
        val to: String,
        override val beginMs: Long,
        override val durationMs: Long,
    ) : SmilAnimation() {
        init {
            requireValidAttributeName(attribute)
        }
    }

    /**
     * Transform animation: `<animateTransform type=[type.svgToken] from=[from] to=[to] .../>`.
     */
    public data class AnimateTransform(
        override val elementId: String,
        val type: TransformType,
        val from: String,
        val to: String,
        override val beginMs: Long,
        override val durationMs: Long,
    ) : SmilAnimation()

    /**
     * Motion animation along an SVG path: `<animateMotion path=[path] .../>`.
     *
     * [path] is the SVG path `d` attribute string. When the path is not available
     * (no resolver supplied), the builder skips emitting this animation.
     */
    public data class AnimateMotion(
        override val elementId: String,
        val path: String,
        override val beginMs: Long,
        override val durationMs: Long,
    ) : SmilAnimation()

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
