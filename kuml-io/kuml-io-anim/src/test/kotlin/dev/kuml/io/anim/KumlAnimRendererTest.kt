package dev.kuml.io.anim

import dev.kuml.render.smil.SmilAnimation
import dev.kuml.render.smil.SmilTimeline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for [KumlAnimRenderer].
 *
 * Focused on the MP4-specific transparency guard: MP4/H.264 has no standard alpha
 * channel (see [AnimFormat.MP4] KDoc), so `toAnimated` must reject
 * `format = MP4` combined with `transparent = true` *before* invoking [Mp4Encoder],
 * regardless of whether `ffmpeg` is installed on the test machine.
 */
class KumlAnimRendererTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")

        val baseSvg =
            """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
  <rect id="box" x="10" y="10" width="80" height="80" fill="red"/>
</svg>"""

        val anim =
            SmilAnimation.Animate(
                elementId = "box",
                attribute = "opacity",
                from = "0",
                to = "1",
                beginMs = 0L,
                durationMs = 200L,
            )
        val timeline = SmilTimeline(listOf(anim))

        test("toAnimated rejects MP4 + transparent=true before touching the encoder") {
            val opts = AnimRenderOptions(format = AnimFormat.MP4, transparent = true)
            val ex =
                shouldThrow<AnimEncoderException> {
                    KumlAnimRenderer.toAnimated(baseSvg, timeline, opts)
                }
            ex.message shouldContain "transparent background"
            ex.message shouldContain "MP4"
        }

        test("toAnimated does not throw the transparency guard for MP4 + transparent=false").config(
            enabled = EncoderBinaryLocator.isFfmpegAvailable(),
        ) {
            val opts = AnimRenderOptions(format = AnimFormat.MP4, transparent = false)
            // Should proceed past the guard into the encoder (which succeeds when ffmpeg is present).
            val bytes = KumlAnimRenderer.toAnimated(baseSvg, timeline, opts)
            bytes.isNotEmpty() shouldBe true
        }
    })
