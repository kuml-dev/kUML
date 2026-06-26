package dev.kuml.plugin.examples.csharpreverse

import dev.kuml.codegen.reverse.ReverseDiagnostic

internal data class CsharpTypeRef(
    val name: String,
    val isNullable: Boolean = false,
    val isArray: Boolean = false,
    val typeArgs: List<CsharpTypeRef> = emptyList(),
)

internal data class CsharpParam(
    val name: String,
    val type: CsharpTypeRef?,
)

/** Represents a base class or interface in the base list — C# does not distinguish syntactically. */
internal data class CsharpBaseSpec(
    val name: String,
)

internal enum class CsharpMemberKind {
    PROPERTY,
    METHOD,
    FIELD,
}

internal data class CsharpMember(
    val name: String,
    val type: CsharpTypeRef?,
    val kind: CsharpMemberKind,
    val access: String,
    val isStatic: Boolean = false,
    val isAbstract: Boolean = false,
    val isReadOnly: Boolean = false,
    val params: List<CsharpParam> = emptyList(),
    val attributes: List<String> = emptyList(),
)

internal enum class CsharpDeclKind {
    CLASS,
    INTERFACE,
    STRUCT,
    RECORD,
}

internal sealed interface CsharpDeclaration {
    val name: String
    val namespace: String?
}

internal data class CsharpClassDecl(
    override val name: String,
    override val namespace: String?,
    val kind: CsharpDeclKind,
    val isAbstract: Boolean,
    val isSealed: Boolean,
    val isStatic: Boolean,
    val bases: List<CsharpBaseSpec>,
    val members: List<CsharpMember>,
    val attributes: List<String>,
    val typeParams: List<String>,
) : CsharpDeclaration

internal data class CsharpEnumDecl(
    override val name: String,
    override val namespace: String?,
    val literals: List<String>,
) : CsharpDeclaration

internal data class CsharpFileAst(
    val declarations: List<CsharpDeclaration>,
    val diagnostics: List<ReverseDiagnostic> = emptyList(),
)
