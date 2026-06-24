package dev.kuml.io.svg

import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
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
 * Wenn der übergebene Inhalt ein SVG-String ist (beginnt mit `<svg` oder
 * `<?xml`), wird automatisch eine PNG-Datei neben dem SVG erzeugt.
 * Der PNG-Pfad entspricht dem SVG-Pfad mit `.svg`-Suffix ersetzt durch `.png`
 * (bzw. `.png` angehängt, wenn kein `.svg`-Suffix vorhanden ist).
 *
 * Wird in jedem Modul als 1-File-Kopie geführt (kein eigenes Testing-Modul
 * für eine triviale Datei-Write-Funktion).
 */
internal object SampleOutput {
    private val pngOptions = PngRenderOptions(widthPx = 2400)

    fun write(
        filename: String,
        content: String,
    ): Path {
        val target = baseDir().resolve(filename)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
        println("[sample-output] $target")

        val trimmed = content.trimStart()
        if (trimmed.startsWith("<svg") || trimmed.startsWith("<?xml")) {
            writePng(filename, content)
        }

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

    private fun writePng(
        svgFilename: String,
        svg: String,
    ): Path {
        val pngFilename =
            if (svgFilename.endsWith(".svg")) {
                svgFilename.dropLast(4) + ".png"
            } else {
                "$svgFilename.png"
            }
        val pngBytes = KumlPngRenderer.toPng(svg, pngOptions)
        return writeBytes(pngFilename, pngBytes)
    }

    private fun baseDir(): Path = Paths.get(System.getProperty("user.dir")).resolve("build/sample-output")
}
