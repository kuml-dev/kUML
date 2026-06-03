package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// UML 2.x object-diagram building blocks (V1.1).
//
// An object diagram is a snapshot of a runtime configuration: instances of
// classifiers, the values of their slots, and the links between them.
//
// Reference: UML 2.x § 9.5 (InstanceSpecification, Slot, ValueSpecification),
// rendered in UML notation as an underlined `name : ClassName` header with a
// slot compartment `attr = value` below it.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A UML 2.x `InstanceSpecification` — a runtime instance of one or more classifiers.
 *
 * Rendered as a rectangle with the header `name : ClassifierName` (both
 * underlined per UML convention) and a compartment listing the slot values.
 *
 * V1.1 simplifications vs. full UML:
 *  - Single classifier per instance ([classifierId]). UML 2.x allows multiple.
 *  - Anonymous instances (empty [name]) are supported via the colon-only header.
 *
 * @property name Instance name. Empty string for anonymous instances (`: User`).
 * @property classifierId ID of the [UmlClassifier] this instance realises.
 * @property classifierName Human-readable classifier name (denormalised so the
 *  renderer can produce the `name : Type` header without a model-wide lookup).
 * @property slots Slot values for the instance's attributes.
 */
@Serializable
data class UmlInstanceSpecification(
    override val id: String,
    override val name: String,
    val classifierId: String,
    val classifierName: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val slots: List<UmlSlot> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlNamedElement

/**
 * A UML 2.x `Slot` — value(s) assigned to a defining feature of an instance.
 *
 * @property definingFeatureId ID of the [UmlProperty] this slot fills.
 *  Use `""` for slots that just label an ad-hoc attribute name (rare).
 * @property featureName Human-readable feature name (denormalised so the
 *  renderer doesn't have to chase classifier references).
 * @property value The slot value (literal, instance reference, or null).
 */
@Serializable
data class UmlSlot(
    val definingFeatureId: String,
    val featureName: String,
    val value: UmlInstanceValue,
)

/**
 * The value held by a [UmlSlot] or a link end.
 *
 * Sealed because UML 2.x permits only a small fixed set of value shapes in an
 * object-diagram context: a literal expression, a reference to another
 * instance, or an explicit `null`.
 */
@Serializable
sealed interface UmlInstanceValue {
    /** A literal value rendered verbatim (already formatted, e.g. `"42"`, `"\"hi\""`, `"true"`). */
    @Serializable
    data class Literal(
        val text: String,
    ) : UmlInstanceValue

    /** A reference to another [UmlInstanceSpecification] (by ID). */
    @Serializable
    data class InstanceRef(
        val instanceId: String,
    ) : UmlInstanceValue

    /** Explicit absence of a value — rendered as `null`. */
    @Serializable
    data object Null : UmlInstanceValue
}

/**
 * A UML 2.x link — an instance of a [UmlAssociation] connecting two
 * [UmlInstanceSpecification]s.
 *
 * V1.1 supports binary links only (two endpoints). N-ary links are V2.
 *
 * @property associationId Optional ID of the typing [UmlAssociation]. May be
 *  empty if the link is not derived from a named association.
 * @property sourceInstanceId ID of the source [UmlInstanceSpecification].
 * @property targetInstanceId ID of the target [UmlInstanceSpecification].
 * @property sourceRoleName Optional role label rendered at the source end.
 * @property targetRoleName Optional role label rendered at the target end.
 */
@Serializable
data class UmlLink(
    override val id: String,
    val associationId: String,
    val sourceInstanceId: String,
    val targetInstanceId: String,
    val sourceRoleName: String? = null,
    val targetRoleName: String? = null,
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
) : UmlRelationship
