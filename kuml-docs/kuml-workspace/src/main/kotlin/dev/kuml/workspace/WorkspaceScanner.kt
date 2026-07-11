package dev.kuml.workspace

import dev.kuml.markdown.CodeBlockExtractor
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
 *    resolved [OkfType], ` ```kuml ` blocks, and Markdown links.
 *
 * Hidden directories (name starts with `.`) and any directory in [excludeDirs]
 * (typically the render output directory) are skipped so that a second
 * `workspace render` run over the same root does not re-scan its own mirrored output.
 */
public object WorkspaceScanner {
    private val MARKDOWN_LINK = Regex("""\[[^\]]*]\(([^)]+)\)""")

    /**
     * DoS guardrails (defence-in-depth, ADR-0011 spike security review): a workspace scan
     * should never be able to hang or exhaust memory/disk on adversarial or accidental input
     * (a directory symlink cycle, a symlink escaping the root, a huge Markdown file, or an
     * enormous number of files).
     */
    private const val MAX_MD_FILE_COUNT = 20_000
    private const val MAX_MD_FILE_SIZE_BYTES = 20L * 1024 * 1024 // 20 MiB per document

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
                .map { parseDocument(root, it) }

        return OkfWorkspace(root = root, mode = mode, markerFound = markerFound, documents = documents, marker = marker)
    }

    private fun parseDocument(
        root: File,
        file: File,
    ): OkfDocument {
        require(file.length() <= MAX_MD_FILE_SIZE_BYTES) {
            "Refusing to parse ${file.relativeTo(root).path}: ${file.length()} bytes exceeds " +
                "the safety cap of $MAX_MD_FILE_SIZE_BYTES bytes."
        }
        val text = file.readText(Charsets.UTF_8)
        val frontmatter = FrontmatterParser.parse(text)
        val rawType = frontmatter.type
        val type = OkfType.fromId(rawType)
        val kumlBlocks = CodeBlockExtractor.extract(text)
        val links = extractLinks(text)
        val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
        return OkfDocument(
            file = file,
            relativePath = relativePath,
            frontmatter = frontmatter,
            type = type,
            rawType = rawType,
            kumlBlocks = kumlBlocks,
            links = links,
        )
    }

    private fun extractLinks(text: String): List<MarkdownLink> {
        val lines = text.split('\n')
        // Precompute cumulative offsets so we can map a match's char index to a 1-based line.
        val lineStartOffsets = IntArray(lines.size)
        var offset = 0
        for (i in lines.indices) {
            lineStartOffsets[i] = offset
            offset += lines[i].length + 1 // +1 for the '\n' consumed by split
        }

        fun lineOf(charIndex: Int): Int {
            var lo = 0
            var hi = lineStartOffsets.size - 1
            var result = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (lineStartOffsets[mid] <= charIndex) {
                    result = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return result + 1 // 1-based
        }

        return MARKDOWN_LINK
            .findAll(text)
            .map { m ->
                MarkdownLink(target = m.groupValues[1].trim(), line = lineOf(m.range.first))
            }.toList()
    }
}
