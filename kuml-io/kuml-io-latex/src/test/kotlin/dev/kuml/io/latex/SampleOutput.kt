package dev.kuml.io.latex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test-Helper: schreibt visuell überprüfbare TikZ-/Sample-Files unter
 * `<modul>/build/sample-output/<filename>`.
 *
 * Snippet-Files können in ein eigenes `\documentclass{standalone}`-Wrapper
 * gepackt und mit `pdflatex` gebaut werden; Standalone-Files lassen sich
 * direkt kompilieren.
 *
 * Tests behalten ihre eigentlichen Assertions; das Sample-Output ist
 * Komfort fürs Auge nach jedem Test-Lauf.
 */
internal object SampleOutput {
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

    private fun baseDir(): Path = Paths.get(System.getProperty("user.dir")).resolve("build/sample-output")
}
