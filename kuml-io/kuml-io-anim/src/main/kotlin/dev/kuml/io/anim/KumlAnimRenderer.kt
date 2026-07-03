package dev.kuml.io.anim

import dev.kuml.render.smil.SmilTimeline
import java.nio.file.Path

/**
 * Public entry-point for animated APNG / WebP / MP4 export.
 *
 * Orchestrates:
 * 1. [FrameBudget] computation (with DoS caps).
 * 2. Frame sampling via [BatikFrameSampler] (primary).
 * 3. Encoding via [ApngEncoder] (APNG), [WebpEncoder] (WebP), or [Mp4Encoder] (MP4).
 * 4. 50 MiB output-size warning to stderr.
 *
 * Usage:
 * ```kotlin
 * val bytes = KumlAnimRenderer.toAnimated(animatedSvg, timeline, AnimRenderOptions())
 * Files.write(Path.of("diagram.apng"), bytes)
 * ```
 *
 * @see AnimRenderOptions
 * @see AnimFormat
 */
public object KumlAnimRenderer {
    /**
     * Render an SMIL-animated SVG to an animated APNG or WebP byte array.
     *
     * @param animatedSvg The SMIL-animated SVG string (output of StmSmilRenderer etc.).
     * @param timeline The [SmilTimeline] carrying timing information.
     * @param options Export options (format, fps, width, caps).
     * @return Encoded animated image bytes.
     * @throws AnimEncoderException if encoding fails or a required binary is missing.
     */
    public fun toAnimated(
        animatedSvg: String,
        timeline: SmilTimeline,
        options: AnimRenderOptions = AnimRenderOptions.DEFAULT,
    ): ByteArray {
        val totalMs = timeline.totalDurationMs
        if (totalMs <= 0L) {
            throw AnimEncoderException(
                "Timeline has totalDurationMs=$totalMs — cannot produce an animated export. " +
                    "Ensure the diagram has SMIL animations (use --animated with a supported diagram type).",
            )
        }

        // MP4/H.264 has no standardised alpha-channel support in the container/codec
        // combination kUML targets (see AnimFormat.MP4 KDoc). Rather than silently
        // discarding the alpha channel and compositing against an undefined background,
        // reject the combination up front with an actionable message.
        if (options.format == AnimFormat.MP4 && options.transparent) {
            throw AnimEncoderException(
                "MP4 export does not support a transparent background (H.264 has no standard " +
                    "alpha channel). Set transparent = false and choose a backgroundColor, " +
                    "or use --format=apng/webp for transparent animated export.",
            )
        }

        val budget = FrameBudget.compute(totalMs, options)

        val frames = SmilTimelineFrameSampler.sample(animatedSvg, timeline, budget, options)

        val encoded =
            when (options.format) {
                AnimFormat.APNG -> ApngEncoder.encode(frames, budget.intervalMs)
                AnimFormat.WEBP -> WebpEncoder.encode(frames, budget.intervalMs)
                AnimFormat.MP4 -> Mp4Encoder.encode(frames, budget.intervalMs)
            }

        val encodedSize = encoded.size.toLong()

        // Hard rejection: abort if output exceeds maxSizeBytes
        if (encodedSize > options.maxSizeBytes) {
            val sizeMb = encodedSize / (1024 * 1024)
            val maxMb = options.maxSizeBytes / (1024 * 1024)
            throw AnimEncoderException(
                "Animated ${options.format} output is $sizeMb MiB, which exceeds the " +
                    "$maxMb MiB hard limit (maxSizeBytes=${options.maxSizeBytes}). " +
                    "Reduce --width or fps to produce a smaller output.",
            )
        }

        // Soft warning: stderr notice when output exceeds warnSizeBytes
        if (encodedSize > options.warnSizeBytes) {
            val sizeMb = encodedSize / (1024 * 1024)
            System.err.println(
                "[kuml] WARNING: Animated ${options.format} output is $sizeMb MiB " +
                    "(>${options.warnSizeBytes / (1024 * 1024)} MiB threshold). " +
                    "Consider reducing --width or fps.",
            )
        }

        return encoded
    }

    /**
     * Render an SMIL-animated SVG to an animated file.
     *
     * @param animatedSvg The SMIL-animated SVG string.
     * @param timeline The [SmilTimeline] carrying timing information.
     * @param output Destination file path.
     * @param options Export options.
     * @throws AnimEncoderException if encoding fails or a required binary is missing.
     */
    public fun toAnimatedFile(
        animatedSvg: String,
        timeline: SmilTimeline,
        output: Path,
        options: AnimRenderOptions = AnimRenderOptions.DEFAULT,
    ) {
        val bytes = toAnimated(animatedSvg, timeline, options)
        output.toFile().apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }
}
