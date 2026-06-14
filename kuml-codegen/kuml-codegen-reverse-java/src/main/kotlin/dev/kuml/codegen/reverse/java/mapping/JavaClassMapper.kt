package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface

/**
 * Maps a JavaParser [ClassOrInterfaceDeclaration] to either [UmlClass] or [UmlInterface].
 *
 * Features mapped:
 * - visibility, isAbstract (class only)
 * - Generic type parameters → stereotype `«template»` + metadata `kuml.template.params`
 * - Nested (inner) classes → metadata `kuml.java.enclosing`
 * - `record` → stereotype `«record»`
 * - `sealed` → stereotype `«sealed»` + metadata `kuml.java.permits`
 */
internal object JavaClassMapper {
    data class MappedClassifier(
        val umlClass: UmlClass? = null,
        val umlInterface: UmlInterface? = null,
    ) {
        val id: String get() = umlClass?.id ?: umlInterface!!.id
    }

    fun map(
        decl: ClassOrInterfaceDeclaration,
        packageName: String,
        enclosingName: String? = null,
    ): MappedClassifier {
        val simpleName = decl.nameAsString
        val qualifiedName = buildQualifiedName(packageName, enclosingName, simpleName)
        val visibility = JavaVisibilityMapper.map(decl)

        val stereotypes =
            buildList {
                if (decl.typeParameters.isNonEmpty) add("template")
                // JavaParser 3.26: records have their own RecordDeclaration type;
                // ClassOrInterfaceDeclaration does NOT represent records.
                // Sealed classes: detected via modifier keyword string.
                if (decl.modifiers.any { it.keyword.asString() == "sealed" }) add("sealed")
            }

        val metadata =
            buildMap<String, KumlMetaValue> {
                if (decl.typeParameters.isNonEmpty) {
                    val params = decl.typeParameters.map { it.nameAsString }
                    put("kuml.template.params", KumlMetaValue.Items(params.map { KumlMetaValue.Text(it) }))
                    decl.typeParameters.forEach { tp ->
                        if (tp.typeBound.isNonEmpty) {
                            val bound = tp.typeBound.map { it.asString() }.joinToString(" & ")
                            put("kuml.template.bounds.${tp.nameAsString}", KumlMetaValue.Text(bound))
                        }
                    }
                }
                if (enclosingName != null) {
                    put("kuml.java.enclosing", KumlMetaValue.Text(enclosingName))
                }
            }

        return if (decl.isInterface) {
            MappedClassifier(
                umlInterface =
                    UmlInterface(
                        id = qualifiedName,
                        name = simpleName,
                        visibility = visibility,
                        stereotypes = stereotypes,
                        metadata = metadata,
                    ),
            )
        } else {
            val isAbstract = decl.modifiers.any { it.keyword == Modifier.Keyword.ABSTRACT }
            MappedClassifier(
                umlClass =
                    UmlClass(
                        id = qualifiedName,
                        name = simpleName,
                        visibility = visibility,
                        isAbstract = isAbstract,
                        stereotypes = stereotypes,
                        metadata = metadata,
                    ),
            )
        }
    }

    private fun buildQualifiedName(
        packageName: String,
        enclosingName: String?,
        simpleName: String,
    ): String {
        val prefix = if (packageName.isNotBlank()) packageName else ""
        return when {
            enclosingName != null && prefix.isNotBlank() -> "$prefix.$enclosingName.$simpleName"
            enclosingName != null -> "$enclosingName.$simpleName"
            prefix.isNotBlank() -> "$prefix.$simpleName"
            else -> simpleName
        }
    }
}
