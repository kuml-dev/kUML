package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.MethodDeclaration
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlTypeRef

/** Maps a JavaParser [MethodDeclaration] to a [UmlOperation]. */
internal object JavaOperationMapper {
    fun map(
        method: MethodDeclaration,
        classId: String,
    ): UmlOperation {
        val visibility = JavaVisibilityMapper.map(method)
        val isAbstract = method.modifiers.any { it.keyword == Modifier.Keyword.ABSTRACT }
        val isStatic = method.modifiers.any { it.keyword == Modifier.Keyword.STATIC }

        val returnTypeName = mapTypeName(method.typeAsString)
        val returnType = if (returnTypeName == "void") UmlTypeRef("void") else UmlTypeRef(returnTypeName)

        val parameters =
            method.parameters.mapIndexed { idx, param ->
                UmlParameter(
                    id = "$classId.${method.nameAsString}.param$idx",
                    name = param.nameAsString,
                    type = UmlTypeRef(mapTypeName(param.typeAsString)),
                    direction = ParameterDirection.IN,
                )
            }

        return UmlOperation(
            id = "$classId.${method.nameAsString}",
            name = method.nameAsString,
            visibility = visibility,
            parameters = parameters,
            returnType = returnType,
            isAbstract = isAbstract,
            isStatic = isStatic,
        )
    }

    internal fun mapTypeName(raw: String): String =
        when (raw) {
            "int" -> "Int"
            "long" -> "Long"
            "boolean" -> "Boolean"
            "double" -> "Double"
            "float" -> "Float"
            "short" -> "Short"
            "byte" -> "Byte"
            "char" -> "Char"
            "void" -> "void"
            else -> raw
        }
}
