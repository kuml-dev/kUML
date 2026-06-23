package dev.kuml.plugin.examples.tsreverse

internal class TsLexer(
    private val src: String,
) {
    private var pos = 0

    private companion object {
        val KEYWORDS =
            setOf(
                "export",
                "default",
                "declare",
                "abstract",
                "class",
                "interface",
                "enum",
                "extends",
                "implements",
                "import",
                "from",
                "type",
                "namespace",
                "module",
                "readonly",
                "static",
                "private",
                "protected",
                "public",
                "override",
                "async",
                "function",
                "const",
                "let",
                "var",
                "new",
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
                "throw",
                "try",
                "catch",
                "finally",
                "in",
                "of",
                "typeof",
                "instanceof",
                "keyof",
                "infer",
                "never",
                "any",
                "unknown",
                "void",
                "null",
                "undefined",
                "true",
                "false",
                "get",
                "set",
                "as",
                "constructor",
            )
    }

    fun tokenize(): List<TsToken> {
        val tokens = mutableListOf<TsToken>()
        while (pos < src.length) {
            skipWhitespaceAndComments()
            if (pos >= src.length) break
            tokens += nextToken()
        }
        tokens += TsToken(kind = TsTokenKind.EOF, text = "")
        return tokens
    }

    private fun skipWhitespaceAndComments() {
        while (pos < src.length) {
            when {
                src[pos].isWhitespace() -> pos++
                src.startsWith("//", pos) -> {
                    while (pos < src.length && src[pos] != '\n') pos++
                }
                src.startsWith("/*", pos) -> {
                    pos += 2
                    while (pos < src.length - 1 && !src.startsWith("*/", pos)) pos++
                    if (pos < src.length - 1) pos += 2
                }
                else -> return
            }
        }
    }

    private fun nextToken(): TsToken {
        val c = src[pos]
        return when {
            c == '\'' || c == '"' || c == '`' -> readStringLiteral(c)
            c.isDigit() -> readNumber()
            c.isLetter() || c == '_' || c == '$' -> readIdentOrKeyword()
            c == '@' -> TsToken(kind = TsTokenKind.AT, text = "@").also { pos++ }
            c == '{' -> TsToken(kind = TsTokenKind.LBRACE, text = "{").also { pos++ }
            c == '}' -> TsToken(kind = TsTokenKind.RBRACE, text = "}").also { pos++ }
            c == '(' -> TsToken(kind = TsTokenKind.LPAREN, text = "(").also { pos++ }
            c == ')' -> TsToken(kind = TsTokenKind.RPAREN, text = ")").also { pos++ }
            c == '<' -> TsToken(kind = TsTokenKind.LANGLE, text = "<").also { pos++ }
            c == '>' -> TsToken(kind = TsTokenKind.RANGLE, text = ">").also { pos++ }
            c == '[' -> TsToken(kind = TsTokenKind.LBRACKET, text = "[").also { pos++ }
            c == ']' -> TsToken(kind = TsTokenKind.RBRACKET, text = "]").also { pos++ }
            c == ':' -> TsToken(kind = TsTokenKind.COLON, text = ":").also { pos++ }
            c == ';' -> TsToken(kind = TsTokenKind.SEMICOLON, text = ";").also { pos++ }
            c == ',' -> TsToken(kind = TsTokenKind.COMMA, text = ",").also { pos++ }
            c == '.' -> TsToken(kind = TsTokenKind.DOT, text = ".").also { pos++ }
            c == '|' -> TsToken(kind = TsTokenKind.PIPE, text = "|").also { pos++ }
            c == '&' -> TsToken(kind = TsTokenKind.AMPERSAND, text = "&").also { pos++ }
            c == '=' -> TsToken(kind = TsTokenKind.EQUALS, text = "=").also { pos++ }
            c == '?' -> TsToken(kind = TsTokenKind.QUESTION, text = "?").also { pos++ }
            c == '*' -> TsToken(kind = TsTokenKind.STAR, text = "*").also { pos++ }
            c == '!' -> TsToken(kind = TsTokenKind.BANG, text = "!").also { pos++ }
            else -> {
                pos++
                TsToken(kind = TsTokenKind.IDENT, text = c.toString())
            }
        }
    }

    private fun readStringLiteral(quote: Char): TsToken {
        val start = pos++
        if (quote == '`') {
            var depth = 1
            while (pos < src.length && depth > 0) {
                when {
                    src[pos] == '\\' -> pos += 2
                    src[pos] == '`' -> {
                        depth--
                        pos++
                    }
                    else -> pos++
                }
            }
        } else {
            while (pos < src.length && src[pos] != quote && src[pos] != '\n') {
                if (src[pos] == '\\') pos++
                pos++
            }
            if (pos < src.length) pos++
        }
        return TsToken(kind = TsTokenKind.STRING_LIT, text = src.substring(start, pos))
    }

    private fun readNumber(): TsToken {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == '_')) pos++
        return TsToken(kind = TsTokenKind.NUMBER_LIT, text = src.substring(start, pos))
    }

    private fun readIdentOrKeyword(): TsToken {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '$')) pos++
        val text = src.substring(start, pos)
        val kind = if (text in KEYWORDS) TsTokenKind.KEYWORD else TsTokenKind.IDENT
        return TsToken(kind = kind, text = text)
    }
}
