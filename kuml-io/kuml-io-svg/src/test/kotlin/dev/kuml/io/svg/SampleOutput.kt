package dev.kuml.io.svg

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test-Helper: schreibt visuell überprüfbare SVG-/Sample-Files unter
 * `<modul>/build/sample-output/<filename>`.
 *
 * Tests behalten ihre eigentlichen Assertions; das Sample-Output ist eine
 * Komfort-Ergänzung, um nach jedem Test-Lauf die produzierten SVGs im
 * Browser öffnen zu können.
 *
 * Wird in jedem Modul als 1-File-Kopie geführt (kein eigenes Testing-Modul
 * für eine triviale Datei-Write-Funktion).
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
