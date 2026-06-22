package dev.kuml.bpmn.dsl

import dev.kuml.bpmn.model.ProcessDiagram

/**
 * Builder for a [ProcessDiagram] — a named view scoped to a single process.
 *
 * Collects the element ids that should be rendered in this diagram view.
 * An empty [include] block means "include all elements" — the renderer
 * uses the empty-list as a short-hand (same convention as BDD/IBD in SysML 2).
 */
@BpmnDsl
class ProcessDiagramBuilder(
    val name: String,
    val processId: String,
) {
    private val elementIds: MutableList<String> = mutableListOf()

    /**
     * Add one or more element ids to the diagram view.
     *
     * @param ids The element ids to include (vararg — accepts any number).
     */
    fun include(vararg ids: String) {
        elementIds += ids.toList()
    }

    internal fun build(): ProcessDiagram = ProcessDiagram(name = name, processId = processId, elementIds = elementIds.toList())
}
