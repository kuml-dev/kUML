package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.uml.UmlProperty
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Maps Kotlin properties ([KtProperty]) and primary constructor val/var parameters
 * ([KtParameter]) to [UmlProperty].
 */
internal class KtPropertyMapper(
    private val typeResolver: KtTypeResolver,
) {
    fun map(
        prop: KtProperty,
        ownerId: String,
    ): UmlProperty {
        val name = prop.name ?: "_"
        val typeRef = typeResolver.resolve(prop.typeReference)
        val multiplicity = KtMultiplicityInferrer.infer(prop.typeReference)
        val visibility = KtVisibilityMapper.map(prop)

        val stereotypes =
            buildList {
                if (prop.hasModifier(KtTokens.LATEINIT_KEYWORD)) add("lateinit")
                if (prop.hasModifier(KtTokens.CONST_KEYWORD)) add("const")
                if (prop.hasDelegateExpression()) add("delegated")
            }

        return UmlProperty(
            id = "$ownerId.attr.$name",
            name = name,
            visibility = visibility,
            type = typeRef,
            multiplicity = multiplicity,
            isReadOnly = !prop.isVar,
            stereotypes = stereotypes,
        )
    }

    fun fromParameter(
        param: KtParameter,
        ownerId: String,
    ): UmlProperty {
        val name = param.name ?: "_"
        val typeRef = typeResolver.resolve(param.typeReference)
        val multiplicity = KtMultiplicityInferrer.infer(param.typeReference)
        val visibility = KtVisibilityMapper.map(param)

        return UmlProperty(
            id = "$ownerId.attr.$name",
            name = name,
            visibility = visibility,
            type = typeRef,
            multiplicity = multiplicity,
            isReadOnly = !param.isMutable,
            stereotypes = emptyList(),
        )
    }
}
