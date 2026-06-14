package dev.kuml.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Zentraler Compose-State-Holder für die kUML Desktop App.
 *
 * Hält Script-Inhalt, Render-Ergebnis, aktives Theme/Sprache und Fehlerstatus.
 * V3.0.12 ergänzt persistente AppSettings (Datei-IO, Recent-Files, Window-Geometry).
 */
class AppState {
    /** Aktueller kUML-Script-Quelltext im Editor. */
    var script by mutableStateOf(WELCOME_SCRIPT)

    /** Zuletzt erfolgreich gerenderter SVG-String; leer wenn noch kein Render. */
    var lastSvg by mutableStateOf("")

    /** Letzter Render-/Compile-Fehler; null wenn kein Fehler. */
    var lastError by mutableStateOf<String?>(null)

    /** Aktives Theme (eines aus ThemeRegistry.names()). */
    var theme by mutableStateOf("plain")

    /** UI-Sprachcode: "de" oder "en". */
    var language by mutableStateOf("en")

    /** Gibt an, ob gerade ein Render-Vorgang läuft (für Spinner-Anzeige). */
    var isRendering by mutableStateOf(false)

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
