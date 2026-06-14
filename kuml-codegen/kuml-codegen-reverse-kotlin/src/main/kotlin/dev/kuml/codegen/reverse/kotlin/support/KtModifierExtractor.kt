package dev.kuml.codegen.reverse.kotlin.support

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtModifierListOwner

/** Extension helpers for extracting common Kotlin modifiers from PSI elements. */
internal object KtModifierExtractor {
    fun KtModifierListOwner.isAbstract(): Boolean = hasModifier(KtTokens.ABSTRACT_KEYWORD)

    fun KtModifierListOwner.isOpen(): Boolean = hasModifier(KtTokens.OPEN_KEYWORD)

    fun KtModifierListOwner.isFinal(): Boolean = hasModifier(KtTokens.FINAL_KEYWORD)

    fun KtModifierListOwner.isOverride(): Boolean = hasModifier(KtTokens.OVERRIDE_KEYWORD)

    fun KtModifierListOwner.isSuspend(): Boolean = hasModifier(KtTokens.SUSPEND_KEYWORD)
}
