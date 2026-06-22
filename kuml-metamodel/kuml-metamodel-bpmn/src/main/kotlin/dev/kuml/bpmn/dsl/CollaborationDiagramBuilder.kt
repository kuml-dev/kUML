package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.CollaborationDiagram

/**
 * Builder for a [CollaborationDiagram].
 *
 * Instantiated via [BpmnModelBuilder.collaborationDiagram].
 *
 * Example:
 * ```kotlin
 * collaborationDiagram("Order Collab View", collaborationId = "collab1") {
 *     include("buyer", "seller", "collab1_mf_1")
 * }
 * ```
 *
 * V3.1.4 — BPMN Collaboration: Metamodell, DSL und SVG-Renderer
 */
@BpmnDsl
class CollaborationDiagramBuilder(
    private val name: String,
    private val collaborationId: String,
) {
    private val elementIds = mutableListOf<String>()

    /**
     * Includes specific element IDs in this diagram view.
     *
     * When no IDs are included, the diagram shows all elements in the
     * referenced collaboration (participants + message flows).
     *
     * @param ids Element IDs to include in the view.
     */
    fun include(vararg ids: String) {
        elementIds += ids.toList()
    }

    internal fun build(): CollaborationDiagram =
        CollaborationDiagram(
            name = name,
            collaborationId = collaborationId,
            elementIds = elementIds.toList(),
        )
}
