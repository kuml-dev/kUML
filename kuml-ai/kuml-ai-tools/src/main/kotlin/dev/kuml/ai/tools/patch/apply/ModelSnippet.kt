package dev.kuml.ai.tools.patch.apply

import kotlinx.serialization.Serializable

/**
 * Textual representation of the relevant slice of a model — for diff display only.
 *
 * The text is NOT a full DSL dump; it includes only the elements that a patch
 * touches (plus an N=2 context window around each touched element). This keeps
 * the diff narrow in V3.0.24's preview dialog.
 */
@Serializable
public data class ModelSnippet(
    /** Element ids included in this snippet, in stable order. */
    val elementIds: List<String>,
    /** Pretty-printed textual representation (DSL-flavoured, monospace-rendered). */
    val text: String,
)
