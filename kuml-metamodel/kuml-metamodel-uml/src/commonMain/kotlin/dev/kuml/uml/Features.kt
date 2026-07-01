package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ── Property (Attribute) ──────────────────────────────────────────────────────

/**
 * A UML property — structural feature of a [UmlClassifier].
 *
 * Used for attributes of classes, interfaces, and association ends.
 *
 * @property type Type reference for this property.
 * @property multiplicity Multiplicity of this property (default: exactly one).
 * @property defaultValue String representation of the default value expression, if any.
 * @property isStatic `true` if this is a class-scope (static) feature.
 * @property isReadOnly `true` if the property value may not be changed after initialisation.
 */
@Serializable
data class UmlProperty(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PRIVATE,
    val type: UmlTypeRef,
    val multiplicity: Multiplicity = Multiplicity(),
    val defaultValue: String? = null,
    val isStatic: Boolean = false,
    val isReadOnly: Boolean = false,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlNamedElement,
    Stereotypable

// ── Operation ─────────────────────────────────────────────────────────────────

/**
 * A UML operation — behavioural feature of a [UmlClassifier].
 *
 * @property parameters Owned parameters (including the return parameter if present).
 *   Use [ParameterDirection.RETURN] for the return value.
 * @property returnType Convenience shorthand for the return type.
 *   Takes precedence over a [ParameterDirection.RETURN] parameter for display purposes.
 * @property isAbstract `true` if this operation has no implementation.
 * @property isStatic `true` if this is a class-scope (static) operation.
 */
@Serializable
data class UmlOperation(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val parameters: List<UmlParameter> = emptyList(),
    val returnType: UmlTypeRef? = null,
    val isAbstract: Boolean = false,
    val isStatic: Boolean = false,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlNamedElement,
    Stereotypable

// ── Parameter ─────────────────────────────────────────────────────────────────

/** Direction of a [UmlParameter]. */
enum class ParameterDirection { IN, OUT, INOUT, RETURN }

/**
 * A parameter of a [UmlOperation].
 *
 * @property type Type of this parameter.
 * @property direction In/out direction (default: [ParameterDirection.IN]).
 * @property defaultValue Default value expression as a string, if any.
 */
@Serializable
data class UmlParameter(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val type: UmlTypeRef,
    val direction: ParameterDirection = ParameterDirection.IN,
    val defaultValue: String? = null,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlNamedElement,
    Stereotypable

// ── Constraint ────────────────────────────────────────────────────────────────

/**
 * A structural placeholder for a UML constraint (OCL or other language).
 *
 * Interpretation of the constraint [body] is deferred to Phase 2
 * (`kuml-core-ocl`). In Phase 1 the body is stored as a raw string.
 *
 * @property body The constraint expression (e.g. an OCL invariant).
 * @property language Constraint language identifier (default: `"OCL"`).
 */
@Serializable
data class UmlConstraint(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val body: String,
    val language: String = "OCL",
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement
