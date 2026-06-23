package dev.kuml.plugin.examples.tsreverse

import dev.kuml.core.model.KumlElement
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility

internal class TsModelBuilder(
    private val typeMapper: TsTypeMapper,
) {
    // TODO: cross-file type resolution — when implemented, accept an importIndex: Map<String, String>
    //  parameter here and use it to resolve imported type names to their source module, so that
    //  UmlTypeRef.name for imported types reflects the fully-qualified module path instead of the
    //  bare local name.
    fun buildElements(fileAsts: List<TsFileAst>): List<KumlElement> {
        val elements = mutableListOf<KumlElement>()
        val relationships = mutableListOf<KumlElement>()
        val knownIds = mutableSetOf<String>()

        for (ast in fileAsts) {
            for (decl in ast.declarations) {
                val id = "ts::${decl.name}"
                if (id in knownIds) continue
                knownIds += id
                when (decl) {
                    is TsInterfaceDecl -> {
                        elements += buildInterface(decl)
                        relationships += buildInterfaceInheritance(decl)
                    }
                    is TsClassDecl -> {
                        elements += buildClass(decl)
                        relationships += buildClassRelationships(decl)
                    }
                    is TsEnumDecl -> elements += buildEnum(decl)
                }
            }
        }

        return elements + relationships
    }

    private fun buildInterface(decl: TsInterfaceDecl): UmlInterface {
        val attrs = decl.members.filter { !it.isMethod }.map { buildProperty(it, decl.name) }
        val ops = decl.members.filter { it.isMethod }.map { buildOperation(it, decl.name) }
        return UmlInterface(
            id = "ts::${decl.name}",
            name = decl.name,
            visibility = Visibility.PUBLIC,
            attributes = attrs,
            operations = ops,
            stereotypes = decl.decorators,
        )
    }

    private fun buildClass(decl: TsClassDecl): UmlClass {
        val attrs = decl.members.filter { !it.isMethod }.map { buildProperty(it, decl.name) }
        val ops = decl.members.filter { it.isMethod }.map { buildOperation(it, decl.name) }
        return UmlClass(
            id = "ts::${decl.name}",
            name = decl.name,
            visibility = Visibility.PUBLIC,
            isAbstract = decl.isAbstract,
            attributes = attrs,
            operations = ops,
            stereotypes = decl.decorators,
        )
    }

    private fun buildEnum(decl: TsEnumDecl): UmlEnumeration {
        val literals =
            decl.literals.mapIndexed { i, lit ->
                UmlEnumerationLiteral(id = "ts::${decl.name}::$lit", name = lit)
            }
        return UmlEnumeration(
            id = "ts::${decl.name}",
            name = decl.name,
            literals = literals,
        )
    }

    private fun isCollectionType(name: String): Boolean =
        name.startsWith("Array<") ||
            name.startsWith("List<") ||
            name.startsWith("Set<") ||
            name.startsWith("ReadonlyArray<")

    private fun buildProperty(
        member: TsMember,
        owner: String,
    ): UmlProperty {
        val tsTypeName = member.type?.let { flattenTypeRef(it) } ?: "Object"
        val isArray = member.type?.isArray == true || tsTypeName.endsWith("[]") || isCollectionType(tsTypeName)
        val baseType = if (isArray) tsTypeName.removeSuffix("[]") else tsTypeName
        val multiplicity = if (isArray) Multiplicity(lower = 0, upper = null) else Multiplicity()
        val visibility =
            when (member.visibility) {
                "private" -> Visibility.PRIVATE
                "protected" -> Visibility.PROTECTED
                else -> Visibility.PUBLIC
            }
        return UmlProperty(
            id = "ts::$owner::${member.name}",
            name = member.name,
            visibility = visibility,
            type = UmlTypeRef(name = typeMapper.map(baseType)),
            multiplicity = multiplicity,
            isReadOnly = member.isReadonly,
            isStatic = member.isStatic,
        )
    }

    private fun buildOperation(
        member: TsMember,
        owner: String,
    ): UmlOperation {
        val retTypeName = member.type?.let { flattenTypeRef(it) } ?: "void"
        val params =
            member.params.mapIndexed { i, p ->
                val pType = p.type?.let { flattenTypeRef(it) } ?: "Object"
                UmlParameter(
                    id = "ts::$owner::${member.name}::${p.name}",
                    name = p.name,
                    type = UmlTypeRef(name = typeMapper.map(pType)),
                )
            }
        val visibility =
            when (member.visibility) {
                "private" -> Visibility.PRIVATE
                "protected" -> Visibility.PROTECTED
                else -> Visibility.PUBLIC
            }
        return UmlOperation(
            id = "ts::$owner::${member.name}()",
            name = member.name,
            visibility = visibility,
            parameters = params,
            returnType = UmlTypeRef(name = typeMapper.map(retTypeName)),
            isAbstract = member.isAbstract,
            isStatic = member.isStatic,
        )
    }

    private fun buildInterfaceInheritance(decl: TsInterfaceDecl): List<KumlElement> {
        val result = mutableListOf<KumlElement>()
        decl.extendsTypes.forEachIndexed { i, ref ->
            result +=
                UmlGeneralization(
                    id = "ts::gen::${decl.name}::${ref.name}::$i",
                    specificId = "ts::${decl.name}",
                    generalId = "ts::${ref.name}",
                )
        }
        return result
    }

    private fun buildClassRelationships(decl: TsClassDecl): List<KumlElement> {
        val result = mutableListOf<KumlElement>()
        decl.superClass?.let { sup ->
            result +=
                UmlGeneralization(
                    id = "ts::gen::${decl.name}::${sup.name}",
                    specificId = "ts::${decl.name}",
                    generalId = "ts::${sup.name}",
                )
        }
        decl.implementsTypes.forEachIndexed { i, iface ->
            result +=
                UmlInterfaceRealization(
                    id = "ts::real::${decl.name}::${iface.name}::$i",
                    implementingId = "ts::${decl.name}",
                    interfaceId = "ts::${iface.name}",
                )
        }
        return result
    }

    private fun flattenTypeRef(ref: TsTypeRef): String {
        if (ref.isUnion) {
            return ref.unionTypes
                .firstOrNull { it.name != "null" && it.name != "undefined" }
                ?.let { flattenTypeRef(it) } ?: ref.name
        }
        if (ref.typeArgs.isEmpty() && !ref.isArray) return ref.name
        val base = ref.name
        val args = ref.typeArgs.joinToString(", ") { flattenTypeRef(it) }
        val generic = if (args.isEmpty()) base else "$base<$args>"
        return if (ref.isArray) "$generic[]" else generic
    }
}
