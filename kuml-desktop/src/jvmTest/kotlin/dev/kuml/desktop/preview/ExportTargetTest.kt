package dev.kuml.desktop.preview

import dev.kuml.desktop.render.DesktopRenderPipeline
import dev.kuml.desktop.render.DesktopRenderResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private fun svgOf(
    width: String? = null,
    viewBox: String? = null,
): String {
    val attrs = listOfNotNull(width?.let { "width=\"$it\"" }, viewBox?.let { "viewBox=\"$it\"" }).joinToString(" ")
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" $attrs><rect width=\"10\" height=\"10\" fill=\"#123456\"/></svg>"
}

/** Parses [bytes] as XML with every external-entity and DOCTYPE vector switched off (XXE-safe). */
private fun parseSafely(bytes: ByteArray): Document {
    val f = DocumentBuilderFactory.newInstance()
    f.isNamespaceAware = true
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    f.isXIncludeAware = false
    f.isExpandEntityReferences = false
    return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

class ExportTargetTest :
    FunSpec({
        test("exportTarget is null without a diagram") {
            exportTarget(format = ImageFormat.SVG, svg = null, baseName = "x") shouldBe null
            exportTarget(format = ImageFormat.PNG, svg = "  \n", baseName = "x") shouldBe null
        }

        test("file name: base name plus extension, suffixes stripped, forbidden characters replaced, fallback diagram") {
            fun name(base: String?) = exportTarget(format = ImageFormat.SVG, svg = "<svg/>", baseName = base)!!.suggestedFileName
            name("diagram") shouldBe "diagram.svg"
            name("model.kuml.kts") shouldBe "model.svg"
            name("notes.md") shouldBe "notes.svg"
            name("a/b\\c:d*e?f\"g<h>i|j") shouldBe "a_b_c_d_e_f_g_h_i_j.svg"
            name(null) shouldBe "diagram.svg"
            name("") shouldBe "diagram.svg"
            name("   ") shouldBe "diagram.svg"
            name("..") shouldBe "diagram.svg"
            exportTarget(format = ImageFormat.PNG, svg = "<svg/>", baseName = "x")!!.suggestedFileName shouldEndWith ".png"
        }

        test("pngWidthFor: twice the viewBox width, else the width attribute (px and decimals allowed)") {
            pngWidthFor(svgOf(viewBox = "0 0 600 300")) shouldBe 1200
            pngWidthFor(svgOf(viewBox = "0,0,700.5,300")) shouldBe 1401
            pngWidthFor(svgOf(width = "900")) shouldBe 1800
            pngWidthFor(svgOf(width = "900px")) shouldBe 1800
            pngWidthFor(svgOf(width = "512.5")) shouldBe 1025
            // viewBox wins over width
            pngWidthFor(svgOf(width = "900", viewBox = "0 0 500 300")) shouldBe 1000
        }

        test("pngWidthFor: clamped to 800..4096, fallback 1600 when unreadable") {
            pngWidthFor(svgOf(viewBox = "0 0 100 100")) shouldBe 800
            pngWidthFor(svgOf(viewBox = "0 0 400 100")) shouldBe 800
            pngWidthFor(svgOf(viewBox = "0 0 401 100")) shouldBe 802
            pngWidthFor(svgOf(viewBox = "0 0 2048 100")) shouldBe 4096
            pngWidthFor(svgOf(viewBox = "0 0 99999 100")) shouldBe 4096
            pngWidthFor(svgOf(width = "100%")) shouldBe 1600
            pngWidthFor(svgOf(width = "auto")) shouldBe 1600
            pngWidthFor(svgOf(viewBox = "0 0 abc 100")) shouldBe 1600
            pngWidthFor(svgOf(viewBox = "0 0 -5 100")) shouldBe 1600
            pngWidthFor(svgOf(viewBox = "0 0 0 100")) shouldBe 1600
            pngWidthFor(svgOf()) shouldBe 1600
            pngWidthFor("") shouldBe 1600
            pngWidthFor("not even xml <<<") shouldBe 1600
        }

        test("pngWidthFor ignores stroke-width and attributes outside the svg start tag") {
            pngWidthFor("<svg xmlns=\"x\" stroke-width=\"900\"><g width=\"1000\"/></svg>") shouldBe 1600
        }

        test("pngWidthFor stays fast and safe on hostile input") {
            pngWidthFor("<svg " + "a=\"b\" ".repeat(50_000)) shouldBe 1600
            pngWidthFor("<svg" + " ".repeat(1_000_000)) shouldBe 1600
            pngWidthFor("<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg width=\"&x;\"></svg>") shouldBe 1600
        }

        test("shownSvg: single-file mode uses lastSvg, workspace mode only docSvg, blank counts as none") {
            shownSvg(workspaceMode = false, docSvg = "<doc/>", lastSvg = "<last/>") shouldBe "<last/>"
            shownSvg(workspaceMode = true, docSvg = "<doc/>", lastSvg = "<last/>") shouldBe "<doc/>"
            shownSvg(workspaceMode = false, docSvg = null, lastSvg = "") shouldBe null
            shownSvg(workspaceMode = false, docSvg = null, lastSvg = null) shouldBe null
            shownSvg(workspaceMode = true, docSvg = "  ", lastSvg = "<last/>") shouldBe null
        }

        test("a workspace document without a diagram never falls back to the stale single-file svg") {
            shownSvg(workspaceMode = true, docSvg = null, lastSvg = "<svg>old single file</svg>") shouldBe null
        }

        test("singleFileSvg prefers the frozen simulation base svg") {
            singleFileSvg(simulationBaseSvg = "<base/>", lastSvg = "<newer/>") shouldBe "<base/>"
            singleFileSvg(simulationBaseSvg = null, lastSvg = "<newer/>") shouldBe "<newer/>"
        }

        test("SVG bytes are the UTF-8 text and well-formed XML") {
            val svg = svgOf(viewBox = "0 0 600 300").replace("<rect", "<text>Äöü€</text><rect")
            val target = exportTarget(format = ImageFormat.SVG, svg = svg, baseName = "t")!!
            val bytes = exportBytes(target)
            String(bytes, Charsets.UTF_8) shouldBe svg
            parseSafely(bytes).documentElement.tagName shouldBe "svg"
        }

        test("PNG bytes carry the PNG signature and the computed width") {
            val svg = svgOf(viewBox = "0 0 700 350")
            val bytes = exportBytes(exportTarget(format = ImageFormat.PNG, svg = svg, baseName = "t")!!)
            bytes.take(8).map { it.toInt() and 0xFF } shouldBe listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            ImageIO.read(ByteArrayInputStream(bytes)).width shouldBe pngWidthFor(svg)
            pngWidthFor(svg) shouldBe 1400
        }

        test("a real rendered kUML diagram exports to well-formed SVG and a PNG of the expected width") {
            val script =
                """
                classDiagram(name = "Export") {
                    val a = classOf(name = "Foo") { attribute(name = "id", type = "Long") }
                    val b = classOf(name = "Bar") { }
                }
                """.trimIndent()
            val result = DesktopRenderPipeline.render(script = script, themeName = "plain")
            result.shouldBeInstanceOf<DesktopRenderResult.Svg>()
            val svg = result.svg
            parseSafely(exportBytes(exportTarget(format = ImageFormat.SVG, svg = svg, baseName = "r")!!))
            val png = exportBytes(exportTarget(format = ImageFormat.PNG, svg = svg, baseName = "r")!!)
            ImageIO.read(ByteArrayInputStream(png)).width shouldBe pngWidthFor(svg)
        }

        test("an unusable svg makes PNG export fail loudly rather than write garbage") {
            val target = exportTarget(format = ImageFormat.PNG, svg = "this is not svg", baseName = "bad")!!
            val failure = runCatching { exportBytes(target) }.exceptionOrNull()
            (failure != null) shouldBe true
        }
    })
