package dev.kuml.desktop.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.kuml.desktop.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.swing.JSVGCanvas
import org.apache.batik.util.XMLResourceDescriptor
import org.w3c.dom.svg.SVGDocument
import java.io.StringReader
import javax.swing.SwingUtilities

/**
 * Vorschau-Pane: nur JSVGCanvas via SwingPanel.
 *
 * Fehler und Lade-Status werden im StatusBar von MainWindow angezeigt —
 * NICHT hier als Compose-Overlay, weil SwingPanel (heavyweight AWT) immer
 * über Compose-Layern (lightweight) malt und Overlays so unsichtbar wären.
 */
@Composable
fun PreviewPane(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    val canvas = remember { JSVGCanvas() }

    LaunchedEffect(state.lastSvg) {
        if (state.lastSvg.isNotBlank()) {
            val doc: SVGDocument? = withContext(Dispatchers.IO) {
                parseSvg(state.lastSvg)
            }
            if (doc != null) {
                SwingUtilities.invokeLater { canvas.setSVGDocument(doc) }
            }
        }
    }

    SwingPanel(
        factory = { canvas },
        modifier = modifier.fillMaxSize(),
    )
}

private fun parseSvg(svgString: String): SVGDocument? =
    try {
        val parser = XMLResourceDescriptor.getXMLParserClassName()
        val factory = SAXSVGDocumentFactory(parser)
        factory.createSVGDocument("https://kuml.dev/desktop", StringReader(svgString))
    } catch (_: Exception) {
        null
    }
