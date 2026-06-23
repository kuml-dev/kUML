package dev.kuml.plugin.examples.tsreverse

internal enum class TsTokenKind {
    KEYWORD,
    IDENT,
    AT,
    LBRACE,
    RBRACE,
    LPAREN,
    RPAREN,
    LANGLE,
    RANGLE,
    LBRACKET,
    RBRACKET,
    COLON,
    SEMICOLON,
    COMMA,
    DOT,
    PIPE,
    AMPERSAND,
    EQUALS,
    QUESTION,
    STAR,
    BANG,
    STRING_LIT,
    NUMBER_LIT,
    EOF,
}

internal data class TsToken(
    val kind: TsTokenKind,
    val text: String,
)
