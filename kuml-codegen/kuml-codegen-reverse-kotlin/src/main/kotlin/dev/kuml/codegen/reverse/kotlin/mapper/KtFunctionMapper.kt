package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.uml.UmlOperation
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Maps Kotlin functions and constructors to [UmlOperation].
 */
internal class KtFunctionMapper(
    private val paramMapper: KtParameterMapper,
    private val typeResolver: KtTypeResolver,
) {
    fun map(
        func: KtNamedFunction,
        ownerId: String,
    ): UmlOperation {
        val name = func.name ?: "_"
        val opId = "$ownerId.op.$name"
        val visibility = KtVisibilityMapper.map(func)
        val isAbstract = func.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        val isStatic = false // Kotlin top-level functions aren't modeled as static in UML here

        val stereotypes =
            buildList {
                if (func.hasModifier(KtTokens.SUSPEND_KEYWORD)) add("suspend")
                if (func.hasModifier(KtTokens.INLINE_KEYWORD)) add("inline")
                if (func.hasModifier(KtTokens.OPERATOR_KEYWORD)) add("operator")
                if (func.hasModifier(KtTokens.INFIX_KEYWORD)) add("infix")
                if (func.hasModifier(KtTokens.TAILREC_KEYWORD)) add("tailrec")
                if (func.hasModifier(KtTokens.EXTERNAL_KEYWORD)) add("external")
                if (func.receiverTypeReference != null) add("extension")
            }

        val params = func.valueParameters.map { param -> paramMapper.map(param, opId) }
        val returnType = func.typeReference?.let { typeResolver.resolve(it) }

        return UmlOperation(
            id = opId,
            name = name,
            visibility = visibility,
            parameters = params,
            returnType = returnType,
            isAbstract = isAbstract,
            isStatic = isStatic,
            stereotypes = stereotypes,
        )
    }

    fun fromConstructor(
        ctor: KtConstructor<*>,
        ownerId: String,
        className: String,
        index: Int = 0,
    ): UmlOperation {
        // Use index to generate unique IDs for primary vs secondary constructors
        val opId = "$ownerId.ctor.$index"
        val visibility = KtVisibilityMapper.map(ctor)

        val params =
            ctor
                .getValueParameters()
                .filter { !it.hasValOrVar() } // val/var params become properties, not ctor params in UML
                .map { param -> paramMapper.map(param, opId) }

        return UmlOperation(
            id = opId,
            name = className,
            visibility = visibility,
            parameters = params,
            returnType = null,
            isAbstract = false,
            isStatic = false,
            stereotypes = listOf("constructor"),
        )
    }
}
