package dev.kuml.io.svg.erm

import dev.kuml.erm.model.ErmView
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Renders an [ErmView] as a dashed-border box: `«view» name` header, followed
 * by a truncated preview of the raw SQL `query`.
 *
 * Only invoked when `diagram.showViews` is `true` (checked by the caller).
 */
internal fun renderErmView(
    view: ErmView,
    layout: NodeLayout,
    @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
    b: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    b.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(view.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-erm-view"))

        var cy = ErmSizing.TITLE_ROW_H - 8f
        tag(
            "text",
            mapOf(
                "class" to "kuml-stereotype",
                "x" to fmt(w / 2f),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text("«view» ${view.name ?: view.id}") }
        cy = ErmSizing.TITLE_ROW_H

        tag(
            "line",
            mapOf(
                "x1" to "0",
                "y1" to fmt(cy + ErmSizing.DIVIDER_GAP / 2f),
                "x2" to fmt(w),
                "y2" to fmt(cy + ErmSizing.DIVIDER_GAP / 2f),
                "class" to "kuml-divider",
            ),
        )
        cy += ErmSizing.DIVIDER_GAP

        val preview = view.query.replace("\n", " ").trim()
        val maxChars = ((w - 2 * ErmSizing.PAD_X) / ErmSizing.SMALL_CHAR_PX).toInt().coerceAtLeast(4)
        val truncated = if (preview.length > maxChars) preview.take(maxChars - 1) + "…" else preview
        tag(
            "text",
            mapOf("class" to "kuml-erm-view-query", "x" to fmt(ErmSizing.PAD_X), "y" to fmt(cy)),
        ) { text(truncated) }
    }
}

private fun fmt(v: Float): String = fmt2(v)
