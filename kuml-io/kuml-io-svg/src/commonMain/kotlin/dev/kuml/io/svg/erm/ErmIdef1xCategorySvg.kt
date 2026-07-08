package dev.kuml.io.svg.erm

import dev.kuml.erm.model.ErmCategory
import dev.kuml.erm.model.ErmEntity
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Renders an [ErmCategory] discriminator circle (V3.4.5) — the classic
 * IDEF1X "category cluster" glyph: a circle, with one horizontal
 * completeness bar underneath when [ErmCategory.complete] is `false`
 * (partial categorisation), or two parallel bars when `true` (every
 * supertype row belongs to exactly one subtype).
 *
 * The supertype→circle and circle→subtype connector lines are drawn by the
 * caller (`KumlSvgRenderer.renderErmIdef1x`) as plain `.kuml-edge` lines —
 * this function only draws the circle shape and its completeness bar(s).
 *
 * [discriminatorName], when non-null, is the resolved attribute name backing
 * [ErmCategory.discriminatorAttributeId] — drawn as a small label to the
 * right of the circle.
 */
internal fun renderIdef1xCategoryCircle(
    category: ErmCategory,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    b: SvgBuilder,
    discriminatorName: String? = null,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(cx, cy)

    b.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(category.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "circle",
            mapOf("cx" to fmt(cx), "cy" to fmt(cy), "r" to fmt(r), "class" to "kuml-erm-idef1x-category"),
        )

        val barWidth = r * BAR_WIDTH_FACTOR
        val barY1 = h + BAR_GAP_PX
        drawCompletenessBar(cx, barWidth, barY1, this)
        if (category.complete) {
            drawCompletenessBar(cx, barWidth, barY1 + BAR_SPACING_PX, this)
        }

        if (discriminatorName != null) {
            tag(
                "text",
                mapOf(
                    "class" to "kuml-erm-idef1x-card",
                    "x" to fmt(w + LABEL_GAP_PX),
                    "y" to fmt(cy + 4f),
                ),
            ) { text(discriminatorName) }
        }
    }
}

/** Draws a single horizontal completeness bar centered under the circle. */
private fun drawCompletenessBar(
    cx: Float,
    barWidth: Float,
    barY: Float,
    b: SvgBuilder,
) {
    b.tag(
        "line",
        mapOf(
            "x1" to fmt(cx - barWidth / 2f),
            "y1" to fmt(barY),
            "x2" to fmt(cx + barWidth / 2f),
            "y2" to fmt(barY),
            "class" to "kuml-erm-idef1x-completeness",
        ),
    )
}

/** Resolves the display name of [ErmCategory.discriminatorAttributeId] on the supertype [ErmEntity], if set. */
internal fun idef1xDiscriminatorName(
    category: ErmCategory,
    supertype: ErmEntity?,
): String? {
    val attrId = category.discriminatorAttributeId ?: return null
    return supertype?.attributes?.firstOrNull { it.id == attrId }?.let { it.name ?: it.id }
}

/** Fraction of the circle radius used as the completeness bar's half-width. */
private const val BAR_WIDTH_FACTOR: Float = 1.4f

/** Vertical gap between the circle's bottom and the first completeness bar. */
private const val BAR_GAP_PX: Float = 4f

/** Vertical spacing between the two completeness bars when [ErmCategory.complete] is `true`. */
private const val BAR_SPACING_PX: Float = 4f

/** Horizontal gap between the circle's right edge and the discriminator-name label. */
private const val LABEL_GAP_PX: Float = 6f

private fun fmt(v: Float): String = fmt2(v)
