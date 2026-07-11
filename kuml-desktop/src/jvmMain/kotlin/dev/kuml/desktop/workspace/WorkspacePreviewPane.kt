package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.preview.parseSvg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.batik.swing.JSVGCanvas
import org.w3c.dom.svg.SVGDocument
import javax.swing.SwingUtilities

/**
 * Diagram column of the Knowledge Workspace viewer (V3.6.4).
 *
 * Conditionally mounts the Batik `JSVGCanvas` [SwingPanel] only when [docSvg] is
 * non-null; empty/error/loading states are pure Compose [Text]. This avoids the
 * heavyweight-over-lightweight paint-order problem documented on
 * `dev.kuml.desktop.preview.PreviewPane` (the same reason the single-file editor
 * shows errors in the `StatusBar` rather than as a Compose overlay on the canvas).
 */
@Composable
fun WorkspacePreviewPane(
    docSvg: String?,
    docError: String?,
    isRendering: Boolean,
    hasSelection: Boolean,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    when {
        docSvg != null -> SvgCanvas(svg = docSvg, modifier = modifier)
        docError != null -> MessageBox(text = docError, color = Color(0xFFCC0000), modifier = modifier)
        isRendering -> MessageBox(text = strings.statusRendering, color = Color.Gray, modifier = modifier)
        hasSelection -> MessageBox(text = strings.previewProseEmpty, color = Color.Gray, modifier = modifier)
        else -> MessageBox(text = strings.statusNoDiagram, color = Color.Gray, modifier = modifier)
    }
}

@Composable
private fun SvgCanvas(
    svg: String,
    modifier: Modifier,
) {
    val canvas = remember { JSVGCanvas() }
    LaunchedEffect(svg) {
        val doc: SVGDocument? = withContext(Dispatchers.IO) { parseSvg(svg) }
        if (doc != null) {
            SwingUtilities.invokeLater { canvas.setSVGDocument(doc) }
        }
    }
    SwingPanel(factory = { canvas }, modifier = modifier.fillMaxSize())
}

@Composable
private fun MessageBox(
    text: String,
    color: Color,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = color, modifier = Modifier.padding(16.dp))
    }
}
