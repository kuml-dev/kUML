package dev.kuml.plugin.examples.csharpreverse

/**
 * Handwritten lexer for structural C# analysis.
 *
 * Note: ANTLR4 was evaluated for this wave (V3.1.40) but rejected because no reliable
 * ANTLR4 C# grammar artifact is available on Maven Central. The handwritten recursive-descent
 * approach established in V3.1.39 (plugin-reverse-cpp) is used instead (Option B).
 *
 * Phase order:
 * 1. Strip preprocessor lines (lines starting with `#` — handles #region, #if, #pragma etc.).
 * 2. Strip string and char literals — must run before comment stripping so that
 *    comment-marker sequences inside string literals are not mistakenly removed.
 *    Handles regular "...", verbatim @"...", interpolated $"..." (best-effort brace escape),
 *    and char literals '...'.
 * 3. Strip `//` line comments and block comments.
 * 4. Tokenize into IDENTIFIER / KEYWORD / PUNCT / NUMBER / STRING_LIT / EOF tokens.
 *
 * C# complex grammar, method bodies, LINQ expressions, and lambdas are out of scope —
 * structural level only.
 */
internal class CsharpLexer(
    private val source: String,
) {
    fun tokenize(): List<CsharpToken> {
        val stripped = stripPreprocessor(source)
        val noStrings = stripStringLiterals(stripped)
        val noComments = stripComments(noStrings)
        return lex(noComments)
    }

    // ── Phase 1: Strip preprocessor lines ────────────────────────────────────

    private fun stripPreprocessor(src: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = src.length
        while (i < len) {
            // Skip leading whitespace on current line to check for '#'
            val lineStart = i
            var j = i
            while (j < len && src[j] != '\n' && src[j].isWhitespace()) j++
            if (j < len && src[j] == '#') {
                // Strip entire preprocessor directive
                while (i < len && src[i] != '\n') i++
                sb.append('\n')
                if (i < len) i++ // consume \n
            } else {
                // Emit line verbatim
                while (i < len && src[i] != '\n') {
                    sb.append(src[i])
                    i++
                }
                if (i < len) {
                    sb.append('\n')
                    i++
                }
            }
        }
        return sb.toString()
    }

    // ── Phase 2: Strip string and char literals ───────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    private fun stripStringLiterals(src: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = src.length
        while (i < len) {
            val c = src[i]
            when {
                // Verbatim string: @"..." — no escape sequences, doubled "" to embed quote
                c == '@' && i + 1 < len && src[i + 1] == '"' -> {
                    i += 2 // skip @"
                    while (i < len) {
                        if (src[i] == '"') {
                            i++
                            if (i < len && src[i] == '"') {
                                i++ // doubled quote inside verbatim string
                            } else {
                                break // end of verbatim string
                            }
                        } else {
                            if (src[i] == '\n') sb.append('\n') // preserve newlines
                            i++
                        }
                    }
                    sb.append("\"\"")
                }
                // Interpolated string: $"..." — best-effort: strip like regular string
                // (braces inside may confuse brace-depth tracking, but that's acceptable
                //  at the structural level)
                c == '$' && i + 1 < len && src[i + 1] == '"' -> {
                    i += 2 // skip $"
                    while (i < len && src[i] != '"') {
                        if (src[i] == '\\') i++ // skip escape
                        if (i < len) i++
                    }
                    if (i < len) i++ // closing "
                    sb.append("\"\"")
                }
                // Regular string: "..."
                c == '"' -> {
                    i++ // opening "
                    while (i < len && src[i] != '"') {
                        if (src[i] == '\\') i++ // skip escape char
                        if (i < len) i++
                    }
                    if (i < len) i++ // closing "
                    sb.append("\"\"")
                }
                // Char literal: '...'
                c == '\'' -> {
                    i++ // opening '
                    while (i < len && src[i] != '\'') {
                        if (src[i] == '\\') i++ // skip escape
                        if (i < len) i++
                    }
                    if (i < len) i++ // closing '
                    sb.append("''")
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    // ── Phase 3: Strip comments ───────────────────────────────────────────────

    private fun stripComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = src.length
        while (i < len) {
            if (i + 1 < len && src[i] == '/' && src[i + 1] == '/') {
                // Line comment: skip to end of line
                while (i < len && src[i] != '\n') i++
            } else if (i + 1 < len && src[i] == '/' && src[i + 1] == '*') {
                i += 2
                while (i + 1 < len && !(src[i] == '*' && src[i + 1] == '/')) {
                    if (src[i] == '\n') sb.append('\n')
                    i++
                }
                i += 2 // skip */
            } else {
                sb.append(src[i])
                i++
            }
        }
        return sb.toString()
    }

    // ── Phase 4: Tokenize ─────────────────────────────────────────────────────

    private fun lex(src: String): List<CsharpToken> {
        val tokens = mutableListOf<CsharpToken>()
        var i = 0
        val len = src.length
        var line = 1

        while (i < len) {
            val c = src[i]
            when {
                c == '\n' -> {
                    line++
                    i++
                }
                c.isWhitespace() -> i++
                // Verbatim identifier: @identifier
                c == '@' && i + 1 < len && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
                    i++ // skip @
                    val start = i
                    while (i < len && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    val text = src.substring(start, i)
                    tokens += CsharpToken(CsharpTokenType.IDENTIFIER, text, line)
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < len && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    val text = src.substring(start, i)
                    val type = if (text in KEYWORDS) CsharpTokenType.KEYWORD else CsharpTokenType.IDENTIFIER
                    tokens += CsharpToken(type, text, line)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < len && (src[i].isLetterOrDigit() || src[i] == '.' || src[i] == '_')) i++
                    tokens += CsharpToken(CsharpTokenType.NUMBER, src.substring(start, i), line)
                }
                // Multi-char punctuation
                c == ':' && i + 1 < len && src[i + 1] == ':' -> {
                    tokens += CsharpToken(CsharpTokenType.PUNCT, "::", line)
                    i += 2
                }
                c == '?' && i + 1 < len && src[i + 1] == '.' -> {
                    tokens += CsharpToken(CsharpTokenType.PUNCT, "?.", line)
                    i += 2
                }
                c == '=' && i + 1 < len && src[i + 1] == '>' -> {
                    tokens += CsharpToken(CsharpTokenType.PUNCT, "=>", line)
                    i += 2
                }
                c in PUNCTS -> {
                    tokens += CsharpToken(CsharpTokenType.PUNCT, c.toString(), line)
                    i++
                }
                else -> i++ // skip unknown chars (stripped string placeholders etc.)
            }
        }
        tokens += CsharpToken(CsharpTokenType.EOF, "", line)
        return tokens
    }

    private companion object {
        val KEYWORDS: Set<String> =
            setOf(
                "abstract",
                "as",
                "base",
                "bool",
                "break",
                "byte",
                "case",
                "catch",
                "char",
                "checked",
                "class",
                "const",
                "continue",
                "decimal",
                "default",
                "delegate",
                "do",
                "double",
                "else",
                "enum",
                "event",
                "explicit",
                "extern",
                "false",
                "finally",
                "fixed",
                "float",
                "for",
                "foreach",
                "get",
                "goto",
                "if",
                "implicit",
                "in",
                "int",
                "interface",
                "internal",
                "is",
                "lock",
                "long",
                "namespace",
                "new",
                "null",
                "object",
                "operator",
                "out",
                "override",
                "params",
                "partial",
                "private",
                "protected",
                "public",
                "readonly",
                "record",
                "ref",
                "return",
                "sbyte",
                "sealed",
                "set",
                "short",
                "sizeof",
                "stackalloc",
                "static",
                "string",
                "struct",
                "switch",
                "this",
                "throw",
                "true",
                "try",
                "typeof",
                "uint",
                "ulong",
                "unchecked",
                "unsafe",
                "ushort",
                "using",
                "virtual",
                "void",
                "volatile",
                "where",
                "while",
                "yield",
            )

        val PUNCTS: Set<Char> = setOf('{', '}', '(', ')', '[', ']', ';', ':', ',', '<', '>', '=', '?', '.', '*', '&', '~')
    }
}
