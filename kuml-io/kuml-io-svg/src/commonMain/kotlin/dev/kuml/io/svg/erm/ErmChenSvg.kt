package dev.kuml.io.svg.erm

import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.RelationshipKind
import dev.kuml.io.svg.EdgeLabelGeometry
import dev.kuml.io.svg.EdgePathBuilder
import dev.kuml.io.svg.SvgBuilder
import dev.kuml.io.svg.fmt2
import dev.kuml.io.svg.xmlEscapeAttr
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.renderer.theme.core.KumlTheme

/**
 * ERM/Chen-notation shape renderers (V3.4.4).
 *
 * Unlike Martin/Bachman (one box per entity, attributes as rows, relationship
 * as a plain edge), Chen expands attributes and relationships into their own
 * shapes — see `dev.kuml.layout.bridge.erm.ErmChenLayoutBridge`'s KDoc for the
 * full rationale. This file only draws shapes; the graph expansion and
 * ID-prefix dispatch live in `KumlSvgRenderer.renderErmChen`.
 *
 * **Z-order note** (see `ErmMartinSvg.renderErmEntity`'s comment for the git
 * history this guards against): inside a `tag("g") { … }` block always write
 * to the child builder (`this`), never to the outer `b` parameter — shape
 * first, text second, so text never gets painted over by a later shape.
 *
 * V3.4.4 scope: attributes always render as a plain oval, PK names
 * underlined. There is no metamodel support yet for multivalued / derived /
 * composite attributes (double oval / dashed oval / nested oval) — see this
 * wave's plan section "Scope-Klärung" for why. `diagram.showIndexes` has no
 * effect on the Chen path: indexes and check constraints are physical-schema
 * concepts, Chen is conceptual-only.
 */

/** Renders an [ErmEntity] as a Chen title-only box (no attribute compartment — attributes are separate ovals). */
internal fun renderChenEntity(
    entity: ErmEntity,
    layout: NodeLayout,
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
            val inset = ErmChenSizing.WEAK_BORDER_INSET
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
        tag(
            "text",
            mapOf(
                "class" to "kuml-title",
                "x" to fmt(w / 2f),
                "y" to fmt(h / 2f + 5f),
                "text-anchor" to "middle",
            ),
        ) { text(entity.name ?: entity.id) }
    }
}

/** Renders an [ErmAttribute] as an ellipse, name centered, PK name underlined. */
internal fun renderChenAttribute(
    attr: ErmAttribute,
    layout: NodeLayout,
    b: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f

    b.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(attr.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag(
            "ellipse",
            mapOf(
                "cx" to fmt(cx),
                "cy" to fmt(cy),
                "rx" to fmt(cx),
                "ry" to fmt(cy),
                "class" to "kuml-erm-chen-attribute",
            ),
        )
        val name = attr.name ?: attr.id
        tag(
            "text",
            mapOf("class" to "kuml-body", "x" to fmt(cx), "y" to fmt(cy + 4f), "text-anchor" to "middle"),
        ) { text(name) }

        if (attr.primaryKey) {
            val nameWidth = name.length * ErmChenSizing.BODY_CHAR_PX
            tag(
                "line",
                mapOf(
                    "x1" to fmt(cx - nameWidth / 2f),
                    "y1" to fmt(cy + 7f),
                    "x2" to fmt(cx + nameWidth / 2f),
                    "y2" to fmt(cy + 7f),
                    "class" to "kuml-erm-pk-underline",
                ),
            )
        }
    }
}

/** Renders an [ErmRelationship] as a diamond (rhombus); [RelationshipKind.IDENTIFYING] gets a double border. */
internal fun renderChenRelationshipNode(
    rel: ErmRelationship,
    layout: NodeLayout,
    b: SvgBuilder,
) {
    val x = layout.bounds.origin.x
    val y = layout.bounds.origin.y
    val w = layout.bounds.size.width
    val h = layout.bounds.size.height
    val cx = w / 2f
    val cy = h / 2f

    b.tag(
        "g",
        mapOf("id" to xmlEscapeAttr(rel.id), "transform" to "translate(${fmt(x)},${fmt(y)})"),
    ) {
        tag("polygon", mapOf("points" to diamondPoints(w, h), "class" to "kuml-erm-chen-relationship"))
        if (rel.kind == RelationshipKind.IDENTIFYING) {
            val inset = ErmChenSizing.WEAK_BORDER_INSET
            tag(
                "polygon",
                mapOf("points" to diamondPoints(w, h, inset), "class" to "kuml-erm-chen-relationship-inner"),
            )
        }
        val name = rel.name ?: rel.id
        tag(
            "text",
            mapOf("class" to "kuml-body", "x" to fmt(cx), "y" to fmt(cy + 4f), "text-anchor" to "middle"),
        ) { text(name) }
    }
}

/** Axis-aligned diamond vertices (top, right, bottom, left) for a `w`×`h` bounding box, optionally inset by [inset] px per vertex. */
private fun diamondPoints(
    w: Float,
    h: Float,
    inset: Float = 0f,
): String {
    val cx = w / 2f
    val cy = h / 2f
    val top = "${fmt(cx)},${fmt(inset)}"
    val right = "${fmt(w - inset)},${fmt(cy)}"
    val bottom = "${fmt(cx)},${fmt(h - inset)}"
    val left = "${fmt(inset)},${fmt(cy)}"
    return "$top $right $bottom $left"
}

/**
 * Renders a plain diamond↔entity or entity↔attribute connector line.
 *
 * [cardinality] is `null` for entity↔attribute connectors (attributes carry
 * no cardinality). For diamond↔entity connectors, the label is placed near
 * the **entity end** of the line (the route's `target`, by
 * `ErmChenLayoutBridge`'s convention of `source = diamond, target = entity`)
 * so the two ends of a relationship (e.g. `1` and `N`) don't collide at the
 * diamond.
 */
internal fun renderChenConnector(
    route: EdgeRoute,
    cardinality: Cardinality?,
    b: SvgBuilder,
) {
    val (tagName, attrs) = EdgePathBuilder.build(route)
    b.tag(tagName, attrs + mapOf("class" to "kuml-edge"))

    if (cardinality != null) {
        // Tangent pointing INTO the target node; negate to point away from it,
        // back along the edge — the direction the label should be offset in.
        val intoTarget = EdgeLabelGeometry.targetSegmentTangent(route)
        val awayFromTarget = -intoTarget.first to -intoTarget.second
        val label = chenCardinalityLabel(cardinality)
        val x = route.target.x + awayFromTarget.first * ErmChenSizing.CARDINALITY_LABEL_OFFSET_PX
        val y = route.target.y + awayFromTarget.second * ErmChenSizing.CARDINALITY_LABEL_OFFSET_PX
        b.tag(
            "text",
            mapOf(
                "class" to "kuml-erm-chen-cardinality",
                "x" to fmt(x),
                "y" to fmt(y),
                "text-anchor" to "middle",
            ),
        ) { text(label) }
    }
}

/**
 * Maps a [Cardinality] to the classic Chen `1`/`N` label vocabulary.
 *
 * V3.4.4 scope: simple `1`/`N` labels only (no `(min,max)` pairs) — see this
 * wave's plan for the rationale.
 */
private fun chenCardinalityLabel(cardinality: Cardinality): String = if (cardinality.many) "N" else "1"

private fun fmt(v: Float): String = fmt2(v)
