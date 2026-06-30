package dev.kuml.io.anim

/**
 * Output format for animated export.
 *
 * - [APNG]: Animated PNG — no external binary required, pure-JVM assembly.
 * - [WEBP]: Animated WebP — requires `img2webp` (libwebp) or `ffmpeg` on PATH.
 */
public enum class AnimFormat {
    APNG,
    WEBP,
}

/**
 * Options governing the animated APNG / WebP export pipeline.
 *
 * @param format Target format: [AnimFormat.APNG] or [AnimFormat.WEBP].
 * @param fps Nominal frames-per-second. Default 25; auto-reduced to 12 when
 *   the 500-frame cap would be exceeded at 25 fps. Must be in 1..60.
 * @param widthPx Output width in pixels. Capped at 4 096 for animated export to
 *   prevent per-frame memory exhaustion (500 frames × 4 096 px is already ~500 MiB
 *   of raw RGBA before compression). Must be in 1..4_096.
 * @param transparent When `true` the background is transparent (RGBA).
 *   When `false`, [backgroundColor] fills the background.
 * @param backgroundColor Background fill colour when [transparent] is `false`.
 *   Standard CSS/SVG colour string, e.g. `"white"` or `"#ffffff"`.
 * @param maxFrames Hard cap on the number of frames (DoS guard). Default 500.
 * @param maxDurationMs Hard cap on the animation duration in ms (DoS guard). Default 30 000.
 * @param warnSizeBytes Emit a stderr warning when the encoded output exceeds this
 *   size in bytes. Default 50 MiB (52_428_800).
 * @param maxSizeBytes Hard cap on the encoded output size in bytes. Encoding is aborted
 *   with [AnimEncoderException] when the result would exceed this limit. Default 200 MiB
 *   (209_715_200). Must be ≥ [warnSizeBytes].
 * @param maxSvgBytes Hard cap on the SVG input size in bytes before parsing. Inputs
 *   larger than this are rejected to prevent XML entity-expansion (billion-laughs) attacks
 *   from consuming heap before any frame-count cap fires. Default 10 MiB (10_485_760).
 */
public data class AnimRenderOptions(
    val format: AnimFormat = AnimFormat.APNG,
    val fps: Int = 25,
    val widthPx: Int = 1024,
    val transparent: Boolean = true,
    val backgroundColor: String = "white",
    val maxFrames: Int = 500,
    val maxDurationMs: Long = 30_000L,
    val warnSizeBytes: Long = 52_428_800L,
    val maxSizeBytes: Long = 209_715_200L,
    val maxSvgBytes: Long = 10_485_760L,
) {
    init {
        require(fps in 1..60) { "fps must be in 1..60, got $fps" }
        require(widthPx in 1..4_096) { "widthPx must be in 1..4096, got $widthPx" }
        require(maxFrames in 1..10_000) { "maxFrames must be in 1..10000, got $maxFrames" }
        require(maxDurationMs > 0) { "maxDurationMs must be > 0, got $maxDurationMs" }
        require(maxSizeBytes >= warnSizeBytes) {
            "maxSizeBytes ($maxSizeBytes) must be >= warnSizeBytes ($warnSizeBytes)"
        }
        require(maxSvgBytes > 0) { "maxSvgBytes must be > 0, got $maxSvgBytes" }
    }

    public companion object {
        public val DEFAULT: AnimRenderOptions = AnimRenderOptions()
    }
}
