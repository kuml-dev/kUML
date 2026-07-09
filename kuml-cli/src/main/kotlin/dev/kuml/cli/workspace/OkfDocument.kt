package dev.kuml.cli.workspace

import dev.kuml.markdown.KumlCodeBlock
import java.io.File

/** A Markdown link target found in an [OkfDocument]'s body, with its 1-based source line. */
internal data class MarkdownLink(
    val target: String,
    val line: Int,
)

/**
 * A single Markdown document inside an [OkfWorkspace], with its frontmatter and
 * extracted ` ```kuml ` blocks / links already parsed.
 *
 * @property file Absolute path to the source file.
 * @property relativePath POSIX-style path relative to the workspace root (`/`-separated,
 *  even on Windows) — stable across OSes for use in findings/reports.
 * @property frontmatter Parsed YAML frontmatter (see [FrontmatterParser]).
 * @property type The resolved [OkfType], or `null` if `type:` is absent or unrecognised.
 * @property rawType The raw `type:` string as written in the frontmatter, unresolved.
 * @property kumlBlocks All ` ```kuml ` fenced code blocks in the document, in source order.
 * @property links All Markdown links (`[text](target)`) found in the document body.
 */
internal data class OkfDocument(
    val file: File,
    val relativePath: String,
    val frontmatter: Frontmatter,
    val type: OkfType?,
    val rawType: String?,
    val kumlBlocks: List<KumlCodeBlock>,
    val links: List<MarkdownLink>,
)

/** The declared or inferred mode of an [OkfWorkspace] (ADR-0011). */
internal enum class WorkspaceMode { KNOWLEDGE, ENGINEERING, UNKNOWN }

/**
 * A scanned OKF workspace: its root directory, resolved [mode], whether a
 * `.kuml-workspace.toml` marker was found, and every Markdown document discovered.
 */
internal data class OkfWorkspace(
    val root: File,
    val mode: WorkspaceMode,
    val markerFound: Boolean,
    val documents: List<OkfDocument>,
)
