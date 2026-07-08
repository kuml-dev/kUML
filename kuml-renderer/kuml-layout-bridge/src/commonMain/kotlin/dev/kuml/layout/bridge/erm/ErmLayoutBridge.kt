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
 * Translates an [ErmModel] + [ErmDiagram] projection into a [LayoutGraph].
 *
 * ERM entities are structurally identical to UML class boxes for layout
 * purposes (boxes with attribute lists, connected by edges), so — unlike
 * Blueprint or BPMN Choreography — this bridge feeds `elk.layered` rather
 * than a bespoke geometric layout, exactly like [dev.kuml.layout.bridge.UmlLayoutBridge].
 *
 * **Design decisions** (V3.4.2):
 *  - Foreign-key [dev.kuml.erm.model.ErmAttribute]s do **not** produce their
 *    own [LayoutEdge] — the owning [dev.kuml.erm.model.ErmRelationship] is
 *    the edge; the FK marker is drawn *inside* the box by the renderer. This
 *    avoids double-routing the same logical connection.
 *  - Indexes, check constraints, and (non-view) entity content never become
 *    their own [LayoutNode]s — they render inside their owning entity's box.
 *  - [dev.kuml.erm.model.ErmView]s become their own [LayoutNode]s when
 *    `diagram.showViews` is `true`, but never gain edges of their own (V3.4.2
 *    has no first-class "view references entity" edge type).
 *  - [LayoutGraph.groups] is always empty — no compound/group layout is
 *    needed for a flat ERM diagram.
 *  - Self-referencing relationships (`sourceEntityId == targetEntityId`,
 *    e.g. `Employee.managerId → Employee`) are passed straight through to
 *    ELK as a self-edge. ELK renders a tight loop; polishing that into a
 *    dedicated C-shaped route is left as a follow-up (see V3.4.2 plan
 *    "bekannte Stolperfallen").
 */
public object ErmLayoutBridge {
    /**
     * Builds a [LayoutGraph] from the entities/relationships/views visible in
     * [diagram].
     *
     * @param model The owning ERM model (source of truth for all elements).
     * @param diagram The projection — empty `elementIds` means "the whole
     *   model", exactly like [ErmDiagram]'s own KDoc specifies.
     * @param sizeProvider Supplies intrinsic box sizes; pass an
     *   [dev.kuml.layout.bridge.erm.ErmContentSizeProvider] for content-aware
     *   sizing (recommended) or [SizeProvider.constant] for tests.
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

        return LayoutGraph(nodes = nodes, edges = edges, groups = emptyList())
    }
}
