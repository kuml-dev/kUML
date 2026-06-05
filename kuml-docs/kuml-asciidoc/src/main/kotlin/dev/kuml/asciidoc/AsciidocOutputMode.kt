package dev.kuml.asciidoc

import java.io.File

/**
 * Steuert, wie gerenderte Diagramme zurück in das AsciiDoc-Dokument eingebettet werden.
 *
 *  - [InlineSvg] — der kUML-Block wird durch einen `++++ <svg…> ++++` Passthrough-Block
 *    ersetzt. Der SVG-Markup wird vom Asciidoctor-Konverter unangetastet ans HTML
 *    durchgereicht. Antora-kompatibel.
 *
 *  - [LinkedSvg] — SVG wird in [assetsDir] geschrieben und als `image::path[]` referenziert.
 *    Antora erwartet typischerweise Bilder unter `modules/<m>/images/`; der Aufrufer
 *    setzt `assetsDir` entsprechend, der Block referenziert den Dateinamen.
 *
 *  - [LinkedPng] — wie [LinkedSvg] aber via Batik gerenderte PNGs in [widthPx].
 */
public sealed class AsciidocOutputMode {
    /** Inline `<svg>` als Asciidoctor-Passthrough (`++++ … ++++`). */
    public data object InlineSvg : AsciidocOutputMode()

    /** Schreibt `.svg`-Dateien in [assetsDir], referenziert via `image::name.svg[]`. */
    public data class LinkedSvg(
        public val assetsDir: File,
    ) : AsciidocOutputMode()

    /** Schreibt `.png`-Dateien in [assetsDir] (in [widthPx]), referenziert via `image::name.png[]`. */
    public data class LinkedPng(
        public val assetsDir: File,
        public val widthPx: Int = 1024,
    ) : AsciidocOutputMode()
}
