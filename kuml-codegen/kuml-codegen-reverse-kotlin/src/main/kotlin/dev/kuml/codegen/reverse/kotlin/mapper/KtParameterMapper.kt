package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlParameter
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Maps a [KtParameter] to a [UmlParameter].
 *
 * Direction is always [ParameterDirection.IN] in V3.0.8 (no out-params in Kotlin).
 */
internal class KtParameterMapper(
    private val typeResolver: KtTypeResolver,
) {
    fun map(
        param: KtParameter,
        ownerId: String,
    ): UmlParameter {
        val name = param.name ?: "_"
        return UmlParameter(
            id = "$ownerId.param.$name",
            name = name,
            type = typeResolver.resolve(param.typeReference),
            direction = ParameterDirection.IN,
            defaultValue = param.defaultValue?.text,
        )
    }
}
