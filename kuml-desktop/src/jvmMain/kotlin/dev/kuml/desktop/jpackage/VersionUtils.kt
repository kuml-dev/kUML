package dev.kuml.desktop.jpackage

/**
 * V3.0.14 — Konvertiert eine kUML-Versionszeichenkette in ein jpackage-kompatibles Format.
 *
 * jpackage (und macOS CFBundleShortVersionString) erfordern, dass das erste Versionssegment ≥ 1 ist.
 * kUML-Versionen starten mit "0." (z.B. "0.11.0"), daher wird das führende "0." entfernt
 * und das Schema zu MINOR.PATCH.0 transformiert.
 *
 * Beispiele:
 * - "0.11.0" → "11.0.0"
 * - "0.3.5"  → "3.5.0"
 * - "1.0.0"  → "1.0.0" (unverändert)
 * - "2.1.3"  → "2.1.3" (unverändert)
 */
public fun versionForJpackage(projectVersion: String): String {
    return if (projectVersion.startsWith("0.")) {
        val rest = projectVersion.removePrefix("0.")
        val parts = rest.split(".")
        val minor = parts.getOrElse(0) { "0" }
        val patch = parts.getOrElse(1) { "0" }
        "$minor.$patch.0"
    } else {
        projectVersion
    }
}
