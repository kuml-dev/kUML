package dev.kuml.ai.tools.patch

import dev.kuml.ai.tools.patch.apply.ModelSnippet
import kotlinx.serialization.Serializable

/**
 * Data fed to V3.0.24's diff-preview dialog.
 *
 * The dialog is OUT OF SCOPE in V3.0.25 — this struct must be stable enough that
 * the UI can be added without renaming any fields.
 *
 * The textual `before` / `after` snippets are NOT full DSL dumps; they include
 * only the elements that the patch touches (plus an N=2 context window around
 * each touched element). Keeps the diff narrow and avoids quadratic blowup
 * on large models.
 *
 * The optional SVG paths are populated only when [PatchApplyEngine.diff] was
 * called with rendering enabled (UI hint — backend builds them on demand).
 */
@Serializable
public data class PatchDiff(
    val patchId: String,
    val before: ModelSnippet,
    val after: ModelSnippet,
    val elementChanges: List<ElementChange>,
    val svgBeforePath: String? = null,
    val svgAfterPath: String? = null,
)

/** A single element-level change within a [PatchDiff]. */
@Serializable
public data class ElementChange(
    val elementId: String,
    /** "added" | "removed" | "modified" */
    val kind: String,
    val before: String? = null,
    val after: String? = null,
)
