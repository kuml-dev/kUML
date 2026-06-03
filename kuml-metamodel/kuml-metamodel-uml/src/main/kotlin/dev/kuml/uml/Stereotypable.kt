package dev.kuml.uml

import kotlinx.serialization.Serializable

// ── TagValue ─────────────────────────────────────────────────────────────────

/**
 * A discriminated-union value type for stereotype tagged-values.
 *
 * [Map]<String, [Any]?> is not directly kotlinx-serialization-compatible;
 * this sealed hierarchy provides a type-safe, serializable alternative.
 * The public DSL in `kuml-profile-api` accepts [Any]? and maps via
 * `Any?.toTagValue()` at build-time.
 */
@Serializable
sealed class TagValue {
    @Serializable
    data class StringVal(
        val v: String,
    ) : TagValue()

    @Serializable
    data class IntVal(
        val v: Int,
    ) : TagValue()

    @Serializable
    data class LongVal(
        val v: Long,
    ) : TagValue()

    @Serializable
    data class DoubleVal(
        val v: Double,
    ) : TagValue()

    @Serializable
    data class BoolVal(
        val v: Boolean,
    ) : TagValue()

    @Serializable
    data class EnumVal(
        val typeName: String,
        val valueName: String,
    ) : TagValue()

    @Serializable
    data class ListVal(
        val items: List<TagValue>,
    ) : TagValue()
}

// ── AppliedStereotype ─────────────────────────────────────────────────────────

/**
 * Minimal view of a stereotype application, defined in this module so that
 * [Stereotypable] can live here without a circular dependency on
 * `kuml-profile-api`.
 *
 * The concrete implementation is `KumlStereotypeApplication` in `kuml-profile-api`,
 * which implements this interface. Dependency direction:
 * `kuml-profile-api` → `kuml-metamodel-uml`, never the reverse.
 *
 * Not sealed: concrete implementations live in separate modules (cross-module
 * sealing is forbidden by the Kotlin compiler).
 */
interface AppliedStereotype {
    val profileNamespace: String
    val stereotypeName: String
    val tags: Map<String, TagValue>
}

// ── Stereotypable ─────────────────────────────────────────────────────────────

/**
 * Marker for UML metamodel elements that can have stereotypes applied.
 *
 * All implementing classes carry [appliedStereotypes] as the last constructor
 * parameter with a default of [emptyList], preserving backwards compatibility
 * for all existing call sites.
 *
 * The existing [UmlNamedElement.stereotypes] field (a plain `List<String>`) is
 * the V1.0 placeholder and remains unchanged. This interface provides the typed
 * V1.1 replacement.
 */
interface Stereotypable {
    val appliedStereotypes: List<AppliedStereotype>
}
