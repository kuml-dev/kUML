package dev.kuml.plugin.examples.tsreverse

internal data class TsTypeParam(
    val name: String,
)

internal data class TsTypeRef(
    val name: String,
    val typeArgs: List<TsTypeRef> = emptyList(),
    val isArray: Boolean = false,
    val isOptional: Boolean = false,
    val isUnion: Boolean = false,
    val unionTypes: List<TsTypeRef> = emptyList(),
)

internal data class TsParam(
    val name: String,
    val type: TsTypeRef?,
    val isOptional: Boolean = false,
)

internal data class TsMember(
    val name: String,
    val type: TsTypeRef?,
    val isMethod: Boolean,
    val isOptional: Boolean = false,
    val isReadonly: Boolean = false,
    val isStatic: Boolean = false,
    val isAbstract: Boolean = false,
    val visibility: String = "public",
    val params: List<TsParam> = emptyList(),
    val typeParams: List<TsTypeParam> = emptyList(),
    val decorators: List<String> = emptyList(),
)

internal data class TsImport(
    val names: List<String>,
    val from: String,
)

internal sealed interface TsDeclaration {
    val name: String
    val decorators: List<String>
}

internal data class TsInterfaceDecl(
    override val name: String,
    override val decorators: List<String> = emptyList(),
    val typeParams: List<TsTypeParam> = emptyList(),
    val extendsTypes: List<TsTypeRef> = emptyList(),
    val members: List<TsMember> = emptyList(),
) : TsDeclaration

internal data class TsClassDecl(
    override val name: String,
    override val decorators: List<String> = emptyList(),
    val isAbstract: Boolean = false,
    val typeParams: List<TsTypeParam> = emptyList(),
    val superClass: TsTypeRef? = null,
    val implementsTypes: List<TsTypeRef> = emptyList(),
    val members: List<TsMember> = emptyList(),
) : TsDeclaration

internal data class TsEnumDecl(
    override val name: String,
    override val decorators: List<String> = emptyList(),
    val literals: List<String> = emptyList(),
) : TsDeclaration

internal data class TsFileAst(
    val imports: List<TsImport> = emptyList(),
    val declarations: List<TsDeclaration> = emptyList(),
)
