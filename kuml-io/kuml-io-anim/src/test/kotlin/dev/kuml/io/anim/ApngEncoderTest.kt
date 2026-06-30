package dev.kuml.io.anim

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Tests for [ApngEncoder].
 *
 * Uses synthetic single-pixel PNG frames to keep the test fast and dependency-free.
 * Validates:
 * - PNG signature is correct
 * - acTL chunk is present with num_frames=3 and num_plays=0
 * - Three fcTL chunks with strictly increasing sequence numbers
 * - Two fdAT chunks (frames 2 and 3) each with a 4-byte sequence_number prefix
 * - ImageIO can decode the first frame
 */
class ApngEncoderTest :
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

        val frame1 = syntheticPng(255, 0, 0) // red
        val frame2 = syntheticPng(0, 255, 0) // green
        val frame3 = syntheticPng(0, 0, 255) // blue
        val frames = listOf(frame1, frame2, frame3)
        val delayMs = 200L
        val apng = ApngEncoder.encode(frames, delayMs)

        test("output starts with PNG signature") {
            val sig = apng.copyOfRange(0, 8)
            sig.contentEquals(PNG_SIGNATURE) shouldBe true
        }

        test("acTL chunk is present with num_frames=3 and num_plays=0") {
            val chunks = parsePngChunks(apng)
            val actl = chunks.firstOrNull { it.typeString == "acTL" }
            actl shouldNotBe null
            val numFrames = actl!!.data.readInt(0)
            val numPlays = actl.data.readInt(4)
            numFrames shouldBe 3
            numPlays shouldBe 0
        }

        test("three fcTL chunks with strictly increasing sequence numbers") {
            val chunks = parsePngChunks(apng)
            val fctls = chunks.filter { it.typeString == "fcTL" }
            fctls.size shouldBe 3
            val seqNos = fctls.map { it.data.readInt(0) }
            // Sequence numbers must be strictly increasing
            for (i in 1 until seqNos.size) {
                seqNos[i] shouldBeGreaterThanOrEqualTo seqNos[i - 1] + 1
            }
        }

        test("two fdAT chunks for frames 2 and 3") {
            val chunks = parsePngChunks(apng)
            val fdats = chunks.filter { it.typeString == "fdAT" }
            fdats.size shouldBe 2
            // Each fdAT must start with a 4-byte sequence number
            for (fdat in fdats) {
                fdat.data.size shouldBeGreaterThanOrEqualTo 4
            }
        }

        test("sequence numbers across fcTL and fdAT are monotonically increasing") {
            val chunks = parsePngChunks(apng)
            val seqChunks = chunks.filter { it.typeString == "fcTL" || it.typeString == "fdAT" }
            val seqNos = seqChunks.map { it.data.readInt(0) }
            for (i in 1 until seqNos.size) {
                seqNos[i] shouldBeGreaterThanOrEqualTo seqNos[i - 1] + 1
            }
        }

        test("ImageIO can decode first frame from output") {
            // The APNG is a valid PNG — ImageIO reads the default (first) frame
            val img = ImageIO.read(apng.inputStream())
            img shouldNotBe null
            img.width shouldBe 4
            img.height shouldBe 4
        }
    })
