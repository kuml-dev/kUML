package dev.kuml.desktop.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.batik.swing.JSVGCanvas
import org.w3c.dom.svg.SVGDocument
import java.awt.geom.AffineTransform
import javax.swing.SwingUtilities

/** Zoom step for the preview's Zoom In / Zoom Out buttons (P4, design review). */
private const val ZOOM_STEP = 1.25

/**
 * Vorschau-Pane: JSVGCanvas via SwingPanel plus a zoom/fit control strip (P4, design review —
 * parity with the obsidian-kuml plugin's zoom/pan/download toolbar).
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
            val doc: SVGDocument? =
                withContext(Dispatchers.IO) {
                    parseSvg(state.lastSvg)
                }
            if (doc != null) {
                SwingUtilities.invokeLater { canvas.setSVGDocument(doc) }
            }
        }
    }

    Column(modifier = modifier.testTag("kuml-preview")) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconButton(onClick = { zoomCanvas(canvas, ZOOM_STEP) }) { Text("+") }
            IconButton(onClick = { zoomCanvas(canvas, 1.0 / ZOOM_STEP) }) { Text("–") }
            IconButton(onClick = { canvas.renderingTransform = AffineTransform() }) { Text("100%") }
            IconButton(onClick = { canvas.resetRenderingTransform() }) { Text("Fit") }
        }
        SwingPanel(
            factory = { canvas },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Zooms [canvas] by [factor] around its own viewport centre. */
private fun zoomCanvas(
    canvas: JSVGCanvas,
    factor: Double,
) {
    val width = canvas.width.takeIf { it > 0 } ?: 400
    val height = canvas.height.takeIf { it > 0 } ?: 300
    val current = canvas.renderingTransform ?: AffineTransform()
    canvas.renderingTransform = zoomedTransform(current, factor, width / 2.0, height / 2.0)
}

/**
 * Computes a new rendering transform that zooms [current] by [factor] around
 * ([centerX], [centerY]). Extracted as a pure function so it is unit-testable without a
 * live Batik/Swing canvas — [AffineTransform] is a plain `java.awt.geom` value type.
 */
internal fun zoomedTransform(
    current: AffineTransform,
    factor: Double,
    centerX: Double,
    centerY: Double,
): AffineTransform {
    val pivot = AffineTransform()
    pivot.translate(centerX, centerY)
    pivot.scale(factor, factor)
    pivot.translate(-centerX, -centerY)
    pivot.concatenate(current)
    return pivot
}
