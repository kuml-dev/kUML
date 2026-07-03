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
 * Tests for [Mp4Encoder].
 *
 * When `ffmpeg` is not on PATH (the typical CI case), the encode test is skipped
 * via [io.kotest.core.test.TestCaseConfig.enabled]. The AnimEncoderException test
 * always runs.
 */
class Mp4EncoderTest :
    FunSpec({
        System.setProperty("java.awt.headless", "true")

        fun syntheticPng(
            r: Int,
            g: Int,
            b: Int,
        ): ByteArray {
            // Opaque frame (TYPE_INT_RGB) — MP4/H.264 has no alpha channel, so frames
            // fed to Mp4Encoder are expected to already be composited against an
            // opaque background (mirrors what KumlAnimRenderer enforces upstream).
            val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
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

        test("encode produces an MP4 file (ftyp box) when ffmpeg is available").config(
            enabled = EncoderBinaryLocator.isFfmpegAvailable(),
        ) {
            val bytes = Mp4Encoder.encode(frames, 200L)
            bytes.size shouldBe bytes.size // sanity: non-throwing
            // MP4 files start with a 4-byte size field, then the ASCII box type "ftyp"
            // at offset 4..7 (ISO base media file format).
            val ftyp = bytes.copyOfRange(4, 8)
            val ftypStr = String(ftyp, Charsets.ISO_8859_1)
            ftypStr shouldBe "ftyp"
        }

        test("AnimEncoderException thrown with actionable message when ffmpeg not available") {
            if (!EncoderBinaryLocator.isFfmpegAvailable()) {
                val ex =
                    shouldThrow<AnimEncoderException> {
                        Mp4Encoder.encode(frames, 200L)
                    }
                ex.message shouldContain "No MP4 encoder found"
                ex.message shouldContain "ffmpeg"
            } else {
                // On a machine that has ffmpeg, skip this check — encoding succeeds instead.
            }
        }
    })
