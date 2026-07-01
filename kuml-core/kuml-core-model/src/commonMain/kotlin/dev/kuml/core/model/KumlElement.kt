package dev.kuml.core.model

/**
 * Root interface for all elements in a kUML model.
 *
 * This is intentionally a plain (non-sealed) interface so that language-specific
 * metamodels in separate modules (e.g. `kuml-metamodel-uml`, `kuml-metamodel-c4`)
 * can implement it. Kotlin's sealed restriction requires subtypes to live in the
 * same module and package, which is incompatible with the multi-module design.
 *
 * Sealed exhaustivity is provided one level down — per language:
 * - `sealed interface UmlElement : KumlElement` (in kuml-metamodel-uml)
 * - `sealed interface C4Element  : KumlElement` (in kuml-metamodel-c4)
 *
 * When processing a model, code always operates on the language-specific sealed
 * type and can use exhaustive `when` expressions there.
 */
interface KumlElement {
    /** Stable identifier for traceability and diff. Derived deterministically from the element's qualified path. */
    val id: String

    /** Arbitrary metadata. Use [KumlMetaValue] sub-types for type-safe, serializable values. */
    val metadata: Map<String, KumlMetaValue>
}
