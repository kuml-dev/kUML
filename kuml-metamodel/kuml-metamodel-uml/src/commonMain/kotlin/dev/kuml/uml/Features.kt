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
 * The OCL contextual constraint stereotype (V3.2.22) — mirrors the standard
 * OCL keywords `inv:`/`def:`/`pre:`/`post:`/`body:` that precede a constraint
 * body in concrete OCL syntax.
 *
 * @property Invariant `inv:` — a classifier-scoped invariant (the pre-V3.2.22
 *   default; must hold for every instance of the classifier at all times).
 * @property Definition `def:` — a reusable named helper (attribute/operation)
 *   declared in the classifier scope and referenceable from later constraints,
 *   rather than an assertion in itself.
 * @property Precondition `pre:` — an operation entry condition. Requires
 *   [UmlConstraint.contextOperation] to name the constrained operation.
 * @property Postcondition `post:` — an operation exit condition. May reference
 *   `result` (the operation's return value) and `expr@pre` (pre-state
 *   snapshots). Requires [UmlConstraint.contextOperation].
 * @property Body `body:` — a full operation definition via its return-value
 *   expression (`result = ...`). May reference `result`. Requires
 *   [UmlConstraint.contextOperation].
 */
@Serializable
enum class UmlConstraintKind { Invariant, Definition, Precondition, Postcondition, Body }

/**
 * A structural placeholder for a UML constraint (OCL or other language).
 *
 * Interpretation of the constraint [body] is deferred to Phase 2
 * (`kuml-core-ocl`). In Phase 1 the body is stored as a raw string.
 *
 * @property body The constraint expression (e.g. an OCL invariant).
 * @property language Constraint language identifier (default: `"OCL"`).
 * @property kind The OCL contextual stereotype (V3.2.22); defaults to
 *   [UmlConstraintKind.Invariant] to preserve pre-V3.2.22 semantics for
 *   existing constraints.
 * @property contextOperation Name of the [UmlOperation] this constraint is
 *   scoped to, required for [UmlConstraintKind.Precondition],
 *   [UmlConstraintKind.Postcondition], and [UmlConstraintKind.Body] (validated
 *   by `kuml-core-ocl`'s `OclValidator`); `null` for
 *   [UmlConstraintKind.Invariant] and [UmlConstraintKind.Definition], which
 *   are classifier-scoped rather than operation-scoped.
 */
@Serializable
data class UmlConstraint(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val body: String,
    val language: String = "OCL",
    val kind: UmlConstraintKind = UmlConstraintKind.Invariant,
    val contextOperation: String? = null,
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement
