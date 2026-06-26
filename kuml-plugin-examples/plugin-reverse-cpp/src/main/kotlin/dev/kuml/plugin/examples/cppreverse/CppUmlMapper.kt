package dev.kuml.plugin.examples.cppreverse

import dev.kuml.core.model.KumlElement
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility

/**
 * Maps parsed C++ AST declarations to kUML model elements.
 *
 * ID scheme: `cpp::<namespace>::<Name>` (namespace omitted when null/empty).
 *
 * Forward declarations produce no element. When both a forward decl and a full
 * definition exist for the same qualified name, the definition wins (dedupe via
 * [knownIds] set).
 *
 * All inheritance (C++ base specifiers) is modelled as [UmlGeneralization] —
 * C++ has no interface keyword; the all-pure-virtual → UmlInterface convention
 * is NOT applied (safe default).
 */
internal class CppUmlMapper(
    private val typeMapper: CppTypeMapper,
) {
    fun buildElements(fileAsts: List<CppFileAst>): List<KumlElement> {
        val elements = mutableListOf<KumlElement>()
        val relationships = mutableListOf<KumlElement>()
        val knownIds = mutableSetOf<String>()

        // Two-pass: first collect full definitions, then process
        // (ensures that if a forward decl appears after a full def, we still get the def)
        for (ast in fileAsts) {
            for (decl in ast.declarations) {
                val id = qualifiedId(decl.namespace, decl.name)
                when (decl) {
                    is CppClassDecl -> {
                        if (decl.isForwardDecl) {
                            // Register as forward-only if not yet known; will be replaced by full def
                            if (id !in knownIds) {
                                // Don't add to knownIds yet — let a potential full def win
                            }
                            continue
                        }
                        // Full definition — wins over any forward decl
                        if (id in knownIds) continue
                        knownIds += id
                        elements += buildClass(decl)
                        relationships += buildGeneralizations(decl)
                    }
                    is CppEnumDecl -> {
                        if (id in knownIds) continue
                        knownIds += id
                        elements += buildEnum(decl)
                    }
                }
            }
        }

        return elements + relationships
    }

    // ── Class ─────────────────────────────────────────────────────────────────

    private fun buildClass(decl: CppClassDecl): UmlClass {
        val id = qualifiedId(decl.namespace, decl.name)
        val attrs = decl.members.filter { !it.isMethod }.map { buildProperty(it, id) }
        val ops = decl.members.filter { it.isMethod }.map { buildOperation(it, id) }
        return UmlClass(
            id = id,
            name = decl.name,
            visibility = Visibility.PUBLIC,
            isAbstract = decl.members.any { it.isPureVirtual },
            attributes = attrs,
            operations = ops,
        )
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    private fun buildEnum(decl: CppEnumDecl): UmlEnumeration {
        val id = qualifiedId(decl.namespace, decl.name)
        val literals =
            decl.literals.map { lit ->
                UmlEnumerationLiteral(id = "$id::$lit", name = lit)
            }
        return UmlEnumeration(
            id = id,
            name = decl.name,
            literals = literals,
        )
    }

    // ── Relationships ─────────────────────────────────────────────────────────

    private fun buildGeneralizations(decl: CppClassDecl): List<KumlElement> {
        val childId = qualifiedId(decl.namespace, decl.name)
        return decl.bases.mapIndexed { i, base ->
            UmlGeneralization(
                id = "cpp::gen::${decl.name}::${base.name}::$i",
                specificId = childId,
                generalId = qualifiedId(null, base.name),
            )
        }
    }

    // ── Property ──────────────────────────────────────────────────────────────

    private fun buildProperty(
        member: CppMember,
        ownerId: String,
    ): UmlProperty {
        val typeName = flattenTypeRef(member.type)
        return UmlProperty(
            id = "$ownerId::${member.name}",
            name = member.name,
            visibility = toVisibility(member.access),
            type = UmlTypeRef(name = typeMapper.map(typeName)),
            isReadOnly = member.isConst,
            isStatic = member.isStatic,
        )
    }

    // ── Operation ─────────────────────────────────────────────────────────────

    private fun buildOperation(
        member: CppMember,
        ownerId: String,
    ): UmlOperation {
        val retTypeName = flattenTypeRef(member.type)
        val params =
            member.params.mapIndexed { i, p ->
                val pTypeName = flattenTypeRef(p.type)
                val pName = p.name.ifEmpty { "param$i" }
                UmlParameter(
                    id = "$ownerId::${member.name}::$pName",
                    name = pName,
                    type = UmlTypeRef(name = typeMapper.map(pTypeName)),
                )
            }
        return UmlOperation(
            id = "$ownerId::${member.name}()",
            name = member.name,
            visibility = toVisibility(member.access),
            parameters = params,
            returnType = UmlTypeRef(name = typeMapper.map(retTypeName)),
            isAbstract = member.isPureVirtual,
            isStatic = member.isStatic,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun qualifiedId(
        namespace: String?,
        name: String,
    ): String = if (namespace.isNullOrEmpty()) "cpp::$name" else "cpp::$namespace::$name"

    private fun flattenTypeRef(ref: CppTypeRef?): String {
        if (ref == null) return "void"
        if (ref.templateArgs.isEmpty()) return ref.name
        val args = ref.templateArgs.joinToString(", ") { flattenTypeRef(it) }
        return "${ref.name}<$args>"
    }

    private fun toVisibility(access: String): Visibility =
        when (access) {
            "public" -> Visibility.PUBLIC
            "protected" -> Visibility.PROTECTED
            else -> Visibility.PRIVATE
        }
}
