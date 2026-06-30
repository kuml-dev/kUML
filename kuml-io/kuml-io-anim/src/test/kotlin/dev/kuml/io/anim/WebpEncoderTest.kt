package dev.kuml.io.anim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Tests for [WebpEncoder].
 *
 * When no WebP encoder binary is on PATH (the typical CI case), the encode test
 * is skipped via [io.kotest.core.test.TestCaseConfig.enabled]. The
 * AnimEncoderException test always runs.
 */
class WebpEncoderTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")

        fun syntheticPng(
            r: Int,
            g: Int,
            b: Int,
        ): ByteArray {
            val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
            val graphics = img.createGraphics()
            graphics.color = Color(r, g, b)
            graphics.fillRect(0, 0, 4, 4)
            graphics.dispose()
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "png", baos)
            return baos.toByteArray()
        }

        val frames =
            listOf(
                syntheticPng(255, 0, 0),
                syntheticPng(0, 255, 0),
                syntheticPng(0, 0, 255),
            )

        test("encode produces RIFF/WEBP bytes when encoder is available").config(
            enabled = EncoderBinaryLocator.isWebpAvailable(),
        ) {
            val bytes = WebpEncoder.encode(frames, 200L)
            // RIFF signature: 52 49 46 46
            val riff = bytes.copyOfRange(0, 4)
            val riffStr = String(riff, Charsets.ISO_8859_1)
            riffStr shouldBe "RIFF"
            // WebP marker at offset 8: 57 45 42 50
            val webp = bytes.copyOfRange(8, 12)
            val webpStr = String(webp, Charsets.ISO_8859_1)
            webpStr shouldBe "WEBP"
        }

        test("AnimEncoderException thrown with actionable message when no binary available") {
            // We simulate missing binary by testing with an unreachable binary name via the locator
            // Directly call encode when we know the locator returns null
            if (!EncoderBinaryLocator.isWebpAvailable()) {
                val ex =
                    shouldThrow<AnimEncoderException> {
                        WebpEncoder.encode(frames, 200L)
                    }
                ex.message shouldContain "No animated-WebP encoder found"
                ex.message shouldContain "img2webp"
            } else {
                // On a machine that has img2webp, skip this check — the encoding succeeds
            }
        }
    })
