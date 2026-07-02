package dev.kuml.core.ocl

internal sealed class OclToken {
    data class Ident(
        val name: String,
    ) : OclToken()

    data class IntLit(
        val value: Int,
    ) : OclToken()

    data class RealLit(
        val value: Double,
    ) : OclToken()

    data class StrLit(
        val value: String,
    ) : OclToken()

    data object TrueLit : OclToken()

    data object FalseLit : OclToken()

    data object NullLit : OclToken()

    data object Self : OclToken()

    data object Dot : OclToken()

    data object Arrow : OclToken() // "->"

    data object LParen : OclToken()

    data object RParen : OclToken()

    data object Pipe : OclToken() // "|"

    data object Comma : OclToken()

    data object Semicolon : OclToken() // used only in iterate(iterVar; accVar = init | body)

    data object AtPre : OclToken() // "@pre" — post-condition pre-state snapshot marker

    data class Op(
        val sym: String,
    ) : OclToken() // =, <>, <, >, <=, >=, +, -, *, /

    data object Eof : OclToken()
}
