package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.label.StmLabelWrap
import dev.kuml.sysml2.edge.EdgeLabelPart

/** Horizontal alignment of the text inside a placed label box. */
internal enum class StmLabelAnchor { Left, Right, Center }

internal typealias StmLabelLine = dev.kuml.layout.bridge.label.StmLabelLine

/** A placed, wrapped STM edge label: [lines] of styled parts centred on ([cx], [cy]) inside a [w] x [h] box. */
internal data class StmLabelBlock(
    val lines: List<StmLabelLine>,
    val fullText: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val anchor: StmLabelAnchor = StmLabelAnchor.Center,
    /** Id of the placed label (the edge id); written to `data-label-id`. */
    val labelId: String = "",
)

/** Kind of a node-like obstacle for the label plan. */
internal enum class StmObstacleKind { Simple, Composite, Region }

/**
 * A node-like obstacle in final canvas coordinates. [Simple][StmObstacleKind.Simple] states and
 * pseudo-states block their whole rectangle. A [Composite][StmObstacleKind.Composite] contributes its frame,
 * header band ([headerBottom]), [dividers] and [regionNameRects]. A [Region][StmObstacleKind.Region] is not drawn
 * on its own and blocks nothing (its name and separators arrive via the composite).
 */
internal data class StmObstacleNode(
    val id: String,
    val kind: StmObstacleKind,
    val rect: Rect,
    val headerBottom: Float? = null,
    val regionNameRects: List<Rect> = emptyList(),
    val dividers: List<Pair<Point, Point>> = emptyList(),
)

/**
 * Text wrapping and box sizes of SysML 2 state-transition labels (PAL wave 2D-a). The implementation moved to
 * `StmLabelWrap` in the layout bridge (wave F) so the layout can reserve room for the labels; this object keeps the
 * names the renderer and its tests use.
 */
internal object StmLabelPlacer {
    const val CHAR_W: Float = StmLabelWrap.CHAR_W
    const val LINE_H: Float = StmLabelWrap.LINE_H
    const val PAD: Float = StmLabelWrap.PAD

    /** Default wrap width in px (`layout { labelMaxWidth = ... }` changes it, range 120..400). */
    const val DEFAULT_MAX_WIDTH_PX: Float = StmLabelWrap.DEFAULT_MAX_WIDTH_PX

    /** Number of characters that fit a line of [maxWidthPx]. */
    fun maxChars(maxWidthPx: Float): Int = StmLabelWrap.maxChars(maxWidthPx = maxWidthPx)

    /** See [StmLabelWrap.wrap]. */
    fun wrap(
        parts: List<EdgeLabelPart>,
        maxWidthPx: Float = DEFAULT_MAX_WIDTH_PX,
    ): List<StmLabelLine> = StmLabelWrap.wrap(parts = parts, maxWidthPx = maxWidthPx)

    /** Width and height of the box of [lines]. */
    fun boxSize(lines: List<StmLabelLine>): Size = StmLabelWrap.boxSize(lines = lines)
}
