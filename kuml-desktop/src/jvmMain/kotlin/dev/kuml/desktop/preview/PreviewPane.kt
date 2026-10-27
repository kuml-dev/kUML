package dev.kuml.desktop.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.AppState
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.simulation.SimulationBar
import dev.kuml.desktop.simulation.SimulationSidePanel
import dev.kuml.desktop.simulation.SimulationTracePane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.batik.bridge.UpdateManagerAdapter
import org.apache.batik.bridge.UpdateManagerEvent
import org.apache.batik.swing.svg.JSVGComponent
import java.awt.geom.AffineTransform
import javax.swing.SwingUtilities

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
 *
 * V3.x (Live-Simulation) — while [AppState.simulation] is active, the canvas shows the
 * session's one-time-rendered `baseSvg` instead of [AppState.lastSvg], and highlight rings are
 * flipped visible/hidden per step via [SimulationHighlightPatcher] (a live DOM patch through
 * Batik's [org.apache.batik.bridge.UpdateManager]) rather than a fresh `setSVGDocument` per
 * step — the latter would reset Batik's `renderingTransform`, throwing away the user's zoom/pan.
 * [SimulationBar] sits ABOVE the `SwingPanel`, [SimulationTracePane] to its right — both plain
 * Compose, never overlaid on top of the heavyweight canvas.
 */
@Composable
fun PreviewPane(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    val canvas = remember { createPreviewCanvas() }
    val patcher = remember { SimulationHighlightPatcher() }
    var documentReady by remember { mutableStateOf(false) }
    val strings = Strings.forLanguage(state.language)
    val session = state.simulation

    // Genau EIN Dokument pro Sitzung (Spez. C12): während einer laufenden Simulation ist
    // effectiveSvg konstant (baseSvg), spätere state.lastSvg-Renders (Editor weiter getippt)
    // lassen das Preview in Ruhe (Spez. D18 — der Nutzer sieht stattdessen das Veraltet-Banner).
    val effectiveSvg = session?.baseSvg ?: state.lastSvg

    DisposableEffect(canvas) {
        val listener =
            object : UpdateManagerAdapter() {
                override fun managerStarted(e: UpdateManagerEvent) {
                    SwingUtilities.invokeLater { documentReady = true }
                }

                override fun managerStopped(e: UpdateManagerEvent) {
                    SwingUtilities.invokeLater { documentReady = false }
                }
            }
        canvas.addUpdateManagerListener(listener)
        onDispose { canvas.removeUpdateManagerListener(listener) }
    }

    LaunchedEffect(effectiveSvg, session != null) {
        if (effectiveSvg.isBlank()) return@LaunchedEffect
        val doc = withContext(Dispatchers.IO) { parseSvg(effectiveSvg) } ?: return@LaunchedEffect
        SwingUtilities.invokeLater {
            documentReady = false
            patcher.resetBaseline()
            // Batik builds NO UpdateManager at all for a static (AUTODETECT + script-free SVG)
            // document — every highlight-ring DOM patch would silently fail without this. Only
            // dynamic during a session, back to AUTODETECT afterwards: minimizes the time
            // Batik's script/event machinery is armed at all (see SimulationHighlightPatcher KDoc).
            canvas.setDocumentState(if (session != null) JSVGComponent.ALWAYS_DYNAMIC else JSVGComponent.AUTODETECT)
            canvas.setSVGDocument(doc)
        }
    }

    val highlights = session?.activeVertexIds() ?: emptySet()
    LaunchedEffect(session, documentReady, highlights) {
        if (session != null && documentReady) patcher.apply(canvas = canvas, target = highlights)
    }

    // The inspector's selected transition: a hidden overlay path per edge is flipped visible
    // (same live DOM patch as the rings, so zoom and pan survive).
    val selectedEdge = session?.selectedTransitionId
    LaunchedEffect(session, documentReady, selectedEdge) {
        if (session != null && documentReady) patcher.showEdge(canvas = canvas, transitionId = selectedEdge)
    }

    Column(modifier = modifier.testTag("kuml-preview")) {
        ZoomToolbar(canvas = canvas, strings = strings)
        if (session != null) {
            SimulationBar(state = state, session = session, strings = strings)
        }
        Row(Modifier.fillMaxSize()) {
            SwingPanel(
                factory = { canvas },
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            if (session != null) {
                if (session.inspectorVisible) {
                    SimulationSidePanel(session = session, strings = strings, modifier = Modifier.fillMaxHeight())
                } else {
                    SimulationTracePane(
                        session = session,
                        strings = strings,
                        modifier = Modifier.width(260.dp).fillMaxHeight(),
                    )
                }
            }
        }
    }
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
