package dev.kuml.plugin.examples.cppreverse

import dev.kuml.codegen.reverse.ReverseDiagnostic

internal data class CppTypeRef(
    val name: String,
    val isPointer: Boolean = false,
    val isReference: Boolean = false,
    val isConst: Boolean = false,
    val templateArgs: List<CppTypeRef> = emptyList(),
)

internal data class CppParam(
    val name: String,
    val type: CppTypeRef?,
)

internal data class CppBaseSpec(
    val name: String,
    val access: String, // "public" | "protected" | "private"
    val isVirtual: Boolean = false,
)

internal data class CppMember(
    val name: String,
    val type: CppTypeRef?,
    val isMethod: Boolean,
    val access: String, // "public" | "protected" | "private"
    val isStatic: Boolean = false,
    val isVirtual: Boolean = false,
    val isPureVirtual: Boolean = false,
    val isConst: Boolean = false,
    val params: List<CppParam> = emptyList(),
)

internal sealed interface CppDeclaration {
    val name: String
    val namespace: String?
}

internal data class CppClassDecl(
    override val name: String,
    override val namespace: String?,
    val isStruct: Boolean,
    val bases: List<CppBaseSpec>,
    val members: List<CppMember>,
    val isTemplate: Boolean = false,
    val isForwardDecl: Boolean = false,
) : CppDeclaration

internal data class CppEnumDecl(
    override val name: String,
    override val namespace: String?,
    val isEnumClass: Boolean,
    val literals: List<String>,
) : CppDeclaration

internal data class CppFileAst(
    val declarations: List<CppDeclaration>,
    val diagnostics: List<ReverseDiagnostic> = emptyList(),
)
