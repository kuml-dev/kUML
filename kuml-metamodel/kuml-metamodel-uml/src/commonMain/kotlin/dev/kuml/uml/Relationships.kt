package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A UML relationship between two or more elements.
 *
 * Relationships reference their endpoints by [UmlElement.id] (String),
 * not by object reference. This keeps the model tree-shaped, serializable,
 * and diff-friendly. Resolution of id → element is handled by a later
 * index / resolver layer.
 */
@Serializable
sealed interface UmlRelationship : UmlElement

// ── Association ───────────────────────────────────────────────────────────────

/**
 * A UML association between two classifiers.
 *
 * V1 assumes exactly two [ends]. The [aggregation] kind applies to the
 * whole association (COMPOSITE or SHARED on the owning end).
 *
 * @property name Optional association name (shown on the line label).
 * @property ends Exactly two [UmlAssociationEnd] values.
 * @property aggregation Aggregation kind for the source end (default: none).
 */
@Serializable
data class UmlAssociation(
    override val id: String,
    val name: String? = null,
    val ends: List<UmlAssociationEnd>,
    val aggregation: AggregationKind = AggregationKind.NONE,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlRelationship,
    Stereotypable

/**
 * One end of a [UmlAssociation].
 *
 * @property typeId The [UmlElement.id] of the classifier at this end.
 * @property role Optional role name shown near the end.
 * @property multiplicity Multiplicity of this end (default: `1`).
 * @property navigable Whether this end is navigable from the other end.
 */
@Serializable
data class UmlAssociationEnd(
    val typeId: String,
    val role: String? = null,
    val multiplicity: Multiplicity = Multiplicity(),
    val navigable: Boolean = true,
)

// ── Generalization ────────────────────────────────────────────────────────────

/**
 * A UML generalization (inheritance) from a specific to a general classifier.
 *
 * @property specificId [UmlElement.id] of the more specific (child) classifier.
 * @property generalId [UmlElement.id] of the more general (parent) classifier.
 */
@Serializable
data class UmlGeneralization(
    override val id: String,
    val specificId: String,
    val generalId: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlRelationship,
    Stereotypable

// ── Interface Realization ─────────────────────────────────────────────────────

/**
 * A UML interface realization — a class implements an interface contract.
 *
 * @property implementingId [UmlElement.id] of the implementing class.
 * @property interfaceId [UmlElement.id] of the realized interface.
 */
@Serializable
data class UmlInterfaceRealization(
    override val id: String,
    val implementingId: String,
    val interfaceId: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlRelationship,
    Stereotypable

// ── Dependency ────────────────────────────────────────────────────────────────

/**
 * A UML dependency — the client element uses or depends on the supplier.
 *
 * @property clientId [UmlElement.id] of the dependent (client) element.
 * @property supplierId [UmlElement.id] of the element being depended upon (supplier).
 * @property name Optional stereotype or label (e.g. `"<<use>>"`, `"<<call>>"`).
 */
@Serializable
data class UmlDependency(
    override val id: String,
    val clientId: String,
    val supplierId: String,
    val name: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlRelationship,
    Stereotypable
