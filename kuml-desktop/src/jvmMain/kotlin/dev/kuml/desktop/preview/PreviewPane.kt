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
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.batik.bridge.UpdateManagerAdapter
import org.apache.batik.bridge.UpdateManagerEvent
import org.apache.batik.swing.svg.JSVGComponent
import java.awt.geom.AffineTransform
import javax.swing.SwingUtilities

private val logger = KotlinLogging.logger {}

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
    /** Save-as-image handler for the toolbar's SVG/PNG buttons; `null` keeps them disabled. */
    onExport: ((ImageFormat) -> Unit)? = null,
) {
    var batikFailureSerial by remember { mutableIntStateOf(0) }
    val canvas =
        remember {
            createPreviewCanvas(onDocumentFailed = { SwingUtilities.invokeLater { batikFailureSerial++ } })
        }
    val patcher = remember { SimulationHighlightPatcher() }
    var documentReady by remember { mutableStateOf(false) }
    val strings = Strings.forLanguage(state.language)
    val session = state.simulation

    // What the canvas currently shows and which base SVG Batik failed to build/render (never retried).
    var choice by remember { mutableStateOf<PreviewChoice>(PreviewChoice.Empty) }
    var failedBase by remember { mutableStateOf<String?>(null) }
    // (svg, dynamic) of the document currently loaded into the canvas; avoids pointless re-swaps.
    val loaded = remember { arrayOfNulls<Pair<String, Boolean>>(1) }

    // Genau EIN Dokument pro Sitzung (Spez. C12): während einer laufenden Simulation ist das Dokument
    // konstant (baseSvg), spätere state.lastSvg-Renders (Editor weiter getippt) lassen das Preview in Ruhe
    // (Spez. D18 — der Nutzer sieht stattdessen das Veraltet-Banner). Nur wenn baseSvg nicht anzeigbar
    // ist, fällt die Vorschau sichtbar auf den normalen Render zurück (Nacharbeit 24).
    val baseSvgNow = session?.baseSvg
    val effectiveSvg = choice.svgOrNull() ?: state.lastSvg

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

    // A Batik build/render failure of the session's base SVG is reported asynchronously (never as an
    // exception): remember it so the decision below falls back to the plain render.
    LaunchedEffect(batikFailureSerial) {
        val c = choice
        if (batikFailureSerial > 0 && c is PreviewChoice.Simulation) {
            logger.warn { "preview: Batik failed to show the simulation base SVG; falling back to the plain render" }
            failedBase = c.svg
        }
    }

    LaunchedEffect(session, baseSvgNow, state.lastSvg, failedBase) {
        val sessionActive = session != null
        val current = choice
        // A running simulation keeps its frozen document; later editor renders do not touch it.
        if (sessionActive && current is PreviewChoice.Simulation && current.svg == baseSvgNow && failedBase != baseSvgNow) {
            return@LaunchedEffect
        }
        val lastSvg = state.lastSvg
        val baseDoc =
            if (sessionActive && !baseSvgNow.isNullOrBlank() && failedBase != baseSvgNow) {
                withContext(Dispatchers.IO) { parseSvg(baseSvgNow) }
            } else {
                null
            }
        val needLast = !sessionActive || baseDoc == null
        val lastDoc = if (needLast && lastSvg.isNotBlank()) withContext(Dispatchers.IO) { parseSvg(lastSvg) } else null
        val next =
            previewDocumentChoice(
                sessionActive = sessionActive,
                baseSvg = baseSvgNow,
                baseParsed = baseDoc != null,
                lastSvg = lastSvg,
                lastParsed = lastDoc != null,
            )
        logger.debug {
            "preview: choice=${next::class.simpleName} session=$sessionActive baseParsed=${baseDoc != null} " +
                "lastParsed=${lastDoc != null} baseLen=${baseSvgNow?.length ?: 0} lastLen=${lastSvg.length}"
        }
        state.simulationPreviewFallback = next is PreviewChoice.FallbackStatic
        val doc =
            when (next) {
                is PreviewChoice.Simulation -> baseDoc
                is PreviewChoice.FallbackStatic, is PreviewChoice.Normal -> lastDoc
                PreviewChoice.Empty -> null
            }
        if (doc == null) {
            if (sessionActive) {
                // Neither document can be shown: end the session with a visible reason instead of an empty area.
                logger.warn { "preview: no displayable document for the active simulation; ending the session" }
                choice = PreviewChoice.Empty
                state.simulation?.close()
                state.simulation = null
                state.statusNotice = strings.simPreviewUnavailable
                state.statusNoticeSerial++
            }
            return@LaunchedEffect
        }
        choice = next
        val svg = next.svgOrNull() ?: return@LaunchedEffect
        val dynamic = next is PreviewChoice.Simulation
        if (loaded[0] == svg to dynamic) return@LaunchedEffect
        loaded[0] = svg to dynamic
        SwingUtilities.invokeLater {
            documentReady = false
            patcher.resetBaseline()
            // Batik builds NO UpdateManager at all for a static (AUTODETECT + script-free SVG)
            // document — every highlight-ring DOM patch would silently fail without this. Only
            // dynamic during a session, back to AUTODETECT afterwards: minimizes the time
            // Batik's script/event machinery is armed at all (see SimulationHighlightPatcher KDoc).
            canvas.setDocumentState(if (dynamic) JSVGComponent.ALWAYS_DYNAMIC else JSVGComponent.AUTODETECT)
            canvas.setSVGDocument(doc)
        }
    }

    val simulationShown = choice is PreviewChoice.Simulation
    val highlights = session?.activeVertexIds() ?: emptySet()
    LaunchedEffect(session, documentReady, highlights, simulationShown) {
        if (session != null && simulationShown && documentReady) patcher.apply(canvas = canvas, target = highlights)
    }

    // The inspector's selected transition: a hidden overlay path per edge is flipped visible
    // (same live DOM patch as the rings, so zoom and pan survive).
    val selectedEdge = session?.selectedTransitionId
    LaunchedEffect(session, documentReady, selectedEdge, simulationShown) {
        if (session != null && simulationShown && documentReady) patcher.showEdge(canvas = canvas, transitionId = selectedEdge)
    }

    Column(modifier = modifier.testTag("kuml-preview")) {
        ZoomToolbar(
            canvas = canvas,
            strings = strings,
            exportSvg = shownSvg(workspaceMode = false, docSvg = null, lastSvg = effectiveSvg),
            onExport = onExport,
        )
        if (session != null) {
            SimulationBar(
                state = state,
                session = session,
                strings = strings,
                fallbackHint = choice is PreviewChoice.FallbackStatic,
            )
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
