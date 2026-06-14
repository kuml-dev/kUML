package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.uml.Multiplicity
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Infers [Multiplicity] from a Kotlin [KtTypeReference] using PSI-only heuristics.
 *
 * Rules:
 * - `List<T>`, `Set<T>`, `Collection<T>`, `Flow<T>`, `Array<*>`, `Sequence<T>` → `0..*`
 * - `T?` (nullable) → `0..1`
 * - everything else → `1..1`
 */
internal object KtMultiplicityInferrer {
    /** Container types that represent many values. */
    private val MANY_CONTAINERS =
        setOf(
            "List",
            "MutableList",
            "ArrayList",
            "Set",
            "MutableSet",
            "HashSet",
            "LinkedHashSet",
            "TreeSet",
            "Collection",
            "MutableCollection",
            "Iterable",
            "MutableIterable",
            "Flow",
            "Sequence",
            "Array",
        )

    fun infer(typeRef: KtTypeReference?): Multiplicity {
        if (typeRef == null) return Multiplicity(lower = 1, upper = 1)

        val typeElement = typeRef.typeElement

        // Nullable type → 0..1
        if (typeElement is KtNullableType) {
            val inner = typeElement.innerType
            if (inner is KtUserType) {
                val baseName = inner.referencedName ?: return Multiplicity(lower = 0, upper = 1)
                if (baseName in MANY_CONTAINERS) {
                    return Multiplicity(lower = 0, upper = null)
                }
            }
            return Multiplicity(lower = 0, upper = 1)
        }

        // User type — check for container
        if (typeElement is KtUserType) {
            val baseName = typeElement.referencedName ?: return Multiplicity(lower = 1, upper = 1)
            if (baseName in MANY_CONTAINERS) {
                return Multiplicity(lower = 0, upper = null)
            }
        }

        return Multiplicity(lower = 1, upper = 1)
    }
}
