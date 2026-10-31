package dev.kuml.core.model.layout

import kotlinx.serialization.Serializable

/** Main flow direction of a diagram, as written in a `layout { direction = Right }` block. */
@Serializable
enum class LayoutFlow { Down, Right, Up, Left }

/**
 * Layout steering hints of one diagram (PAL wave 2B: one shared type for all diagram families).
 * Deliberately coarse: no pixel positions, no pairwise relative constraints — only the flow
 * direction, a column wrap, ordering of whole layers and two soft/hard size targets.
 *
 * The serialised form of the first five fields is identical to the former SysML 2 only spec,
 * the new fields are optional with defaults, so old JSON keeps decoding.
 *
 * @property direction main flow direction; `null` keeps the diagram type's default
 * @property wrapColumns at most this many nodes per layer; further nodes of the layer move to the next layers
 * @property sameLayer groups of element ids that are placed in the same layer
 * @property first element ids pinned to the first layer
 * @property last element ids pinned to the last layer
 * @property maxWidth hard upper bound of the diagram width in px
 * @property aspectRatio soft target for width / height
 */
@Serializable
data class LayoutSpec(
    val direction: LayoutFlow? = null,
    val wrapColumns: Int? = null,
    val sameLayer: List<List<String>> = emptyList(),
    val first: List<String> = emptyList(),
    val last: List<String> = emptyList(),
    val maxWidth: Int? = null,
    val aspectRatio: Double? = null,
) {
    /** True if the spec carries no hint at all. */
    val isEmpty: Boolean
        get() =
            direction == null &&
                wrapColumns == null &&
                sameLayer.isEmpty() &&
                first.isEmpty() &&
                last.isEmpty() &&
                maxWidth == null &&
                aspectRatio == null

    /** Merges [next] onto this spec: scalars of [next] win when set, lists are appended. */
    fun mergedWith(next: LayoutSpec): LayoutSpec =
        LayoutSpec(
            direction = next.direction ?: direction,
            wrapColumns = next.wrapColumns ?: wrapColumns,
            sameLayer = sameLayer + next.sameLayer,
            first = first + next.first,
            last = last + next.last,
            maxWidth = next.maxWidth ?: maxWidth,
            aspectRatio = next.aspectRatio ?: aspectRatio,
        )
}

/** The options of a `layout { }` block; used by the effect matrix and by diagnostics. */
enum class LayoutOption { DIRECTION, WRAP_COLUMNS, SAME_LAYER, FIRST, LAST, MAX_WIDTH, ASPECT_RATIO }

/** Hard bounds for [LayoutSpec] values (DoS protection against hostile input). */
object LayoutLimits {
    const val WRAP_COLUMNS_MAX = 32
    const val GROUPS = 32
    const val IDS_PER_GROUP = 64
    const val MAX_WIDTH_MIN = 200
    const val MAX_WIDTH_MAX = 20_000
    const val ASPECT_MIN = 0.1
    const val ASPECT_MAX = 10.0

    /** Pictures inside `1/IMPLICIT_FRAME .. IMPLICIT_FRAME` are never re-wrapped implicitly. */
    const val IMPLICIT_FRAME = 2.5
    const val ASPECT_TOLERANCE = 0.15
    const val MAX_ITERATIONS = 2
}

/** Diagnostic codes of the `layout { }` block. */
object LayoutCodes {
    const val NO_EFFECT = "KUML-LAYOUT-W001"
    const val MAX_WIDTH_UNREACHED = "KUML-LAYOUT-W002"
    const val ASPECT_UNREACHED = "KUML-LAYOUT-W003"
    const val UNKNOWN_ID = "KUML-LAYOUT-W004"
    const val OUT_OF_RANGE = "KUML-LAYOUT-E001"
}
