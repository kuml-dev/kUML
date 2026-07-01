package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * One parameter of a [ConstraintDefinition] — the typed-pin record on the
 * constraint's edge (V2.0.12).
 *
 * Parameters are intentionally **NOT** [Sysml2Element]s: they are not
 * top-level addressable objects in the model. They are sub-records owned
 * by their parent [ConstraintDefinition] (analogous to enum members owned
 * by their enum), and their addressable id is the synthetic concatenation
 * `"<constraintId>::<parameterName>"` used as the endpoint id in
 * [BindingConnectorUsage]s. Keeping parameters as a flat data class avoids
 * an extra registry slot in [Sysml2Model] for what is essentially a leaf
 * struct.
 *
 * Carries three slots:
 *  - [name] — the local parameter name within the constraint, e.g.
 *    `"F"`, `"m"`, `"a"` for Newton's second law `F = m·a`. Used as the
 *    pin label on the SVG renderer's parameter list and as the second half
 *    of the synthetic endpoint id (`"NewtonsLaw::F"`).
 *  - [typeId] — optional reference to an [AttributeDefinition] id like
 *    `"Mass"`. The V2.0.12 MVP uses the typeId only for rendering (it
 *    surfaces as `name : Type` on the parameter list); a typed-link to the
 *    referenced definition is V2.x polish.
 *  - [direction] — [ConstraintParameterDirection] discriminator. Defaults
 *    to [ConstraintParameterDirection.Inout] so an under-specified
 *    constraint is silently treated as a bidirectional relationship.
 *
 * V2.0.12 MVP scope:
 *  - Plain data record; no specialisation, no nesting, no nested parameter
 *    groups.
 *  - Multiplicity is implicitly `1..1` — multi-valued parameters are V2.x
 *    polish.
 *  - typeId-resolution is the renderer's responsibility (longest-prefix
 *    or exact match against [AttributeDefinition] ids); no metamodel-side
 *    cross-reference checking.
 */
@Serializable
data class ConstraintParameter(
    /** Local name within the constraint — e.g. `"F"`, `"m"`, `"a"`. */
    val name: String,
    /** Optional type reference — typically an [AttributeDefinition] id like `"Mass"`. */
    val typeId: String? = null,
    /** Direction of the parameter. Defaults to [ConstraintParameterDirection.Inout]. */
    val direction: ConstraintParameterDirection = ConstraintParameterDirection.Inout,
)
