package dev.kuml.runtime

import kotlinx.serialization.json.JsonElement

/**
 * Public bridge to the runtime's own JSON to value mapping.
 *
 * Uses exactly the same rules as snapshot/restore (`"true"` becomes a Boolean, integral numbers
 * become `Long`, other numbers `Double`, objects become `Map`, arrays become `List`), so a value
 * edited from the outside behaves like a value that survived a snapshot round trip.
 */
public fun JsonElement.toRuntimeValue(): Any? = toKotlinValue()

/** Inverse of [toRuntimeValue]: maps a runtime variable value to its JSON form. */
public fun runtimeValueToJson(value: Any?): JsonElement = value.toJsonElement()
