package dev.kuml.workspace

import dev.kuml.markdown.KumlCodeBlock
import java.io.File

/** A Markdown link target found in an [OkfDocument]'s body, with its 1-based source line. */
public data class MarkdownLink(
    public val target: String,
    public val line: Int,
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
 * @property typeLine 1-based line of the effective `type:` entry in the frontmatter (the last non-empty one,
 *  as the parser reads it), or `null`.
 */
public data class OkfDocument(
    public val file: File,
    public val relativePath: String,
    public val frontmatter: Frontmatter,
    public val type: OkfType?,
    public val rawType: String?,
    public val kumlBlocks: List<KumlCodeBlock>,
    public val links: List<MarkdownLink>,
    /** 1-based line of the effective `type:` entry in the frontmatter (the last non-empty one, as the parser reads it), or `null`. */
    public val typeLine: Int? = null,
)

/** The declared or inferred mode of an [OkfWorkspace] (ADR-0011). */
public enum class WorkspaceMode { KNOWLEDGE, ENGINEERING, UNKNOWN }

/**
 * A scanned OKF workspace: its root directory, resolved [mode], whether a
 * `.kuml-workspace.toml` marker was found, and every Markdown document discovered.
 *
 * @property marker The parsed `.kuml-workspace.toml` marker file, or `null` when
 *  [markerFound] is `false` (no marker file at the workspace root at all). Additive
 *  field — carries the [WorkspaceMarker] parsed by [WorkspaceMarkerParser] so callers
 *  (e.g. `workspace init`, a future Desktop viewer) can read `name`/`strict` without
 *  re-parsing the marker file themselves.
 */
public data class OkfWorkspace(
    public val root: File,
    public val mode: WorkspaceMode,
    public val markerFound: Boolean,
    public val documents: List<OkfDocument>,
    public val marker: WorkspaceMarker? = null,
)
