package dev.kuml.workspace

import java.io.File

/** Severity of an [OkfFinding]. */
public enum class OkfSeverity { ERROR, WARNING }

/**
 * A single OKF conformance finding (ADR-0011 spike rule set), structured like
 * the `KumlError` schema used elsewhere in the CLI (code/severity/file/line/message/suggestion)
 * so it can be JSON-serialised the same way.
 */
public data class OkfFinding(
    public val code: String,
    public val severity: OkfSeverity,
    public val file: String,
    public val line: Int,
    public val message: String,
    public val suggestion: String? = null,
)

/**
 * Structural OKF-conformance checks over a scanned [OkfWorkspace] (ADR-0011, FT-1/FT-2).
 *
 * Spike rule set:
 * - `OKF-E-001` — frontmatter missing or has no `type:` field.
 * - `OKF-W-002` — unrecognised `type:` value (custom vocabulary is allowed, warning only —
 *   escalated to `ERROR` under [validate]'s `strictVocabulary` mode).
 * - `OKF-E-003` — a diagram type ([OkfType.requiresKumlBlock]) has no ` ```kuml ` block.
 * - `OKF-W-004` — a document has more than one ` ```kuml ` block ("one file = one diagram" discipline).
 * - `OKF-E-005` — a relative Markdown link to a `.md` target that does not exist (cross-link integrity).
 * - `OKF-W-006` — the workspace root has no `index.md` with `type: KumlWorkspace`.
 *
 * All messages are in English, matching the kUML convention for structured
 * findings (see [dev.kuml.cli.validate.StructuralViolation] / `KumlError`).
 */
public object OkfValidator {
    /**
     * @param strictVocabulary When `true`, an unrecognised `type:` value ([checkKnownType],
     *  `OKF-W-002`) is reported at [OkfSeverity.ERROR] instead of [OkfSeverity.WARNING]. The
     *  finding code stays `OKF-W-002` in both modes — only the severity changes — so JSON
     *  consumers keep a stable code space instead of forking into a parallel `OKF-E-002`.
     */
    public fun validate(
        ws: OkfWorkspace,
        strictVocabulary: Boolean = false,
    ): List<OkfFinding> {
        val findings = mutableListOf<OkfFinding>()

        for (doc in ws.documents) {
            findings += checkFrontmatterPresence(doc)
            findings += checkKnownType(doc, strictVocabulary)
            findings += checkDiagramBlockPresence(doc)
            findings += checkBlockCount(doc)
            findings += checkLinks(ws.root, doc)
        }

        findings += checkIndexDocument(ws)

        return findings
    }

    private fun checkFrontmatterPresence(doc: OkfDocument): List<OkfFinding> {
        if (!doc.frontmatter.present || doc.rawType == null) {
            return listOf(
                OkfFinding(
                    code = "OKF-E-001",
                    severity = OkfSeverity.ERROR,
                    file = doc.relativePath,
                    line = 1,
                    message = "Missing frontmatter or 'type:' field.",
                    suggestion = "Add a leading YAML frontmatter block with a 'type:' field, e.g. 'type: Concept'.",
                ),
            )
        }
        return emptyList()
    }

    private fun checkKnownType(
        doc: OkfDocument,
        strictVocabulary: Boolean,
    ): List<OkfFinding> {
        if (doc.rawType != null && doc.type == null) {
            val didYouMean = Levenshtein.closest(doc.rawType, OkfType.entries.map { it.id }, maxDistance = 3)
            val suggestion =
                buildString {
                    append("Custom types are allowed, but double-check for typos against the OKF vocabulary (ADR-0011).")
                    if (didYouMean != null) append(" Did you mean '$didYouMean'?")
                }
            return listOf(
                OkfFinding(
                    code = "OKF-W-002",
                    severity = if (strictVocabulary) OkfSeverity.ERROR else OkfSeverity.WARNING,
                    file = doc.relativePath,
                    line = 1,
                    message = "Unrecognised 'type: ${doc.rawType}' — not part of the OKF vocabulary.",
                    suggestion = suggestion,
                ),
            )
        }
        return emptyList()
    }

    private fun checkDiagramBlockPresence(doc: OkfDocument): List<OkfFinding> {
        val type = doc.type ?: return emptyList()
        if (type.requiresKumlBlock && doc.kumlBlocks.isEmpty()) {
            return listOf(
                OkfFinding(
                    code = "OKF-E-003",
                    severity = OkfSeverity.ERROR,
                    file = doc.relativePath,
                    line = 1,
                    message = "Document declares 'type: ${type.id}' but contains no ```kuml block.",
                    suggestion = "Add a ```kuml fenced code block with the diagram DSL, or change 'type:' to a non-diagram type.",
                ),
            )
        }
        return emptyList()
    }

    private fun checkBlockCount(doc: OkfDocument): List<OkfFinding> {
        if (doc.kumlBlocks.size > 1) {
            return listOf(
                OkfFinding(
                    code = "OKF-W-004",
                    severity = OkfSeverity.WARNING,
                    file = doc.relativePath,
                    line = doc.kumlBlocks[1].startLine,
                    message = "Document contains ${doc.kumlBlocks.size} ```kuml blocks; OKF convention is one file = one diagram.",
                    suggestion = "Split additional diagrams into their own Markdown files.",
                ),
            )
        }
        return emptyList()
    }

    private fun checkLinks(
        root: File,
        doc: OkfDocument,
    ): List<OkfFinding> {
        val findings = mutableListOf<OkfFinding>()
        for (link in doc.links) {
            val target = link.target
            if (target.contains("://") || target.startsWith("mailto:") || target.startsWith("#")) continue
            val withoutAnchor = target.substringBefore('#')
            if (!withoutAnchor.endsWith(".md")) continue
            val resolved = File(doc.file.parentFile, withoutAnchor).normalize()
            if (!resolved.exists()) {
                findings +=
                    OkfFinding(
                        code = "OKF-E-005",
                        severity = OkfSeverity.ERROR,
                        file = doc.relativePath,
                        line = link.line,
                        message = "Broken link: '$target' does not resolve to an existing file.",
                        suggestion = "Fix the link target or create the missing file.",
                    )
            }
        }
        return findings
    }

    private fun checkIndexDocument(ws: OkfWorkspace): List<OkfFinding> {
        val hasWorkspaceIndex =
            ws.documents.any { it.relativePath == "index.md" && it.type == OkfType.KUML_WORKSPACE }
        if (!hasWorkspaceIndex) {
            return listOf(
                OkfFinding(
                    code = "OKF-W-006",
                    severity = OkfSeverity.WARNING,
                    file = "index.md",
                    line = 1,
                    message = "Workspace root has no index.md with 'type: KumlWorkspace'.",
                    suggestion = "Add an index.md at the workspace root with 'type: KumlWorkspace' as a navigational entry point.",
                ),
            )
        }
        return emptyList()
    }
}
