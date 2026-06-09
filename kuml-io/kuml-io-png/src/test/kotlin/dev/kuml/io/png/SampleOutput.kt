package dev.kuml.io.png

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test-Helper: schreibt visuell überprüfbare PNG-Sample-Files unter
 * `<modul>/build/sample-output/<filename>`.
 *
 * Tests behalten ihre eigentlichen Assertions; das Sample-Output ist eine
 * Komfort-Ergänzung, um nach jedem Test-Lauf die produzierten PNGs
 * direkt ansehen zu können (analog zu SampleOutput in kuml-io-svg).
 *
 * Wird als 1-File-Kopie im Modul geführt.
 */
internal object SampleOutput {
    fun write(
        filename: String,
        bytes: ByteArray,
    ): Path {
        val target = baseDir().resolve(filename)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        println("[sample-output] $target")
        return target
    }

    private fun baseDir(): Path = Paths.get(System.getProperty("user.dir")).resolve("build/sample-output")
}
