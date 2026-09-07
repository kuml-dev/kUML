package dev.kuml.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.kuml.desktop.io.AppSettings
import dev.kuml.desktop.io.RecentFiles
import dev.kuml.desktop.simulation.SimulationSession
import dev.kuml.desktop.workspace.OpenWorkspace
import java.io.File

/**
 * Zentraler Compose-State-Holder für die kUML Desktop App.
 *
 * Hält Script-Inhalt, Render-Ergebnis, aktives Theme/Sprache und Fehlerstatus.
 * V3.0.12 ergänzt persistente AppSettings (Datei-IO, Recent-Files, Window-Geometry).
 */
class AppState(
    initialSettings: AppSettings = AppSettings.DEFAULT,
) {
    /** Aktueller kUML-Script-Quelltext im Editor. */
    var script by mutableStateOf(WELCOME_SCRIPT)

    /** Zuletzt erfolgreich gerenderter SVG-String; leer wenn noch kein Render. */
    var lastSvg by mutableStateOf("")

    /** Letzter Render-/Compile-Fehler; null wenn kein Fehler. */
    var lastError by mutableStateOf<String?>(null)

    /** Aktives Theme (eines aus ThemeRegistry.names()). */
    var theme by mutableStateOf(initialSettings.theme)

    /** UI-Sprachcode: "de" oder "en". */
    var language by mutableStateOf(initialSettings.language)

    /** Gibt an, ob gerade ein Render-Vorgang läuft (für Spinner-Anzeige). */
    var isRendering by mutableStateOf(false)

    /** Aktuell geöffnete Datei; null wenn noch nicht gespeichert/geöffnet. */
    var currentFile by mutableStateOf<File?>(null)

    /** Gibt an, ob ungespeicherte Änderungen vorliegen. */
    var isDirty by mutableStateOf(false)

    /** Liste der zuletzt geöffneten Dateien (Pfade). */
    val recentFiles: SnapshotStateList<String> = mutableStateListOf(*initialSettings.recentFiles.toTypedArray())

    /** Zuletzt verwendetes Verzeichnis für Datei-Dialoge. */
    var lastDir by mutableStateOf<String?>(initialSettings.lastDir)

    /** Fensterbreite in Pixeln. */
    var windowWidth by mutableStateOf(initialSettings.windowWidth)

    /** Fensterhöhe in Pixeln. */
    var windowHeight by mutableStateOf(initialSettings.windowHeight)

    /** Fenster-X-Position; -1 = Plattform-Default. */
    var windowX by mutableStateOf(initialSettings.windowX)

    /** Fenster-Y-Position; -1 = Plattform-Default. */
    var windowY by mutableStateOf(initialSettings.windowY)

    // V3.0.24 — AI panel

    /** Gibt an, ob der AI-Assistant-Panel offen ist. */
    var aiPanelOpen by mutableStateOf(initialSettings.aiPanelOpen)

    /** Breite des AI-Panels in Pixeln. */
    var aiPanelWidthPx by mutableStateOf(initialSettings.aiPanelWidthPx)

    // V3.6.4 — Knowledge Workspace viewer

    /** Kanonische Pfade der vom Nutzer explizit vertrauten Workspace-Wurzeln. */
    val trustedWorkspaces: SnapshotStateList<String> =
        mutableStateListOf(*initialSettings.trustedWorkspaces.toTypedArray())

    /** Aktuell geöffneter Workspace (Knowledge oder Engineering); null = Single-File-Modus. */
    var openWorkspace by mutableStateOf<OpenWorkspace?>(null)

    // P5 — Ansichtsmodus (Quelltext/Geteilt/Diagramm)

    /** Aktiver Ansichtsmodus zwischen Editor- und Vorschau-Pane. */
    var viewMode by mutableStateOf(
        runCatching { ViewMode.valueOf(initialSettings.viewMode) }.getOrDefault(ViewMode.SPLIT),
    )

    // V3.7.4 — opt-in "Powered by kUML" watermark (View ▸ Wasserzeichen). See
    // AppSettings.showWatermark's KDoc for the default-off rationale.
    var showWatermark by mutableStateOf(initialSettings.showWatermark)

    // P8 — editor find bar. Deliberately transient (NOT persisted in AppSettings/toSettings()
    // below): an open search bar is not session state a user expects to see again after
    // restarting the app, unlike viewMode/showWatermark/theme/language.
    var findBarOpen by mutableStateOf(false)

    // V3.x — Live-Simulation von Zustandsautomaten im Editor. Deliberately NOT persisted (like
    // findBarOpen above): a running simulation is not state a user expects to see resumed after
    // an app restart — the sandboxed runtime/thread pool behind it doesn't survive a restart
    // anyway. `internal` (not public, unlike every other AppState property) because
    // SimulationSession itself is internal to this module — a public var can't expose an
    // internal type. AppState is only ever used from within kuml-desktop, so this costs nothing.
    internal var simulation by mutableStateOf<SimulationSession?>(null)

    /**
     * Review fix — `true` while `MainWindow.startSimulation()`'s script-eval + ELK-layout work
     * (dispatched on `Dispatchers.IO`, see `SimulationSession.start`) is in flight, i.e. the
     * window between the user triggering "Werkzeuge ▸ Simulieren"/Ctrl+R and [simulation]
     * actually being assigned. Without this, the menu item's `enabled` check
     * (`simulation == null && lastDiagramSimulatable`) stayed true for that entire 1-3s window,
     * so a second Ctrl+R (the async start gives no visible feedback) started a SECOND
     * `SimulationSession` — including its own `TimeLimitedGuardEvaluator` cached thread pool —
     * whose result then silently overwrote the first in [simulation] without ever closing it: a
     * `kuml-sandbox-guard-*` thread-pool leak for the JVM's lifetime. Deliberately NOT persisted,
     * same reasoning as [simulation] itself.
     */
    internal var simulationStarting by mutableStateOf(false)

    /** Whether the last successfully rendered diagram type can be simulated (Spez. A3). */
    var lastDiagramSimulatable by mutableStateOf(false)

    /**
     * Lädt Dateiinhalt in den Editor und aktualisiert Metadaten.
     * Setzt isDirty=false, aktualisiert currentFile, lastDir und recentFiles.
     */
    fun loadFrom(
        file: File,
        content: String,
    ) {
        script = content
        currentFile = file
        isDirty = false
        lastDir = file.parentFile?.absolutePath
        val updated = RecentFiles.add(list = recentFiles.toList(), path = file.absolutePath)
        recentFiles.clear()
        recentFiles.addAll(updated)
    }

    /**
     * Markiert den aktuellen Zustand als gespeichert unter der gegebenen Datei.
     * Setzt isDirty=false, aktualisiert currentFile, lastDir und recentFiles.
     */
    fun markSaved(file: File) {
        currentFile = file
        isDirty = false
        lastDir = file.parentFile?.absolutePath
        val updated = RecentFiles.add(list = recentFiles.toList(), path = file.absolutePath)
        recentFiles.clear()
        recentFiles.addAll(updated)
    }

    /** Serialisiert den aktuellen State in persistierbare AppSettings. */
    fun toSettings(): AppSettings =
        AppSettings(
            theme = theme,
            language = language,
            recentFiles = recentFiles.toList(),
            lastDir = lastDir,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            windowX = windowX,
            windowY = windowY,
            aiPanelOpen = aiPanelOpen,
            aiPanelWidthPx = aiPanelWidthPx,
            trustedWorkspaces = trustedWorkspaces.toList(),
            viewMode = viewMode.name,
            showWatermark = showWatermark,
        )

    /** Ansichtsmodus zwischen Editor (Quelltext) und Vorschau (Diagramm). Siehe [viewMode]. */
    enum class ViewMode { SOURCE, SPLIT, DIAGRAM }

    companion object {
        val WELCOME_SCRIPT: String =
            """
import dev.kuml.uml.*

classDiagram(name = "Beispiel") {
    val fahrzeug = classOf(name = "Fahrzeug") {
        attribute(name = "id", type = "Long")
        attribute(name = "kennzeichen", type = "String")
    }
    val motor = classOf(name = "Motor") {
        attribute(name = "leistung", type = "Int")
    }
    association(source = fahrzeug, target = motor)
}
            """.trimIndent()
    }
}
