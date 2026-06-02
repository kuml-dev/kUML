package dev.kuml.codegen.kotlin

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlTypeRef

internal object KotlinTypeMapper {
    /** Primitive / built-in type mappings from UML names to Kotlin. */
    private val PRIMITIVES: Map<String, String> =
        mapOf(
            "String" to "String",
            "string" to "String",
            "str" to "String",
            "Int" to "Int",
            "Integer" to "Int",
            "int" to "Int",
            "Long" to "Long",
            "long" to "Long",
            "Float" to "Float",
            "float" to "Float",
            "Double" to "Double",
            "double" to "Double",
            "Boolean" to "Boolean",
            "boolean" to "Boolean",
            "bool" to "Boolean",
            "Byte" to "Byte",
            "Short" to "Short",
            "Char" to "Char",
            "Any" to "Any",
            "Unit" to "Unit",
            "void" to "Unit",
            "Void" to "Unit",
            "UUID" to "java.util.UUID",
            "Date" to "java.time.LocalDate",
            "LocalDate" to "java.time.LocalDate",
            "DateTime" to "java.time.LocalDateTime",
            "LocalDateTime" to "java.time.LocalDateTime",
            "Instant" to "java.time.Instant",
            "BigDecimal" to "java.math.BigDecimal",
        )

    /**
     * Maps a [UmlTypeRef] + [Multiplicity] to a Kotlin type string.
     *
     * | Multiplicity | Result |
     * |---|---|
     * | `(1, 1)` | `TypeName` |
     * | `(0, 1)` | `TypeName?` |
     * | `(0, *)` or `(1, *)` or `upper > 1` | `List<TypeName>` |
     */
    internal fun toKotlinType(
        typeRef: UmlTypeRef,
        multiplicity: Multiplicity,
    ): String {
        val base = PRIMITIVES[typeRef.name] ?: typeRef.name.sanitizeClassName()
        val upper = multiplicity.upper
        return when {
            upper == null || upper > 1 -> "List<$base>"
            multiplicity.lower == 0 && upper == 1 -> "$base?"
            else -> base
        }
    }

    /** Maps a return type (no multiplicity context — just the name). */
    internal fun toKotlinReturnType(typeRef: UmlTypeRef?): String =
        if (typeRef == null) "Unit" else (PRIMITIVES[typeRef.name] ?: typeRef.name.sanitizeClassName())

    private fun String.sanitizeClassName(): String =
        this
            .replaceFirstChar { it.uppercase() }
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
}
