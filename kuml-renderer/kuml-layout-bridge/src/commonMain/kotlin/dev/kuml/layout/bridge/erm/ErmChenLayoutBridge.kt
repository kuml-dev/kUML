package dev.kuml.layout.bridge.erm

import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EndpointRef
import dev.kuml.layout.LayoutEdge
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutNode
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.SizeProvider

/**
 * Translates an [ErmModel] + [ErmDiagram] projection into a Chen-notation
 * [LayoutGraph] (V3.4.4).
 *
 * Unlike [ErmLayoutBridge] (Martin/Bachman), Chen notation is a fundamentally
 * different graph shape: attributes and relationships are **not** drawn
 * inside an entity box or as a plain edge between two entities — they become
 * first-class layout nodes of their own:
 *
 * - Each [dev.kuml.erm.model.ErmEntity] → one "title only" [LayoutNode].
 * - Each of its [dev.kuml.erm.model.ErmAttribute]s → its own oval
 *   [LayoutNode], connected to the owning entity by a plain [LayoutEdge].
 * - Each [dev.kuml.erm.model.ErmRelationship] → its own diamond
 *   [LayoutNode], connected to both its source and target entity by two
 *   plain [LayoutEdge]s (no cardinality glyphs on the entity border — the
 *   cardinality is a **label** on the diamond↔entity edge instead). The two
 *   edges are **not** symmetric: the source-entity edge points
 *   `sourceEntity → diamond` and the target-entity edge points
 *   `diamond → targetEntity`, so the chain `sourceEntity → diamond →
 *   targetEntity` preserves the relationship's directionality in the layout
 *   graph — mirroring [ErmIdef1xLayoutBridge]'s `supertype → circle →
 *   subtype` chaining for its synthetic category node. Bug-fix V3.4.6: making
 *   both edges point away from the diamond (`diamond → sourceEntity` and
 *   `diamond → targetEntity`) collapses every relationship into the same
 *   2-hop shape regardless of model size, capping ELK's layered layout at
 *   exactly 3 layers (diamonds / entities / attributes) no matter how many
 *   entities or relationships exist — denser models (e.g. 8 entities, 12
 *   relationships) then degenerate into one extremely wide row instead of
 *   spreading across layers.
 * - Each [dev.kuml.erm.model.ErmView] (when `diagram.showViews`) → a
 *   free-standing [LayoutNode] with no edges (Chen has no first-class "view
 *   references entity" notation).
 *
 * This expanded graph is still handed to `elk.layered` (not the geometric
 * Grid engine) — see this wave's plan for the full rationale: ELK already
 * routes the Martin/Bachman ERM pipeline, is proven at handling many small
 * satellite nodes fanning out from a hub, and the Grid engine has no notion
 * of "radially arrange ovals around their owning box" that would be needed
 * to do better here.
 *
 * **ID-prefix convention**: every synthetic [NodeId]/[EdgeId] this bridge
 * emits is prefixed with one of the `*_PREFIX` constants below. The renderer
 * (`dev.kuml.io.svg.KumlSvgRenderer.renderErmChen`) MUST dispatch on these
 * prefixes — never by looking an id up in the model and guessing its kind by
 * which lookup succeeded first, since entity/attribute/relationship ids are
 * independent id spaces and could otherwise collide (see this wave's plan,
 * "bekannte Stolperfallen" #1).
 *
 * [LayoutGraph.groups] is always empty — flat diagram, no compound layout
 * needed.
 */
public object ErmChenLayoutBridge {
    /** Prefix for a synthetic node id wrapping an [dev.kuml.erm.model.ErmEntity.id]. */
    public const val ENTITY_PREFIX: String = "chen-entity::"

    /** Prefix for a synthetic node id wrapping an [dev.kuml.erm.model.ErmAttribute.id]. */
    public const val ATTR_PREFIX: String = "chen-attr::"

    /** Prefix for a synthetic node id — the diamond — wrapping an [dev.kuml.erm.model.ErmRelationship.id]. */
    public const val REL_PREFIX: String = "chen-rel::"

    /** Prefix for a synthetic node id wrapping an [dev.kuml.erm.model.ErmView.id]. */
    public const val VIEW_PREFIX: String = "chen-view::"

    /** Prefix for the synthetic edge id connecting an entity to one of its attribute ovals. */
    public const val ATTR_EDGE_PREFIX: String = "chen-attredge::"

    /**
     * Prefix for the synthetic edge id connecting a relationship's source entity to its
     * diamond. Points `sourceEntity → diamond` (note the direction: unlike
     * [REL_EDGE_TGT_PREFIX], the diamond is this edge's *target*, not its source) so that
     * the diamond preserves a real `sourceEntity → diamond → targetEntity` chain in the
     * layout graph instead of two edges radiating away from the diamond.
     */
    public const val REL_EDGE_SRC_PREFIX: String = "chen-reledge-src::"

    /**
     * Prefix for the synthetic edge id connecting a relationship diamond to its target
     * entity. Points `diamond → targetEntity`, completing the `sourceEntity → diamond →
     * targetEntity` chain together with [REL_EDGE_SRC_PREFIX].
     */
    public const val REL_EDGE_TGT_PREFIX: String = "chen-reledge-tgt::"

    /**
     * Builds a Chen-notation [LayoutGraph] from the entities/attributes/
     * relationships/views visible in [diagram].
     *
     * @param model The owning ERM model (source of truth for all elements).
     * @param diagram The projection — empty `elementIds` means "the whole
     *   model", exactly like [ErmDiagram]'s own KDoc specifies.
     * @param sizeProvider Supplies intrinsic sizes for every synthetic node
     *   kind (entity title box, attribute oval, relationship diamond, view
     *   box); pass an [ErmChenSizeProvider] for content-aware sizing
     *   (recommended) or [SizeProvider.constant] for tests.
     */
    public fun toChenLayoutGraph(
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
        val edges = mutableListOf<LayoutEdge>()

        for (entity in model.entities) {
            if (entity.id !in visibleIds) continue

            nodes.add(
                LayoutNode(
                    id = NodeId(ENTITY_PREFIX + entity.id),
                    intrinsicSize = sizeProvider.sizeOf(ENTITY_PREFIX + entity.id, "ErmChenEntity"),
                ),
            )

            for (attr in entity.attributes) {
                nodes.add(
                    LayoutNode(
                        id = NodeId(ATTR_PREFIX + attr.id),
                        intrinsicSize = sizeProvider.sizeOf(ATTR_PREFIX + attr.id, "ErmChenAttribute"),
                    ),
                )
                edges.add(
                    LayoutEdge(
                        id = EdgeId(ATTR_EDGE_PREFIX + entity.id + "::" + attr.id),
                        source = EndpointRef(nodeId = NodeId(ENTITY_PREFIX + entity.id)),
                        target = EndpointRef(nodeId = NodeId(ATTR_PREFIX + attr.id)),
                    ),
                )
            }
        }

        for (rel in model.relationships) {
            if (rel.sourceEntityId !in visibleIds || rel.targetEntityId !in visibleIds) continue

            nodes.add(
                LayoutNode(
                    id = NodeId(REL_PREFIX + rel.id),
                    intrinsicSize = sizeProvider.sizeOf(REL_PREFIX + rel.id, "ErmChenRelationship"),
                ),
            )
            edges.add(
                LayoutEdge(
                    id = EdgeId(REL_EDGE_SRC_PREFIX + rel.id),
                    source = EndpointRef(nodeId = NodeId(ENTITY_PREFIX + rel.sourceEntityId)),
                    target = EndpointRef(nodeId = NodeId(REL_PREFIX + rel.id)),
                ),
            )
            edges.add(
                LayoutEdge(
                    id = EdgeId(REL_EDGE_TGT_PREFIX + rel.id),
                    source = EndpointRef(nodeId = NodeId(REL_PREFIX + rel.id)),
                    target = EndpointRef(nodeId = NodeId(ENTITY_PREFIX + rel.targetEntityId)),
                ),
            )
        }

        if (diagram.showViews) {
            for (view in model.views) {
                if (view.id !in visibleIds) continue
                nodes.add(
                    LayoutNode(
                        id = NodeId(VIEW_PREFIX + view.id),
                        intrinsicSize = sizeProvider.sizeOf(VIEW_PREFIX + view.id, "ErmChenView"),
                    ),
                )
            }
        }

        return LayoutGraph(nodes = nodes, edges = edges, groups = emptyList())
    }
}
