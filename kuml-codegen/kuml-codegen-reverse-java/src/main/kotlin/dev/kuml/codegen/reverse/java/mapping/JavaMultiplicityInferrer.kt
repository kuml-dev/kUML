package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.`type`.Type
import dev.kuml.uml.Multiplicity

/**
 * Infers [Multiplicity] and the unwrapped element type from a JavaParser field type.
 *
 * Container logic:
 * - `List<X>`, `ArrayList<X>`, `Set<X>`, `HashSet<X>`, `LinkedHashSet<X>`,
 *   `Collection<X>`, `Iterable<X>` → `0..*`, element = X
 * - `Optional<X>` → `0..1`, element = X
 * - `X[]` → `0..*`, element = X
 * - Everything else → `1..1`, element = the type itself
 */
internal object JavaMultiplicityInferrer {
    data class Result(
        val multiplicity: Multiplicity,
        /** The unwrapped element type name (without generic wrapper). */
        val elementTypeName: String,
        /** Whether the type is a known container wrapper. */
        val isContainer: Boolean,
    )

    private val MANY_CONTAINERS =
        setOf(
            "List",
            "ArrayList",
            "LinkedList",
            "Set",
            "HashSet",
            "LinkedHashSet",
            "TreeSet",
            "Collection",
            "Iterable",
        )

    fun infer(type: Type): Result {
        // Array type: X[] → 0..*
        if (type.isArrayType) {
            val element = type.asArrayType().componentType
            return Result(
                multiplicity = Multiplicity(lower = 0, upper = null),
                elementTypeName = element.asString(),
                isContainer = true,
            )
        }

        // Parameterised type check
        if (type.isClassOrInterfaceType) {
            val cit = type.asClassOrInterfaceType()
            val baseName = cit.nameAsString

            if (baseName == "Optional") {
                val arg = cit.typeArguments.map { it[0].asString() }.orElse("Object")
                return Result(
                    multiplicity = Multiplicity(lower = 0, upper = 1),
                    elementTypeName = arg,
                    isContainer = true,
                )
            }

            if (baseName in MANY_CONTAINERS) {
                val arg = cit.typeArguments.map { it[0].asString() }.orElse("Object")
                return Result(
                    multiplicity = Multiplicity(lower = 0, upper = null),
                    elementTypeName = arg,
                    isContainer = true,
                )
            }
        }

        // Plain (non-container) type → exactly one
        return Result(
            multiplicity = Multiplicity(lower = 1, upper = 1),
            elementTypeName = type.asString(),
            isContainer = false,
        )
    }
}
