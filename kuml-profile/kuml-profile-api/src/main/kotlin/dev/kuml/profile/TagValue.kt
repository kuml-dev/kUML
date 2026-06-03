package dev.kuml.profile

import dev.kuml.uml.TagValue

/**
 * Convert an arbitrary [Any]? value to a [TagValue] for use in stereotype tags.
 *
 * Allowed value types in V1.1: [String], [Int], [Long], [Double], [Float],
 * [Boolean], [Enum] subclasses, [List] of those types, and `null`
 * (serialised as the string `"null"`).
 */
public fun Any?.toTagValue(): TagValue =
    when (this) {
        null -> TagValue.StringVal("null")
        is String -> TagValue.StringVal(this)
        is Int -> TagValue.IntVal(this)
        is Long -> TagValue.LongVal(this)
        is Double -> TagValue.DoubleVal(this)
        is Float -> TagValue.DoubleVal(this.toDouble())
        is Boolean -> TagValue.BoolVal(this)
        is Enum<*> ->
            TagValue.EnumVal(
                this::class.qualifiedName ?: this::class.simpleName ?: "?",
                this.name,
            )
        is List<*> -> TagValue.ListVal(this.map { it.toTagValue() })
        else -> TagValue.StringVal(this.toString())
    }
