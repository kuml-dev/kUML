package dev.kuml.expr

/**
 * Recursive-descent parser for the kUML OCL-like expression subset.
 *
 * V2.0.20a — covers guard expressions.  No external dependencies.
 *
 * Grammar (informal, top-to-bottom precedence):
 *   expr     ::= or
 *   or       ::= and ('||' and)*
 *   and      ::= not ('&&' not)*
 *   not      ::= '!' not | compare
 *   compare  ::= add (('==' | '!=' | '<' | '<=' | '>' | '>=') add)?
 *   add      ::= mul (('+' | '-') mul)*
 *   mul      ::= unary (('*' | '/') unary)*
 *   unary    ::= '-' unary | primary
 *   primary  ::= literal | attrRef | funcCall | '(' expr ')'
 *   attrRef  ::= IDENT ('.' IDENT)*
 *   funcCall ::= IDENT '(' (expr (',' expr)*)? ')'
 *   literal  ::= INT | REAL | STRING | 'true' | 'false' | 'null'
 */
public object OclLikeExpressionParser {
    /**
     * Parses [input] into a [KumlExpression].
     *
     * @throws ParseException if the input is syntactically invalid.
     */
    public fun parse(input: String): KumlExpression {
        val preprocessed = stripComments(input)
        val tokens = tokenize(preprocessed)
        val parser = Parser(tokens, preprocessed)
        val expr = parser.parseExpr()
        if (!parser.isAtEnd()) {
            val tok = parser.peek()
            throw ParseException(ParseError("Unexpected token '${tok.text}' at column ${tok.column}", tok.column))
        }
        return expr
    }

    /**
     * Attempts to parse [input]. Returns null + appends an error to [errors]
     * if parsing fails.  Does NOT throw.
     */
    public fun tryParse(
        input: String,
        errors: MutableList<ParseError> = mutableListOf(),
    ): KumlExpression? =
        try {
            if (input.isBlank()) {
                errors += ParseError("Empty expression", 0)
                null
            } else {
                parse(input)
            }
        } catch (e: ParseException) {
            errors += e.error
            null
        } catch (_: Exception) {
            errors += ParseError("Internal parse error", 0)
            null
        }

    // ── Comment preprocessing ─────────────────────────────────────────────────

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            if (i + 1 < src.length && src[i] == '/' && src[i + 1] == '/') {
                // Line comment: skip to end of line
                while (i < src.length && src[i] != '\n') i++
            } else if (i + 1 < src.length && src[i] == '/' && src[i + 1] == '*') {
                // Block comment: skip to */
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                if (i + 1 < src.length) i += 2 // consume */
            } else {
                sb.append(src[i++])
            }
        }
        return sb.toString()
    }

    // ── Tokeniser ─────────────────────────────────────────────────────────────

    private data class Token(
        val kind: TokenKind,
        val text: String,
        val column: Int,
    )

    private enum class TokenKind {
        IDENT,
        INT,
        REAL,
        STRING,
        TRUE,
        FALSE,
        NULL,
        PLUS,
        MINUS,
        STAR,
        SLASH,
        BANG,
        EQ,
        NEQ,
        LT,
        LTE,
        GT,
        GTE,
        AND,
        OR,
        LPAREN,
        RPAREN,
        COMMA,
        DOT,
        EOF,
    }

    private fun tokenize(src: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        fun col() = i + 1 // 1-based column

        while (i < src.length) {
            // Skip whitespace
            if (src[i].isWhitespace()) {
                i++
                continue
            }

            val start = i
            val c = src[i]

            when {
                // Two-char operators
                i + 1 < src.length && c == '&' && src[i + 1] == '&' -> {
                    tokens += Token(TokenKind.AND, "&&", col())
                    i += 2
                }
                i + 1 < src.length && c == '|' && src[i + 1] == '|' -> {
                    tokens += Token(TokenKind.OR, "||", col())
                    i += 2
                }
                i + 1 < src.length && c == '=' && src[i + 1] == '=' -> {
                    tokens += Token(TokenKind.EQ, "==", col())
                    i += 2
                }
                i + 1 < src.length && c == '!' && src[i + 1] == '=' -> {
                    tokens += Token(TokenKind.NEQ, "!=", col())
                    i += 2
                }
                i + 1 < src.length && c == '<' && src[i + 1] == '=' -> {
                    tokens += Token(TokenKind.LTE, "<=", col())
                    i += 2
                }
                i + 1 < src.length && c == '>' && src[i + 1] == '=' -> {
                    tokens += Token(TokenKind.GTE, ">=", col())
                    i += 2
                }
                // Single-char operators
                c == '<' -> {
                    tokens += Token(TokenKind.LT, "<", col())
                    i++
                }
                c == '>' -> {
                    tokens += Token(TokenKind.GT, ">", col())
                    i++
                }
                c == '+' -> {
                    tokens += Token(TokenKind.PLUS, "+", col())
                    i++
                }
                c == '-' -> {
                    tokens += Token(TokenKind.MINUS, "-", col())
                    i++
                }
                c == '*' -> {
                    tokens += Token(TokenKind.STAR, "*", col())
                    i++
                }
                c == '/' -> {
                    tokens += Token(TokenKind.SLASH, "/", col())
                    i++
                }
                c == '!' -> {
                    tokens += Token(TokenKind.BANG, "!", col())
                    i++
                }
                c == '(' -> {
                    tokens += Token(TokenKind.LPAREN, "(", col())
                    i++
                }
                c == ')' -> {
                    tokens += Token(TokenKind.RPAREN, ")", col())
                    i++
                }
                c == ',' -> {
                    tokens += Token(TokenKind.COMMA, ",", col())
                    i++
                }
                c == '.' -> {
                    tokens += Token(TokenKind.DOT, ".", col())
                    i++
                }
                // String literals — single or double quote
                c == '\'' || c == '"' -> {
                    val startCol = col()
                    val quote = c
                    i++
                    val sb = StringBuilder()
                    while (i < src.length && src[i] != quote) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            i++
                            sb.append(
                                when (src[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    '\\' -> '\\'
                                    '\'' -> '\''
                                    '"' -> '"'
                                    else -> src[i]
                                },
                            )
                            i++
                        } else {
                            sb.append(src[i++])
                        }
                    }
                    if (i >= src.length) {
                        throw ParseException(ParseError("Unterminated string literal", startCol))
                    }
                    i++ // closing quote
                    tokens += Token(TokenKind.STRING, sb.toString(), startCol)
                }
                // Numeric literals
                c.isDigit() -> {
                    val startCol = col()
                    val sb = StringBuilder()
                    while (i < src.length && (src[i].isDigit() || src[i] == '_')) {
                        if (src[i] != '_') sb.append(src[i])
                        i++
                    }
                    if (i < src.length &&
                        src[i] == '.' &&
                        (i + 1 >= src.length || src[i + 1] != '.')
                    ) {
                        sb.append(src[i++])
                        while (i < src.length && (src[i].isDigit() || src[i] == '_')) {
                            if (src[i] != '_') sb.append(src[i])
                            i++
                        }
                        tokens += Token(TokenKind.REAL, sb.toString(), startCol)
                    } else {
                        tokens += Token(TokenKind.INT, sb.toString(), startCol)
                    }
                }
                // Identifiers & keywords
                c.isLetter() || c == '_' -> {
                    val startCol = col()
                    val sb = StringBuilder()
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) {
                        sb.append(src[i++])
                    }
                    val text = sb.toString()
                    val kind =
                        when (text) {
                            "true" -> TokenKind.TRUE
                            "false" -> TokenKind.FALSE
                            "null" -> TokenKind.NULL
                            else -> TokenKind.IDENT
                        }
                    tokens += Token(kind, text, startCol)
                }
                else -> {
                    throw ParseException(ParseError("Unexpected character '$c'", col()))
                }
            }
        }
        tokens += Token(TokenKind.EOF, "<EOF>", src.length + 1)
        return tokens
    }

    // ── Recursive-descent parser ──────────────────────────────────────────────

    private class Parser(
        private val tokens: List<Token>,
        @Suppress("UNUSED_PARAMETER") src: String,
    ) {
        private var pos = 0

        internal fun isAtEnd(): Boolean = peek().kind == TokenKind.EOF

        internal fun peek(): Token = tokens[pos]

        private fun advance(): Token = tokens[pos++]

        private fun check(kind: TokenKind): Boolean = peek().kind == kind

        private fun match(vararg kinds: TokenKind): Boolean {
            if (peek().kind in kinds) {
                advance()
                return true
            }
            return false
        }

        private fun consume(
            kind: TokenKind,
            msg: String,
        ): Token {
            if (!check(kind)) {
                val tok = peek()
                throw ParseException(ParseError("$msg (got '${tok.text}')", tok.column))
            }
            return advance()
        }

        internal fun parseExpr(): KumlExpression = parseOr()

        private fun parseOr(): KumlExpression {
            var left = parseAnd()
            while (check(TokenKind.OR)) {
                advance()
                val right = parseAnd()
                left = BinaryOp(BinaryOperator.OR, left, right)
            }
            return left
        }

        private fun parseAnd(): KumlExpression {
            var left = parseNot()
            while (check(TokenKind.AND)) {
                advance()
                val right = parseNot()
                left = BinaryOp(BinaryOperator.AND, left, right)
            }
            return left
        }

        private fun parseNot(): KumlExpression {
            if (check(TokenKind.BANG)) {
                advance()
                return UnaryOp(UnaryOperator.NOT, parseNot())
            }
            return parseCompare()
        }

        private fun parseCompare(): KumlExpression {
            val left = parseAdd()
            val opTok = peek()
            val op =
                when (opTok.kind) {
                    TokenKind.EQ -> BinaryOperator.EQ
                    TokenKind.NEQ -> BinaryOperator.NEQ
                    TokenKind.LT -> BinaryOperator.LT
                    TokenKind.LTE -> BinaryOperator.LTE
                    TokenKind.GT -> BinaryOperator.GT
                    TokenKind.GTE -> BinaryOperator.GTE
                    else -> return left
                }
            advance()
            val right = parseAdd()
            return BinaryOp(op, left, right)
        }

        private fun parseAdd(): KumlExpression {
            var left = parseMul()
            while (check(TokenKind.PLUS) || check(TokenKind.MINUS)) {
                val op = if (advance().kind == TokenKind.PLUS) BinaryOperator.ADD else BinaryOperator.SUB
                val right = parseMul()
                left = BinaryOp(op, left, right)
            }
            return left
        }

        private fun parseMul(): KumlExpression {
            var left = parseUnary()
            while (check(TokenKind.STAR) || check(TokenKind.SLASH)) {
                val op = if (advance().kind == TokenKind.STAR) BinaryOperator.MUL else BinaryOperator.DIV
                val right = parseUnary()
                left = BinaryOp(op, left, right)
            }
            return left
        }

        private fun parseUnary(): KumlExpression {
            if (check(TokenKind.MINUS)) {
                advance()
                return UnaryOp(UnaryOperator.NEG, parseUnary())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): KumlExpression {
            val tok = peek()
            return when (tok.kind) {
                TokenKind.TRUE -> {
                    advance()
                    LiteralBool(true)
                }
                TokenKind.FALSE -> {
                    advance()
                    LiteralBool(false)
                }
                TokenKind.NULL -> {
                    advance()
                    LiteralNull
                }
                TokenKind.INT -> {
                    advance()
                    LiteralInt(tok.text.toLongOrNull() ?: throw ParseException(ParseError("Invalid int literal '${tok.text}'", tok.column)))
                }
                TokenKind.REAL -> {
                    advance()
                    LiteralReal(
                        tok.text.toDoubleOrNull() ?: throw ParseException(ParseError("Invalid real literal '${tok.text}'", tok.column)),
                    )
                }
                TokenKind.STRING -> {
                    advance()
                    LiteralString(tok.text)
                }
                TokenKind.IDENT -> {
                    advance()
                    val parts = mutableListOf(tok.text)
                    // Check for function call: IDENT '('
                    if (check(TokenKind.LPAREN)) {
                        advance() // consume '('
                        val args = mutableListOf<KumlExpression>()
                        if (!check(TokenKind.RPAREN)) {
                            args += parseExpr()
                            while (check(TokenKind.COMMA)) {
                                advance()
                                args += parseExpr()
                            }
                        }
                        consume(TokenKind.RPAREN, "Expected ')' after function arguments")
                        FunctionCall(tok.text, args)
                    } else {
                        // attrRef: IDENT ('.' IDENT)*
                        while (check(TokenKind.DOT)) {
                            advance()
                            val next = consume(TokenKind.IDENT, "Expected identifier after '.'")
                            parts += next.text
                        }
                        AttributeRef(parts)
                    }
                }
                TokenKind.LPAREN -> {
                    advance()
                    val inner = parseExpr()
                    consume(TokenKind.RPAREN, "Expected closing ')'")
                    inner
                }
                else -> throw ParseException(ParseError("Unexpected token '${tok.text}'", tok.column))
            }
        }
    }
}

public data class ParseError(
    val message: String,
    val column: Int,
)

public class ParseException(
    public val error: ParseError,
) : RuntimeException(error.message)
