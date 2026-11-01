package dev.kuml.io.svg.sysml2

import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt3
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.GroupLayout
import dev.kuml.sysml2.ActivityPartitionDefinition

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
    headerHeight: Float = PARTITION_HEADER_HEIGHT,
) {
    val gx = groupLayout.bounds.origin.x + paddingPx
    val gy = groupLayout.bounds.origin.y + paddingPx
    val gw = groupLayout.bounds.size.width
    val gh = groupLayout.bounds.size.height

    builder.tag(
        name = "g",
        attrs =
            mapOf(
                "id" to xmlEscapeAttr("activityPartition:${partition.id}"),
                "transform" to "translate(${fmtPart(gx)},${fmtPart(gy)})",
            ),
    ) {
        // Wave 2F: a lane is a plain column. No band fill; a 1 px outline in the edge colour (neighbouring lanes
        // share the line) and a neutral header band. The name is painted later, over the edges.
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "width" to fmtPart(gw),
                    "height" to fmtPart(gh),
                    "class" to "kuml-lane",
                ),
        )
        tag(
            name = "rect",
            attrs =
                mapOf(
                    "width" to fmtPart(gw),
                    "height" to fmtPart(headerHeight),
                    "class" to "kuml-lane-header-band",
                ),
        )
    }
}

/**
 * Wave C2b: draws the name of a swimlane after every edge, on its own backdrop (the band colour). The
 * name sits flush left unless an edge of [edges] crosses that spot, then it slides to the first free
 * gap ([placeFrameLabel]); with no free spot it stays left and the group is marked
 * `data-kuml-fallback="occluded"`.
 */
internal fun renderActivityPartitionHeader(
    partition: ActivityPartitionDefinition,
    groupLayout: GroupLayout,
    paddingPx: Float,
    builder: SvgBuilder,
    edges: List<List<dev.kuml.layout.Point>> = emptyList(),
    headerHeight: Float = PARTITION_HEADER_HEIGHT,
) {
    val gx = groupLayout.bounds.origin.x + paddingPx
    val gy = groupLayout.bounds.origin.y + paddingPx
    val gw = groupLayout.bounds.size.width
    // Wave 2F: the name is wrapped to the lane width (never shortened), upright, semi-bold, centred.
    val lines =
        dev.kuml.layout.bridge.ActTextWrap
            .wrapActName(text = partition.name, maxWidthPx = gw - 16f, maxLines = 6)
    builder.tag(
        name = "g",
        attrs =
            mapOf(
                "id" to xmlEscapeAttr("activityPartitionHeader:${partition.id}"),
                "transform" to "translate(${fmtPart(gx)},${fmtPart(gy)})",
                "class" to "kuml-lane-header",
            ),
    ) {
        tag(
            name = "text",
            attrs =
                mapOf(
                    "class" to "kuml-title",
                    "text-anchor" to "middle",
                    "style" to "font-size:13px;font-weight:600;font-style:normal",
                ),
        ) {
            lines.forEachIndexed { i, line ->
                tag(
                    name = "tspan",
                    attrs = mapOf("x" to fmtPart(gw / 2f), "y" to fmtPart(18f + i * 16f)),
                ) { text(line) }
            }
        }
    }
}

/**
 * Height (px) of the solid header bar at the top of a swimlane (V2.0.16).
 * Roomy enough to fit a `«stereotype»`-style label without crowding the
 * action boxes below.
 */
internal const val PARTITION_HEADER_HEIGHT: Float = 28f

/**
 * SVG `stroke-dasharray` pattern for the swimlane outer outline (V2.0.16).
 * Long-dash-short-gap rhythm reads as a "container" boundary without
 * competing with solid action box borders.
 */
internal const val PARTITION_BORDER_DASH: String = "6 4"

private fun fmtPart(v: Float): String = fmt3(v)
