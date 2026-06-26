package dev.kuml.render.smil

/**
 * Configuration for [SmilTimelineBuilder].
 *
 * @param stepMs duration (and inter-step offset) in milliseconds for each trace entry.
 * @param highlightColor CSS/SVG colour used for [SmilAnimation.Fill] highlights on STM nodes.
 * @param tokenRadius radius in pixels for token dot elements in Activity diagrams (informational).
 * @param tokenElementIdPrefix element-id prefix for token SVG circles injected externally.
 * @param pathResolver maps an edge ID to an SVG path `d` string. When the function returns
 *   `null` for a given edge, [SmilAnimation.AnimateMotion] is skipped for that token.
 * @param maxAnimations maximum number of [SmilAnimation] entries that [SmilTimelineBuilder]
 *   will produce from a single [dev.kuml.runtime.TraceFile]. When the limit is exceeded the
 *   builder throws [IllegalArgumentException]. This cap prevents heap exhaustion from
 *   maliciously crafted or accidentally oversized trace files.
 *   Default: [DEFAULT_MAX_ANIMATIONS].
 */
public data class BuildOptions(
    val stepMs: Long = 600L,
    val highlightColor: String = "#ffd54a",
    val tokenRadius: Int = 6,
    val tokenElementIdPrefix: String = "smil-token-",
    val pathResolver: (edgeId: String) -> String? = { _ -> null },
    val maxAnimations: Int = DEFAULT_MAX_ANIMATIONS,
) {
    public companion object {
        /** Default cap on the number of animations produced from a single trace file. */
        public const val DEFAULT_MAX_ANIMATIONS: Int = 10_000
    }
}
