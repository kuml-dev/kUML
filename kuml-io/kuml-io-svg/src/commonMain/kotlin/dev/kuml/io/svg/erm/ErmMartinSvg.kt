package dev.kuml.io.svg.erm

import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * Renders an [ErmEntity] as a Martin-notation (crow's-foot) box.
 *
 * Layout (top to bottom) mirrors [ErmSizing]'s KDoc exactly — the vertical
 * `cy` increments used here MUST match
 * `dev.kuml.layout.bridge.erm.ErmContentSizeProvider`'s height computation,
 * otherwise rows overflow the box.
 *
 * - Title bar: entity name, bold, centered. `weak` entities get a second,
 *   inset rectangle drawn just inside the outer border (double-border
 *   convention for weak entities).
 * - Primary-key compartment: every [ErmAttribute] with `primaryKey = true`,
 *   name underlined, `PK` marker in the left column.
 * - Attribute compartment: every remaining attribute, with `FK` / `U`
 *   markers where applicable, `NN` suffix when not nullable, and an
 *   italicised ` = default` suffix when a default expression is set.
 * - Index / check-constraint compartment: only rendered when
 *   `diagram.showIndexes` is `true` and the entity has any.
 */
internal fun renderErmEntity(
    entity: ErmEntity,
    layout: NodeLayout,
    diagram: ErmDiagram,
    theme: KumlTheme,
    b: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height

    b.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(entity.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("rect", mapOf("width" to fmt(w), "height" to fmt(h), "class" to "kuml-erm-entity"))
        if (entity.weak) {
            val inset = ErmSizing.WEAK_BORDER_INSET
            tag(
                "rect",
                mapOf(
                    "x" to fmt(inset),
                    "y" to fmt(inset),
                    "width" to fmt(w - 2 * inset),
                    "height" to fmt(h - 2 * inset),
                    "class" to "kuml-erm-entity-inner",
                ),
            )
        }

        var cy = ErmSizing.TITLE_ROW_H - 8f
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(w / 2f),
                "y" to fmt(cy),
                "text-anchor" to "middle",
            ),
        ) { text(entity.name ?: entity.id) }
        cy = ErmSizing.TITLE_ROW_H

        val pkAttrs = entity.primaryKey
        val nonPkAttrs = entity.attributes.filterNot { it.primaryKey }

        // NOTE: must use `this` (the child SvgBuilder created for this <g> block by
        // SvgBuilder.tag), NOT the outer `b` parameter — `b` is the *parent* builder
        // this <g> element is being written into, and appending to it here would
        // interleave content ahead of (and outside) the <g>'s own accumulated
        // children (see git history for the visual bug this caused: divider lines
        // and attribute rows rendered before the entity rect/title, which then
        // painted over them since the white-filled rect is appended last).
        if (pkAttrs.isNotEmpty()) {
            cy = renderDivider(w, cy, this)
            for (attr in pkAttrs) {
                cy = renderAttributeRow(attr, w, cy, marker = "PK", underline = true, this)
            }
        }

        if (nonPkAttrs.isNotEmpty()) {
            cy = renderDivider(w, cy, this)
            for (attr in nonPkAttrs) {
                val marker =
                    when {
                        attr.foreignKey != null -> "FK"
                        attr.unique -> "U"
                        else -> ""
                    }
                cy = renderAttributeRow(attr, w, cy, marker = marker, underline = false, this)
            }
        }

        if (diagram.showIndexes && (entity.indexes.isNotEmpty() || entity.checks.isNotEmpty())) {
            cy = renderDivider(w, cy, this)
            for (idx in entity.indexes) {
                val uniqueTag = if (idx.unique) " UNIQUE" else ""
                val label = "«idx» ${idx.name ?: idx.id} (${idx.attributeIds.joinToString(", ")})$uniqueTag"
                tag(
                    "text",
                    mapOf("class" to "kuml-erm-index", "x" to fmt(ErmSizing.PAD_X), "y" to fmt(cy)),
                ) { text(label) }
                cy += ErmSizing.ROW_H
            }
            for (check in entity.checks) {
                val label = "«check» ${check.expression}"
                tag(
                    "text",
                    mapOf("class" to "kuml-erm-index", "x" to fmt(ErmSizing.PAD_X), "y" to fmt(cy)),
                ) { text(label) }
                cy += ErmSizing.ROW_H
            }
        }
    }
}

/** Draws a compartment divider and returns the `cy` advanced past [ErmSizing.DIVIDER_GAP]. */
private fun renderDivider(
    w: Float,
    cy: Float,
    b: SvgBuilder,
): Float {
    val dividerY = cy + ErmSizing.DIVIDER_GAP / 2f
    b.tag(
        "line",
        mapOf(
            "x1" to "0",
            "y1" to fmt(dividerY),
            "x2" to fmt(w),
            "y2" to fmt(dividerY),
            "class" to "kuml-divider",
        ),
    )
    return cy + ErmSizing.DIVIDER_GAP
}

/** Draws a single attribute row (marker column + `name : TYPE` + NN/default suffix) and returns the advanced `cy`. */
private fun renderAttributeRow(
    attr: ErmAttribute,
    w: Float,
    cy: Float,
    marker: String,
    underline: Boolean,
    b: SvgBuilder,
): Float {
    if (marker.isNotEmpty()) {
        b.tag(
            "text",
            mapOf("class" to "kuml-erm-marker", "x" to fmt(4f), "y" to fmt(cy)),
        ) { text(marker) }
    }

    val nameX = ErmSizing.MARKER_COL_W
    val baseLine = "${attr.name ?: attr.id} : ${attr.type.render()}"
    val suffix = buildString {
        if (!attr.nullable) append(" NN")
    }
    b.tag(
        "text",
        mapOf("class" to "kuml-body", "x" to fmt(nameX), "y" to fmt(cy)),
    ) { text(baseLine + suffix) }

    if (underline) {
        // Underline just the "name" portion (not the type) — matches the classic
        // ERM/Martin PK convention of underlining primary-key column names.
        val nameOnly = attr.name ?: attr.id
        val nameWidth = nameOnly.length * ErmSizing.BODY_CHAR_PX
        b.tag(
            "line",
            mapOf(
                "x1" to fmt(nameX),
                "y1" to fmt(cy + 2f),
                "x2" to fmt(nameX + nameWidth),
                "y2" to fmt(cy + 2f),
                "class" to "kuml-erm-pk-underline",
            ),
        )
    }

    val default = attr.default
    if (default != null) {
        b.tag(
            "text",
            mapOf("class" to "kuml-erm-default", "x" to fmt(w - ErmSizing.PAD_X), "y" to fmt(cy), "text-anchor" to "end"),
        ) { text("= $default") }
    }
    return cy + ErmSizing.ROW_H
}

private fun fmt(v: Float): String = fmt2(v)
