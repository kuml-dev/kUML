package dev.kuml.workspace

import dev.kuml.markdown.CodeBlockExtractor
import java.io.File

/**
 * Parses a single Markdown document's text into an [OkfDocument] (extracted out of
 * [WorkspaceScanner] so a caller that already holds a buffer in memory — the kUML Desktop
 * document editor — can re-parse it without a round trip through the filesystem).
 *
 * [parse] is pure string handling (no I/O at all); [parseFile] adds the DoS guardrail
 * ([MAX_MD_FILE_SIZE_BYTES]) and the actual file read on top of it. [WorkspaceScanner.scan]
 * delegates to [parseFile] for every discovered `*.md` file — this object is the sole owner
 * of the per-document parsing logic.
 */
public object OkfDocumentParser {
    private val MARKDOWN_LINK = Regex("""\[[^\]]*]\(([^)]+)\)""")

    /**
     * DoS guardrail (defence-in-depth, ADR-0011 spike security review): a single OKF
     * document should never be able to exhaust memory on adversarial or accidental input.
     * Moved here from `WorkspaceScanner` (V-next, editable-workspace welle) so the desktop
     * document writer can enforce the exact same cap on an in-memory buffer before it is
     * ever written to disk — see `OkfDocumentWriter`.
     */
    public const val MAX_MD_FILE_SIZE_BYTES: Long = 20L * 1024 * 1024 // 20 MiB per document

    /**
     * Parses [text] as the OKF document at [file] (relative to [root]). Pure string
     * parsing — [file] is used only to compute [OkfDocument.relativePath] and is never
     * read from disk here.
     */
    public fun parse(
        root: File,
        file: File,
        text: String,
    ): OkfDocument {
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

    /**
     * Reads [file] (UTF-8) and parses it via [parse]. Throws [IllegalArgumentException]
     * when [file] exceeds [MAX_MD_FILE_SIZE_BYTES] — the same guardrail `WorkspaceScanner`
     * has always enforced, checked here before any content is read into memory.
     */
    public fun parseFile(
        root: File,
        file: File,
    ): OkfDocument {
        require(file.length() <= MAX_MD_FILE_SIZE_BYTES) {
            "Refusing to parse ${file.relativeTo(root).path}: ${file.length()} bytes exceeds " +
                "the safety cap of $MAX_MD_FILE_SIZE_BYTES bytes."
        }
        val text = file.readText(Charsets.UTF_8)
        return parse(root = root, file = file, text = text)
    }

    /** All Markdown links (`[text](target)`) in [text], with their 1-based source line. */
    public fun extractLinks(text: String): List<MarkdownLink> {
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
