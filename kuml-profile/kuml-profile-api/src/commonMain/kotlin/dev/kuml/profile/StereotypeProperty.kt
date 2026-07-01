package dev.kuml.profile

import kotlin.reflect.KClass

/**
 * A typed property (tagged-value definition) on a stereotype.
 *
 * Allowed types in V1.1: [String], [Int], [Long], [Double], [Boolean],
 * enum classes, and [List] (of primitive types).
 */
public data class StereotypeProperty<T : Any>(
    val name: String,
    val type: KClass<T>,
    val default: T? = null,
    val required: Boolean = (default == null),
    val min: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "StereotypeProperty.name must not be blank" }
        require(type in ALLOWED_TYPES || type.isEnumClass()) {
            "Stereotype property '$name': type ${type.simpleName} is not allowed in V1.1. " +
                "Allowed: String, Int, Long, Double, Boolean, enum classes, List<primitive>."
        }
    }

    public companion object {
        public val ALLOWED_TYPES: Set<KClass<*>> =
            setOf(
                String::class,
                Int::class,
                Long::class,
                Double::class,
                Boolean::class,
                List::class,
            )
    }
}
