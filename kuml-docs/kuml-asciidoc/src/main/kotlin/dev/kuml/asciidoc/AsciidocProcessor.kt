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
     */
    public fun process(
        input: String,
        mode: AsciidocOutputMode,
        baseName: String = "diagram",
    ): AsciidocProcessResult {
        val blocks = AsciidocBlockExtractor.extract(input)
        if (blocks.isEmpty()) return AsciidocProcessResult(input)

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

            val extracted = AsciidocRenderPipeline.evaluate(source, virtualName)
            val theme = AsciidocRenderPipeline.resolveTheme(block.theme)

            val replacement: List<String> =
                when (mode) {
                    AsciidocOutputMode.InlineSvg -> {
                        val svg = AsciidocRenderPipeline.renderSvg(extracted, theme)
                        // Asciidoctor-Passthrough-Block: `++++` öffnet/schließt, alles dazwischen
                        // landet 1:1 im HTML-Output (Antora-kompatibel).
                        listOf("++++", svg, "++++")
                    }
                    is AsciidocOutputMode.LinkedSvg -> {
                        mode.assetsDir.mkdirs()
                        val stem = block.name ?: defaultStem(block, baseName, idx)
                        val file = File(mode.assetsDir, "$stem.svg")
                        file.writeText(AsciidocRenderPipeline.renderSvg(extracted, theme), Charsets.UTF_8)
                        assets += file
                        listOf("image::${file.name}[${AsciidocRenderPipeline.diagramName(extracted)}]")
                    }
                    is AsciidocOutputMode.LinkedPng -> {
                        mode.assetsDir.mkdirs()
                        val stem = block.name ?: defaultStem(block, baseName, idx)
                        val width = block.width ?: mode.widthPx
                        val file = File(mode.assetsDir, "$stem.png")
                        file.writeBytes(AsciidocRenderPipeline.renderPng(extracted, width, theme))
                        assets += file
                        listOf("image::${file.name}[${AsciidocRenderPipeline.diagramName(extracted)}]")
                    }
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
}
