package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.support.DiagnosticCollector
import dev.kuml.codegen.reverse.kotlin.support.KtFqnPool
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Traverses all known FQNs in the [KtFqnPool] and builds generalization/realization relationships.
 *
 * For each declaration:
 * - If supertype is in pool and is an interface → [UmlInterfaceRealization]
 * - If supertype is in pool and is a class → [UmlGeneralization]
 * - If supertype is not in pool → REV-K-050 INFO diagnostic (external supertype)
 */
internal class KtGeneralizationMapper(
    private val pool: KtFqnPool,
    private val diagnostics: DiagnosticCollector,
) {
    data class GeneralizationResult(
        val generalizations: List<UmlGeneralization>,
        val realizations: List<UmlInterfaceRealization>,
    )

    fun mapAll(): GeneralizationResult {
        val generalizations = mutableListOf<UmlGeneralization>()
        val realizations = mutableListOf<UmlInterfaceRealization>()
        var relIdx = 0

        for (fqn in pool.allFqns()) {
            val decl = pool.resolve(fqn) ?: continue
            val specificId = pool.idOf(fqn) ?: continue

            val superTypeEntries = decl.superTypeListEntries
            for (entry in superTypeEntries) {
                val typeRef = entry.typeReference ?: continue
                val typeElement = typeRef.typeElement as? KtUserType ?: continue
                val superName = typeElement.referencedName ?: continue

                // Try to find the supertype in the pool
                val superFqn = pool.allFqns().firstOrNull { it == superName || it.endsWith(".$superName") }

                if (superFqn == null) {
                    diagnostics.info(
                        "REV-K-050",
                        "Supertype '$superName' of '$fqn' not found in pool — external type, skipped.",
                    )
                    continue
                }

                val generalId = pool.idOf(superFqn) ?: continue
                val superDecl = pool.resolve(superFqn)

                val isInterface = superDecl is KtClass && superDecl.isInterface()

                if (isInterface) {
                    realizations +=
                        UmlInterfaceRealization(
                            id = "kt:rel.${relIdx++}",
                            implementingId = specificId,
                            interfaceId = generalId,
                        )
                } else {
                    generalizations +=
                        UmlGeneralization(
                            id = "kt:rel.${relIdx++}",
                            specificId = specificId,
                            generalId = generalId,
                        )
                }
            }
        }

        return GeneralizationResult(generalizations, realizations)
    }
}
