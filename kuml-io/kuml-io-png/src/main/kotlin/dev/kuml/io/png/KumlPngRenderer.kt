package dev.kuml.io.png

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutResult
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import java.io.File
import java.nio.file.Path

/**
 * Rendert kUML-Diagramme als PNG-Byte-Array oder -Datei.
 *
 * Intern wird zunächst ein SVG-String über [KumlSvgRenderer] erzeugt und
 * anschließend via Apache Batik Transcoder zu PNG gerastert.
 *
 * **Hinweis:** PNG-Export setzt Apache Batik voraus, das reflection-intensiv ist
 * und **nicht** mit GraalVM Native Image kompatibel ist. Der PNG-Pfad läuft daher
 * ausschließlich im Fat-JAR-Modus. Der SVG-Pfad bleibt Native-Image-fähig.
 *
 * Beispiel:
 * ```kotlin
 * val svg = KumlSvgRenderer.toSvg(diagram, layoutResult)
 * val png = KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = 1024))
 * Files.write(Path.of("out.png"), png)
 * ```
 *
 * @see PngRenderOptions
 * @see KumlSvgRenderer
 */
public object KumlPngRenderer {
    private val transcoder = PngTranscoderImpl()

    /**
     * Konvertiert einen SVG-String zu einem PNG-Byte-Array.
     *
     * @param svg wohlgeformter SVG-String (z.B. aus [KumlSvgRenderer.toSvg])
     * @param options Render-Optionen; Standard: [PngRenderOptions.DEFAULT]
     * @return PNG-Byte-Array (beginnt mit den PNG-Magic-Bytes `89 50 4E 47 ...`)
     */
    public fun toPng(
        svg: String,
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
    ): ByteArray = transcoder.transcode(svg, options)

    /**
     * Convenience-Overload: Rendert ein UML-Diagramm direkt zu PNG.
     *
     * Ruft intern [KumlSvgRenderer.toSvg] auf und wandelt das Ergebnis zu PNG.
     *
     * @param diagram das UML-Diagramm mit allen Elementen
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Render-Optionen; Standard: [PngRenderOptions.DEFAULT]
     * @return PNG-Byte-Array
     */
    public fun toPng(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
    ): ByteArray {
        val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, theme)
        return toPng(svg, options)
    }

    /**
     * Convenience-Overload: Rendert ein C4-Diagramm direkt zu PNG.
     *
     * Ruft intern [KumlSvgRenderer.toSvg] auf und wandelt das Ergebnis zu PNG.
     *
     * @param diagram das C4-Diagramm
     * @param model das übergeordnete C4-Modell für Element-Lookup
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Render-Optionen; Standard: [PngRenderOptions.DEFAULT]
     * @return PNG-Byte-Array
     */
    public fun toPng(
        diagram: C4Diagram,
        model: C4Model,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
    ): ByteArray {
        val svg = KumlSvgRenderer.toSvg(diagram, model, layoutResult, theme)
        return toPng(svg, options)
    }

    /**
     * Convenience-Overload: Konvertiert SVG zu PNG und schreibt es in eine Datei.
     *
     * Beispiel:
     * ```kotlin
     * val file = KumlPngRenderer.toPngFile(svg, Path.of("out.png"))
     * println("Gespeichert: ${file.absolutePath}")
     * ```
     *
     * @param svg wohlgeformter SVG-String
     * @param out Zieldatei-Pfad
     * @param options Render-Optionen; Standard: [PngRenderOptions.DEFAULT]
     * @return die geschriebene [File]
     */
    public fun toPngFile(
        svg: String,
        out: Path,
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
    ): File {
        val bytes = toPng(svg, options)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }
}
