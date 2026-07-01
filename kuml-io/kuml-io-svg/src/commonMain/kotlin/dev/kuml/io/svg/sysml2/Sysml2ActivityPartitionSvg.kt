package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.GroupLayout
import dev.kuml.sysml2.ActivityPartitionDefinition
import dev.kuml.io.svg.fmt3

/**
 * Renders a SysML-2 [ActivityPartitionDefinition] as a swimlane container
 * (V2.0.16) — the dashed vertical lane with a solid header bar that wraps
 * all action nodes whose `partitionId` references this partition.
 *
 * **Pipeline position** — called from the ACT-overload of `KumlSvgRenderer.toSvg`
 * **BEFORE** the standard node loop. The action nodes (and any edges
 * between them) are rendered on top of the lane outline, so the swimlane
 * contour stays visually behind the foreground graph elements. The C4
 * `«system»`-group rendering follows the same back-to-front convention.
 *
 * **Visual encoding**:
 *  - Outer dashed rectangle (`stroke-dasharray="6 4"`) using the
 *    `kuml-class` stroke style — gives the lane a recognisable
 *    "swimlane outline" look without competing with action box borders.
 *  - Solid header bar at the top of the lane (height
 *    [PARTITION_HEADER_HEIGHT]) filled with a light theme-aware tint;
 *    the partition name is centred horizontally inside the header
 *    using the `kuml-stereotype` text class so it visually matches the
 *    stereotype headers on action boxes.
 *
 * The function is a no-op when the bridge did not allocate bounds for the
 * partition (i.e. the layout-engine couldn't fit the group). That happens
 * when the model has a partition definition but no visible actions
 * reference it — silent skip mirrors the bridge's silent-skip policy on
 * dangling references.
 *
 * V2.x — out of scope:
 *  - Horizontal lanes (lanes that run left-to-right with time flowing across).
 *  - Hierarchical / nested partitions.
 *  - Custom header colours per partition (today the renderer uses a single
 *    theme tint; per-partition colour is a future polish wave that needs
 *    a `colour` slot on [ActivityPartitionDefinition]).
 *  - LaTeX rendering of partitions — SVG-only in V2.0.16.
 */
internal fun renderActivityPartitionGroup(
    partition: ActivityPartitionDefinition,
    groupLayout: GroupLayout,
    paddingPx: Float,
    builder: SvgBuilder,
) {
    val gx = groupLayout.bounds.origin.x + paddingPx
    val gy = groupLayout.bounds.origin.y + paddingPx
    val gw = groupLayout.bounds.size.width
    val gh = groupLayout.bounds.size.height

    builder.tag(
        "g",
        mapOf(
            "id" to xmlEscapeAttr("activityPartition:${partition.id}"),
            "transform" to "translate(${fmtPart(gx)},${fmtPart(gy)})",
        ),
    ) {
        // 1. Outer dashed lane outline — encompasses the whole partition
        //    height including the header bar. White fill so action nodes
        //    on top render against a clean background instead of the
        //    canvas tint.
        tag(
            "rect",
            mapOf(
                "width" to fmtPart(gw),
                "height" to fmtPart(gh),
                "class" to "kuml-class",
                "fill" to "white",
                "stroke-dasharray" to PARTITION_BORDER_DASH,
            ),
        )

        // 2. Solid header bar at the top — visually identifies which lane
        //    this is. Uses a kuml-stereotype-class label so the header
        //    text harmonises with the «stereotype»-text on action boxes.
        //    The header is rendered as a separate rect (not a path with
        //    fill-rule) so SVG-2-strict viewers render it correctly.
        tag(
            "rect",
            mapOf(
                "width" to fmtPart(gw),
                "height" to fmtPart(PARTITION_HEADER_HEIGHT),
                "class" to "kuml-class",
                "fill" to "#f3f3f3",
            ),
        )

        // 3. Header label — partition name, centred horizontally in the
        //    header bar, baseline-anchored mid-header.
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmtPart(gw / 2f),
                "y" to fmtPart(PARTITION_HEADER_HEIGHT / 2f + 4f),
                "text-anchor" to "middle",
            ),
        ) { text(partition.name) }
    }
}

/**
 * Height (px) of the solid header bar at the top of a swimlane (V2.0.16).
 * Roomy enough to fit a `«stereotype»`-style label without crowding the
 * action boxes below.
 */
internal const val PARTITION_HEADER_HEIGHT: Float = 24f

/**
 * SVG `stroke-dasharray` pattern for the swimlane outer outline (V2.0.16).
 * Long-dash-short-gap rhythm reads as a "container" boundary without
 * competing with solid action box borders.
 */
internal const val PARTITION_BORDER_DASH: String = "6 4"

private fun fmtPart(v: Float): String = fmt3(v)
