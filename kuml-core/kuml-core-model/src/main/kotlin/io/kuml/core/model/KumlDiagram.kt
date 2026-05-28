package io.kuml.core.model

/**
 * A single diagram within a kUML model.
 *
 * @property name Human-readable name of the diagram.
 * @property type The diagram type (determines renderer and DSL elements).
 * @property elements Child elements defined inside this diagram.
 */
data class KumlDiagram(
    val name: String,
    val type: DiagramType = DiagramType.CLASS,
    val elements: List<KumlElement> = emptyList(),
) : KumlElement
