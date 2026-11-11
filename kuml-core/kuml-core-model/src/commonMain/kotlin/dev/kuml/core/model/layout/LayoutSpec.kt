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
 * @property labelMaxWidth wrap width of edge labels in px (state machines only, 120..400, default 220)
 * @property showSubjectEdges requirement diagrams only (wave 2E-a): draw a «subject» edge for every `subjectRef`;
 *   `null` / `false` (the default) shows the subject as a text line in the card instead
 * @property actorSide use-case diagrams only (wave 2E-b): which side of the subject frame the actors stand on
 *   (`null` = [ActorSide.Auto])
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
    val labelMaxWidth: Int? = null,
    val showSubjectEdges: Boolean? = null,
    val actorSide: ActorSide? = null,
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
                aspectRatio == null &&
                labelMaxWidth == null &&
                showSubjectEdges == null &&
                actorSide == null

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
            labelMaxWidth = next.labelMaxWidth ?: labelMaxWidth,
            showSubjectEdges = next.showSubjectEdges ?: showSubjectEdges,
            actorSide = next.actorSide ?: actorSide,
        )
}

/** The options of a `layout { }` block; used by the effect matrix and by diagnostics. */
enum class LayoutOption {
    DIRECTION,
    WRAP_COLUMNS,
    SAME_LAYER,
    FIRST,
    LAST,
    MAX_WIDTH,
    ASPECT_RATIO,
    LABEL_MAX_WIDTH,
    SHOW_SUBJECT_EDGES,
    ACTOR_SIDE,
}

/** Where the actors of a use-case diagram stand relative to the subject frame (wave 2E-b). */
@Serializable
enum class ActorSide { Auto, Left, Right, Both }

/** Hard bounds for [LayoutSpec] values (DoS protection against hostile input). */
object LayoutLimits {
    const val WRAP_COLUMNS_MAX = 32
    const val GROUPS = 32
    const val IDS_PER_GROUP = 64
    const val MAX_WIDTH_MIN = 200
    const val MAX_WIDTH_MAX = 20_000
    const val LABEL_MAX_WIDTH_MIN = 120
    const val LABEL_MAX_WIDTH_MAX = 400
    const val ASPECT_MIN = 0.1
    const val ASPECT_MAX = 10.0

    /** Pictures inside `1/IMPLICIT_FRAME .. IMPLICIT_FRAME` are never re-wrapped implicitly. */
    const val IMPLICIT_FRAME = 2.5
    const val ASPECT_TOLERANCE = 0.15
    const val MAX_ITERATIONS = 2

    /** Wave 2E-a group grid (requirement diagrams): gap between grid cells in px. */
    const val GRID_GUTTER_PX = 40f

    /** Gap between the block rail and the grid in px. */
    const val RAIL_GAP_PX = 48f

    /** Minimum gap between two blocks of the rail in px. */
    const val RAIL_BLOCK_GAP_PX = 24f

    /** Target width / height of the automatic column count when only `aspectRatio` / `maxWidth` is set. */
    const val GRID_DEFAULT_ASPECT = 1.6

    /** At most this many groups take part in the grid (matches the requirement checker's group limit). */
    const val GRID_MAX_GROUPS = 64
}

/** Diagnostic codes of the `layout { }` block. */
object LayoutCodes {
    const val NO_EFFECT = "KUML-LAYOUT-W001"
    const val MAX_WIDTH_UNREACHED = "KUML-LAYOUT-W002"
    const val ASPECT_UNREACHED = "KUML-LAYOUT-W003"
    const val UNKNOWN_ID = "KUML-LAYOUT-W004"

    /** A label of a parametric diagram has no free spot and sits on its own line (PAL wave 2C-b3). */
    const val LABEL_NO_FREE_SPOT = "KUML-LAYOUT-W005"

    /** An interruptible-region frame still overlaps a node that is not a member of it (PAL wave 2F). */
    const val REGION_OVERLAP = "KUML-LAYOUT-W006"

    /** A requirement stands in two groups; only the first one in declaration order holds it (Nacharbeit 55). */
    const val DUPLICATE_GROUP_MEMBERSHIP = "KUML-LAYOUT-W007"

    /**
     * A `first()` / `last()` pin sits on a node whose edges contradict it (a `last` node with outgoing edges, a `first` node
     * with incoming ones); the engine draws those edges against the flow direction (Nacharbeit 79). The pin is kept.
     */
    const val LAYER_PIN_CONFLICT = "KUML-LAYOUT-W008"
    const val OUT_OF_RANGE = "KUML-LAYOUT-E001"

    /** Informational: a `sameLayer` of groups widens its row beyond the column count (wave 2E-a). Not an error. */
    const val SAME_LAYER_WIDENS_ROW = "KUML-LAYOUT-I001"
}
