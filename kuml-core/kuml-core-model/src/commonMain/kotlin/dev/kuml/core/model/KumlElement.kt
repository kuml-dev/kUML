package dev.kuml.core.model

import kotlinx.serialization.Polymorphic

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
 *
 * Because this base is open (not sealed), kotlinx.serialization cannot
 * auto-derive a serializer for fields typed as [KumlElement] (e.g.
 * [KumlDiagram.elements]). It is annotated [Polymorphic] so that a
 * runtime `SerializersModule` can register the concrete subtype tree for
 * each language module that should be (de)serializable. See
 * `dev.kuml.uml.UmlSerializersModule` in `kuml-metamodel-uml` for the
 * UML registration. Decoding an unregistered subtype throws
 * `SerializationException` — this is an explicit, scoped boundary, not
 * a silent failure.
 */
@Polymorphic
interface KumlElement {
    /** Stable identifier for traceability and diff. Derived deterministically from the element's qualified path. */
    val id: String

    /** Arbitrary metadata. Use [KumlMetaValue] sub-types for type-safe, serializable values. */
    val metadata: Map<String, KumlMetaValue>
}
