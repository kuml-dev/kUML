package dev.kuml.io.svg.bpmn.smil

import dev.kuml.render.smil.SpeedFactor

/**
 * Tuning parameters for BPMN token-flow animation.
 *
 * [tokenColor] and [highlightColor] are validated against a CSS-color allowlist in `init`
 * to prevent SVG attribute injection — they are interpolated directly into SVG element
 * attributes (`fill`, etc.) that bypass [dev.kuml.render.smil.SmilXml] escaping.
 *
 * Accepted formats:
 * - Short hex: `#rgb` (e.g. `#f00`)
 * - Long hex: `#rrggbb` (e.g. `#2962ff`)
 * - Long hex with alpha: `#rrggbbaa`
 * - Named CSS colors from the allowlist (e.g. `white`, `black`, `red`)
 *
 * @param speedFactor Playback speed. Values > 1.0 compress the timeline (faster animation).
 * @param tokenColor Fill colour of the token circle travelling along SequenceFlow paths.
 * @param highlightColor Fill colour applied to gateway diamonds when the token passes through.
 *
 * V3.1.30 — BPMN SMIL Renderer
 */
public data class BpmnAnimationContext(
    val speedFactor: SpeedFactor = SpeedFactor.DEFAULT,
    val tokenColor: String = "#2962ff",
    val highlightColor: String = "#ffd54a",
) {
    init {
        requireSafeCssColor(tokenColor, "tokenColor")
        requireSafeCssColor(highlightColor, "highlightColor")
    }

    public companion object {
        /** Maximum number of token animation steps in a single render call. */
        public const val MAX_ANIMATIONS: Int = 500

        private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

        /**
         * Named CSS colours permitted as color values.
         *
         * This list covers the colours used in kUML themes plus the most common named
         * colours that might appear in user-supplied tokens. Extend as needed — but never
         * remove entries without checking usages.
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

        /** Default context — blue token, amber gateway highlight, 1× speed. */
        public val DEFAULT: BpmnAnimationContext = BpmnAnimationContext()

        internal fun requireSafeCssColor(
            value: String,
            paramName: String,
        ) {
            val isHex = HEX_COLOR_REGEX.matches(value)
            val isNamed = value.lowercase() in NAMED_COLOR_ALLOWLIST
            require(isHex || isNamed) {
                "BpmnAnimationContext.$paramName must be a hex color (#rgb / #rrggbb / #rrggbbaa) " +
                    "or a named CSS color from the allowlist, got: '$value'. " +
                    "This check prevents SVG attribute injection."
            }
        }
    }
}
