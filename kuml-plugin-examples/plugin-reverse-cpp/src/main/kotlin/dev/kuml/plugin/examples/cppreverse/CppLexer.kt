package dev.kuml.plugin.examples.cppreverse

/**
 * Handwritten lexer for structural C++ analysis.
 *
 * Phase order:
 * 1. Strip preprocessor lines (lines starting with `#`, respecting `\` continuations).
 * 2. Strip `//` and `/* */` comments.
 * 3. Strip string and char literals.
 * 4. Tokenize into IDENTIFIER / KEYWORD / PUNCT / NUMBER / STRING_LIT / EOF tokens.
 *
 * Template meta-programming, preprocessor macros, and full C++ semantics are out of scope.
 */
internal class CppLexer(
    private val source: String,
) {
    fun tokenize(): List<CppToken> {
        val stripped = stripPreprocessor(source)
        val noComments = stripComments(stripped)
        val noStrings = stripStringLiterals(noComments)
        return lex(noStrings)
    }

    // ── Phase 1: Strip preprocessor lines ────────────────────────────────────

    private fun stripPreprocessor(src: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = src.length
        while (i < len) {
            // Find the start of a line — skip to first non-space on the line
            val lineStart = i
            // Find end of current line (handling line continuations)
            if (src[i] == '#') {
                // Skip entire preprocessor directive including line continuations
                while (i < len) {
                    if (src[i] == '\n') {
                        i++
                        break
                    } else if (src[i] == '\\' && i + 1 < len && src[i + 1] == '\n') {
                        // Line continuation — skip both chars
                        i += 2
                    } else {
                        i++
                    }
                }
                // Replace removed content with a newline to preserve line numbers
                sb.append('\n')
            } else {
                // Find end of line, emit it verbatim
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

    // ── Phase 2: Strip comments ───────────────────────────────────────────────

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

    // ── Phase 3: Strip string and char literals ───────────────────────────────

    private fun stripStringLiterals(src: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = src.length
        while (i < len) {
            when {
                src[i] == '"' -> {
                    i++ // opening "
                    while (i < len && src[i] != '"') {
                        if (src[i] == '\\') i++ // skip escape
                        i++
                    }
                    if (i < len) i++ // closing "
                    sb.append("\"\"")
                }
                src[i] == '\'' -> {
                    i++ // opening '
                    while (i < len && src[i] != '\'') {
                        if (src[i] == '\\') i++ // skip escape
                        i++
                    }
                    if (i < len) i++ // closing '
                    sb.append("''")
                }
                else -> {
                    sb.append(src[i])
                    i++
                }
            }
        }
        return sb.toString()
    }

    // ── Phase 4: Tokenize ─────────────────────────────────────────────────────

    private fun lex(src: String): List<CppToken> {
        val tokens = mutableListOf<CppToken>()
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
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < len && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    val text = src.substring(start, i)
                    val type = if (text in KEYWORDS) CppTokenType.KEYWORD else CppTokenType.IDENTIFIER
                    tokens += CppToken(type, text, line)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < len && (src[i].isLetterOrDigit() || src[i] == '.' || src[i] == '_')) i++
                    tokens += CppToken(CppTokenType.NUMBER, src.substring(start, i), line)
                }
                c == ':' && i + 1 < len && src[i + 1] == ':' -> {
                    tokens += CppToken(CppTokenType.PUNCT, "::", line)
                    i += 2
                }
                c in PUNCTS -> {
                    tokens += CppToken(CppTokenType.PUNCT, c.toString(), line)
                    i++
                }
                else -> i++ // skip unknown chars (e.g. stripped string placeholders)
            }
        }
        tokens += CppToken(CppTokenType.EOF, "", line)
        return tokens
    }

    private companion object {
        val KEYWORDS: Set<String> =
            setOf(
                "class",
                "struct",
                "enum",
                "namespace",
                "template",
                "typename",
                "public",
                "protected",
                "private",
                "virtual",
                "static",
                "const",
                "inline",
                "explicit",
                "friend",
                "mutable",
                "volatile",
                "override",
                "final",
                "constexpr",
                "consteval",
                "constinit",
                "extern",
                "register",
                "operator",
                "typedef",
                "using",
                "auto",
                "decltype",
                "noexcept",
                "return",
                "if",
                "else",
                "for",
                "while",
                "do",
                "switch",
                "case",
                "break",
                "continue",
                "goto",
                "default",
                "new",
                "delete",
                "true",
                "false",
                "nullptr",
                "void",
                "bool",
                "int",
                "long",
                "short",
                "char",
                "float",
                "double",
                "signed",
                "unsigned",
                "size_t",
                "int8_t",
                "int16_t",
                "int32_t",
                "int64_t",
                "uint8_t",
                "uint16_t",
                "uint32_t",
                "uint64_t",
            )

        val PUNCTS: Set<Char> = setOf('{', '}', '(', ')', ';', ':', ',', '<', '>', '*', '&', '=', '~')
    }
}
