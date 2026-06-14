package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.support.DiagnosticCollector
import dev.kuml.uml.UmlClass
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Maps a Kotlin object declaration ([KtObjectDeclaration]) to [UmlClass] with `<<object>>` stereotype.
 *
 * Anonymous objects (no name) are skipped with REV-K-021 diagnostic.
 * Companion objects get an additional `<<companion>>` stereotype.
 */
internal class KtObjectMapper(
    private val propertyMapper: KtPropertyMapper,
    private val functionMapper: KtFunctionMapper,
    private val diagnostics: DiagnosticCollector,
) {
    /**
     * @return [UmlClass] or null if the object is anonymous.
     */
    fun map(
        obj: KtObjectDeclaration,
        fqn: String,
        id: String,
    ): UmlClass? {
        if (obj.isObjectLiteral()) {
            diagnostics.info(
                "REV-K-021",
                "Anonymous object literal skipped — not representable in UML class diagram.",
                file = obj.containingFile?.name,
            )
            return null
        }

        val name = obj.name ?: return null
        val visibility = KtVisibilityMapper.map(obj)

        val stereotypes =
            buildList {
                add("object")
                if (obj.isCompanion()) add("companion")
            }

        val attributes =
            obj.declarations
                .filterIsInstance<KtProperty>()
                .map { prop -> propertyMapper.map(prop, id) }
                .sortedBy { it.name }

        val operations =
            obj.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
                .map { func -> functionMapper.map(func, id) }
                .sortedBy { it.name }

        return UmlClass(
            id = id,
            name = name,
            visibility = visibility,
            isAbstract = false,
            attributes = attributes,
            operations = operations,
            stereotypes = stereotypes,
        )
    }
}
