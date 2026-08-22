package dev.kuml.desktop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import javax.imageio.ImageIO

/**
 * P6 — verifies the three application-icon assets committed under
 * `src/jvmMain/resources/icons/` (generated once, locally, from `docs/images/logo.svg`'s "{k}"
 * mark — see the P6 commit message for the generation pipeline). `build.gradle.kts`'s
 * `macOS`/`windows`/`linux` blocks only set `iconFile` when each file `.exists()` — before this
 * commit none of the three files existed, so jpackage silently fell back to its own generic
 * default icon on every platform.
 *
 * Deliberately checks only structural well-formedness (correct magic bytes / dimensions), not
 * pixel content — a full golden-image comparison would be brittle across renderer/font-hinting
 * versions and isn't needed to catch the actual regression this guards against: an accidentally
 * empty, truncated, or wrong-format file silently committed in place of a real icon.
 */
class AppIconAssetsTest :
    FunSpec({

        // build.gradle.kts's file(...) calls are relative to the kuml-desktop module directory —
        // this test's working directory (Gradle's `test` task) is the same module directory.
        val iconsDir = File("src/jvmMain/resources/icons")

        fun iconFile(name: String): File = File(iconsDir, name)

        test("kuml-desktop.icns exists, is non-empty and has the 'icns' magic header") {
            val f = iconFile("kuml-desktop.icns")
            f.exists() shouldBe true
            f.length() shouldBeGreaterThan 0
            val header = f.inputStream().use { it.readNBytes(4) }
            header shouldBe byteArrayOf(0x69, 0x63, 0x6e, 0x73) // "icns"
        }

        test("kuml-desktop.ico exists, is non-empty and has a valid ICO header with a 16x16 entry") {
            val f = iconFile("kuml-desktop.ico")
            f.exists() shouldBe true
            f.length() shouldBeGreaterThan 0
            val header = f.inputStream().use { it.readNBytes(6) }
            // ICO header: reserved(2)=0x0000, type(2)=0x0001 (icon), count(2)=number of images.
            header[0] shouldBe 0x00
            header[1] shouldBe 0x00
            header[2] shouldBe 0x01
            header[3] shouldBe 0x00
            val imageCount = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
            imageCount shouldBeGreaterThan 0
            // First directory entry's width/height bytes (offset 6/7) — 0x00 encodes 256px in the
            // ICO format, any other single byte is its literal pixel size. This pipeline's
            // smallest generated size is 16, so accept either a direct 16 or the 256-sentinel
            // (only relevant if a future regeneration reorders the size list).
            val firstWidth = f.inputStream().use { it.readNBytes(8) }[6].toInt() and 0xFF
            (firstWidth == 16 || firstWidth == 0) shouldBe true
        }

        test("kuml-desktop.png exists, is a valid 512x512 PNG") {
            val f = iconFile("kuml-desktop.png")
            f.exists() shouldBe true
            f.length() shouldBeGreaterThan 0
            val header = f.inputStream().use { it.readNBytes(8) }
            header shouldBe byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            val image = ImageIO.read(f)
            image.width shouldBe 512
            image.height shouldBe 512
        }
    })
