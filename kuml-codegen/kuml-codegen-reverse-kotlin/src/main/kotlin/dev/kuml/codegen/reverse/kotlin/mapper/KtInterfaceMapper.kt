package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.uml.UmlInterface
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Maps a Kotlin interface ([KtClass] with `isInterface() == true`) to [UmlInterface].
 */
internal class KtInterfaceMapper(
    private val propertyMapper: KtPropertyMapper,
    private val functionMapper: KtFunctionMapper,
) {
    fun map(
        ktInterface: KtClass,
        fqn: String,
        id: String,
    ): UmlInterface {
        val name = ktInterface.name ?: "_"
        val visibility = KtVisibilityMapper.map(ktInterface)

        val stereotypes =
            buildList {
                if (ktInterface.isSealed()) add("sealed")
                if (ktInterface.hasModifier(KtTokens.FUN_KEYWORD)) add("fun")
            }

        val attributes =
            ktInterface.declarations
                .filterIsInstance<KtProperty>()
                .map { prop -> propertyMapper.map(prop, id) }
                .sortedBy { it.name }

        val operations =
            ktInterface.declarations
                .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
                .map { func -> functionMapper.map(func, id) }
                .sortedBy { it.name }

        return UmlInterface(
            id = id,
            name = name,
            visibility = visibility,
            attributes = attributes,
            operations = operations,
            stereotypes = stereotypes,
        )
    }
}
