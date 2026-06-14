package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef

/**
 * Classifies a field as either a [UmlAssociation] or a [UmlProperty].
 *
 * Decision rule (see CLAUDE.md / Plan § 11.7):
 * 1. Container-wrapping (List, Optional, X[]) is stripped first — determines Multiplicity.
 * 2. If the element type name is a known Map type → skip with WARN `REV-J-003`.
 * 3. If element type resolves as a user class (in source roots) → UmlAssociation.
 * 4. If element type is JRE/external/unresolved → UmlProperty.
 */
internal class JavaAssociationDetector(
    private val resolver: JavaTypeResolver,
) {
    sealed class FieldClassification {
        data class AsAssociation(
            val association: UmlAssociation,
        ) : FieldClassification()

        /**
         * Field mapped as a UmlProperty.
         * [diagnostic] is non-null when the type was unresolved (REV-J-002 WARN).
         */
        data class AsProperty(
            val property: UmlProperty,
            val diagnostic: ReverseDiagnostic? = null,
        ) : FieldClassification()

        data class Skipped(
            val diagnostic: ReverseDiagnostic,
        ) : FieldClassification()
    }

    fun classify(
        field: FieldDeclaration,
        variable: VariableDeclarator,
        ownerClassId: String,
        fileName: String,
    ): FieldClassification {
        // Use variable.type to capture array dimensions (field.elementType strips them)
        val fieldType = variable.type
        val rawTypeName = field.elementType.asString()
        val lineNr = field.begin.map { it.line }.orElse(null)

        // Check for Map at the raw level — skip with REV-J-003
        if (isMapType(rawTypeName)) {
            return FieldClassification.Skipped(
                ReverseDiagnostic(
                    severity = ReverseDiagnostic.Severity.WARN,
                    code = "REV-J-003",
                    message =
                        "Map<K,V> field '${variable.nameAsString}' skipped — " +
                            "qualified associations not yet modeled (V1 metamodel has no qualifier slot).",
                    file = fileName,
                    line = lineNr,
                ),
            )
        }

        val inferred = JavaMultiplicityInferrer.infer(fieldType)
        val elementTypeName = inferred.elementTypeName
        val multiplicity = inferred.multiplicity

        // Resolve the element type
        val resolved = resolver.resolveOrExternal(elementTypeName)

        return when (resolved) {
            is JavaTypeResolver.ResolvedTypeKind.UserClass -> {
                val targetId = resolved.qualifiedName
                FieldClassification.AsAssociation(
                    UmlAssociation(
                        id = "$ownerClassId.assoc.${variable.nameAsString}",
                        name = variable.nameAsString,
                        ends =
                            listOf(
                                UmlAssociationEnd(
                                    typeId = ownerClassId,
                                    role = null,
                                    multiplicity = Multiplicity(1, 1),
                                    navigable = false,
                                ),
                                UmlAssociationEnd(
                                    typeId = targetId,
                                    role = variable.nameAsString,
                                    multiplicity = multiplicity,
                                    navigable = true,
                                ),
                            ),
                    ),
                )
            }

            is JavaTypeResolver.ResolvedTypeKind.Jre ->
                FieldClassification.AsProperty(buildProperty(field, variable, elementTypeName, multiplicity, ownerClassId))

            JavaTypeResolver.ResolvedTypeKind.External -> {
                // Unresolved type — fallback property + REV-J-002 WARN
                val diag =
                    ReverseDiagnostic(
                        severity = ReverseDiagnostic.Severity.WARN,
                        code = "REV-J-002",
                        message =
                            "Could not resolve type '$elementTypeName' for field " +
                                "'${variable.nameAsString}' — treating as external.",
                        file = fileName,
                        line = lineNr,
                    )
                FieldClassification.AsProperty(
                    property = buildProperty(field, variable, elementTypeName, multiplicity, ownerClassId),
                    diagnostic = diag,
                )
            }
        }
    }

    private fun buildProperty(
        field: FieldDeclaration,
        variable: VariableDeclarator,
        elementTypeName: String,
        multiplicity: Multiplicity,
        ownerClassId: String,
    ): UmlProperty {
        val mappedType =
            when (elementTypeName) {
                "int" -> "Int"
                "long" -> "Long"
                "boolean" -> "Boolean"
                "double" -> "Double"
                "float" -> "Float"
                "short" -> "Short"
                "byte" -> "Byte"
                "char" -> "Char"
                else -> elementTypeName
            }
        return UmlProperty(
            id = "$ownerClassId.${variable.nameAsString}",
            name = variable.nameAsString,
            visibility = JavaVisibilityMapper.map(field),
            type = UmlTypeRef(name = mappedType),
            multiplicity = multiplicity,
            isStatic = field.modifiers.any { it.keyword.asString() == "static" },
            isReadOnly = field.modifiers.any { it.keyword.asString() == "final" },
        )
    }

    private fun isMapType(typeName: String): Boolean {
        val base = typeName.substringBefore("<").trim()
        return base in setOf("Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "SortedMap")
    }
}
