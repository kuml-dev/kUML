package dev.kuml.core.script.interpreter

/**
 * Recursive-descent parser for the kUML data-DSL interpreter subset (Welle 9).
 *
 * Grammar (informal, EBNF-ish):
 * ```
 * script      ::= call EOF
 * call        ::= IDENT '(' argList? ')' block?
 * argList     ::= arg (',' arg)* ','?
 * arg         ::= (IDENT '=')? expr
 * block       ::= '{' statement* '}'
 * statement   ::= valBinding | assignment | callStatement
 * valBinding  ::= 'val' IDENT '=' call
 * assignment  ::= IDENT '=' expr            (* property set on implicit receiver *)
 * callStmt    ::= call
 * expr        ::= STRING | INT | 'true' | 'false'
 *               | IDENT '.' IDENT           (* enum member ref, e.g. Visibility.PROTECTED *)
 *               | IDENT                      (* val handle reference *)
 *               | call                       (* nested builder call as an argument *)
 * ```
 *
 * There are **no** operators, no arithmetic, no conditionals, no loops, no
 * lambdas-as-values, no method chains beyond a single dotted enum member. The
 * grammar has no production for `::class`, `.java`, `.invoke(...)`, indexing,
 * or free-standing expressions — so those inputs are ordinary syntax errors,
 * which is exactly the structural RCE-impossibility this track is built to
 * demonstrate.
 *
 * Follows the same shape as the `Parser` inner class of
 * `dev.kuml.expr.OclLikeExpressionParser`.
 *
 * V0.23.3 — Welle 9.
 */
internal class DslParseException(
    override val message: String,
    val line: Int,
) : RuntimeException(message)

internal object DslParser {
    /**
     * Default recursion-depth ceiling used by the parameterless [parse] overload.
     * Mirrors [InterpreterLimits.DEFAULT]'s `maxNestingDepth`; kept as an explicit
     * constant so the parser has no dependency on the evaluator's config type.
     */
    const val DEFAULT_MAX_DEPTH: Int = 64

    fun parse(
        src: String,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): DslScript {
        val tokens =
            try {
                DslLexer.tokenize(src)
            } catch (e: DslLexException) {
                throw DslParseException(e.message, e.line)
            }
        val parser = Parser(tokens, maxDepth)
        val root = parser.parseCall()
        parser.expect(DslTokenKind.EOF, "Expected end of script after top-level diagram call")
        return DslScript(root)
    }

    private class Parser(
        private val tokens: List<DslToken>,
        private val maxDepth: Int,
    ) {
        private var pos = 0

        /** Current recursion depth of the mutually-recursive descent. */
        private var depth = 0

        /**
         * Runs [body] one recursion level deeper, rejecting input that nests past
         * [maxDepth] with a plain [DslParseException] — this is the guard that
         * prevents a deeply nested payload from overflowing the JVM stack with an
         * uncaught `StackOverflowError`.
         */
        private inline fun <T> nested(body: () -> T): T {
            if (++depth > maxDepth) {
                throw DslParseException(
                    "Maximum nesting depth of $maxDepth exceeded — input is too deeply nested.",
                    peek().line,
                )
            }
            try {
                return body()
            } finally {
                depth--
            }
        }

        private fun peek(): DslToken = tokens[pos]

        private fun advance(): DslToken = tokens[pos++]

        private fun check(kind: DslTokenKind): Boolean = peek().kind == kind

        fun expect(
            kind: DslTokenKind,
            msg: String,
        ): DslToken {
            if (!check(kind)) {
                val t = peek()
                throw DslParseException("$msg (got '${t.text}')", t.line)
            }
            return advance()
        }

        /**
         * call ::= IDENT ( '(' argList? ')' )? block?
         *
         * The argument list is optional when a trailing lambda follows directly,
         * e.g. `source { ... }` — Kotlin's parenthesis-less trailing-lambda form,
         * which real vault scripts use for `source`/`target` association ends.
         */
        fun parseCall(): DslCall =
            nested {
                parseCallInner()
            }

        private fun parseCallInner(): DslCall {
            val nameTok = expect(DslTokenKind.IDENT, "Expected a builder name")
            val name = nameTok.text
            val args = mutableListOf<DslArg>()
            if (check(DslTokenKind.LPAREN)) {
                advance() // '('
                if (!check(DslTokenKind.RPAREN)) {
                    args += parseArg()
                    while (check(DslTokenKind.COMMA)) {
                        advance()
                        if (check(DslTokenKind.RPAREN)) break // trailing comma
                        args += parseArg()
                    }
                }
                expect(DslTokenKind.RPAREN, "Expected ')' to close arguments of '$name'")
            } else if (!check(DslTokenKind.LBRACE)) {
                // No '(' and no trailing lambda → not a call at all.
                val t = peek()
                throw DslParseException("Expected '(' or '{' after '$name' (got '${t.text}')", t.line)
            }

            val body =
                if (check(DslTokenKind.LBRACE)) {
                    parseBlock()
                } else {
                    null
                }
            return DslCall(name = name, args = args, body = body, line = nameTok.line)
        }

        /** arg ::= (IDENT '=')? expr */
        private fun parseArg(): DslArg {
            // Named argument: IDENT '=' ...  (lookahead two tokens)
            if (check(DslTokenKind.IDENT) &&
                pos + 1 < tokens.size &&
                tokens[pos + 1].kind == DslTokenKind.ASSIGN
            ) {
                val nameTok = advance() // IDENT
                advance() // '='
                val value = parseExpr()
                return DslArg(name = nameTok.text, value = value)
            }
            return DslArg(name = null, value = parseExpr())
        }

        /** block ::= '{' statement* '}' */
        private fun parseBlock(): List<DslStatement> =
            nested {
                parseBlockInner()
            }

        private fun parseBlockInner(): List<DslStatement> {
            expect(DslTokenKind.LBRACE, "Expected '{'")
            val stmts = mutableListOf<DslStatement>()
            while (!check(DslTokenKind.RBRACE) && !check(DslTokenKind.EOF)) {
                stmts += parseStatement()
            }
            expect(DslTokenKind.RBRACE, "Expected '}' to close block")
            return stmts
        }

        private fun parseStatement(): DslStatement =
            nested {
                parseStatementInner()
            }

        private fun parseStatementInner(): DslStatement {
            if (check(DslTokenKind.KEYWORD_VAL)) {
                val valTok = advance()
                val nameTok = expect(DslTokenKind.IDENT, "Expected identifier after 'val'")
                expect(DslTokenKind.ASSIGN, "Expected '=' after 'val ${nameTok.text}'")
                val call = parseCall()
                return DslValBinding(name = nameTok.text, value = call, line = valTok.line)
            }

            // IDENT starts either a property assignment (IDENT '=' expr) or a
            // bare builder call (IDENT '(' ... ')').
            if (check(DslTokenKind.IDENT)) {
                val nameTok = peek()
                if (pos + 1 < tokens.size && tokens[pos + 1].kind == DslTokenKind.ASSIGN) {
                    advance() // IDENT
                    advance() // '='
                    val value = parseExpr()
                    return DslPropertyAssignment(property = nameTok.text, value = value, line = nameTok.line)
                }
                return DslCallStatement(parseCall())
            }

            val t = peek()
            throw DslParseException("Unexpected token '${t.text}' — expected 'val', a property assignment, or a builder call", t.line)
        }

        /** expr ::= literal | memberRef | identifier | call */
        private fun parseExpr(): DslExpr =
            nested {
                parseExprInner()
            }

        private fun parseExprInner(): DslExpr {
            val t = peek()
            return when (t.kind) {
                DslTokenKind.STRING -> {
                    advance()
                    DslString(t.text)
                }
                DslTokenKind.INT -> {
                    advance()
                    DslInt(
                        t.text.toLongOrNull()
                            ?: throw DslParseException("Invalid integer literal '${t.text}'", t.line),
                    )
                }
                DslTokenKind.TRUE -> {
                    advance()
                    DslBool(true)
                }
                DslTokenKind.FALSE -> {
                    advance()
                    DslBool(false)
                }
                DslTokenKind.IDENT -> {
                    // Three cases: call (IDENT '('), member ref (IDENT '.' IDENT),
                    // or a bare val-handle identifier.
                    if (pos + 1 < tokens.size && tokens[pos + 1].kind == DslTokenKind.LPAREN) {
                        return parseCall()
                    }
                    if (pos + 1 < tokens.size && tokens[pos + 1].kind == DslTokenKind.DOT) {
                        val qualTok = advance() // qualifier
                        advance() // '.'
                        val memberTok = expect(DslTokenKind.IDENT, "Expected member name after '${qualTok.text}.'")
                        // Reject further dotting — no `A.B.C`, no `x.foo()` method chains.
                        if (check(DslTokenKind.DOT) || check(DslTokenKind.LPAREN)) {
                            throw DslParseException(
                                "Chained member access / method calls are not part of the interpreter DSL grammar " +
                                    "(only enum member references like 'Visibility.PUBLIC' are allowed)",
                                peek().line,
                            )
                        }
                        return DslMemberRef(qualifier = qualTok.text, member = memberTok.text, line = qualTok.line)
                    }
                    val idTok = advance()
                    DslIdentifier(name = idTok.text, line = idTok.line)
                }
                else -> throw DslParseException("Unexpected token '${t.text}' in argument position", t.line)
            }
        }
    }
}
