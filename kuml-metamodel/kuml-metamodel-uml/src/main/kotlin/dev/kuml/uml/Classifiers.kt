package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

/**
 * A UML classifier — a type that can own features and participate in relationships.
 *
 * Sealed subtypes cover all V1-relevant structural classifiers:
 * [UmlClass], [UmlInterface], [UmlEnumeration], [UmlActor], [UmlUseCase], [UmlComponent].
 */
@Serializable
sealed interface UmlClassifier : UmlNamedElement

// ── Class ─────────────────────────────────────────────────────────────────────

/**
 * A UML class.
 *
 * @property isAbstract `true` for abstract classes.
 * @property attributes Owned properties (attributes).
 * @property operations Owned operations.
 * @property constraints OCL or other constraints attached to this class.
 *   Structural placeholder — interpretation is Phase 2 ([kuml-core-ocl]).
 */
@Serializable
data class UmlClass(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val isAbstract: Boolean = false,
    val attributes: List<UmlProperty> = emptyList(),
    val operations: List<UmlOperation> = emptyList(),
    val constraints: List<UmlConstraint> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlClassifier

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * A UML interface — a classifier that declares a contract without implementation.
 *
 * @property attributes Owned properties (rare in interfaces but permitted by UML 2.x).
 * @property operations Owned operation specifications.
 */
@Serializable
data class UmlInterface(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val attributes: List<UmlProperty> = emptyList(),
    val operations: List<UmlOperation> = emptyList(),
    val constraints: List<UmlConstraint> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlClassifier

// ── Enumeration ───────────────────────────────────────────────────────────────

/** A UML enumeration. */
@Serializable
data class UmlEnumeration(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val literals: List<UmlEnumerationLiteral> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlClassifier

/** A single literal value of a [UmlEnumeration]. */
@Serializable
data class UmlEnumerationLiteral(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

// ── Package ───────────────────────────────────────────────────────────────────

/**
 * A UML package that groups named elements.
 *
 * Packages can be nested — [members] may contain other [UmlPackage] instances.
 */
@Serializable
data class UmlPackage(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val members: List<UmlNamedElement> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement
