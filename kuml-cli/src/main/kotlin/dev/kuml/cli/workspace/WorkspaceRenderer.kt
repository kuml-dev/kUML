package dev.kuml.cli.workspace

import dev.kuml.cli.RenderPipeline
import dev.kuml.cli.ScriptEvaluationException
import dev.kuml.core.config.KumlConfig
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.OkfWorkspace
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Renders every ` ```kuml ` block in an [OkfWorkspace] via the full multi-metamodel
 * [RenderPipeline] (UML, C4, SysML 2, BPMN, Blueprint, ERM — not just UML).
 *
 * Reuse mechanic: [RenderPipeline.run] takes a `File`, so each block's source is
 * written to a temporary `<stem>.kuml.kts` script and rendered through the exact
 * same pipeline `kuml render` uses. Zero changes to [RenderPipeline] were needed;
 * every diagram type it supports is available immediately.
 *
 * Robustness: a render failure in one document is caught and recorded as a
 * [RenderReport.failures] entry — it does not abort the run. A single broken
 * diagram must not prevent the rest of an OKF workspace from rendering.
 *
 * Trust model (ADR-0011 spike security review): [RenderPipeline.run] executes the
 * ` ```kuml ` block's Kotlin script source directly — this is arbitrary code execution
 * by design, inherited from `kuml render` and pre-existing in the render pipeline, not
 * introduced by workspace rendering. `workspace render` must therefore only ever be run
 * over workspaces the invoking user already trusts, exactly like `kuml render` itself.
 * It must not be wired into a service that renders documents submitted by untrusted third
 * parties without additional sandboxing (separate OS user/container, no ambient credentials,
 * resource limits) around the whole process, not just this class.
 */
internal object WorkspaceRenderer {
    /**
     * @property rendered Output files (SVG/PNG) successfully written.
     * @property skipped Relative paths of documents with no ```kuml block (prose/collection docs).
     * @property failures One [OkfFinding] (code `OKF-E-007`) per block that failed to render.
     */
    data class RenderReport(
        val rendered: List<File>,
        val skipped: List<String>,
        val failures: List<OkfFinding>,
    )

    /**
     * @param ws The scanned workspace to render.
     * @param outputDir Destination root; the workspace's directory structure is mirrored under it.
     * @param format `"svg"` or `"png"`.
     * @param width Pixel width, PNG only.
     * @param mirror When `true`, every Markdown document (diagram or not) is copied to
     *  [outputDir] with its ```kuml blocks replaced by `![title](stem.svg)` image links —
     *  producing a GitHub-renderable output workspace. When `false`, only the rendered
     *  diagram assets are written.
     */
    fun render(
        ws: OkfWorkspace,
        outputDir: File,
        format: String,
        width: Int,
        mirror: Boolean,
    ): RenderReport {
        outputDir.mkdirs()
        val rendered = mutableListOf<File>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<OkfFinding>()

        for (doc in ws.documents) {
            if (doc.kumlBlocks.isEmpty()) {
                skipped += doc.relativePath
                if (mirror) mirrorDocument(outputDir, doc, imagePathsByBlockIndex = emptyList())
                continue
            }

            val docStem = doc.file.nameWithoutExtension
            val imagePaths = arrayOfNulls<String>(doc.kumlBlocks.size)

            doc.kumlBlocks.forEachIndexed { idx, block ->
                val rawStem = block.name ?: if (doc.kumlBlocks.size > 1) "$docStem-${idx + 1}" else docStem
                val stem = sanitizeStem(rawStem)
                val docRelDir = File(doc.relativePath).parent
                val targetDir = if (docRelDir != null) File(outputDir, docRelDir) else outputDir
                targetDir.mkdirs()

                val tempDir = Files.createTempDirectory("kuml-okf-render").toFile()
                try {
                    val outFile = resolveWithin(targetDir, "$stem.$format")
                    val tempScript = resolveWithin(tempDir, "$stem.kuml.kts")
                    tempScript.writeText(block.source, Charsets.UTF_8)
                    RenderPipeline.run(
                        input = tempScript,
                        output = outFile.toPath(),
                        format = format,
                        width = width,
                        themeName = null,
                        config = KumlConfig.DEFAULT,
                        layoutEngineOverride = null,
                    )
                    rendered += outFile
                    imagePaths[idx] = "$stem.$format"
                } catch (e: ScriptEvaluationException) {
                    failures +=
                        OkfFinding(
                            code = "OKF-E-007",
                            severity = OkfSeverity.ERROR,
                            file = doc.relativePath,
                            line = block.startLine,
                            message = "Failed to render kuml block: ${e.message}",
                        )
                } catch (e: IOException) {
                    failures +=
                        OkfFinding(
                            code = "OKF-E-007",
                            severity = OkfSeverity.ERROR,
                            file = doc.relativePath,
                            line = block.startLine,
                            message = "I/O error while rendering kuml block: ${e.message}",
                        )
                } catch (e: IllegalArgumentException) {
                    // Defence-in-depth backstop from resolveWithin(); sanitizeStem() should
                    // already prevent this from ever firing, but a failed block must not
                    // abort the rest of the workspace render.
                    failures +=
                        OkfFinding(
                            code = "OKF-E-007",
                            severity = OkfSeverity.ERROR,
                            file = doc.relativePath,
                            line = block.startLine,
                            message = "Refusing to render kuml block: ${e.message}",
                        )
                } finally {
                    tempDir.deleteRecursively()
                }
            }

            if (mirror) mirrorDocument(outputDir, doc, imagePaths.toList())
        }

        return RenderReport(rendered = rendered, skipped = skipped, failures = failures)
    }

    /**
     * Copies [doc] into [outputDir] (preserving its relative path), replacing each
     * ```kuml block with `![title](image)` when [imagePathsByBlockIndex] has a
     * non-null entry at that block's index, or leaving it untouched otherwise
     * (render failure — keep the source visible for debugging).
     *
     * Replacements are applied in reverse block order so earlier line numbers stay
     * valid while later ones are substituted (same convention as `MarkdownProcessor.process`).
     */
    private fun mirrorDocument(
        outputDir: File,
        doc: OkfDocument,
        imagePathsByBlockIndex: List<String?>,
    ) {
        val lines =
            doc.file
                .readText(Charsets.UTF_8)
                .split('\n')
                .toMutableList()

        doc.kumlBlocks.withIndex().toList().reversed().forEach { (idx, block) ->
            val imagePath = imagePathsByBlockIndex.getOrNull(idx) ?: return@forEach
            val title = block.name ?: doc.file.nameWithoutExtension
            val from = (block.startLine - 1).coerceAtLeast(0)
            val to = block.endLine.coerceAtMost(lines.size)
            repeat(to - from) { lines.removeAt(from) }
            lines.add(from, "![$title]($imagePath)")
        }

        val outFile = File(outputDir, doc.relativePath)
        outFile.parentFile?.mkdirs()
        outFile.writeText(lines.joinToString("\n"), Charsets.UTF_8)
    }

    /**
     * Reduces an attacker-controlled `name` attribute (or a document stem derived
     * from a scanned path) to a safe file-name stem: strips any path components
     * (`/`, `\`, `..`) by keeping only the last path segment, then replaces every
     * character outside `[A-Za-z0-9._-]` with `_`. Falls back to `"block"` if
     * nothing safe remains (e.g. a name of just `".."` or `"///"`).
     *
     * This is the primary defence against path traversal via `name="../../etc/passwd"`
     * in a ` ```kuml {name="..."} ` block — see ADR-0011 spike security review.
     */
    private fun sanitizeStem(raw: String): String {
        val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = lastSegment.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val trimmed = cleaned.trim('.', '_', '-')
        return trimmed.ifEmpty { "block" }
    }

    /**
     * Resolves [childName] under [base] and asserts the result stays inside
     * [base] after normalization — a defence-in-depth backstop in case
     * [sanitizeStem] is ever bypassed or a future caller forgets to apply it.
     */
    private fun resolveWithin(
        base: File,
        childName: String,
    ): File {
        val resolved = File(base, childName)
        val normalizedResolved = resolved.toPath().normalize()
        val normalizedBase = base.toPath().normalize()
        require(normalizedResolved.startsWith(normalizedBase)) {
            "Refusing to write outside of $normalizedBase: $normalizedResolved"
        }
        return resolved
    }
}
