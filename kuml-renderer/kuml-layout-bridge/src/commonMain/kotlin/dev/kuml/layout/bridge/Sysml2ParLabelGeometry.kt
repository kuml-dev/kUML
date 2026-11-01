package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutCodes
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.label.Sysml2LabelPlanResult
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model

/**
 * Obstacle geometry of a parametric diagram for the one shared label placer (PAL wave 2C-b3): the pin squares and the
 * estimated text rectangles of the constraint boxes, in the coordinates of the enriched layout (no canvas padding).
 */
internal object Sysml2ParLabelGeometry {
    /** Height of one text line rectangle (11 px text). */
    private const val TEXT_H: Float = 13f

    /** Distance from the baseline up to the top of the text rectangle. */
    private const val TEXT_ASCENT: Float = 10f

    fun pinRects(
        model: Sysml2Model,
        diagram: ParDiagram,
        layout: LayoutResult,
    ): List<Rect> {
        val nesting = ParNesting.of(model = model, visibleIds = diagram.elementIds)
        val half = ParConstraintGeometry.PIN_SIZE / 2f
        val out = ArrayList<Rect>()
        for ((id, c) in nesting.constraints) {
            val bounds = layout.nodes[NodeId(id)]?.bounds ?: continue
            c.parameters.indices.forEach { i ->
                val p = ParConstraintGeometry.pinPoint(constraint = c, index = i, bounds = bounds)
                out +=
                    Rect(origin = dev.kuml.layout.Point(x = p.x - half, y = p.y - half), size = Size(width = 2f * half, height = 2f * half))
            }
        }
        return out
    }

    fun textRects(
        model: Sysml2Model,
        diagram: ParDiagram,
        layout: LayoutResult,
    ): List<Rect> {
        val nesting = ParNesting.of(model = model, visibleIds = diagram.elementIds)
        val units = ParConstraintGeometry.unitLookup(model)
        val out = ArrayList<Rect>()
        for ((id, c) in nesting.constraints) {
            val b = layout.nodes[NodeId(id)]?.bounds ?: continue
            val x0 = b.origin.x
            val y0 = b.origin.y
            val w = b.size.width

            fun centred(
                baseline: Float,
                chars: Int,
                charPx: Float,
            ) {
                val tw = minOf(chars * charPx, w)
                out +=
                    Rect(
                        origin = dev.kuml.layout.Point(x = x0 + (w - tw) / 2f, y = y0 + baseline - TEXT_ASCENT),
                        size = Size(width = tw, height = TEXT_H),
                    )
            }
            centred(ParConstraintGeometry.STEREOTYPE_BASELINE, "«constraint»".length, Sysml2LayoutBridge.BDD_BODY_CHAR_PX)
            centred(
                ParConstraintGeometry.NAME_BASELINE,
                c.name.length.coerceAtMost(Sysml2Limits.TEXT),
                Sysml2LayoutBridge.IBD_TITLE_CHAR_PX,
            )
            val lines = ParConstraintGeometry.expressionLinesOf(c)
            lines.forEachIndexed { i, l ->
                centred(
                    ParConstraintGeometry.EXPRESSION_BASELINE + i * ParConstraintGeometry.EXPR_LINE_H,
                    l.length,
                    ParConstraintGeometry.EXPRESSION_CHAR_PX,
                )
            }
            c.parameters.forEachIndexed { i, p ->
                val tw =
                    minOf(
                        ParConstraintGeometry.parameterLine(p = p, unitOf = units).length * Sysml2LayoutBridge.BDD_BODY_CHAR_PX,
                        w - ParConstraintGeometry.PARAM_TEXT_INDENT,
                    )
                val base = ParConstraintGeometry.paramBaseline(expressionLines = lines.size, index = i)
                out +=
                    Rect(
                        origin = dev.kuml.layout.Point(x = x0 + ParConstraintGeometry.PARAM_TEXT_INDENT, y = y0 + base - TEXT_ASCENT),
                        size = Size(width = tw, height = TEXT_H),
                    )
            }
        }
        return out
    }

    fun warnings(plan: Sysml2LabelPlanResult): List<LayoutSpecFinding> =
        plan.labels.entries
            .filter { !it.value.clean }
            .sortedBy { it.key }
            .take(MAX_WARNINGS)
            .map {
                LayoutSpecFinding(
                    code = LayoutCodes.LABEL_NO_FREE_SPOT,
                    message = "The label of binding '${it.key.take(Sysml2Limits.TEXT)}' has no free spot and sits on its line.",
                )
            }

    private const val MAX_WARNINGS: Int = 64
}
