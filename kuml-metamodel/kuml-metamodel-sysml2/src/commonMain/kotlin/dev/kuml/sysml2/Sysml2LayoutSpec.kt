package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/** Main flow direction of a diagram, as written in a `layout { direction = Right }` block. */
@Serializable
enum class Sysml2LayoutFlow { Down, Right, Up, Left }

/**
 * Layout steering hints of one diagram (wave C3). Deliberately coarse: no pixel positions, no pairwise
 * relative constraints — only the flow direction, a column wrap and ordering of whole layers.
 *
 * @property direction main flow direction; `null` keeps the diagram type's default
 * @property wrapColumns at most this many nodes per layer; further nodes of the layer move to the next layers
 * @property sameLayer groups of element ids that are placed in the same layer
 * @property first element ids pinned to the first layer
 * @property last element ids pinned to the last layer
 */
@Serializable
data class Sysml2LayoutSpec(
    val direction: Sysml2LayoutFlow? = null,
    val wrapColumns: Int? = null,
    val sameLayer: List<List<String>> = emptyList(),
    val first: List<String> = emptyList(),
    val last: List<String> = emptyList(),
) {
    /** True if the spec carries no hint at all. */
    val isEmpty: Boolean
        get() = direction == null && wrapColumns == null && sameLayer.isEmpty() && first.isEmpty() && last.isEmpty()
}
