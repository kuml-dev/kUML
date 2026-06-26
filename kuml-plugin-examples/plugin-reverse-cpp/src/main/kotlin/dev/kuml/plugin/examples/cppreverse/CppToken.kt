package dev.kuml.plugin.examples.cppreverse

internal enum class CppTokenType {
    IDENTIFIER,
    KEYWORD,
    PUNCT,
    NUMBER,
    STRING_LIT,
    EOF,
}

internal data class CppToken(
    val type: CppTokenType,
    val text: String,
    val line: Int,
)
