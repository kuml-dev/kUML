package io.kuml.core.model

/**
 * Root container for a kUML model.
 *
 * A model is the result of evaluating one or more `*.kuml.kts` script files.
 *
 * @property diagrams All diagrams defined in this model.
 */
data class KumlModel(
    val diagrams: List<KumlDiagram> = emptyList(),
) : KumlElement
