package dev.kuml.io.png

import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.KumlDiagram
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutResult
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
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
     * @param svgOptions SVG-Render-Optionen für die interne SVG-Zwischenstufe
     *   (z.B. `watermark` für die "Powered by kUML"-Wasserzeichen-Funktion);
     *   Standard: [SvgRenderOptions.DEFAULT]
     * @return PNG-Byte-Array
     */
    public fun toPng(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
        svgOptions: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): ByteArray {
        val svg = KumlSvgRenderer.toSvg(diagram, layoutResult, theme, svgOptions)
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
     * @param svgOptions SVG-Render-Optionen für die interne SVG-Zwischenstufe;
     *   Standard: [SvgRenderOptions.DEFAULT]
     * @return PNG-Byte-Array
     */
    public fun toPng(
        diagram: C4Diagram,
        model: C4Model,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
        svgOptions: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): ByteArray {
        val svg = KumlSvgRenderer.toSvg(diagram, model, layoutResult, theme, svgOptions)
        return toPng(svg, options)
    }

    /**
     * Convenience-Overload: Rendert ein SysML-2-Diagramm direkt zu PNG (V2.0.14).
     *
     * Diese **eine** Methode bedient alle acht SysML-2-Diagrammtypen — BDD, IBD,
     * UC, REQ, STM, ACT, SEQ, PAR — via Dispatch auf den versiegelten
     * [Sysml2Diagram]-Subtyp. Hintergrund: [KumlSvgRenderer] hat **keine**
     * generische `toSvg(model, Sysml2Diagram, …)`-Variante (jede der acht
     * Subtypen hat ein eigenes typisiertes Overload mit unterschiedlicher
     * Renderpfad-Logik — z.B. PAR mit `ParEdgeAdapter`, SEQ mit direktem
     * Message-Renderer). Wir dispatchen den Subtyp deshalb hier per
     * `when (diagram)`-Block, der dank `sealed interface` vom Kotlin-Compiler
     * exhaustivitäts-geprüft ist — kommt später ein neunter Subtyp dazu, muss
     * diese Methode gepflegt werden, bevor das Modul kompiliert.
     *
     * Der eigentliche PNG-Pfad bleibt derselbe wie für UML/C4: SVG-String aus
     * dem SVG-Renderer holen, an [PngTranscoderImpl] zur Rasterisierung
     * weiterreichen. Es gibt keine SysML-2-spezifischen Render-Optionen — die
     * vom SVG-Pfad eingebauten Stereotyp-Labels (`«part def»`, `«requirement»`,
     * `«constraint»`, gestricheltes `«include»` / `«satisfy»` etc.,
     * arrow-styled `«extend»`) sind im SVG bereits präsent, Batik
     * rasterisiert sie ohne Sonderbehandlung.
     *
     * @param model das übergeordnete SysML-2-Modell (Definitionen + Usages)
     * @param diagram der konkrete Diagramm-Subtyp; einer von
     *   [BdDiagram], [IbdDiagram], [UcDiagram], [ReqDiagram], [StmDiagram],
     *   [ActDiagram], [SeqDiagram], [ParDiagram]
     * @param layoutResult berechnete Positionen und Routing-Pfade aus dem
     *   [dev.kuml.layout.bridge.Sysml2LayoutBridge]
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Render-Optionen; Standard: [PngRenderOptions.DEFAULT]
     * @param svgOptions SVG-Render-Optionen für die interne SVG-Zwischenstufe;
     *   Standard: [SvgRenderOptions.DEFAULT]
     * @return PNG-Byte-Array (beginnt mit den PNG-Magic-Bytes `89 50 4E 47 ...`)
     */
    public fun toPng(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme(),
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
        svgOptions: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): ByteArray {
        val svg =
            when (diagram) {
                is BdDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
                is IbdDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
                is UcDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
                is ReqDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
                is StmDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
                is ActDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
                is SeqDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
                is ParDiagram -> KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, svgOptions)
            }
        return toPng(svg, options)
    }

    /**
     * Convenience-Overload: Rendert ein SysML-2-Diagramm zu PNG und schreibt
     * es in eine Datei (V2.0.14).
     *
     * Spiegelt das UML/C4-`toPngFile`-Muster für die acht SysML-2-Diagramm-
     * Subtypen. Erzeugt fehlende Eltern-Verzeichnisse mit `mkdirs()`, schreibt
     * die PNG-Bytes binär.
     *
     * @param model das übergeordnete SysML-2-Modell
     * @param diagram der konkrete Diagramm-Subtyp (siehe
     *   [toPng]`(Sysml2Model, Sysml2Diagram, …)`)
     * @param layoutResult berechnete Positionen und Routing-Pfade
     * @param out Zieldatei-Pfad
     * @param theme visuelles Theme; Standard: [PlainTheme]
     * @param options Render-Optionen; Standard: [PngRenderOptions.DEFAULT]
     * @param svgOptions SVG-Render-Optionen für die interne SVG-Zwischenstufe;
     *   Standard: [SvgRenderOptions.DEFAULT]
     * @return die geschriebene [File]
     */
    public fun toPngFile(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        layoutResult: LayoutResult,
        out: Path,
        theme: KumlTheme = PlainTheme(),
        options: PngRenderOptions = PngRenderOptions.DEFAULT,
        svgOptions: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    ): File {
        val bytes = toPng(model, diagram, layoutResult, theme, options, svgOptions)
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
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
