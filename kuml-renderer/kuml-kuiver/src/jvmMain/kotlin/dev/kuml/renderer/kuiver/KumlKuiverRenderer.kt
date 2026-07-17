package dev.kuml.renderer.kuiver

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.model.KumlDiagram
import dev.kuml.layout.LayoutResult
import dev.kuml.renderer.theme.KumlTheme
import dev.kuml.renderer.theme.PlainTheme

/**
 * Renders a kUML diagram with a pre-computed [LayoutResult] using Compose Multiplatform
 * via the [Kuiver](https://github.com/justdeko/kuiver) graph viewer.
 *
 * Two overloads are provided — one for UML ([KumlDiagram]) and one for C4 ([C4Diagram]).
 * Both accept the same [theme] and [modifier] parameters.
 *
 * The renderer is headless-capable: it does not require a dedicated `KuiverViewerState`
 * with pan/zoom interaction. Callers that need interactive pan/zoom should wrap the
 * output in their own `KuiverViewer` + `rememberKuiverViewerState` (see follow-up
 * ticket `kuml-widget-compose`).
 *
 * **Deviation from spec:** `KuiverViewer` unconditionally requires a `KuiverViewerState`.
 * We construct one internally via `rememberKuiverViewerState` with a
 * `LayoutConfig.Custom` provider that reproduces the `LayoutResult` positions —
 * effectively a no-op layout pass. This keeps the renderer API clean while satisfying
 * the Kuiver API constraint.
 *
 * Example:
 * ```kotlin
 * val layoutResult = elkEngine.layout(layoutGraph)
 * KumlKuiverRenderer.Render(diagram, layoutResult, PlainTheme)
 * ```
 *
 * @see dev.kuml.renderer.theme.PlainTheme the V1 default theme
 * @see dev.kuml.renderer.kuiver.KuiverGraphAdapter
 */
public object KumlKuiverRenderer {
    /**
     * Renders a [KumlDiagram] (UML) with a pre-computed [layoutResult].
     *
     * Node and edge Composables are dispatched via [NodeContentDispatcher] and
     * [EdgeContentDispatcher] respectively.
     *
     * @param diagram the UML diagram — provides the element list for node dispatch
     * @param layoutResult positions and dimensions produced by any [dev.kuml.layout.KumlLayoutEngine]
     * @param theme visual theme; defaults to [PlainTheme]
     * @param modifier Compose modifier applied to the viewer container
     */
    @Composable
    public fun Render(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme,
        modifier: Modifier = Modifier,
    ) {
        val kuiver = KuiverGraphAdapter.toKuiver(layoutResult)
        val layoutConfig = KuiverGraphAdapter.layoutConfig(layoutResult)
        val state =
            rememberKuiverViewerState(
                initialKuiver = kuiver,
                layoutConfig = layoutConfig,
            )

        // Build a quick lookup: Kuiver node ID → KumlElement
        val elementIndex = diagram.elements.associateBy { it.id }

        KuiverViewer(
            state = state,
            modifier = modifier,
            config =
                KuiverViewerConfig(
                    showDebugBounds = false,
                    fitToContent = false,
                ),
            nodeContent = { kuiverNode ->
                val element = elementIndex[kuiverNode.id]
                if (element != null) {
                    NodeContentDispatcher.render(element, theme)
                } else {
                    GenericFallbackNode(
                        element =
                            object : dev.kuml.core.model.KumlElement {
                                override val id = kuiverNode.id
                                override val metadata = emptyMap<String, dev.kuml.core.model.KumlMetaValue>()
                            },
                        theme = theme,
                    )
                }
            },
            edgeContent = { kuiverEdge, sourceOffset, targetOffset ->
                // Edge IDs encode "sourceId--targetId"; look up the relationship by ID
                // from the diagram if present. Fall back to a generic edge otherwise.
                val rel =
                    diagram.elements
                        .filterIsInstance<dev.kuml.core.model.KumlElement>()
                        .find { it.id == kuiverEdge.fromId + "--" + kuiverEdge.toId }
                if (rel != null) {
                    EdgeContentDispatcher.render(rel, kuiverEdge, sourceOffset, targetOffset, theme)
                } else {
                    GenericFallbackEdge(sourceOffset, targetOffset, theme)
                }
            },
        )
    }

    /**
     * Renders a [C4Diagram] with a pre-computed [layoutResult].
     *
     * Elements are resolved from the [model] registry by ID.
     *
     * @param diagram the C4 diagram — provides the element-ID list for node dispatch
     * @param model the root C4 model; element lookup is performed here
     * @param layoutResult positions and dimensions produced by any [dev.kuml.layout.KumlLayoutEngine]
     * @param theme visual theme; defaults to [PlainTheme]
     * @param modifier Compose modifier applied to the viewer container
     */
    @Composable
    public fun Render(
        diagram: C4Diagram,
        model: C4Model,
        layoutResult: LayoutResult,
        theme: KumlTheme = PlainTheme,
        modifier: Modifier = Modifier,
    ) {
        val kuiver = KuiverGraphAdapter.toKuiver(layoutResult)
        val layoutConfig = KuiverGraphAdapter.layoutConfig(layoutResult)
        val state =
            rememberKuiverViewerState(
                initialKuiver = kuiver,
                layoutConfig = layoutConfig,
            )

        // Build lookup: element ID → C4Element
        val elementIndex = model.elements.associateBy { it.id }
        val relationshipIndex = model.relationships.associateBy { it.id }

        KuiverViewer(
            state = state,
            modifier = modifier,
            config =
                KuiverViewerConfig(
                    showDebugBounds = false,
                    fitToContent = false,
                ),
            nodeContent = { kuiverNode ->
                val element = elementIndex[kuiverNode.id]
                if (element != null) {
                    NodeContentDispatcher.render(element, theme)
                } else {
                    GenericFallbackNode(
                        element =
                            object : dev.kuml.core.model.KumlElement {
                                override val id = kuiverNode.id
                                override val metadata = emptyMap<String, dev.kuml.core.model.KumlMetaValue>()
                            },
                        theme = theme,
                    )
                }
            },
            edgeContent = { kuiverEdge, sourceOffset, targetOffset ->
                val rel = relationshipIndex[kuiverEdge.fromId + "--" + kuiverEdge.toId]
                if (rel != null) {
                    EdgeContentDispatcher.render(rel, kuiverEdge, sourceOffset, targetOffset, theme)
                } else {
                    GenericFallbackEdge(sourceOffset, targetOffset, theme)
                }
            },
        )
    }
}
