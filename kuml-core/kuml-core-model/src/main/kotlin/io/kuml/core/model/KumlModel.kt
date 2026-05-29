package io.kuml.core.model

/**
 * Root container for a kUML model.
 *
 * A model wraps a single [root] element and carries the metadata that
 * classifies it: which modeling [language] it uses and on which MDA [level]
 * it lives.
 *
 * @property root The top-level element of this model.
 * @property language The modeling language used (UML, SYSML2, C4, MIXED).
 * @property level The MDA abstraction level (CIM, PIM, PSM, DEPLOYMENT).
 * @property name Human-readable name of the model.
 * @property metadata Arbitrary additional metadata.
 */
data class KumlModel(
    val root: KumlElement,
    val language: ModelingLanguage,
    val level: ModelLevel,
    val name: String,
    val metadata: Map<String, Any> = emptyMap(),
)
