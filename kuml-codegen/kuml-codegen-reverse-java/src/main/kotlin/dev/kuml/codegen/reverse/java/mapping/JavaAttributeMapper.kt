package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef

/**
 * Maps a JavaParser [FieldDeclaration] to a [UmlProperty].
 *
 * Only called when [JavaAssociationDetector] has classified the field as a plain property
 * (not a user-type association). Association-classified fields are handled separately.
 */
internal object JavaAttributeMapper {
    fun map(
        field: FieldDeclaration,
        variable: VariableDeclarator,
        idPrefix: String,
    ): UmlProperty {
        val visibility = JavaVisibilityMapper.map(field)
        val isStatic = field.modifiers.any { it.keyword == Modifier.Keyword.STATIC }
        val isReadOnly = field.modifiers.any { it.keyword == Modifier.Keyword.FINAL }

        val (multiplicity, elementTypeName) = inferTypeAndMultiplicity(field, variable)

        return UmlProperty(
            id = "$idPrefix.${variable.nameAsString}",
            name = variable.nameAsString,
            visibility = visibility,
            type = UmlTypeRef(name = elementTypeName),
            multiplicity = multiplicity,
            isStatic = isStatic,
            isReadOnly = isReadOnly,
        )
    }

    private fun inferTypeAndMultiplicity(
        field: FieldDeclaration,
        variable: VariableDeclarator,
    ): Pair<Multiplicity, String> {
        // Use variable.type to capture array dimensions (field.elementType strips them)
        val result = JavaMultiplicityInferrer.infer(variable.type)
        return result.multiplicity to mapPrimitive(result.elementTypeName)
    }

    private fun mapPrimitive(name: String): String =
        when (name) {
            "int" -> "Int"
            "long" -> "Long"
            "boolean" -> "Boolean"
            "double" -> "Double"
            "float" -> "Float"
            "short" -> "Short"
            "byte" -> "Byte"
            "char" -> "Char"
            else -> name
        }
}
