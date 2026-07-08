package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.SizeProvider

/**
 * Translates an [ErmModel] + [ErmDiagram] projection into an IDEF1X-notation
 * [LayoutGraph] (V3.4.5).
 *
 * Unlike [ErmChenLayoutBridge], entities, views, and relationships keep their
 * **real** model ids — IDEF1X entity boxes and relationship edges are drawn
 * exactly like [ErmLayoutBridge]'s Martin/Bachman graph (rounded corners and
 * cardinality glyphs are a renderer-level concern, not a layout-shape
 * concern). Only [dev.kuml.erm.model.ErmCategory] clusters need a synthetic
 * node — the discriminator circle has no model-level id of its own — so this
 * bridge injects exactly one extra [LayoutNode] per visible category, plus
 * one edge from the supertype to the circle and one edge from the circle to
 * each subtype.
 *
 * A model with no categories therefore produces a graph structurally
 * identical to [ErmLayoutBridge]'s — there is no "has categories?" branch;
 * this bridge is always used for [dev.kuml.erm.model.ErmNotation.IDEF1X],
 * category or not.
 *
 * **ID-prefix convention**: only the synthetic category-circle node and its
 * two edge kinds carry a `*_PREFIX` from the constants below — entity/view/
 * relationship ids are unprefixed real model ids. The renderer
 * (`dev.kuml.io.svg.KumlSvgRenderer.renderErmIdef1x`) MUST check the prefix
 * first and only fall back to real-id lookups (entity/view maps) for
 * unprefixed ids — never guess by which lookup succeeds first, since a
 * category id and an entity id are independent id spaces that could
 * otherwise collide (mirrors the Chen bridge's "bekannte Stolperfallen" #1).
 *
 * [LayoutGraph.groups] is always empty — flat diagram, no compound layout
 * needed.
 */
public object ErmIdef1xLayoutBridge {
    /** Prefix for the synthetic node id wrapping an [dev.kuml.erm.model.ErmCategory.id]'s discriminator circle. */
    public const val CATEGORY_NODE_PREFIX: String = "idef1x-cat::"

    /** Prefix for the synthetic edge id connecting a category's supertype entity to its discriminator circle. */
    public const val CATEGORY_EDGE_SUP_PREFIX: String = "idef1x-catedge-sup::"

    /** Prefix for the synthetic edge id connecting a category's discriminator circle to one of its subtype entities. */
    public const val CATEGORY_EDGE_SUB_PREFIX: String = "idef1x-catedge-sub::"

    /** Fixed bounding-box size (square) for a category discriminator circle. */
    public const val CATEGORY_CIRCLE_SIZE: Float = 24f

    /**
     * Builds an IDEF1X-notation [LayoutGraph] from the entities/relationships/
     * views/categories visible in [diagram].
     *
     * @param model The owning ERM model (source of truth for all elements).
     * @param diagram The projection — empty `elementIds` means "the whole
     *   model", exactly like [ErmDiagram]'s own KDoc specifies.
     * @param sizeProvider Supplies intrinsic box sizes for entities/views;
     *   pass an [ErmContentSizeProvider] for content-aware sizing
     *   (recommended) or [SizeProvider.constant] for tests. Category circles
     *   are never asked of the [sizeProvider] — their size is always
     *   [CATEGORY_CIRCLE_SIZE], set directly by this bridge.
     */
    public fun toLayoutGraph(
        model: ErmModel,
        diagram: ErmDiagram,
        sizeProvider: SizeProvider = SizeProvider.constant(),
    ): LayoutGraph {
        val visibleIds: Set<String> =
            diagram.elementIds.ifEmpty {
                val entityIds = model.entities.map { it.id }
                val viewIds = if (diagram.showViews) model.views.map { it.id } else emptyList()
                entityIds + viewIds
            }.toSet()

        val nodes = mutableListOf<LayoutNode>()

        for (entity in model.entities) {
            if (entity.id !in visibleIds) continue
            nodes.add(
                LayoutNode(
                    id = NodeId(entity.id),
                    intrinsicSize = sizeProvider.sizeOf(entity.id, "ErmEntity"),
                ),
            )
        }

        if (diagram.showViews) {
            for (view in model.views) {
                if (view.id !in visibleIds) continue
                nodes.add(
                    LayoutNode(
                        id = NodeId(view.id),
                        intrinsicSize = sizeProvider.sizeOf(view.id, "ErmView"),
                    ),
                )
            }
        }

        val edges = mutableListOf<LayoutEdge>()
        for (rel in model.relationships) {
            if (rel.sourceEntityId !in visibleIds || rel.targetEntityId !in visibleIds) continue
            edges.add(
                LayoutEdge(
                    id = EdgeId(rel.id),
                    source = EndpointRef(nodeId = NodeId(rel.sourceEntityId)),
                    target = EndpointRef(nodeId = NodeId(rel.targetEntityId)),
                ),
            )
        }

        val categoryCircleSize = Size(CATEGORY_CIRCLE_SIZE, CATEGORY_CIRCLE_SIZE)
        for (category in model.categories) {
            if (category.supertypeEntityId !in visibleIds) continue
            val visibleSubtypes = category.subtypeEntityIds.filter { it in visibleIds }
            if (visibleSubtypes.isEmpty()) continue

            val circleNodeId = NodeId(CATEGORY_NODE_PREFIX + category.id)
            nodes.add(LayoutNode(id = circleNodeId, intrinsicSize = categoryCircleSize))

            edges.add(
                LayoutEdge(
                    id = EdgeId(CATEGORY_EDGE_SUP_PREFIX + category.id),
                    source = EndpointRef(nodeId = NodeId(category.supertypeEntityId)),
                    target = EndpointRef(nodeId = circleNodeId),
                ),
            )
            for (subtypeId in visibleSubtypes) {
                edges.add(
                    LayoutEdge(
                        id = EdgeId(CATEGORY_EDGE_SUB_PREFIX + category.id + "::" + subtypeId),
                        source = EndpointRef(nodeId = circleNodeId),
                        target = EndpointRef(nodeId = NodeId(subtypeId)),
                    ),
                )
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = emptyList())
    }
}
