package dev.kuml.renderer.kuiver

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.layout.LayoutConfig
import dev.kuml.layout.LayoutResult

/**
 * Adapts a [LayoutResult] into the data structures required by Kuiver.
 *
 * This object is plain Kotlin (no Compose runtime) and can therefore be
 * tested without an active composition.
 *
 * **Deviations from spec:**
 * - `LayoutResult.nodes` is `Map<NodeId, NodeLayout>` (not a `List`).
 *   `NodeLayout.bounds` is a `Rect(origin: Point, size: Size)` — so
 *   position is `bounds.origin.x/y` and dimensions are `bounds.size.width/height`.
 * - `LayoutResult.edges` is `Map<EdgeId, EdgeRoute>` — source/target IDs are
 *   resolved via a separate `LayoutGraph`, but the renderer only needs positions
 *   for the `LayoutConfig.Custom` provider. Edge IDs are passed through directly.
 * - `KuiverNode.dimensions` uses `Dp`, not raw `Float`. Values from the
 *   `LayoutResult` are in abstract pixels; they are wrapped in `.dp` so Kuiver
 *   treats them as density-independent points.
 * - `LayoutConfig.Custom` accepts a `(Kuiver, LayoutConfig) -> Kuiver` provider
 *   (not `(Kuiver) -> Map<id, Offset>`). The provider returns the *same* Kuiver
 *   with node positions already set — our positions come from the `LayoutResult`,
 *   so we update each node's `position` field in place.
 */
internal object KuiverGraphAdapter {
    /**
     * Converts a [LayoutResult] into a [Kuiver] graph with absolute positions
     * and dimensions taken from the layout result.
     *
     * Only nodes are populated with positions/dimensions; edges carry IDs only.
     * The Kuiver layout step is bypassed by [layoutConfig].
     */
    internal fun toKuiver(layoutResult: LayoutResult): Kuiver =
        buildKuiver {
            layoutResult.nodes.forEach { (nodeId, nodeLayout) ->
                addNode(
                    KuiverNode(
                        id = nodeId.value,
                        dimensions =
                            NodeDimensions(
                                width = nodeLayout.bounds.size.width.dp,
                                height = nodeLayout.bounds.size.height.dp,
                            ),
                        position =
                            Offset(
                                x = nodeLayout.bounds.origin.x,
                                y = nodeLayout.bounds.origin.y,
                            ),
                    ),
                )
            }
            layoutResult.edges.forEach { (edgeId, edgeRoute) ->
                // Edge IDs encode "sourceId--targetId" by convention from the Layout-Bridge.
                // In V1 we add the edge only if both node IDs are present; the display
                // positions come from the EdgeRoute, not from Kuiver routing.
                val parts = edgeId.value.split("--", limit = 2)
                if (parts.size == 2) {
                    addEdge(KuiverEdge(fromId = parts[0], toId = parts[1]))
                }
            }
        }

    /**
     * Returns a [LayoutConfig.Custom] that reproduces the positions already stored
     * in the provided [LayoutResult] exactly — i.e. it is a no-op layout pass.
     *
     * The provider receives the measured [Kuiver] (post-measurement) and returns it
     * unchanged; Kuiver then uses the positions already set via [toKuiver].
     */
    internal fun layoutConfig(layoutResult: LayoutResult): LayoutConfig.Custom =
        LayoutConfig.Custom(
            provider = { kuiver, _ ->
                // Positions are already baked into the KuiverNodes by toKuiver().
                // We only need to apply the LayoutResult positions onto the measured
                // graph (which may have had null dimensions filled in by Kuiver).
                val positioned = Kuiver()
                kuiver.nodes.forEach { (id, node) ->
                    val nodeId = dev.kuml.layout.NodeId(id)
                    val nodeLayout = layoutResult.nodes[nodeId]
                    if (nodeLayout != null) {
                        positioned.addNode(
                            node.copy(
                                position =
                                    Offset(
                                        x = nodeLayout.bounds.origin.x,
                                        y = nodeLayout.bounds.origin.y,
                                    ),
                            ),
                        )
                    } else {
                        positioned.addNode(node)
                    }
                }
                kuiver.edges.forEach { edge -> positioned.addEdge(edge) }
                positioned
            },
        )
}
