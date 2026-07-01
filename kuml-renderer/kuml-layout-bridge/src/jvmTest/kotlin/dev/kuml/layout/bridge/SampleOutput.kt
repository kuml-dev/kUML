package dev.kuml.layout.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test-Helper: schreibt visuell überprüfbare Test-Artefakte unter
 * `<module>/build/sample-output/<filename>`.
 *
 * Macht Layout-Resultate, SVG-Renderings und TikZ-Snippets nach jedem
 * Test-Lauf auf der Platte verfügbar — entweder zum Auf-PDF-Bauen
 * (TikZ), zum Browser-Öffnen (SVG) oder zum JSON-Diff (LayoutResult).
 * Die Tests assertieren weiterhin nur über die im Test-Code dokumentierten
 * Invarianten; das Sample-Output ist *nicht* Bestandteil des
 * Test-Verdikts.
 *
 * Jedes Modul, das visuell überprüfbare Ausgaben produziert, hat eine
 * eigene Kopie dieses Helpers (1 File à 30 Zeilen — kein eigenes
 * `kuml-testing`-Modul für eine triviale Funktion).
 *
 * Pfad-Auflösung: Tests starten Gradle aus dem **Repo-Root** mit `cwd =
 * <module>`. `System.getProperty("user.dir")` zeigt also auf das
 * Modul-Verzeichnis; `build/sample-output/` ist relativ dazu. Bei
 * IntelliJ-Run-Configs ohne Module-cwd fällt das Output unter den
 * Repo-Root — auch kein Drama, der Pfad wird unten geloggt.
 */
internal object SampleOutput {
    /** Schreibt [content] unter `<modulebuild>/sample-output/<filename>`. */
    fun write(
        filename: String,
        content: String,
    ): Path {
        val target = baseDir().resolve(filename)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
        println("[sample-output] $target")
        return target
    }

    /** Binäre Variante — für PNGs etc. */
    fun writeBytes(
        filename: String,
        content: ByteArray,
    ): Path {
        val target = baseDir().resolve(filename)
        Files.createDirectories(target.parent)
        Files.write(target, content)
        println("[sample-output] $target")
        return target
    }

    private fun baseDir(): Path = Paths.get(System.getProperty("user.dir")).resolve("build/sample-output")
}
