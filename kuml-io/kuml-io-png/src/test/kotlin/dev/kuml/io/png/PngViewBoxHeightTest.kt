package dev.kuml.io.png

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** PAL Welle D (Punkt 11): PNG height is derived from the SVG viewBox. */
class PngViewBoxHeightTest :
    FunSpec({
        fun svg(attrs: String) = """<svg xmlns="http://www.w3.org/2000/svg" $attrs><rect width="1" height="1"/></svg>"""

        test("height keeps the viewBox aspect ratio") {
            PngTranscoderImpl.viewBoxHeightPx(svg = svg("""viewBox="0 0 1511.90 947""""), widthPx = 1024) shouldBe 641
        }

        test("viewBox with origin offset and commas") {
            PngTranscoderImpl.viewBoxHeightPx(svg = svg("""viewBox="-10,-5,200,100""""), widthPx = 400) shouldBe 200
        }

        test("missing or invalid viewBox falls back to null") {
            PngTranscoderImpl.viewBoxHeightPx(svg = svg(""), widthPx = 1024) shouldBe null
            PngTranscoderImpl.viewBoxHeightPx(svg = svg("""viewBox="0 0 0 10""""), widthPx = 1024) shouldBe null
            PngTranscoderImpl.viewBoxHeightPx(svg = svg("""viewBox="0 0 NaN 10""""), widthPx = 1024) shouldBe null
            PngTranscoderImpl.viewBoxHeightPx(svg = svg("""viewBox="0 0 10""""), widthPx = 1024) shouldBe null
        }

        test("height is capped") {
            PngTranscoderImpl.viewBoxHeightPx(svg = svg("""viewBox="0 0 1 1000000""""), widthPx = 1024) shouldBe 32_768
        }

        test("rendered PNG matches the viewBox aspect ratio") {
            val png =
                KumlPngRenderer.toPng(
                    svg = svg("""viewBox="0 0 300 100" width="300" height="100""""),
                    options = PngRenderOptions(widthPx = 600),
                )
            val img = javax.imageio.ImageIO.read(png.inputStream())
            img.width shouldBe 600
            img.height shouldBe 200
        }
    })
