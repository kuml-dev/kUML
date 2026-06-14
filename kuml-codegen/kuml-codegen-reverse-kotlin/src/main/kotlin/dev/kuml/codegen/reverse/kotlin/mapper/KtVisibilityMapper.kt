package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.uml.Visibility
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtModifierListOwner

/** Maps Kotlin visibility modifiers to [Visibility]. */
internal object KtVisibilityMapper {
    fun map(owner: KtModifierListOwner): Visibility =
        when {
            owner.hasModifier(KtTokens.PRIVATE_KEYWORD) -> Visibility.PRIVATE
            owner.hasModifier(KtTokens.PROTECTED_KEYWORD) -> Visibility.PROTECTED
            owner.hasModifier(KtTokens.INTERNAL_KEYWORD) -> Visibility.PACKAGE
            else -> Visibility.PUBLIC
        }
}
