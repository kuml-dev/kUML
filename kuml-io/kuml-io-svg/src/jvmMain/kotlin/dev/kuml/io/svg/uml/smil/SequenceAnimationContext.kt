package dev.kuml.io.svg.uml.smil

import dev.kuml.render.smil.SpeedFactor

/**
 * Tuning parameters for UML Sequence Diagram message-dot animation.
 *
 * All color parameters are validated against a CSS-color allowlist in `init` to prevent
 * SVG attribute injection — they are interpolated directly into SVG element attributes
 * (`fill`, etc.) that bypass [dev.kuml.render.smil.SmilXml] escaping.
 *
 * Accepted formats:
 * - Short hex: `#rgb` (e.g. `#f00`)
 * - Long hex: `#rrggbb` (e.g. `#2962ff`)
 * - Long hex with alpha: `#rrggbbaa`
 * - Named CSS colors from the allowlist (e.g. `white`, `black`, `red`)
 *
 * @param speedFactor Playback speed. Values > 1.0 compress the timeline (faster animation).
 * @param dotColor Fill colour of the message-dot circle travelling along message arrow paths.
 * @param loopCount How many times the full animation sequence plays. Minimum 1 (single pass).
 *   Use [LOOP_INFINITE] (the default) for an indefinitely repeating animation.
 * @param loopGapMs Pause in milliseconds between loop repetitions (before speedFactor scaling).
 *
 * V3.2 — UML Sequence Diagram SMIL Animation
 */
public data class SequenceAnimationContext(
    val speedFactor: SpeedFactor = SpeedFactor.DEFAULT,
    val dotColor: String = "#2962ff",
    val loopCount: Int = LOOP_INFINITE,
    val loopGapMs: Long = 2_000L,
) {
    init {
        requireSafeCssColor(dotColor, "dotColor")
        require(loopCount >= 1) { "loopCount must be >= 1, got: $loopCount" }
        require(loopGapMs >= 0) { "loopGapMs must be >= 0, got: $loopGapMs" }
    }

    public companion object {
        /** Maximum number of message animation steps in a single render call. */
        public const val MAX_ANIMATIONS: Int = 500

        /**
         * Sentinel value for [loopCount] requesting an indefinitely repeating animation.
         *
         * The SMIL timeline is tiled [LOOP_PRACTICAL_MAX] times internally — enough to
         * cover ~23 minutes of playback at default speed, which is effectively infinite
         * for any realistic presentation scenario.
         */
        public const val LOOP_INFINITE: Int = Int.MAX_VALUE

        /**
         * Internal tiling cap applied when [loopCount] == [LOOP_INFINITE].
         *
         * 200 repetitions at ~8 s/loop (6 s pass + 2 s gap) ≈ 26 minutes.
         * Keeps the generated SVG at a manageable size.
         */
        internal const val LOOP_PRACTICAL_MAX: Int = 200

        /**
         * Maximum total message steps (sentEntries.size × effectiveLoopCount) after
         * loop-tiling expansion.
         *
         * Guards against the post-loop memory footprint: [MAX_ANIMATIONS] (500) pre-loop entries
         * × [LOOP_PRACTICAL_MAX] (200) loops would produce 300,000 [SmilAnimation] objects (3 per
         * message: fade-in, motion, fade-out) — roughly 86 MB heap and a ~34 MB SMIL fragment.
         * This constant caps the message-step product at 10,000 (e.g. 50 entries × 200 loops,
         * or 500 entries × 20 loops), keeping heap usage and SVG size within a safe bound.
         *
         * Note: the guard is expressed in *message steps* (not raw SMIL animation count) so that
         * the threshold remains independent of how many SMIL elements are produced per step.
         *
         * Checked in [dev.kuml.io.svg.uml.smil.SequenceMessageTimelineBuilder.build] before
         * the loop-tiling block.
         */
        public const val MAX_TOTAL_ANIMATION_STEPS: Long = 10_000L

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

        /** Default context — blue dot, 1× speed, infinite loop with 2 s gap. */
        public val DEFAULT: SequenceAnimationContext = SequenceAnimationContext()

        internal fun requireSafeCssColor(
            value: String,
            paramName: String,
        ) {
            val isHex = HEX_COLOR_REGEX.matches(value)
            val isNamed = value.lowercase() in NAMED_COLOR_ALLOWLIST
            require(isHex || isNamed) {
                "SequenceAnimationContext.$paramName must be a hex color (#rgb / #rrggbb / #rrggbbaa) " +
                    "or a named CSS color from the allowlist, got: '$value'. " +
                    "This check prevents SVG attribute injection."
            }
        }
    }
}
