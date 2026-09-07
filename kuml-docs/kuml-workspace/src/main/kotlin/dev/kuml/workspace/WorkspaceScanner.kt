package dev.kuml.workspace

import java.io.File
import java.nio.file.Files

/**
 * Scans an OKF workspace directory tree into an [OkfWorkspace] (ADR-0011).
 *
 * Discovery order:
 * 1. Optional `.kuml-workspace.toml` at the root — parsed via [WorkspaceMarkerParser]
 *    into a [WorkspaceMarker] (`[workspace] mode`, `name`, `kuml-version`; `[okf] version`,
 *    `vocabulary`, `strict`).
 * 2. If no marker (or no `mode` key in it) is found, the mode is *inferred*:
 *    any `*.kuml.kts` file anywhere in the tree → [WorkspaceMode.ENGINEERING];
 *    else an `index.md` plus other `*.md` files with frontmatter → [WorkspaceMode.KNOWLEDGE];
 *    else [WorkspaceMode.UNKNOWN].
 * 3. Every `*.md` file in the tree is parsed into an [OkfDocument] — frontmatter,
 *    resolved [OkfType], ` ```kuml ` blocks, and Markdown links — via [OkfDocumentParser].
 *
 * Hidden directories (name starts with `.`) and any directory in [excludeDirs]
 * (typically the render output directory) are skipped so that a second
 * `workspace render` run over the same root does not re-scan its own mirrored output.
 */
public object WorkspaceScanner {
    /**
     * DoS guardrail (defence-in-depth, ADR-0011 spike security review): a workspace scan
     * should never be able to hang or exhaust memory/disk on adversarial or accidental input
     * (a directory symlink cycle, a symlink escaping the root, or an enormous number of files).
     * The per-document size cap lives on [OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES].
     */
    private const val MAX_MD_FILE_COUNT = 20_000

    public fun scan(
        root: File,
        excludeDirs: Set<File> = emptySet(),
    ): OkfWorkspace {
        val excludeCanonical = excludeDirs.map { it.absoluteFile.normalize() }.toSet()
        val markerFile = File(root, ".kuml-workspace.toml")
        val markerFound = markerFile.isFile
        val marker = if (markerFound) WorkspaceMarkerParser.parse(markerFile.readText()) else null

        val allFiles =
            root
                .walkTopDown()
                .onEnter { dir ->
                    !dir.name.startsWith(".") &&
                        dir.absoluteFile.normalize() !in excludeCanonical &&
                        !Files.isSymbolicLink(dir.toPath())
                }.filter { it.isFile }
                .toList()

        val mdFiles = allFiles.filter { it.extension == "md" }
        require(mdFiles.size <= MAX_MD_FILE_COUNT) {
            "Workspace scan aborted: $root contains ${mdFiles.size} Markdown files, " +
                "exceeding the safety cap of $MAX_MD_FILE_COUNT."
        }
        val hasKumlScripts = allFiles.any { it.name.endsWith(".kuml.kts") }

        val declaredMode = marker?.mode?.takeIf { it != WorkspaceMode.UNKNOWN }

        val mode =
            declaredMode
                ?: when {
                    hasKumlScripts -> WorkspaceMode.ENGINEERING
                    mdFiles.any { it.name == "index.md" } && mdFiles.size > 1 -> WorkspaceMode.KNOWLEDGE
                    else -> WorkspaceMode.UNKNOWN
                }

        val documents =
            mdFiles
                .sortedBy { it.relativeTo(root).path }
                .map { OkfDocumentParser.parseFile(root = root, file = it) }

        return OkfWorkspace(root = root, mode = mode, markerFound = markerFound, documents = documents, marker = marker)
    }
}
