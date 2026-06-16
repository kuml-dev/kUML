package dev.kuml.ai.tools.context

import kotlinx.serialization.json.Json

/**
 * Deep-copies an AnyKumlModel by serializing through kotlinx.serialization JSON.
 *
 * Rationale: every kUML metamodel element (UmlNamedElement, UmlRelationship, C4Model,
 * Sysml2Model) is already @Serializable. AnyKumlModel wraps them in a sealed hierarchy
 * that is also @Serializable. JSON roundtrip is the safest deterministic copy —
 * reflection-based deepCopy is brittle against sealed interfaces and manual copy()
 * cascades miss nested mutables.
 *
 * KumlModel / KumlDiagram (from kuml-core-model) are NOT @Serializable, so the
 * AnyKumlModel.Uml variant stores data in flat lists rather than wrapping KumlModel
 * directly. This sidesteps the serialization gap completely.
 */
internal object DeepCopy {
    internal val json: Json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            // Avoids collision with element `type` fields in UML/SysML2 metamodels
            classDiscriminator = "kuml_type"
        }

    /** Returns a deep-copied clone of the model via JSON roundtrip. */
    internal fun copy(model: AnyKumlModel): AnyKumlModel =
        json.decodeFromString(AnyKumlModel.serializer(), json.encodeToString(AnyKumlModel.serializer(), model))
}
