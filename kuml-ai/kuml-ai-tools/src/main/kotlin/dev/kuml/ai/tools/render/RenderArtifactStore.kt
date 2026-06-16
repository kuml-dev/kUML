package dev.kuml.ai.tools.render

import java.io.File
import java.nio.file.Files

/**
 * Writes rendered SVG/PNG artifacts to a temp directory and returns their paths.
 *
 * The agent LLM receives only the file path + a short summary — not the raw bytes.
 * This keeps the token budget under control for medium/large diagrams.
 */
internal class RenderArtifactStore(
    private val dir: File,
) {
    internal fun writeSvg(
        id: String,
        content: String,
    ): File {
        val file = File(dir, "kuml-ai-render-$id.svg")
        file.writeText(content)
        return file
    }

    internal fun writePng(
        id: String,
        bytes: ByteArray,
    ): File {
        val file = File(dir, "kuml-ai-render-$id.png")
        file.writeBytes(bytes)
        return file
    }

    internal companion object {
        internal fun tempDir(): RenderArtifactStore {
            val dir =
                Files
                    .createTempDirectory("kuml-ai-renders")
                    .toFile()
            dir.deleteOnExit()
            return RenderArtifactStore(dir)
        }

        /** Test-friendly: use an explicit directory. */
        internal fun forDir(dir: File): RenderArtifactStore = RenderArtifactStore(dir)
    }
}
