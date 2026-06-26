package dev.kuml.io.svg.stm.smil

import dev.kuml.render.smil.SpeedFactor

/**
 * Tuning parameters for STM (State Machine) animation.
 *
 * [highlightColor] and [normalColor] are validated against a CSS-color allowlist in `init`
 * to prevent SVG attribute injection — they are interpolated directly into SVG element
 * attributes (`fill`, etc.) that bypass [dev.kuml.render.smil.SmilXml] escaping.
 *
 * Accepted formats:
 * - Short hex: `#rgb` (e.g. `#f00`)
 * - Long hex: `#rrggbb` (e.g. `#ffd54a`)
 * - Long hex with alpha: `#rrggbbaa`
 * - Named CSS colors from the allowlist (e.g. `white`, `black`, `red`)
 *
 * @param speedFactor Playback speed. Values > 1.0 compress the timeline (faster animation).
 * @param highlightColor Fill colour applied to the state rectangle when the state is active.
 * @param normalColor Fill colour to restore after the state highlight ends.
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
public data class StmAnimationContext(
    val speedFactor: SpeedFactor = SpeedFactor.DEFAULT,
    val highlightColor: String = "#ffd54a",
    val normalColor: String = "#ffffff",
) {
    init {
        requireSafeCssColor(highlightColor, "highlightColor")
        requireSafeCssColor(normalColor, "normalColor")
    }

    public companion object {
        /** Maximum number of animation steps in a single render call. */
        public const val MAX_ANIMATIONS: Int = 500

        private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

        /**
         * Named CSS colours permitted as color values.
         *
         * Mirrors [dev.kuml.io.svg.bpmn.smil.BpmnAnimationContext]'s allowlist.
         */
        private val NAMED_COLOR_ALLOWLIST: Set<String> =
            setOf(
                "white",
                "black",
                "red",
                "green",
                "blue",
                "yellow",
                "orange",
                "purple",
                "pink",
                "gray",
                "grey",
                "cyan",
                "magenta",
                "lime",
                "maroon",
                "navy",
                "olive",
                "teal",
                "silver",
                "aqua",
                "fuchsia",
                "none",
                "transparent",
            )

        /** Default context — amber highlight, white normal, 1× speed. */
        public val DEFAULT: StmAnimationContext = StmAnimationContext()

        internal fun requireSafeCssColor(
            value: String,
            paramName: String,
        ) {
            val isHex = HEX_COLOR_REGEX.matches(value)
            val isNamed = value.lowercase() in NAMED_COLOR_ALLOWLIST
            require(isHex || isNamed) {
                "StmAnimationContext.$paramName must be a hex color (#rgb / #rrggbb / #rrggbbaa) " +
                    "or a named CSS color from the allowlist, got: '$value'. " +
                    "This check prevents SVG attribute injection."
            }
        }
    }
}
