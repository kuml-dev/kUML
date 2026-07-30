package dev.kuml.core.script.interpreter

/**
 * Tokeniser for the kUML data-DSL interpreter subset (Welle 9, Option D).
 *
 * Modelled on the tokeniser inside `dev.kuml.expr.OclLikeExpressionParser` (the
 * existing OCL-subset interpreter that is kUML's architectural precedent for
 * "AST + interpreter, no compiler"), but tuned for the *builder* grammar rather
 * than expressions: it emits braces, no arithmetic operators, and treats `=` as
 * a single assignment/named-argument token.
 *
 * Line/column tracking is 1-based for legible parse errors.
 *
 * V0.23.3 — Welle 9.
 */
internal enum class DslTokenKind {
    IDENT,
    KEYWORD_VAL,
    STRING,
    INT,
    TRUE,
    FALSE,
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    COMMA,
    DOT,
    ASSIGN,
    EOF,
}

internal data class DslToken(
    val kind: DslTokenKind,
    val text: String,
    val line: Int,
    val column: Int,
)

/** Thrown for lexical errors. Carries a 1-based [line] for diagnostics. */
internal class DslLexException(
    override val message: String,
    val line: Int,
) : RuntimeException(message)

internal object DslLexer {
    fun tokenize(src: String): List<DslToken> {
        val tokens = mutableListOf<DslToken>()
        var i = 0
        var line = 1
        var lineStart = 0

        fun col() = i - lineStart + 1

        while (i < src.length) {
            val c = src[i]

            // Newlines — the grammar is newline-insensitive (statements are
            // separated structurally), but we track lines for diagnostics.
            if (c == '\n') {
                line++
                i++
                lineStart = i
                continue
            }
            if (c.isWhitespace()) {
                i++
                continue
            }
            // Statement separator — the grammar is otherwise newline/structure
            // delimited, but real scripts put several statements on one line
            // (e.g. `source { multiplicity(spec = "1"); navigable = false }`).
            // We treat ';' as a no-op separator, like whitespace.
            if (c == ';') {
                i++
                continue
            }

            // Comments — line (//) and block (/* */). Block comments may span lines.
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                    if (src[i] == '\n') {
                        line++
                        lineStart = i + 1
                    }
                    i++
                }
                if (i + 1 >= src.length) throw DslLexException(message = "Unterminated block comment", line = line)
                i += 2 // consume */
                continue
            }

            val startCol = col()
            when {
                c == '(' -> {
                    tokens += DslToken(kind = DslTokenKind.LPAREN, text = "(", line = line, column = startCol)
                    i++
                }
                c == ')' -> {
                    tokens += DslToken(kind = DslTokenKind.RPAREN, text = ")", line = line, column = startCol)
                    i++
                }
                c == '{' -> {
                    tokens += DslToken(kind = DslTokenKind.LBRACE, text = "{", line = line, column = startCol)
                    i++
                }
                c == '}' -> {
                    tokens += DslToken(kind = DslTokenKind.RBRACE, text = "}", line = line, column = startCol)
                    i++
                }
                c == ',' -> {
                    tokens += DslToken(kind = DslTokenKind.COMMA, text = ",", line = line, column = startCol)
                    i++
                }
                c == '.' -> {
                    tokens += DslToken(kind = DslTokenKind.DOT, text = ".", line = line, column = startCol)
                    i++
                }
                c == '=' -> {
                    // Bare '=' only. '==' and friends have no place in this grammar.
                    if (i + 1 < src.length && src[i + 1] == '=') {
                        throw DslLexException(
                            message = "'==' is not part of the interpreter DSL grammar (no expressions)",
                            line = line,
                        )
                    }
                    tokens += DslToken(kind = DslTokenKind.ASSIGN, text = "=", line = line, column = startCol)
                    i++
                }
                c == '"' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\n') {
                            throw DslLexException(message = "Unterminated string literal", line = line)
                        }
                        if (src[i] == '\\' && i + 1 < src.length) {
                            i++
                            sb.append(
                                when (src[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    '\\' -> '\\'
                                    '"' -> '"'
                                    '$' -> '$'
                                    else -> throw DslLexException(message = "Unsupported escape '\\${src[i]}'", line = line)
                                },
                            )
                            i++
                        } else if (src[i] == '$') {
                            // String interpolation is explicitly NOT supported —
                            // it would require expression evaluation. Fail clearly.
                            throw DslLexException(
                                message =
                                    "String interpolation ('\$') is not supported by the interpreter mode; " +
                                        "use --eval-strategy=compiler for interpolated strings",
                                line = line,
                            )
                        } else {
                            sb.append(src[i])
                            i++
                        }
                    }
                    if (i >= src.length) throw DslLexException(message = "Unterminated string literal", line = line)
                    i++ // closing quote
                    tokens += DslToken(kind = DslTokenKind.STRING, text = sb.toString(), line = line, column = startCol)
                }
                c.isDigit() || (c == '-' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val sb = StringBuilder()
                    if (c == '-') {
                        sb.append('-')
                        i++
                    }
                    while (i < src.length && (src[i].isDigit() || src[i] == '_')) {
                        if (src[i] != '_') sb.append(src[i])
                        i++
                    }
                    // Reject decimals — the class-diagram subset uses only integer
                    // literals directly; doubles appear only inside string default
                    // values (e.g. "0.19"), never as bare numbers.
                    if (i < src.length && src[i] == '.') {
                        throw DslLexException(
                            message = "Decimal number literals are not supported; wrap the value in a string (e.g. \"0.19\")",
                            line = line,
                        )
                    }
                    tokens += DslToken(kind = DslTokenKind.INT, text = sb.toString(), line = line, column = startCol)
                }
                c.isLetter() || c == '_' -> {
                    val sb = StringBuilder()
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) {
                        sb.append(src[i])
                        i++
                    }
                    val text = sb.toString()
                    val kind =
                        when (text) {
                            "val" -> DslTokenKind.KEYWORD_VAL
                            "true" -> DslTokenKind.TRUE
                            "false" -> DslTokenKind.FALSE
                            else -> DslTokenKind.IDENT
                        }
                    tokens += DslToken(kind = kind, text = text, line = line, column = startCol)
                }
                else -> throw DslLexException(message = "Unexpected character '$c'", line = line)
            }
        }

        tokens += DslToken(kind = DslTokenKind.EOF, text = "<EOF>", line = line, column = col())
        return tokens
    }
}
