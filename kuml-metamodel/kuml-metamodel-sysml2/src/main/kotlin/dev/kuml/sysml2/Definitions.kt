package dev.kuml.sysml2

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.kerml.KermlFeature
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.kerml.KermlType
import kotlinx.serialization.Serializable

/**
 * Sealed root for every SysML 2 **definition** — the "what *is* it" side of
 * the definition/usage duality.
 *
 * A SysML 2 definition is structurally a KerML type that owns features. The
 * features themselves usually surface in tooling as SysML 2 usages
 * ([Sysml2Usage]) — but at the KerML layer there is no separate concept,
 * just `Feature`s and their `typeId`s. The MVP keeps the SysML 2 layer
 * thin and trusts the KerML primitives.
 */
@Serializable
sealed interface Sysml2Definition :
    Sysml2Element,
    KermlType

/**
 * `PartDefinition` — the SysML 2 successor to UML class / SysML 1 block.
 *
 * Represents a system part *type* (e.g. `Vehicle`, `Engine`, `Cylinder`).
 * Owns attribute / port / part usages via [features]. Inheritance is
 * encoded via [specializations] (KerML `:>`).
 */
@Serializable
data class PartDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `AttributeDefinition` — a SysML 2 attribute *type*, e.g. `Mass`, `Voltage`,
 * `Boolean`. Used in BDD diagrams as the typing reference for attribute
 * usages. Backed by a KerML data type (value semantics, not parts).
 */
@Serializable
data class AttributeDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `PortDefinition` — a typed connection point. The SysML 2 successor to
 * SysML 1 ports / UML interface-pair patterns.
 */
@Serializable
data class PortDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition

/**
 * `ConnectionDefinition` — a typed relationship between two port-bearing
 * elements. Connection *usages* live on a `PartDefinition`'s feature list;
 * this is the type they instantiate.
 */
@Serializable
data class ConnectionDefinition(
    override val id: String,
    override val name: String,
    override val qualifiedName: String = name,
    override val isAbstract: Boolean = false,
    override val features: List<KermlFeature> = emptyList(),
    override val specializations: List<KermlSpecialization> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : Sysml2Definition
