package dev.kuml.workspace

/**
 * Result of `kuml workspace convert` (FT-7) — mirrors the shape of
 * `WorkspaceRenderer.RenderReport` (`kuml-cli`) so both commands report
 * consistently.
 *
 * @property converted Relative paths (workspace-root/output-dir relative,
 *  `/`-separated) of the output files written — either `<stem>.kuml.kts`
 *  scripts (`--to kts`) or `<stem>.md` notes (`--to okf`).
 * @property skipped Relative paths of source documents/scripts with nothing to
 *  convert (`--to kts`: a knowledge document with no ` ```kuml ` block —
 *  `OKF-C-001`) or whose output already existed and `--force` was not given.
 * @property findings Warnings/errors accumulated during the run —
 *  `OKF-C-002` (multi-block split), `OKF-C-003` (un-mappable diagram type),
 *  `OKF-C-004` (script evaluation or write failure).
 */
public data class ConvertReport(
    public val converted: List<String>,
    public val skipped: List<String>,
    public val findings: List<OkfFinding>,
)
