package dev.kuml.core.model

import kotlinx.serialization.Serializable

/**
 * A type-safe, serializable metadata value.
 *
 * Replaces the former `Map<String, Any>` metadata type across the kUML model hierarchy.
 * All variants are serializable via kotlinx.serialization and safe for GraalVM Native Image.
 *
 * Usage:
 * ```kotlin
 * val meta = mapOf(
 *     "line"   to KumlMetaValue.Integer(42),
 *     "source" to KumlMetaValue.Text("order.kuml.kts"),
 *     "stable" to KumlMetaValue.Flag(true),
 * )
 * ```
 */
@Serializable
sealed interface KumlMetaValue {
    /**
     * A UTF-8 string value.
     *
     * Implemented as a regular data class (not `@JvmInline value class`) so that
     * kotlinx.serialization can embed the `type` discriminator required for
     * sealed-interface polymorphism.
     */
    @Serializable
    data class Text(
        val value: String,
    ) : KumlMetaValue

    /** A 64-bit integer value. */
    @Serializable
    data class Integer(
        val value: Long,
    ) : KumlMetaValue

    /** A 64-bit floating-point value. */
    @Serializable
    data class Decimal(
        val value: Double,
    ) : KumlMetaValue

    /** A boolean value. */
    @Serializable
    data class Flag(
        val value: Boolean,
    ) : KumlMetaValue

    /** An ordered list of metadata values. */
    @Serializable
    data class Items(
        val value: List<KumlMetaValue>,
    ) : KumlMetaValue

    /** A string-keyed map of metadata values. */
    @Serializable
    data class Entries(
        val value: Map<String, KumlMetaValue>,
    ) : KumlMetaValue
}
