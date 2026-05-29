package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ── Actor ─────────────────────────────────────────────────────────────────────

/**
 * A UML actor — an external entity that interacts with the system.
 *
 * Actor ↔ UseCase associations are modelled as [UmlAssociation].
 * Actor specialisation is modelled as [UmlGeneralization].
 */
@Serializable
data class UmlActor(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlClassifier

// ── Use Case ──────────────────────────────────────────────────────────────────

/**
 * A UML use case — a unit of externally observable system behaviour.
 *
 * @property constraints OCL or other constraints (structural placeholder, Phase 2).
 */
@Serializable
data class UmlUseCase(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val constraints: List<UmlConstraint> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlClassifier

// ── Subject ───────────────────────────────────────────────────────────────────

/**
 * The subject (system boundary) of a use-case diagram.
 *
 * @property useCaseIds The [UmlElement.id]s of the use cases that belong to this subject.
 */
@Serializable
data class UmlUseCaseSubject(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val useCaseIds: List<String> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

// ── Include / Extend ──────────────────────────────────────────────────────────

/**
 * A UML `<<include>>` relationship between two use cases.
 *
 * The base use case unconditionally includes the behaviour of the addition.
 *
 * @property baseId [UmlElement.id] of the including (base) use case.
 * @property additionId [UmlElement.id] of the included use case.
 */
@Serializable
data class UmlInclude(
    override val id: String,
    val baseId: String,
    val additionId: String,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlRelationship

/**
 * A UML `<<extend>>` relationship between two use cases.
 *
 * The extension use case conditionally extends the behaviour of the base
 * at the named extension point.
 *
 * @property baseId [UmlElement.id] of the extended (base) use case.
 * @property extensionId [UmlElement.id] of the extending use case.
 * @property extensionPoint Name of the extension point in the base use case, if specified.
 */
@Serializable
data class UmlExtend(
    override val id: String,
    val baseId: String,
    val extensionId: String,
    val extensionPoint: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlRelationship
