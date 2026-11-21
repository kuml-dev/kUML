@file:Suppress("RequireNamedArguments") // geometry literals

package dev.kuml.io.latex.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmCategory
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCoord
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point

/**
 * IDEF1X glyphs as TikZ primitives, mirroring the SVG renderer (`ErmIdef1xEdgeSvg`, `ErmIdef1xCategorySvg` and the
 * dependent-entity rule of `KumlSvgRenderer.renderErmIdef1x`). The kuml-io-latex module does not depend on kuml-io-svg,
 * so these helpers are duplicated; parity is guarded by tests (Nacharbeit 126).
 */
internal object Idef1xTikz {
    /** Radius of the filled child-end dot. */
    const val DOT_RADIUS_PX: Float = 4f

    /** Half-diagonal of the open parent-end diamond. */
    const val DIAMOND_HALF_PX: Float = 6f

    /** Gap between the child dot and the cardinality letter. */
    const val CARDINALITY_GAP_PX: Float = 10f

    /** Corner radius of dependent entity boxes. */
    const val CORNER_RADIUS_PX: Float = 8f

    /** Inset of the weak-entity inner border (same value as the Martin renderer). */
    const val WEAK_BORDER_INSET_PX: Float = 3f

    private const val BAR_WIDTH_FACTOR: Float = 1.4f
    private const val BAR_GAP_PX: Float = 4f
    private const val BAR_SPACING_PX: Float = 4f
    private const val LABEL_GAP_PX: Float = 6f

    /** Corner radius of the inner border of a weak, dependent entity. */
    val innerCornerRadiusPx: Float get() = maxOf(CORNER_RADIUS_PX - WEAK_BORDER_INSET_PX, 0f)

    /** The IDEF1X child-end letter; `null` (blank) is the default "zero, one or many". Copy of `idef1xCardinalityLabel`. */
    fun cardinalityLabel(cardinality: Cardinality): String? =
        when {
            cardinality.min <= 0 && cardinality.max == 1 -> "Z"
            cardinality.min >= 1 && !cardinality.many -> "1"
            cardinality.min >= 1 && cardinality.many -> "P"
            else -> null
        }

    /** Entities drawn with rounded corners: weak, target of an identifying relationship, or subtype of a category. */
    fun dependentEntityIds(model: ErmModel): Set<String> =
        model.entities
            .filter { it.weak }
            .map { it.id }
            .toSet() +
            model.relationships
                .filter { it.kind == RelationshipKind.IDENTIFYING }
                .map { it.targetEntityId }
                .toSet() +
            model.categories.flatMap { it.subtypeEntityIds }.toSet()

    /** Filled dot touching the entity border at [anchor]. */
    fun appendChildDot(
        anchor: Point,
        dir: Pair<Float, Float>,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val c = ErmTikz.pt(anchor.x + dir.first * DOT_RADIUS_PX, anchor.y + dir.second * DOT_RADIUS_PX)
        out.appendLine("${options.indent}\\fill $c circle (${fmtCoord(DOT_RADIUS_PX)}pt);")
    }

    /** Open diamond whose apex touches the entity border at [anchor]. */
    fun appendParentDiamond(
        anchor: Point,
        dir: Pair<Float, Float>,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val (dx, dy) = dir
        val px = -dy
        val py = dx
        val midX = anchor.x + dx * DIAMOND_HALF_PX
        val midY = anchor.y + dy * DIAMOND_HALF_PX
        val apex = ErmTikz.pt(anchor.x, anchor.y)
        val left = ErmTikz.pt(midX + px * DIAMOND_HALF_PX, midY + py * DIAMOND_HALF_PX)
        val far = ErmTikz.pt(anchor.x + dx * DIAMOND_HALF_PX * 2f, anchor.y + dy * DIAMOND_HALF_PX * 2f)
        val right = ErmTikz.pt(midX - px * DIAMOND_HALF_PX, midY - py * DIAMOND_HALF_PX)
        out.appendLine("${options.indent}\\draw[line width=0.6pt, fill=white] $apex -- $left -- $far -- $right -- cycle;")
    }

    /** The cardinality letter just past the child dot. */
    fun appendCardinalityLabel(
        anchor: Point,
        dir: Pair<Float, Float>,
        label: String,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val offset = DOT_RADIUS_PX * 2f + CARDINALITY_GAP_PX
        val at = ErmTikz.pt(anchor.x + dir.first * offset, anchor.y + dir.second * offset)
        out.appendLine("${options.indent}\\node[kuml-feature, font=\\sffamily\\scriptsize, anchor=base] at $at {${escapeLatex(label)}};")
    }

    /** Category discriminator circle with one (partial) or two (complete) bars and the optional discriminator label. */
    fun appendCategory(
        category: ErmCategory,
        nodeLayout: NodeLayout,
        supertype: ErmEntity?,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        val x = nodeLayout.bounds.origin.x
        val y = nodeLayout.bounds.origin.y
        val w = nodeLayout.bounds.size.width
        val h = nodeLayout.bounds.size.height
        val cx = x + w / 2f
        val cy = y + h / 2f
        val r = minOf(w / 2f, h / 2f)
        val ind = options.indent
        out.appendLine("$ind\\draw[line width=0.6pt, fill=white] ${ErmTikz.pt(cx, cy)} circle (${fmtCoord(r)}pt);")
        val half = r * BAR_WIDTH_FACTOR / 2f
        val barY = y + h + BAR_GAP_PX
        bar(cx = cx, half = half, y = barY, options = options, out = out)
        if (category.complete) bar(cx = cx, half = half, y = barY + BAR_SPACING_PX, options = options, out = out)
        val name = category.discriminatorAttributeId?.let { id -> supertype?.attributes?.firstOrNull { it.id == id } }
        if (name != null) {
            out.appendLine(
                "$ind\\node[kuml-feature, font=\\sffamily\\scriptsize, anchor=base west] " +
                    "at ${ErmTikz.pt(x + w + LABEL_GAP_PX, cy + 4f)} {${escapeLatex(name.name ?: name.id)}};",
            )
        }
    }

    private fun bar(
        cx: Float,
        half: Float,
        y: Float,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        out.appendLine("${options.indent}\\draw[line width=0.6pt] ${ErmTikz.pt(cx - half, y)} -- ${ErmTikz.pt(cx + half, y)};")
    }
}
