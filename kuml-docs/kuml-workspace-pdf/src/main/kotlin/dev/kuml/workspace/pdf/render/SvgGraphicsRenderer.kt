package dev.kuml.workspace.pdf.render

import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.ExternalResourceSecurity
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.NoLoadExternalResourceSecurity
import org.apache.batik.bridge.NoLoadScriptSecurity
import org.apache.batik.bridge.ScriptSecurity
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.util.ParsedURL
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.awt.geom.Dimension2D
import java.io.ByteArrayInputStream
import java.io.IOException

/** A diagram drawn into a PDF form XObject, with its natural size in PDF points (1 SVG px = 1 pt). */
internal class DrawnSvg(
    val width: Float,
    val height: Float,
    val form: PDFormXObject,
)

/** Raised for an SVG that cannot be turned into PDF vector graphics. The message never contains SVG content. */
internal class SvgRenderException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Draws an SVG document into a PDF as vector graphics: SVG -> Batik GVT -> Graphics2D -> PDFBox.
 *
 * Text inside diagrams is converted to glyph outlines (the PdfBoxGraphics2D default), so the diagram
 * looks identical regardless of installed fonts and no font is re-embedded per diagram.
 *
 * Hardening: scripts and every external resource are disabled, validation is off, and documents that
 * declare a DOCTYPE or ENTITY are rejected before parsing (XXE / entity expansion / SSRF).
 */
internal object SvgGraphicsRenderer {
    private const val DUMMY_URI = "http://kuml.invalid/diagram.svg"
    private val SIZE_ATTR = Regex("""^\s*([0-9]*\.?[0-9]+)\s*(px|pt)?\s*$""")

    /** Rejects documents that declare a DOCTYPE or ENTITY (never produced by kUML renderers). */
    private fun guard(svg: String) {
        val head = svg.take(4096)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) {
            throw SvgRenderException(message = "SVG declares a DOCTYPE or ENTITY, which is not allowed")
        }
        if (svg.contains("<!ENTITY", ignoreCase = true)) {
            throw SvgRenderException(message = "SVG declares an ENTITY, which is not allowed")
        }
    }

    private fun secureUserAgent(size: Dimension2D): UserAgentAdapter =
        object : UserAgentAdapter() {
            override fun getScriptSecurity(
                scriptType: String?,
                scriptPURL: ParsedURL?,
                docPURL: ParsedURL?,
            ): ScriptSecurity = NoLoadScriptSecurity(scriptType)

            override fun getExternalResourceSecurity(
                resourceURL: ParsedURL?,
                docURL: ParsedURL?,
            ): ExternalResourceSecurity = NoLoadExternalResourceSecurity()

            override fun isXMLParserValidating(): Boolean = false

            override fun getViewportSize(): Dimension2D = size
        }

    private fun parseDocument(svg: String) =
        SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName()).let { factory ->
            factory.isValidating = false
            factory.createSVGDocument(DUMMY_URI, ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
        }

    private fun numeric(attr: String?): Float? =
        attr?.let {
            SIZE_ATTR
                .matchEntire(it)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
        }

    /** The natural size in points, taken from `width`/`height` or, failing that, the `viewBox`. */
    private fun sizeOf(root: org.w3c.dom.Element): Pair<Float, Float> {
        val w = numeric(root.getAttribute("width"))
        val h = numeric(root.getAttribute("height"))
        if (w != null && h != null && w > 0 && h > 0) return w to h
        val box = root.getAttribute("viewBox").split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }
        if (box.size == 4 && box[2] > 0 && box[3] > 0) return box[2] to box[3]
        throw SvgRenderException(message = "SVG has no usable width/height or viewBox")
    }

    /** Natural size of the SVG in points without drawing it. */
    fun naturalSize(svg: String): Pair<Float, Float> {
        guard(svg)
        return try {
            sizeOf(parseDocument(svg).documentElement)
        } catch (e: SvgRenderException) {
            throw e
        } catch (e: IOException) {
            throw SvgRenderException(message = "SVG could not be parsed", cause = e)
        } catch (e: RuntimeException) {
            throw SvgRenderException(message = "SVG could not be parsed", cause = e)
        }
    }

    /** Draws [svg] into a new form XObject of [doc]. */
    fun draw(
        doc: PDDocument,
        svg: String,
    ): DrawnSvg {
        guard(svg)
        PdfFontSet.registerAwtFontsOnce()
        try {
            val svgDoc = parseDocument(svg)
            val (w, h) = sizeOf(svgDoc.documentElement)
            val size =
                object : Dimension2D() {
                    override fun getWidth(): Double = w.toDouble()

                    override fun getHeight(): Double = h.toDouble()

                    override fun setSize(
                        width: Double,
                        height: Double,
                    ) = Unit
                }
            val ctx = BridgeContext(secureUserAgent(size))
            val gvt = GVTBuilder().build(ctx, svgDoc)
            val g = PdfBoxGraphics2D(doc, w, h)
            try {
                gvt.paint(g)
            } finally {
                g.dispose()
            }
            return DrawnSvg(width = w, height = h, form = g.xFormObject)
        } catch (e: SvgRenderException) {
            throw e
        } catch (e: IOException) {
            throw SvgRenderException(message = "SVG could not be drawn", cause = e)
        } catch (e: RuntimeException) {
            throw SvgRenderException(message = "SVG could not be drawn", cause = e)
        }
    }
}
