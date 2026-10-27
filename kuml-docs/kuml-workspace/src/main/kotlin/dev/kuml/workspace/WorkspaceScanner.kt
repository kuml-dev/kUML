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
 * The scan root is first normalised via [canonicalRoot], so `.`, `..` and symlinked roots work.
 * Hidden directories (name starts with `.`, except the root itself) and any directory in [excludeDirs]
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
    internal const val MAX_MD_FILE_COUNT = 20_000

    /**
     * Absolute + lexically normalised form of [root] (`.`, `..` and `sub/..` segments resolved).
     * Deliberately NOT `toRealPath`: that would turn `/var` into `/private/var` on macOS and break
     * callers (Desktop, tests) that compare [OkfDocument.file] against non-resolved paths.
     */
    public fun canonicalRoot(root: File): File = root.absoluteFile.normalize()

    /**
     * Single source of truth for "is this directory descended into?" — shared by [scan] and
     * [WorkspaceDocumentResolver]. The scan root itself is always walkable (even if its own name
     * starts with `.`, such as `.` or `~/.notes`, or if it is itself a symbolic link);
     * hidden sub-directories, excluded directories and symbolic-link directories are not.
     */
    internal fun isWalkableDir(
        dir: File,
        scanRoot: File,
        excludeCanonical: Set<File>,
    ): Boolean =
        dir == scanRoot ||
            (
                !dir.name.startsWith(".") &&
                    dir.absoluteFile.normalize() !in excludeCanonical &&
                    !Files.isSymbolicLink(dir.toPath())
            )

    internal fun canonicalExcludes(excludeDirs: Set<File>): Set<File> = excludeDirs.map { it.absoluteFile.normalize() }.toSet()

    /**
     * Parses exactly one document ([relativePath], relative to [root]) without scanning the rest of
     * the workspace. The path is validated by [WorkspaceDocumentResolver.resolve]; any violation is
     * returned as a failure carrying a [WorkspaceDocumentResolver.DocumentResolutionException].
     */
    public fun scanDocument(
        root: File,
        relativePath: String,
        excludeDirs: Set<File> = emptySet(),
    ): Result<OkfDocument> {
        val scanRoot = canonicalRoot(root)
        val file =
            WorkspaceDocumentResolver
                .resolve(root = scanRoot, requested = relativePath, excludeDirs = excludeDirs)
                .getOrElse { return Result.failure(it) }
        return try {
            Result.success(OkfDocumentParser.parseFile(root = scanRoot, file = file))
        } catch (_: java.io.IOException) {
            Result.failure(
                WorkspaceDocumentResolver.DocumentResolutionException(
                    rejection = WorkspaceDocumentResolver.Rejection.IO_ERROR,
                    requested = relativePath,
                    suggestion = null,
                ),
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    public fun scan(
        root: File,
        excludeDirs: Set<File> = emptySet(),
    ): OkfWorkspace {
        val scanRoot = canonicalRoot(root)
        val excludeCanonical = canonicalExcludes(excludeDirs)
        val markerFile = File(scanRoot, ".kuml-workspace.toml")
        val markerFound = markerFile.isFile
        val marker = if (markerFound) WorkspaceMarkerParser.parse(markerFile.readText()) else null

        val allFiles =
            scanRoot
                .walkTopDown()
                .onEnter { dir -> isWalkableDir(dir = dir, scanRoot = scanRoot, excludeCanonical = excludeCanonical) }
                .filter { it.isFile }
                .toList()

        val mdFiles = allFiles.filter { it.extension == "md" }
        require(mdFiles.size <= MAX_MD_FILE_COUNT) {
            "Workspace scan aborted: $scanRoot contains ${mdFiles.size} Markdown files, " +
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
                .sortedBy { it.relativeTo(scanRoot).path }
                .map { OkfDocumentParser.parseFile(root = scanRoot, file = it) }

        return OkfWorkspace(root = scanRoot, mode = mode, markerFound = markerFound, documents = documents, marker = marker)
    }
}
