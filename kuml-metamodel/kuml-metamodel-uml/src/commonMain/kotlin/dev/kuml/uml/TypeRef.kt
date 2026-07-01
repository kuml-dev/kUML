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
