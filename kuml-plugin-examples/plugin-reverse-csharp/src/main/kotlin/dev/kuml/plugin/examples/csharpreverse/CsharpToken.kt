package dev.kuml.plugin.examples.csharpreverse

internal enum class CsharpTokenType {
    IDENTIFIER,
    KEYWORD,
    PUNCT,
    NUMBER,
    STRING_LIT,
    EOF,
}

internal data class CsharpToken(
    val type: CsharpTokenType,
    val text: String,
    val line: Int,
)
