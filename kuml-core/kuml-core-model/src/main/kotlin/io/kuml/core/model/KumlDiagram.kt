package io.kuml.core.model

/**
 * A single diagram within a kUML model.
 *
 * @property name Human-readable name of the diagram.
 * @property type The diagram type (determines renderer and DSL elements).
 * @property elements Child elements defined inside this diagram.
 * @property id Stable identifier. Phase-0 placeholder defaulting to [name];
 *   Phase 1 introduces the proper ID strategy.
 * @property metadata Arbitrary additional metadata.
 */
data class KumlDiagram(
    override val name: String,
    val type: DiagramType = DiagramType.CLASS,
    val elements: List<KumlElement> = emptyList(),
    override val id: String = name,
    override val metadata: Map<String, Any> = emptyMap(),
) : KumlNamespaceMember
