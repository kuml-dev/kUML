package dev.kuml.markdown

import java.io.File

/**
 * Result returned by [MarkdownProcessor.process].
 *
 * @property output The Markdown source with every kuml code block replaced
 *  according to the chosen [MarkdownOutputMode].
 * @property assets Files written to disk for [MarkdownOutputMode.LinkedSvg] /
 *  [MarkdownOutputMode.LinkedPng]. Empty for [MarkdownOutputMode.InlineSvg].
 */
public data class MarkdownProcessResult(
    val output: String,
    val assets: List<File> = emptyList(),
)

/**
 * Markdown-Prozessor: substituiert ```` ```kuml ```` Code-Blöcke durch
 * gerenderte Diagramme (inline SVG, verlinktes SVG oder PNG).
 *
 * Beispiel:
 * ```kotlin
 * val processor = MarkdownProcessor()
 * val result = processor.process(
 *     input = File("README.md").readText(),
 *     mode = MarkdownOutputMode.InlineSvg,
 *     baseName = "readme",
 * )
 * File("README.rendered.md").writeText(result.output)
 * ```
 */
public class MarkdownProcessor {
    /**
     * @param input The Markdown source.
     * @param mode How to embed rendered diagrams (see [MarkdownOutputMode]).
     * @param baseName File stem used for generated assets when a block has no
     *  `name` attribute. Index is appended (e.g. `readme-1.svg`, `readme-2.svg`).
     */
    public fun process(
        input: String,
        mode: MarkdownOutputMode,
        baseName: String = "diagram",
    ): MarkdownProcessResult {
        val blocks = CodeBlockExtractor.extract(input)
        if (blocks.isEmpty()) return MarkdownProcessResult(input)

        val assets = mutableListOf<File>()
        val lines = input.split('\n').toMutableList()

        // Process in reverse so line indices stay valid while we substitute.
        blocks.withIndex().reversed().forEach { (idx, block) ->
            val virtualName = block.name?.let { "$it.kuml.kts" } ?: "$baseName-${idx + 1}.kuml.kts"
            val diagram = MarkdownRenderPipeline.evaluate(block.source, virtualName)

            val replacement: List<String> =
                when (mode) {
                    MarkdownOutputMode.InlineSvg -> {
                        val svg = MarkdownRenderPipeline.renderSvg(diagram)
                        listOf(svg)
                    }
                    is MarkdownOutputMode.LinkedSvg -> {
                        mode.assetsDir.mkdirs()
                        val stem = block.name ?: "$baseName-${idx + 1}"
                        val file = File(mode.assetsDir, "$stem.svg")
                        file.writeText(MarkdownRenderPipeline.renderSvg(diagram), Charsets.UTF_8)
                        assets.add(file)
                        listOf("![${diagram.name}](${relativize(file)})")
                    }
                    is MarkdownOutputMode.LinkedPng -> {
                        mode.assetsDir.mkdirs()
                        val stem = block.name ?: "$baseName-${idx + 1}"
                        val width = block.width ?: mode.widthPx
                        val file = File(mode.assetsDir, "$stem.png")
                        file.writeBytes(MarkdownRenderPipeline.renderPng(diagram, width))
                        assets.add(file)
                        listOf("![${diagram.name}](${relativize(file)})")
                    }
                }

            // Replace lines [startLine, endLine] (1-based, inclusive) with [replacement].
            val from = (block.startLine - 1).coerceAtLeast(0)
            val to = block.endLine.coerceAtMost(lines.size)
            // Drop original lines
            repeat(to - from) { lines.removeAt(from) }
            // Insert replacement at `from`
            replacement.forEachIndexed { off, line -> lines.add(from + off, line) }
        }

        return MarkdownProcessResult(output = lines.joinToString("\n"), assets = assets.reversed())
    }

    /** Use just the file name — callers compose paths in their host project. */
    private fun relativize(file: File): String = file.name
}
