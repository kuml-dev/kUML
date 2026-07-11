package dev.kuml.desktop.preview

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.svg.SVGDocument
import java.io.StringReader

/**
 * Parses an SVG string into a Batik [SVGDocument], or `null` on any parse failure.
 *
 * Shared by [PreviewPane] (single-file editor) and the Knowledge Workspace viewer's
 * `WorkspacePreviewPane` (V3.6.4) — factored out here to avoid duplicating the
 * ~6-line Batik wiring (see CLAUDE.md risk note: "parseSvg reuse").
 */
internal fun parseSvg(svgString: String): SVGDocument? =
    try {
        val parser = XMLResourceDescriptor.getXMLParserClassName()
        val factory = SAXSVGDocumentFactory(parser)
        factory.createSVGDocument("https://kuml.dev/desktop", StringReader(svgString))
    } catch (_: Exception) {
        null
    }
