package dev.kuml.asciidoc

import java.io.File

/**
 * Result returned by [AsciidocProcessor.process].
 *
 * @property output Der AsciiDoc-Quelltext, bei dem jeder kUML-Block
 *   gemäss dem gewählten [AsciidocOutputMode] ersetzt wurde.
 * @property assets Dateien, die für [AsciidocOutputMode.LinkedSvg] /
 *   [AsciidocOutputMode.LinkedPng] auf der Festplatte landen. Leer für
 *   [AsciidocOutputMode.InlineSvg].
 */
public data class AsciidocProcessResult(
    public val output: String,
    public val assets: List<File> = emptyList(),
)

/**
 * AsciiDoc-Prozessor: ersetzt `[source,kuml]`-Listing-Blöcke und
 * `kuml::path[…]`-Block-Makros durch gerenderte Diagramme (inline SVG,
 * verlinktes SVG oder PNG).
 *
 * **Antora-Kompatibilität**: das Output ist gültiges AsciiDoc, das Antoras
 * Asciidoctor-Pipeline ohne Extra-Setup konsumiert. Für `LinkedSvg`/`LinkedPng`
 * empfiehlt sich der Antora-Asset-Pfad `modules/<m>/images/`.
 *
 * Beispiel:
 * ```kotlin
 * val processor = AsciidocProcessor()
 * val result = processor.process(
 *     input = File("guide.adoc").readText(),
 *     mode = AsciidocOutputMode.InlineSvg,
 *     baseName = "guide",
 * )
 * File("guide.rendered.adoc").writeText(result.output)
 * ```
 */
public class AsciidocProcessor(
    /**
     * Wurzelverzeichnis, gegen das relative Pfade in `kuml::path[…]`-Block-Makros
     * aufgelöst werden. Default: aktuelles Arbeitsverzeichnis.
     */
    private val baseDir: File = File("."),
) {
    /**
     * @param input AsciiDoc-Quelltext.
     * @param mode Wie Diagramme eingebettet werden (siehe [AsciidocOutputMode]).
     * @param baseName Datei-Stamm für Asset-Dateien, falls ein Block kein
     *   `name`-Attribut hat. Index wird angehängt (z.B. `guide-1.svg`).
     * @param withSource Wenn `true`, wird der ursprüngliche kUML-DSL-Quelltext zusätzlich als
     *   `[source,kotlin]`-Listing vor dem gerenderten Diagramm reproduziert. Einzelne Blöcke
     *   können diesen Default per `showsource=true|false`-Attribut überschreiben
     *   (siehe [AsciidocKumlBlock.showSource]).
     */
    public fun process(
        input: String,
        mode: AsciidocOutputMode,
        baseName: String = "diagram",
        withSource: Boolean = false,
    ): AsciidocProcessResult {
        val blocks = AsciidocBlockExtractor.extract(input)
        if (blocks.isEmpty()) return AsciidocProcessResult(output = input)

        val assets = mutableListOf<File>()
        val lines = input.split('\n').toMutableList()

        // In umgekehrter Reihenfolge ersetzen, damit die Indizes stabil bleiben.
        blocks.withIndex().reversed().forEach { (idx, block) ->
            val virtualName =
                when (block.kind) {
                    AsciidocBlockKind.LISTING ->
                        block.name?.let { "$it.kuml.kts" } ?: "$baseName-${idx + 1}.kuml.kts"
                    AsciidocBlockKind.BLOCK_MACRO -> block.targetPath ?: "$baseName-${idx + 1}.kuml.kts"
                }

            // Block-Makro: Datei einlesen
            val source =
                when (block.kind) {
                    AsciidocBlockKind.LISTING -> block.source
                    AsciidocBlockKind.BLOCK_MACRO -> {
                        val path =
                            block.targetPath
                                ?: error("Block macro at line ${block.startLine} has no path")
                        File(baseDir, path).readText()
                    }
                }

            val extracted = AsciidocRenderPipeline.evaluate(source = source, virtualName = virtualName)
            val theme = AsciidocRenderPipeline.resolveTheme(block.theme)

            val diagramLines: List<String> =
                when (mode) {
                    AsciidocOutputMode.InlineSvg -> {
                        val svg = AsciidocRenderPipeline.renderSvg(extracted = extracted, theme = theme)
                        // Asciidoctor-Passthrough-Block: `++++` öffnet/schließt, alles dazwischen
                        // landet 1:1 im HTML-Output (Antora-kompatibel).
                        listOf("++++", svg, "++++")
                    }
                    is AsciidocOutputMode.LinkedSvg -> {
                        mode.assetsDir.mkdirs()
                        val stem = block.name ?: defaultStem(block = block, baseName = baseName, idx = idx)
                        val file = File(mode.assetsDir, "$stem.svg")
                        file.writeText(AsciidocRenderPipeline.renderSvg(extracted = extracted, theme = theme), Charsets.UTF_8)
                        assets += file
                        listOf("image::${file.name}[${AsciidocRenderPipeline.diagramName(extracted)}]")
                    }
                    is AsciidocOutputMode.LinkedPng -> {
                        mode.assetsDir.mkdirs()
                        val stem = block.name ?: defaultStem(block = block, baseName = baseName, idx = idx)
                        val width = block.width ?: mode.widthPx
                        val file = File(mode.assetsDir, "$stem.png")
                        file.writeBytes(AsciidocRenderPipeline.renderPng(extracted = extracted, widthPx = width, theme = theme))
                        assets += file
                        listOf("image::${file.name}[${AsciidocRenderPipeline.diagramName(extracted)}]")
                    }
                }

            val effectiveShowSource = block.showSource ?: withSource
            val replacement: List<String> =
                if (effectiveShowSource) {
                    sourceListing(source = source) + listOf("") + diagramLines
                } else {
                    diagramLines
                }

            // Ersetze die Zeilen [startLine, endLine] (1-basiert, inklusive) durch [replacement].
            val from = (block.startLine - 1).coerceAtLeast(0)
            val to = block.endLine.coerceAtMost(lines.size)
            repeat(to - from) { lines.removeAt(from) }
            replacement.forEachIndexed { off, line -> lines.add(from + off, line) }
        }

        return AsciidocProcessResult(output = lines.joinToString("\n"), assets = assets.reversed())
    }

    private fun defaultStem(
        block: AsciidocKumlBlock,
        baseName: String,
        idx: Int,
    ): String =
        when (block.kind) {
            AsciidocBlockKind.LISTING -> "$baseName-${idx + 1}"
            AsciidocBlockKind.BLOCK_MACRO -> {
                val raw = block.targetPath ?: "$baseName-${idx + 1}"
                File(raw).nameWithoutExtension.removeSuffix(".kuml")
            }
        }

    /**
     * Reproduces the kUML DSL [source] as an AsciiDoc `[source,kotlin]` listing block. `kotlin`
     * (not `kuml`) is used deliberately — highlight.js (Antora's syntax highlighter) has no
     * `kuml` grammar, but kUML scripts are valid Kotlin, so `kotlin` gives real syntax
     * highlighting instead of unformatted plain text.
     *
     * [source] is normalized to LF-only line endings first (see [normalizeLineEndings]) so that
     * CRLF/CR-terminated input can't defeat the dash-only-line detection in
     * [safeListingDelimiter] — this matters most for `BLOCK_MACRO` blocks, whose [source] is read
     * verbatim from an arbitrary external file and is therefore not bounded by the surrounding
     * `.adoc` document's own fence.
     */
    private fun sourceListing(source: String): List<String> {
        val normalized = normalizeLineEndings(source)
        val delimiter = safeListingDelimiter(normalized)
        return listOf(".kUML source", "[source,kotlin]", delimiter) + normalized.split('\n') + listOf(delimiter)
    }

    /**
     * Normalizes CRLF and lone CR line endings to LF. Asciidoctor itself normalizes line endings
     * and trims trailing whitespace when matching delimiter lines (e.g. `AsciidocBlockExtractor`'s
     * own `LISTING_FENCE = Regex("""^\s*----\s*$""")` already tolerates a trailing `\r`), so any
     * dash-only-line detection performed here must apply the same normalization — otherwise a
     * CRLF-terminated external file containing a line that is visually just `----` (making it
     * `----\r` internally) would slip past [safeListingDelimiter]'s per-character `== '-'` check
     * and later be treated by a real Asciidoctor renderer as the block's closing fence, corrupting
     * the rest of the rendered document.
     */
    private fun normalizeLineEndings(source: String): String = source.replace("\r\n", "\n").replace("\r", "\n")

    /**
     * Computes an AsciiDoc listing-block delimiter (a run of `-` characters, minimum length 4)
     * that does not occur verbatim as a whole line anywhere in [source]. Without this guard, a
     * source file containing a line consisting solely of `----` (e.g. inside a multi-line string
     * literal) would prematurely close the generated `[source,kotlin]` listing block, corrupting
     * the rest of the rendered AsciiDoc document.
     *
     * [source] must already be normalized to LF-only line endings (see [normalizeLineEndings])
     * before calling this — otherwise a trailing `\r` on an otherwise dash-only line would make
     * the per-character `== '-'` check below fail to recognize it as dash-only.
     */
    private fun safeListingDelimiter(source: String): String {
        val longestDashOnlyLine =
            source
                .split('\n')
                .map { it.trimEnd(' ', '\t') }
                .filter { it.isNotEmpty() && it.all { c -> c == '-' } }
                .maxOfOrNull { it.length } ?: 0
        val length = maxOf(4, longestDashOnlyLine + 1)
        return "-".repeat(length)
    }
}
