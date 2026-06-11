package dev.kuml.widget.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.SizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.uml.UmlStateMachine
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.swing.JSVGCanvas
import org.apache.batik.util.XMLResourceDescriptor
import java.io.StringReader

/**
 * Computes an ELK layout for the given [UmlStateMachine] model.
 *
 * Wraps the state machine in a synthetic [KumlDiagram] with [DiagramType.STATE],
 * runs [UmlLayoutBridge.toLayoutGraph] and then [ElkLayoutEngine.layout].
 */
internal fun computeLayout(model: UmlStateMachine): LayoutResult {
    val diagram = KumlDiagram(
        name = model.name,
        type = DiagramType.STATE,
        elements = listOf(model),
        id = model.id,
    )
    val graph = UmlLayoutBridge.toLayoutGraph(diagram, SizeProvider.constant(120f, 60f))
    val engine = ElkLayoutEngine()
    return engine.layout(graph, LayoutHints.DEFAULT)
}

/**
 * Renders the given SVG string to a Batik [JSVGCanvas] via Compose Desktop's [SwingPanel].
 *
 * The SVG is re-rendered whenever [svgString] changes.
 *
 * @param svgString the SVG content to display.
 * @param modifier optional [Modifier].
 */
@Composable
internal fun DiagramPanel(
    svgString: String,
    modifier: Modifier = Modifier,
) {
    val canvas = remember { JSVGCanvas() }
    var lastSvg by remember { mutableStateOf("") }

    LaunchedEffect(svgString) {
        if (svgString != lastSvg && svgString.isNotBlank()) {
            lastSvg = svgString
            try {
                val parser = XMLResourceDescriptor.getXMLParserClassName()
                val factory = SAXSVGDocumentFactory(parser)
                val doc = factory.createSVGDocument(
                    "https://kuml.dev/widget",
                    StringReader(svgString),
                )
                canvas.setSVGDocument(doc)
            } catch (_: Exception) {
                // Ignore parse errors — keep showing the previous document
            }
        }
    }

    SwingPanel(
        factory = { canvas },
        modifier = modifier,
    )
}

/**
 * Produces an SVG string for the given [model] with the given [highlightIds] using
 * the pre-computed [layoutResult].
 */
internal fun renderStateMachineSvg(
    model: UmlStateMachine,
    layoutResult: LayoutResult,
    highlightIds: Set<String>,
): String {
    val diagram = KumlDiagram(
        name = model.name,
        type = DiagramType.STATE,
        elements = listOf(model),
        id = model.id,
    )
    val options = SvgRenderOptions(
        highlightVertexIds = highlightIds,
    )
    return KumlSvgRenderer.toSvg(
        diagram = diagram,
        layoutResult = layoutResult,
        theme = PlainTheme(),
        options = options,
    )
}
