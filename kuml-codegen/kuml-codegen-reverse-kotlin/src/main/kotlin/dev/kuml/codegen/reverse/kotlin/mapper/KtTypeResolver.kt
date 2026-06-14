package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.support.KtFqnPool
import dev.kuml.uml.UmlTypeRef
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Resolves a [KtTypeReference] to a [UmlTypeRef] using PSI-only analysis (no type binding).
 *
 * Strategy:
 * 1. Extract text and detect nullability.
 * 2. Get base type name (unwrapping nullable).
 * 3. Try to resolve via [KtFqnPool]: if found → use pool ID as [UmlTypeRef.referencedId].
 * 4. Otherwise → [UmlTypeRef] with name only (external/primitive type).
 */
internal class KtTypeResolver(
    private val pool: KtFqnPool,
) {
    fun resolve(typeRef: KtTypeReference?): UmlTypeRef {
        if (typeRef == null) return UmlTypeRef(name = "void")

        val typeElement = typeRef.typeElement
        val nullable: Boolean
        val userType: KtUserType?

        if (typeElement is KtNullableType) {
            nullable = true
            userType = typeElement.innerType as? KtUserType
        } else {
            nullable = false
            userType = typeElement as? KtUserType
        }

        val baseName = userType?.referencedName ?: typeRef.text.trimEnd('?')
        val displayName = if (nullable) "$baseName?" else baseName

        // Try to find in pool — exact match or suffix match
        val resolvedId =
            pool.idOf(baseName)
                ?: pool.allFqns().firstOrNull { it == baseName || it.endsWith(".$baseName") }?.let { pool.idOf(it) }

        return UmlTypeRef(name = displayName, referencedId = resolvedId)
    }
}
