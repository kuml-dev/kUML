package dev.kuml.erm.model

import kotlinx.serialization.Serializable

/**
 * ERM notation style used when rendering an [ErmDiagram].
 *
 * `MARTIN` is the "crow's foot" notation and the default. Unlike Blueprint's
 * two diagram classes for two projections, ERM needs no `sealed` diagram
 * hierarchy — one diagram class with a [notation] field covers all four
 * styles, since they only change how cardinalities/relationships are drawn,
 * not the underlying element selection.
 *
 * Rendering the different notations is out of scope for V3.4.1 (comes with
 * the renderer in V3.4.2) — this is purely a model-level property.
 *
 * V3.4.1
 */
@Serializable
enum class ErmNotation { MARTIN, BACHMAN, CHEN, IDEF1X }

/**
 * A named diagram view (projection) over an [ErmModel].
 *
 * Empty [elementIds] means the whole model is projected. [showViews] and
 * [showIndexes] toggle whether first-class [ErmView]s / [ErmIndex]es are
 * drawn as part of the projection.
 *
 * V3.4.1
 */
@Serializable
data class ErmDiagram(
    val name: String,
    val notation: ErmNotation = ErmNotation.MARTIN,
    val elementIds: List<String> = emptyList(),
    val showViews: Boolean = true,
    val showIndexes: Boolean = false,
)
