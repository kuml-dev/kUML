package dev.kuml.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.kuml.desktop.io.AppSettings
import dev.kuml.desktop.io.RecentFiles
import java.io.File

/**
 * Zentraler Compose-State-Holder für die kUML Desktop App.
 *
 * Hält Script-Inhalt, Render-Ergebnis, aktives Theme/Sprache und Fehlerstatus.
 * V3.0.12 ergänzt persistente AppSettings (Datei-IO, Recent-Files, Window-Geometry).
 */
class AppState(initialSettings: AppSettings = AppSettings.DEFAULT) {
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

    /**
     * Lädt Dateiinhalt in den Editor und aktualisiert Metadaten.
     * Setzt isDirty=false, aktualisiert currentFile, lastDir und recentFiles.
     */
    fun loadFrom(file: File, content: String) {
        script = content
        currentFile = file
        isDirty = false
        lastDir = file.parentFile?.absolutePath
        val updated = RecentFiles.add(recentFiles.toList(), file.absolutePath)
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
        val updated = RecentFiles.add(recentFiles.toList(), file.absolutePath)
        recentFiles.clear()
        recentFiles.addAll(updated)
    }

    /** Serialisiert den aktuellen State in persistierbare AppSettings. */
    fun toSettings(): AppSettings = AppSettings(
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
    )

    companion object {
        val WELCOME_SCRIPT: String = """
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
