package dev.kuml.kerml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A KerML type. Root for [KermlClassifier] (structural types) and
 * [KermlDataType] (value types). Every type owns features and may specialise
 * other types.
 *
 * **Why types and features are separate marker hierarchies**: in KerML, a
 * `Feature` *also* has a type — and a `Type` *also* owns features. To stop
 * the metamodel from looping infinitely we have two roots that share
 * [KermlNamespaceMember]. The cross-link lives in [KermlFeature.typeId] (an
 * id reference, not a direct object reference) — same trick as the existing
 * UML metamodel uses for association ends.
 *
 * Like [KermlElement], not sealed — SysML 2's `PartDefinition` /
 * `AttributeDefinition` / `PortDefinition` / `ConnectionDefinition` implement
 * this from a separate module.
 */
interface KermlType : KermlNamespaceMember {
    /** Features (attributes, ports, parameters, …) owned by this type. */
    val features: List<KermlFeature>

    /** `:>`-specialisations of this type. */
    val specializations: List<KermlSpecialization>

    /** `abstract` modifier — the type cannot be directly instantiated. */
    val isAbstract: Boolean
}

/**
 * A structural KerML classifier — the V2.0.3 marker for SysML 2 PartDefinitions,
 * PortDefinitions, ConnectionDefinitions, …
 *
 * SysML 2-specific metaclasses extend this without adding KerML state; they
 * just narrow the meaning ("a `PartDefinition` is a [KermlClassifier] that
 * represents a system part").
 */
@Serializable
data class KermlClassifier(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : KermlType

/**
 * A value type — `DataType` in KerML, `AttributeDefinition` flavor in SysML 2.
 * Represented separately so consumers can dispatch on "is this a `Real`,
 * `Boolean`, `Mass`, …" vs "is this a `Part`".
 */
@Serializable
data class KermlDataType(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : KermlType

/**
 * A KerML feature — the unified concept that, in SysML 2, surfaces as
 * `AttributeUsage`, `PortUsage`, `PartUsage`, `ConnectionEnd`, …
 *
 * The "definition" vs "usage" distinction at the SysML 2 level maps to:
 * a definition is a [KermlType] that owns features; a usage is a feature
 * whose type references that definition. The runtime/diff tooling can
 * inspect [definitionId] to recover the SysML 2-level shape.
 *
 * @property typeId Id of the [KermlType] this feature is typed by — or
 *   null for untyped features (rare; the SysML 2 DSL always supplies one).
 * @property definitionId Id of the SysML 2-level definition this feature
 *   refers to, when applicable. Concretely: for a `PartUsage`, [typeId] and
 *   [definitionId] are equal; for an `AttributeUsage`, [typeId] is the
 *   data type and [definitionId] is the `AttributeDefinition`. The split
 *   only matters in the SysML 2 layer — at the KerML layer it's free
 *   metadata.
 */
@Serializable
data class KermlFeature(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    val typeId: String? = null,
    val definitionId: String? = null,
    val multiplicity: KermlMultiplicity = KermlMultiplicity.EXACTLY_ONE,
    val isAbstract: Boolean = false,
    val isReadOnly: Boolean = false,
    /** Optional default-value expression as raw source text. */
    val defaultExpression: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : KermlNamespaceMember

/**
 * `Type :> Type` — KerML's inheritance/conjugation hookup.
 *
 * `specificId` is the more-specific type (the child), `generalId` the more
 * general one (the parent). Two ids, no direct refs — preserves
 * tree-shapedness for serialisation and diff.
 */
@Serializable
data class KermlSpecialization(
    val specificId: String,
    val generalId: String,
)
