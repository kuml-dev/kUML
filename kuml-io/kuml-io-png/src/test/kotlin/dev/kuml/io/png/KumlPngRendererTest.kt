package dev.kuml.io.png

import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.renderer.theme.core.KumlColor
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlClass
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Minimales Diagramm + Layout für Tests — eine UML-Klasse, 200×100 px Canvas. */
private fun minimalDiagram(): Pair<KumlDiagram, LayoutResult> {
    val diagram =
        KumlDiagram(
            name = "Test",
            elements = listOf(UmlClass(id = "cls1", name = "Box")),
        )
    val layout =
        LayoutResult(
            engineId = LayoutEngineId("test"),
            seed = 1L,
            canvas = Size(200f, 100f),
            nodes =
                mapOf(
                    NodeId("cls1") to NodeLayout(bounds = Rect(Point(10f, 10f), Size(100f, 60f))),
                ),
            edges = emptyMap(),
            groups = emptyMap(),
        )
    return diagram to layout
}

private fun readImage(bytes: ByteArray): BufferedImage =
    ImageIO.read(ByteArrayInputStream(bytes))
        ?: error("ImageIO konnte die Bytes nicht lesen — kein gültiges PNG")

class KumlPngRendererTest :
    FunSpec({

        test("toPng returns valid PNG bytes (magic header)") {
            val (diagram, layout) = minimalDiagram()
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
            val bytes = KumlPngRenderer.toPng(svg)

            SampleOutput.write("uml/class-box-default.png", bytes)

            // PNG-Magic-Header: 89 50 4E 47 0D 0A 1A 0A
            val expected =
                listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                    .map { it.toByte() }
            bytes.take(8) shouldBe expected
        }

        test("toPng respects widthPx option") {
            val (diagram, layout) = minimalDiagram()
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
            val targetWidth = 512
            val bytes = KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = targetWidth))

            SampleOutput.write("uml/class-box-512px.png", bytes)

            val image = readImage(bytes)
            image.width shouldBe targetWidth
        }

        test("toPng applies background color") {
            val (diagram, layout) = minimalDiagram()
            // paintCanvasBackground=false: kein SVG-eigenes Hintergrund-Rect — sonst überdeckt
            // die weiße SVG-Rect die rote Batik-Hintergrundfarbe und der Test schlägt fehl.
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme(), SvgRenderOptions(paintCanvasBackground = false))
            val bgColor = KumlColor(0xFF0000) // rot
            val bytes =
                KumlPngRenderer.toPng(
                    svg,
                    PngRenderOptions(backgroundColor = bgColor, transparent = false),
                )

            SampleOutput.write("uml/class-box-red-bg.png", bytes)

            val image = readImage(bytes)
            // Pixel (0, 0) ist Eckpixel — garantiert Hintergrund (kein Anti-Aliasing-Rand)
            val pixel = image.getRGB(0, 0)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            r shouldBe 0xFF
            g shouldBe 0x00
            b shouldBe 0x00
        }

        test("toPng with transparent flag produces alpha-channel PNG") {
            val (diagram, layout) = minimalDiagram()
            val svg = KumlSvgRenderer.toSvg(diagram, layout, PlainTheme())
            val bytes = KumlPngRenderer.toPng(svg, PngRenderOptions(transparent = true))

            SampleOutput.write("uml/class-box-transparent.png", bytes)

            val image = readImage(bytes)
            image.colorModel.hasAlpha() shouldBe true
        }

        test("toPng convenience overload matches direct svg path") {
            val (diagram, layout) = minimalDiagram()
            val theme = PlainTheme()
            val options = PngRenderOptions.DEFAULT

            val viaSvg =
                KumlPngRenderer.toPng(
                    KumlSvgRenderer.toSvg(diagram, layout, theme),
                    options,
                )
            val viaConvenience = KumlPngRenderer.toPng(diagram, layout, theme, options)

            SampleOutput.write("uml/class-box-convenience.png", viaConvenience)

            // Beide Pfade müssen byte-identisch sein
            viaConvenience shouldBe viaSvg
        }
    })
