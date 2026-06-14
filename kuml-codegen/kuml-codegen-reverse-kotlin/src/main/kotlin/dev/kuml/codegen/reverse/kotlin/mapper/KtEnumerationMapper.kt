package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.support.DiagnosticCollector
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.Visibility
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtEnumEntry

/**
 * Maps a Kotlin enum class ([KtClass] with `isEnum() == true`) to [UmlEnumeration].
 */
internal class KtEnumerationMapper(
    private val diagnostics: DiagnosticCollector,
) {
    fun map(
        ktEnum: KtClass,
        fqn: String,
        id: String,
    ): UmlEnumeration {
        val name = ktEnum.name ?: "_"
        val visibility = KtVisibilityMapper.map(ktEnum)

        val literals =
            ktEnum.declarations
                .filterIsInstance<KtEnumEntry>()
                .mapIndexed { idx, entry ->
                    val entryName = entry.name ?: "LITERAL_$idx"

                    // REV-K-030: enum entry with body
                    if (entry.body != null) {
                        diagnostics.info(
                            "REV-K-030",
                            "Enum entry '$entryName' in '$name' has a body — body members not modeled.",
                            file = ktEnum.containingFile?.name,
                        )
                    }

                    UmlEnumerationLiteral(
                        id = "$id.lit.$entryName",
                        name = entryName,
                        visibility = Visibility.PUBLIC,
                    )
                }

        return UmlEnumeration(
            id = id,
            name = name,
            visibility = visibility,
            literals = literals,
        )
    }
}
