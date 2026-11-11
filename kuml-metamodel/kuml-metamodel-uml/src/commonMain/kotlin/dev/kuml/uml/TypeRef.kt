package dev.kuml.uml

import kotlinx.serialization.Serializable

/**
 * A reference to a UML type used in properties, parameters and ports.
 *
 * @property name Simple or qualified type name (e.g. `"UUID"`, `"domain::Order"`).
 * @property referencedId If the target type is a model element, its stable [UmlElement.id].
 *   `null` for primitive / external type names that are not modelled.
 */
@Serializable
data class UmlTypeRef(
    val name: String,
    val referencedId: String? = null,
)

/**
 * Multiplicity of a structural feature or association end.
 *
 * @property lower Minimum number of values (≥ 0).
 * @property upper Maximum number of values. `null` means unbounded (`*`).
 *
 * Common shorthands: `1` → `Multiplicity(1, 1)`, `0..*` → `Multiplicity(0, null)`,
 * `1..*` → `Multiplicity(1, null)`.
 */
@Serializable
data class Multiplicity(
    val lower: Int = 1,
    val upper: Int? = 1,
)

/**
 * Display label for a multiplicity, or `null` if nothing should be drawn.
 *
 * An implicit `1..1` (the default) yields `null`; an explicit `1..1` yields `"1"`.
 * `lower == upper` gives `"n"`, an unbounded upper gives `"*"`, otherwise `"lower..upper"`.
 */
fun multiplicityLabel(
    multiplicity: Multiplicity,
    explicit: Boolean,
): String? {
    val upper = if (multiplicity.upper == null) "*" else multiplicity.upper.toString()
    val label = if (multiplicity.lower == multiplicity.upper) upper else "${multiplicity.lower}..$upper"
    return if (label == "1" && !explicit) null else label
}

/** Label of this end's multiplicity, honouring [UmlAssociationEnd.multiplicityExplicit]. */
fun UmlAssociationEnd.multiplicityLabel(): String? = multiplicityLabel(multiplicity = multiplicity, explicit = multiplicityExplicit)
