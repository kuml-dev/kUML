package dev.kuml.desktop.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.AppState
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.IconTooltipButton
import dev.kuml.desktop.ui.KumlIcons
import dev.kuml.desktop.ui.tooltipBelow
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
 *
 * V3.7.4 (design review P5) — [dev.kuml.desktop.ui.IconTooltipButton]'s `tooltipPlacement`
 * parameter carries `TooltipPlacement` (an experimental Compose Foundation type) into its own
 * public signature, so every caller — not just `IconTooltipButton`'s own body — must opt in,
 * even calls (like the three below) that never name `TooltipPlacement` explicitly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewPane(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    val canvas = remember { JSVGCanvas() }
    val strings = Strings.forLanguage(state.language)

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
        // Design-Review (UI-Team-Session, siehe CLAUDE.md "UI/UX-Design-Team"): '100%' und 'Fit'
        // waren identischer Code (Batik JGVTComponent.resetRenderingTransform() setzt intern ebenfalls
        // nur die AffineTransform-Identität; das eigentliche Fit-to-Viewport läuft separat über eine
        // interne viewingTransform, die bei jedem Resize automatisch neu berechnet wird — unabhängig
        // vom gedrückten Knopf). '100%' wurde entfernt, nicht 'Fit', weil es beschreibt was der Nutzer
        // erwartet statt was der Code tut. Bewusst keine Zoomfaktor-Anzeige in dieser Leiste — es gab
        // nie eine korrekte, und eine neue einzuführen ist eine separate Entscheidung.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            // P2 (design review): "+"/"–" text glyphs replaced with a proper kUML-owned icon
            // family (see KumlIcons.kt) — no material-icons dependency covers this exact set.
            // Each button is wrapped in a TooltipArea (same pattern as WorkspaceTreePane's
            // TypeBadge) so the icon-only affordance still names itself on hover for a11y.
            // V3.7.4 (design review P5): this strip sits at the TOP of the pane -- a
            // tooltip anchored above the button would be clipped at the window edge, so it
            // is anchored BELOW instead (see tooltipBelow's KDoc).
            IconTooltipButton(
                icon = KumlIcons.ZoomIn,
                description = strings.previewZoomIn,
                onClick = { zoomCanvas(canvas = canvas, factor = ZOOM_STEP) },
                tooltipPlacement = tooltipBelow(),
            )
            IconTooltipButton(
                icon = KumlIcons.ZoomOut,
                description = strings.previewZoomOut,
                onClick = { zoomCanvas(canvas = canvas, factor = 1.0 / ZOOM_STEP) },
                tooltipPlacement = tooltipBelow(),
            )
            IconTooltipButton(
                icon = KumlIcons.FitToWindow,
                description = strings.previewFit,
                onClick = { canvas.resetRenderingTransform() },
                tooltipPlacement = tooltipBelow(),
            )
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
    canvas.renderingTransform = zoomedTransform(current = current, factor = factor, centerX = width / 2.0, centerY = height / 2.0)
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
