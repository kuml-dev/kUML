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
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
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

/** Caps [renderChenConnector]'s [stackIndex] parameter so a dense FK hub doesn't fling labels far from their entity. */
internal const val CHEN_CARDINALITY_MAX_STACK_INDEX: Int = 2

/**
 * Renders a plain diamond↔entity or entity↔attribute connector line.
 *
 * [cardinality] is `null` for entity↔attribute connectors (attributes carry
 * no cardinality). For diamond↔entity connectors, the label is always placed
 * near the **entity end** of the line, never the diamond end, so the two
 * ends of a relationship (e.g. `1` and `N`) don't collide at the diamond.
 *
 * Since Bug-fix V3.4.6, `ErmChenLayoutBridge`'s two diamond↔entity edges are
 * **not** symmetric: the source-entity edge points `sourceEntity → diamond`
 * (entity is the route's `source`) while the target-entity edge points
 * `diamond → targetEntity` (entity is the route's `target`) — see that
 * bridge's KDoc for why. [entitySide] tells this function which end of
 * [route] the entity actually sits on, so it can anchor the label there
 * instead of assuming "entity = target" for every connector.
 *
 * Bug-fix (ERM/Chen cardinality-label collision, fix/erm-chen-label-
 * collisions, V3.4.7): this used to be the only ERM label path with its own
 * ad-hoc inline math instead of a shared `ErmEdgeLabels.kt` helper — see
 * `renderErmCardinalityLabel`'s KDoc for the two defects that caused (a)
 * every SOURCE-side label to land *inside* the entity box, on top of its
 * title (the offset direction was inverted — negating a tangent that
 * already pointed away from the box) and (b) every label, TARGET included,
 * to straddle the connector line (no perpendicular offset component at
 * all). Both are fixed by routing through [renderErmCardinalityLabel].
 *
 * [stackIndex] (0-based, capped at [CHEN_CARDINALITY_MAX_STACK_INDEX]) adds
 * `ErmChenSizing.CARDINALITY_LABEL_STACK_PX` per step to the along-edge
 * offset, so sibling relationships converging on the same entity endpoint
 * (e.g. a hub entity with several FKs) fan their labels apart instead of
 * piling into the same spot. [entityBounds] is an optional belt-and-
 * suspenders guard (already-padding-shifted, see `KumlSvgRenderer.renderErmChen`):
 * if the computed label point still falls inside it (expanded by
 * `ErmChenSizing.CARDINALITY_TITLE_CLEARANCE_PX`), the along-edge offset is
 * grown until the point clears.
 */
internal fun renderChenConnector(
    route: EdgeRoute,
    cardinality: Cardinality?,
    b: SvgBuilder,
    entitySide: ConnectorEntitySide = ConnectorEntitySide.TARGET,
    stackIndex: Int = 0,
    entityBounds: Rect? = null,
) {
    val (tagName, attrs) = EdgePathBuilder.build(route)
    b.tag(tagName, attrs + mapOf("class" to "kuml-edge"))

    if (cardinality != null) {
        val entityPoint: Point
        val outward: Pair<Float, Float>
        when (entitySide) {
            ConnectorEntitySide.TARGET -> {
                // Tangent pointing INTO the target node; negate to point away from
                // it, back along the edge, toward the diamond — the direction the
                // label should offset in.
                val intoTarget = EdgeLabelGeometry.targetSegmentTangent(route)
                outward = -intoTarget.first to -intoTarget.second
                entityPoint = route.target
            }
            ConnectorEntitySide.SOURCE -> {
                // Tangent of the first segment already points FROM the entity
                // TOWARD the first kink — i.e. away from the entity, toward the
                // diamond. That IS the direction the label should offset in, so
                // (unlike the pre-fix code) it is used as-is, not negated.
                outward = EdgeLabelGeometry.sourceSegmentTangent(route)
                entityPoint = route.source
            }
        }
        val label = chenCardinalityLabel(cardinality)
        val clampedStack = stackIndex.coerceIn(0, CHEN_CARDINALITY_MAX_STACK_INDEX)
        val baseOffset = ErmChenSizing.CARDINALITY_LABEL_OFFSET_PX + clampedStack * ErmChenSizing.CARDINALITY_LABEL_STACK_PX
        val alongOffset = clearedAlongOffset(entityPoint, outward, entityBounds, baseOffset)
        b.renderErmCardinalityLabel(entityPoint, outward, label, alongOffset)
    }
}

/**
 * Grows [baseOffsetPx] in small steps until `entityPoint + outward *
 * offset` clears [bounds] (expanded by [ErmChenSizing.CARDINALITY_TITLE_CLEARANCE_PX]) —
 * see [renderChenConnector]'s KDoc. Returns [baseOffsetPx] unchanged when
 * [bounds] is `null` or already cleared, which is the common case: the
 * SOURCE/TARGET direction fix already guarantees clearance for well-formed,
 * border-anchored connector endpoints.
 */
private fun clearedAlongOffset(
    entityPoint: Point,
    outward: Pair<Float, Float>,
    bounds: Rect?,
    baseOffsetPx: Float,
): Float {
    if (bounds == null) return baseOffsetPx
    val margin = ErmChenSizing.CARDINALITY_TITLE_CLEARANCE_PX
    val minX = bounds.origin.x - margin
    val minY = bounds.origin.y - margin
    val maxX = bounds.origin.x + bounds.size.width + margin
    val maxY = bounds.origin.y + bounds.size.height + margin
    var offset = baseOffsetPx
    var guard = 0
    while (guard < CLEARANCE_GUARD_MAX_STEPS) {
        val x = entityPoint.x + outward.first * offset
        val y = entityPoint.y + outward.second * offset
        val outside = x < minX || x > maxX || y < minY || y > maxY
        if (outside) return offset
        offset += CLEARANCE_GUARD_STEP_PX
        guard++
    }
    return offset
}

/** Step size for [clearedAlongOffset]'s push-until-clear loop. */
private const val CLEARANCE_GUARD_STEP_PX: Float = 4f

/** Safety cap on [clearedAlongOffset]'s loop — bounds unbounded growth for adversarial/degenerate models. */
private const val CLEARANCE_GUARD_MAX_STEPS: Int = 20

/**
 * Which end of a diamond↔entity [EdgeRoute] the entity sits on — see
 * [renderChenConnector]'s KDoc. The diamond sits on the other end.
 */
internal enum class ConnectorEntitySide { SOURCE, TARGET }

/**
 * Maps a [Cardinality] to the classic Chen `1`/`N` label vocabulary.
 *
 * V3.4.4 scope: simple `1`/`N` labels only (no `(min,max)` pairs) — see this
 * wave's plan for the rationale.
 */
private fun chenCardinalityLabel(cardinality: Cardinality): String = if (cardinality.many) "N" else "1"

private fun fmt(v: Float): String = fmt2(v)
