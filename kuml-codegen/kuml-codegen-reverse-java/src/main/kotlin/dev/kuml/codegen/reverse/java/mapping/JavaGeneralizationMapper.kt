package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization

/**
 * Maps `extends` and `implements` clauses to UML relationships.
 *
 * Rules (UML 2 compliant):
 * - class extends class → [UmlGeneralization]
 * - interface extends interface → [UmlGeneralization]
 * - class implements interface → [UmlInterfaceRealization] (NOT [UmlGeneralization])
 */
internal object JavaGeneralizationMapper {
    data class Result(
        val generalizations: List<UmlGeneralization>,
        val realizations: List<UmlInterfaceRealization>,
    )

    fun map(
        decl: ClassOrInterfaceDeclaration,
        ownId: String,
        relIdPrefix: String,
    ): Result {
        val generalizations = mutableListOf<UmlGeneralization>()
        val realizations = mutableListOf<UmlInterfaceRealization>()

        // extends (for both class and interface)
        decl.extendedTypes.forEachIndexed { idx, extended ->
            generalizations +=
                UmlGeneralization(
                    id = "$relIdPrefix.gen$idx",
                    specificId = ownId,
                    generalId = extended.nameAsString,
                )
        }

        // implements (only on class declarations — interfaces use extends)
        decl.implementedTypes.forEachIndexed { idx, implemented ->
            realizations +=
                UmlInterfaceRealization(
                    id = "$relIdPrefix.real$idx",
                    implementingId = ownId,
                    interfaceId = implemented.nameAsString,
                )
        }

        return Result(generalizations, realizations)
    }
}
