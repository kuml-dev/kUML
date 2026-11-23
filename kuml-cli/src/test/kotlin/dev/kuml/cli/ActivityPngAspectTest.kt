package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs

/** PAL Welle D (Punkt 11): the PNG of an activity must have the aspect ratio of the SVG viewBox. */
class ActivityPngAspectTest :
    FunSpec({
        test("activity PNG aspect ratio equals the SVG viewBox aspect ratio") {
            val fixture = File("src/test/resources/act-png-aspect.kuml.kts")
            val dir = Files.createTempDirectory("kuml-act-aspect")
            val svgFile = dir.resolve("a.svg")
            val pngFile = dir.resolve("a.png")
            RenderPipeline.run(input = fixture, output = svgFile, format = "svg", width = 1024, themeName = "kuml")
            RenderPipeline.run(input = fixture, output = pngFile, format = "png", width = 1024, themeName = "kuml")
            val vb =
                Regex("""viewBox="([^"]+)"""")
                    .find(svgFile.toFile().readText())!!
                    .groupValues[1]
                    .trim()
                    .split(Regex("\\s+"))
            val svgRatio = vb[2].toDouble() / vb[3].toDouble()
            val img = ImageIO.read(pngFile.toFile())
            val pngRatio = img.width.toDouble() / img.height.toDouble()
            abs(pngRatio - svgRatio) shouldBeLessThan 0.01
            dir.toFile().deleteRecursively()
        }
    })
