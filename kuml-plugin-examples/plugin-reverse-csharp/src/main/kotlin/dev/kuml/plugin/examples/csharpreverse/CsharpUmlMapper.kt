package dev.kuml.plugin.examples.csharpreverse

import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.core.model.KumlElement
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

/**
 * Maps parsed C# AST declarations to kUML model elements.
 *
 * ID scheme: `cs::<namespace>::<Name>` (dot-separated namespace, e.g. `cs::App.Services::MyClass`).
 *
 * Base-list classification:
 * 1. First pass: build a set of all declared interface qualified-names + simple-names.
 * 2. For each base spec: if the name (or simple name) is in the known-interfaces set →
 *    [UmlInterfaceRealization]; else if name matches `I` + uppercase heuristic →
 *    [UmlInterfaceRealization]; else → [UmlGeneralization].
 *
 * Duplicate definitions (same qualified id) emit REV-CS-006 WARN; first wins.
 */
internal class CsharpUmlMapper(
    private val typeMapper: CsharpTypeMapper,
) {
    fun buildElements(fileAsts: List<CsharpFileAst>): Pair<List<KumlElement>, List<ReverseDiagnostic>> {
        val elements = mutableListOf<KumlElement>()
        val relationships = mutableListOf<KumlElement>()
        val knownIds = mutableSetOf<String>()
        val mapperDiagnostics = mutableListOf<ReverseDiagnostic>()

        // First pass: collect all interface names (qualified + simple) for base-list classification
        val knownInterfaceNames = mutableSetOf<String>()
        for (ast in fileAsts) {
            for (decl in ast.declarations) {
                if (decl is CsharpClassDecl && decl.kind == CsharpDeclKind.INTERFACE) {
                    knownInterfaceNames += decl.name
                    val qid = qualifiedId(decl.namespace, decl.name)
                    knownInterfaceNames += qid
                }
            }
        }

        // Second pass: build elements
        for (ast in fileAsts) {
            for (decl in ast.declarations) {
                val id = qualifiedId(decl.namespace, decl.name)
                when (decl) {
                    is CsharpClassDecl -> {
                        if (id in knownIds) {
                            mapperDiagnostics +=
                                ReverseDiagnostic(
                                    severity = ReverseDiagnostic.Severity.WARN,
                                    code = "REV-CS-006",
                                    message =
                                        "Duplicate definition for '$id' — " +
                                            "keeping first occurrence, ignoring '${decl.name}'.",
                                )
                            continue
                        }
                        knownIds += id
                        if (decl.kind == CsharpDeclKind.INTERFACE) {
                            elements += buildInterface(decl)
                        } else {
                            elements += buildClass(decl)
                        }
                        relationships += buildBaseRelationships(decl, knownInterfaceNames)
                    }
                    is CsharpEnumDecl -> {
                        if (id in knownIds) {
                            mapperDiagnostics +=
                                ReverseDiagnostic(
                                    severity = ReverseDiagnostic.Severity.WARN,
                                    code = "REV-CS-006",
                                    message =
                                        "Duplicate enum definition for '$id' — ignoring '${decl.name}'.",
                                )
                            continue
                        }
                        knownIds += id
                        elements += buildEnum(decl)
                    }
                }
            }
        }

        return Pair(elements + relationships, mapperDiagnostics)
    }

    // ── Class ─────────────────────────────────────────────────────────────────

    private fun buildClass(decl: CsharpClassDecl): UmlClass {
        val id = qualifiedId(decl.namespace, decl.name)
        val attrs =
            decl.members
                .filter { it.kind == CsharpMemberKind.FIELD || it.kind == CsharpMemberKind.PROPERTY }
                .map { buildProperty(it, id) }
        val ops =
            decl.members
                .filter { it.kind == CsharpMemberKind.METHOD }
                .map { buildOperation(it, id) }
        // Determine isAbstract: explicit abstract modifier OR all members abstract
        val isAbstract =
            decl.isAbstract ||
                (decl.members.isNotEmpty() && decl.members.all { it.isAbstract })
        // stereotypes from attributes (best-effort)
        return UmlClass(
            id = id,
            name = decl.name,
            visibility = Visibility.PUBLIC,
            isAbstract = isAbstract,
            attributes = attrs,
            operations = ops,
        )
    }

    // ── Interface ─────────────────────────────────────────────────────────────

    private fun buildInterface(decl: CsharpClassDecl): UmlInterface {
        val id = qualifiedId(decl.namespace, decl.name)
        val attrs =
            decl.members
                .filter { it.kind == CsharpMemberKind.FIELD || it.kind == CsharpMemberKind.PROPERTY }
                .map { buildProperty(it, id) }
        val ops =
            decl.members
                .filter { it.kind == CsharpMemberKind.METHOD }
                .map { buildOperation(it, id) }
        return UmlInterface(
            id = id,
            name = decl.name,
            visibility = Visibility.PUBLIC,
            attributes = attrs,
            operations = ops,
        )
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    private fun buildEnum(decl: CsharpEnumDecl): UmlEnumeration {
        val id = qualifiedId(decl.namespace, decl.name)
        val literals = decl.literals.map { UmlEnumerationLiteral(id = "$id::$it", name = it) }
        return UmlEnumeration(id = id, name = decl.name, literals = literals)
    }

    // ── Base relationships ─────────────────────────────────────────────────────

    private fun buildBaseRelationships(
        decl: CsharpClassDecl,
        knownInterfaceNames: Set<String>,
    ): List<KumlElement> {
        val childId = qualifiedId(decl.namespace, decl.name)
        return decl.bases.mapIndexed { i, base ->
            val baseId = resolveBaseId(base.name, decl.namespace)
            if (isInterface(base.name, knownInterfaceNames)) {
                UmlInterfaceRealization(
                    id = "cs::real::${decl.name}::${base.name}::$i",
                    implementingId = childId,
                    interfaceId = baseId,
                )
            } else {
                UmlGeneralization(
                    id = "cs::gen::${decl.name}::${base.name}::$i",
                    specificId = childId,
                    generalId = baseId,
                )
            }
        }
    }

    /**
     * Determine whether a base name refers to an interface.
     * Priority:
     * 1. Name is in the known-interface set (parsed corpus).
     * 2. Heuristic: name starts with 'I' followed by an uppercase letter (e.g. IShape, IDisposable).
     * 3. Otherwise: treat as class → [UmlGeneralization].
     */
    private fun isInterface(
        baseName: String,
        knownInterfaceNames: Set<String>,
    ): Boolean {
        if (baseName in knownInterfaceNames) return true
        // Simple name from potentially qualified base
        val simpleName = baseName.substringAfterLast('.')
        if (simpleName in knownInterfaceNames) return true
        // Naming heuristic: I followed by uppercase
        return simpleName.length >= 2 && simpleName[0] == 'I' && simpleName[1].isUpperCase()
    }

    private fun resolveBaseId(
        baseName: String,
        currentNamespace: String?,
    ): String {
        // If name already dotted, use as-is
        return if (baseName.contains('.')) {
            "cs::$baseName"
        } else {
            qualifiedId(currentNamespace, baseName)
        }
    }

    // ── Property ──────────────────────────────────────────────────────────────

    private fun buildProperty(
        member: CsharpMember,
        ownerId: String,
    ): UmlProperty {
        val typeName = flattenTypeRef(member.type)
        return UmlProperty(
            id = "$ownerId::${member.name}",
            name = member.name,
            visibility = toVisibility(member.access),
            type = UmlTypeRef(name = typeMapper.map(typeName)),
            isReadOnly = member.isReadOnly,
            isStatic = member.isStatic,
        )
    }

    // ── Operation ─────────────────────────────────────────────────────────────

    private fun buildOperation(
        member: CsharpMember,
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
        val paramTypes = params.joinToString(",") { it.type.name }
        return UmlOperation(
            id = "$ownerId::${member.name}($paramTypes)",
            name = member.name,
            visibility = toVisibility(member.access),
            parameters = params,
            returnType = UmlTypeRef(name = typeMapper.map(retTypeName)),
            isAbstract = member.isAbstract,
            isStatic = member.isStatic,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun qualifiedId(
        namespace: String?,
        name: String,
    ): String = if (namespace.isNullOrEmpty()) "cs::$name" else "cs::$namespace::$name"

    private fun flattenTypeRef(ref: CsharpTypeRef?): String {
        if (ref == null) return "void"
        val suffix =
            when {
                ref.isArray -> "[]"
                ref.isNullable -> "?"
                else -> ""
            }
        if (ref.typeArgs.isEmpty()) return "${ref.name}$suffix"
        val args = ref.typeArgs.joinToString(", ") { flattenTypeRef(it) }
        return "${ref.name}<$args>$suffix"
    }

    private fun toVisibility(access: String): Visibility =
        when (access) {
            "public" -> Visibility.PUBLIC
            "protected" -> Visibility.PROTECTED
            "internal" -> Visibility.PACKAGE
            else -> Visibility.PRIVATE
        }
}
