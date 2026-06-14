package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.body.EnumDeclaration
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.Visibility

/** Maps a JavaParser [EnumDeclaration] to a [UmlEnumeration] with its literals. */
internal object JavaEnumerationMapper {
    fun map(
        decl: EnumDeclaration,
        packageName: String,
    ): UmlEnumeration {
        val id = if (packageName.isNotBlank()) "$packageName.${decl.nameAsString}" else decl.nameAsString
        val visibility = JavaVisibilityMapper.map(decl)

        val literals =
            decl.entries.map { entry ->
                UmlEnumerationLiteral(
                    id = "$id.${entry.nameAsString}",
                    name = entry.nameAsString,
                    visibility = Visibility.PUBLIC,
                    metadata =
                        buildMap {
                            val args = entry.arguments
                            if (args.isNonEmpty) {
                                put(
                                    "kuml.java.enum.args",
                                    KumlMetaValue.Text(args.map { it.toString() }.joinToString(",")),
                                )
                            }
                        },
                )
            }

        return UmlEnumeration(
            id = id,
            name = decl.nameAsString,
            visibility = visibility,
            literals = literals,
        )
    }
}
