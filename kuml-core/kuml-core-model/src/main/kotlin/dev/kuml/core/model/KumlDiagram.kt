package dev.kuml.core.model

/**
 * A single diagram within a kUML model.
 *
 * @property name Human-readable name of the diagram.
 * @property type The diagram type (determines renderer and DSL elements).
 * @property elements Child elements defined inside this diagram.
 * @property id Stable identifier derived from the diagram name.
 *   Phase 1 introduces the full deterministic ID strategy via UmlIds.
 * @property metadata Arbitrary additional metadata.
 */
data class KumlDiagram(
    override val name: String,
    val type: DiagramType = DiagramType.CLASS,
    val elements: List<KumlElement> = emptyList(),
    override val id: String = name,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    val config: DiagramConfig? = null,
) : KumlNamespaceMember
