package dev.kuml.codegen.java

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlTypeRef

/**
 * Mappt UML-Typen auf Java-Typen unter Berücksichtigung der Multiplizität.
 *
 * | Multiplicity | Result (for primitive-backed types)   |
 * |---|---|
 * | `(1, 1)` | primitive (z.B. `int`, `boolean`)         |
 * | `(0, 1)` | boxed Wrapper (z.B. `Integer`, `Boolean`) |
 * | `upper > 1` / `(0, *)` | `java.util.List<Boxed>`     |
 *
 * Reference-Types (String, UUID, …) bleiben unverändert.
 */
internal object JavaTypeMapper {
    /** `typeRef.name` to `(primitive, boxed)`. */
    private val PRIMITIVES_BOXED: Map<String, Pair<String, String>> =
        mapOf(
            "Int" to ("int" to "Integer"),
            "Integer" to ("int" to "Integer"),
            "int" to ("int" to "Integer"),
            "Long" to ("long" to "Long"),
            "long" to ("long" to "Long"),
            "Short" to ("short" to "Short"),
            "Byte" to ("byte" to "Byte"),
            "Float" to ("float" to "Float"),
            "float" to ("float" to "Float"),
            "Double" to ("double" to "Double"),
            "double" to ("double" to "Double"),
            "Boolean" to ("boolean" to "Boolean"),
            "boolean" to ("boolean" to "Boolean"),
            "bool" to ("boolean" to "Boolean"),
            "Char" to ("char" to "Character"),
        )

    private val REFERENCE_TYPES: Map<String, String> =
        mapOf(
            "String" to "String",
            "string" to "String",
            "str" to "String",
            "UUID" to "java.util.UUID",
            "Date" to "java.time.LocalDate",
            "LocalDate" to "java.time.LocalDate",
            "DateTime" to "java.time.LocalDateTime",
            "LocalDateTime" to "java.time.LocalDateTime",
            "Instant" to "java.time.Instant",
            "Time" to "java.time.LocalTime",
            "LocalTime" to "java.time.LocalTime",
            "BigDecimal" to "java.math.BigDecimal",
            "BigInteger" to "java.math.BigInteger",
            "Any" to "Object",
            "Unit" to "void",
            "void" to "void",
        )

    fun toJavaType(
        typeRef: UmlTypeRef,
        multiplicity: Multiplicity,
    ): String {
        val name = typeRef.name
        val primitiveBoxed = PRIMITIVES_BOXED[name]
        val reference = REFERENCE_TYPES[name]
        val upper = multiplicity.upper
        val lower = multiplicity.lower

        // Determine the "singular" form for upper == 1.
        // - Primitive (1,1) => primitive
        // - Primitive (0,1) => boxed (primitives can't be null)
        // - Reference => reference
        // - Unknown => use name as-is
        val singular: String =
            when {
                primitiveBoxed != null -> {
                    if (lower == 0) primitiveBoxed.second else primitiveBoxed.first
                }
                reference != null -> reference
                else -> name
            }

        val collectionElement: String =
            when {
                primitiveBoxed != null -> primitiveBoxed.second // List<Integer>, not List<int>
                reference != null -> reference
                else -> name
            }

        return if (upper == null || upper > 1) "java.util.List<$collectionElement>" else singular
    }
}
