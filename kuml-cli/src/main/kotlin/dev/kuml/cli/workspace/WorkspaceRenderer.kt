package dev.kuml.cli.workspace

import dev.kuml.cli.RenderPipeline
import dev.kuml.cli.ScriptEvaluationException
import dev.kuml.core.config.KumlConfig
import dev.kuml.workspace.OkfDocument
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
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
 * Public so that kUML Desktop's MCP workspace tools reuse exactly the CLI's render logic.
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
public object WorkspaceRenderer {
    /**
     * @property rendered Output files (SVG/PNG) successfully written.
     * @property skipped Relative paths of documents with no ```kuml block (prose/collection docs).
     * @property failures One [OkfFinding] (code `OKF-E-007`) per block that failed to render.
     * @property warnings One [OkfFinding] (code `OKF-W-008`) per block that produced more than one
     *  diagram (only the first is rendered). Severity is ERROR instead of WARNING under `strict`.
     */
    public data class RenderReport(
        val rendered: List<File>,
        val skipped: List<String>,
        val failures: List<OkfFinding>,
        val warnings: List<OkfFinding> = emptyList(),
    )

    /**
     * @param documents The documents to render (all of a workspace, or just the `--document` selection).
     * @param outputDir Destination root; the workspace's directory structure is mirrored under it.
     * @param format `"svg"` or `"png"`.
     * @param width Pixel width, PNG only.
     * @param mirror When `true`, every Markdown document (diagram or not) is copied to
     *  [outputDir] with its ```kuml blocks replaced by `![title](stem.svg)` image links —
     *  producing a GitHub-renderable output workspace. When `false`, only the rendered
     *  diagram assets are written.
     * @param themeName Optional theme name passed through to the render pipeline (`null` = default).
     * @param strict Promotes the multi-diagram finding (`OKF-W-008`) from WARNING to ERROR.
     */
    public fun render(
        documents: List<OkfDocument>,
        outputDir: File,
        format: String,
        width: Int,
        mirror: Boolean,
        strict: Boolean = false,
        themeName: String? = null,
    ): RenderReport {
        outputDir.mkdirs()
        if (mirror) runCatching { File(outputDir, OUTPUT_MARKER).writeText("kuml workspace render output\n", Charsets.UTF_8) }
        val rendered = mutableListOf<File>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<OkfFinding>()
        val warnings = mutableListOf<OkfFinding>()

        for (doc in documents) {
            if (doc.kumlBlocks.isEmpty()) {
                skipped += doc.relativePath
                if (mirror) mirrorDocument(outputDir = outputDir, doc = doc, imagePathsByBlockIndex = emptyList())
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

                try {
                    val outFile = resolveWithin(base = targetDir, childName = "$stem.$format")
                    val outcome =
                        WorkspaceBlockEvaluator.withTempScript(stem = stem, source = block.source) { tempScript ->
                            RenderPipeline.run(
                                input = tempScript,
                                output = outFile.toPath(),
                                format = format,
                                width = width,
                                themeName = themeName,
                                config = KumlConfig.DEFAULT,
                                layoutEngineOverride = null,
                            )
                        }
                    rendered += outFile
                    imagePaths[idx] = "$stem.$format"
                    warnings += outcome.noteFindings.map { WorkspaceBlockEvaluator.noteFinding(doc = doc, block = block, finding = it) }
                    if (outcome.diagramCount > 1) {
                        warnings +=
                            WorkspaceBlockEvaluator.multiDiagramFinding(
                                doc = doc,
                                block = block,
                                blockIdx = idx,
                                count = outcome.diagramCount,
                                strict = strict,
                            )
                    }
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
                }
            }

            if (mirror) mirrorDocument(outputDir = outputDir, doc = doc, imagePathsByBlockIndex = imagePaths.toList())
        }

        return RenderReport(rendered = rendered, skipped = skipped, failures = failures, warnings = warnings)
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

    private fun sanitizeStem(raw: String): String = WorkspaceBlockEvaluator.sanitizeStem(raw)

    private fun resolveWithin(
        base: File,
        childName: String,
    ): File = WorkspaceBlockEvaluator.resolveWithin(base = base, childName = childName)

    /**
     * `true` when mirroring documents into [outputDir] cannot overwrite the workspace's own source
     * Markdown. Mirroring writes `outputDir/<relativePath>`, so an output directory that IS the
     * workspace root — or an ancestor of it — would clobber the sources. Sub-directories of the
     * root are excluded from scans; existing source files there are caught by [mirrorCollisions].
     * Compared on real paths (symlinks resolved); a not-yet-existing output is resolved via its
     * nearest existing ancestor.
     */
    internal fun isSafeOutputDir(
        root: File,
        outputDir: File,
    ): Boolean {
        val realRoot = realPathLenient(root)
        val realOut = realPathLenient(outputDir)
        return !realRoot.startsWith(realOut)
    }

    /**
     * Relative paths of documents whose mirror target inside [outputDir] already exists as a file
     * within the workspace [root] and would therefore overwrite a source document. Only relevant
     * when [outputDir] is a sub-directory of the root (it is excluded from scans, so sources there
     * are invisible to the render). Targets below a hidden directory (e.g. the default `.kuml-out`,
     * including output of releases that wrote no marker) are never source documents because the
     * scanner skips hidden directories, so they are not collisions. An output directory carrying
     * [OUTPUT_MARKER] was produced by an earlier render and may be overwritten freely.
     */
    internal fun mirrorCollisions(
        root: File,
        outputDir: File,
        documents: List<OkfDocument>,
    ): List<String> {
        if (File(outputDir, OUTPUT_MARKER).exists()) return emptyList()
        val realRoot = realPathLenient(root)
        return documents
            .filter { doc ->
                val target = File(outputDir, doc.relativePath)
                target.isFile &&
                    realPathLenient(target).let {
                        it.startsWith(realRoot) && !hasHiddenSegment(root = realRoot, target = it)
                    }
            }.map { doc -> doc.relativePath }
    }

    private fun hasHiddenSegment(
        root: java.nio.file.Path,
        target: java.nio.file.Path,
    ): Boolean = root.relativize(target).any { it.toString().startsWith(".") }

    private const val OUTPUT_MARKER = ".kuml-render-output"

    private fun realPathLenient(f: File): java.nio.file.Path {
        var existing: java.nio.file.Path = f.absoluteFile.toPath().normalize()
        val tail = ArrayDeque<java.nio.file.Path>()
        while (!Files.exists(existing)) {
            val parent = existing.parent ?: break
            tail.addFirst(existing.fileName)
            existing = parent
        }
        var real: java.nio.file.Path = runCatching { existing.toRealPath() }.getOrDefault(existing)
        for (t in tail) real = real.resolve(t)
        return real.normalize()
    }
}
