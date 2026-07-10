package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression

internal class OclParser(
    private val tokens: List<OclToken>,
    /**
     * Parallel token positions (V3.2.23), `positions[i]` is the source
     * position of `tokens[i]`. Defaults to empty — the vast majority of
     * call sites only need the parsed AST and construct `OclParser` directly
     * from [OclLexer.tokenize], which discards positions. Callers that need
     * [KumlViolation.sourcePosition] use [OclLexer.tokenizeWithPositions] and
     * pass the resulting list here instead.
     */
    private val positions: List<OclPosition> = emptyList(),
) {
    private var pos = 0

    /**
     * Current recursive-descent depth, tracked via [guardedRecursion]. Grammar
     * parens are transparent in the AST (`parsePrimary`'s `LParen` branch
     * discards the wrapping — see [parsePrimary]), so a chain of nested
     * parens produces *no* extra [dev.kuml.core.ocl.ast.OclExpression] depth
     * but *does* recurse through [parseExpr] once per paren. Without a guard
     * here, an input well under [OclSyntax.MAX_EXPRESSION_LENGTH] chars (e.g.
     * ~2000 nested `(`) drives this recursive-descent parser to a
     * [StackOverflowError] long before [OclSyntax.typeCheck]'s post-parse
     * `nodeCount`/`depth` check ([OclSyntax.MAX_NESTING_DEPTH]) ever runs —
     * silently defeating that guard. The same applies to `not`/unary `-`
     * chains, which recurse directly in [parseNot]/[parseUnary] without going
     * through [parseExpr] at all.
     */
    private var recursionDepth = 0

    private fun peek(): OclToken = tokens.getOrElse(pos) { OclToken.Eof }

    private fun consume(): OclToken = tokens.getOrElse(pos++) { OclToken.Eof }

    /** Position of the token at [pos], or `null` if [positions] was not supplied. */
    private fun currentPosition(): OclPosition? = positions.getOrNull(pos)

    /**
     * Wraps every unbounded-recursion entry point ([parseExpr], [parseNot],
     * [parseUnary]) with a depth cap shared with [OclSyntax]'s post-parse
     * complexity guard ([OclSyntax.MAX_NESTING_DEPTH]), so a
     * [StackOverflowError] can never occur before that guard gets a chance to
     * reject the expression cleanly. Throws [OclEvaluationException] — a type
     * every caller of [OclParser.parse] already catches — instead of letting
     * the JVM stack overflow.
     */
    private fun <T> guardedRecursion(block: () -> T): T {
        recursionDepth++
        if (recursionDepth > OclSyntax.MAX_NESTING_DEPTH) {
            throw OclEvaluationException(
                "expression too complex (nesting exceeds ${OclSyntax.MAX_NESTING_DEPTH} levels)",
                position = currentPosition(),
            )
        }
        try {
            return block()
        } finally {
            recursionDepth--
        }
    }

    private fun expect(token: OclToken) {
        val at = currentPosition()
        val t = consume()
        if (t != token) {
            throw OclEvaluationException("Expected $token but got $t at position ${pos - 1}", position = at)
        }
    }

    private fun matchIdent(name: String): Boolean = peek() is OclToken.Ident && (peek() as OclToken.Ident).name == name

    internal fun parse(): OclExpression =
        parseExpr().also {
            if (peek() != OclToken.Eof) {
                throw OclEvaluationException(
                    "Unexpected token after expression: ${peek()}",
                    position = currentPosition(),
                )
            }
        }

    /**
     * Top-level expression entry point. `let ... in ...` binds weaker than
     * `implies` (it wraps the whole rest of the expression), so it is handled
     * above [parseImplies] in the precedence chain.
     */
    private fun parseExpr(): OclExpression =
        guardedRecursion {
            when {
                matchIdent("let") -> parseLet()
                matchIdent("if") -> parseIf()
                else -> parseImplies()
            }
        }

    private fun parseLet(): OclExpression {
        consume() // 'let'
        val identPos = currentPosition()
        val name =
            (consume() as? OclToken.Ident)?.name
                ?: throw OclEvaluationException("Expected identifier after 'let'", position = identPos)
        val op = peek()
        if (op !is OclToken.Op || op.sym != "=") {
            throw OclEvaluationException("Expected '=' in let-expression but got $op", position = currentPosition())
        }
        consume()
        val initExpr = parseExpr()
        if (!matchIdent("in")) {
            throw OclEvaluationException("Expected 'in' in let-expression but got ${peek()}", position = currentPosition())
        }
        consume()
        val body = parseExpr()
        return OclExpression.LetExpr(name, initExpr, body)
    }

    private fun parseIf(): OclExpression {
        consume() // 'if'
        val cond = parseExpr()
        if (!matchIdent("then")) {
            throw OclEvaluationException("Expected 'then' but got ${peek()}", position = currentPosition())
        }
        consume()
        val thenExpr = parseExpr()
        if (!matchIdent("else")) {
            throw OclEvaluationException("Expected 'else' but got ${peek()}", position = currentPosition())
        }
        consume()
        val elseExpr = parseExpr()
        if (!matchIdent("endif")) {
            throw OclEvaluationException("Expected 'endif' but got ${peek()}", position = currentPosition())
        }
        consume()
        return OclExpression.IfExpr(cond, thenExpr, elseExpr)
    }

    private fun parseImplies(): OclExpression {
        var left = parseOr()
        if (matchIdent("implies")) {
            consume()
            left = OclExpression.BinaryOp("implies", left, parseOr())
        }
        return left
    }

    private fun parseOr(): OclExpression {
        var left = parseAnd()
        while (matchIdent("or")) {
            consume()
            left = OclExpression.BinaryOp("or", left, parseAnd())
        }
        return left
    }

    private fun parseAnd(): OclExpression {
        var left = parseNot()
        while (matchIdent("and")) {
            consume()
            left = OclExpression.BinaryOp("and", left, parseNot())
        }
        return left
    }

    private fun parseNot(): OclExpression =
        guardedRecursion {
            if (matchIdent("not")) {
                consume()
                OclExpression.UnaryOp("not", parseNot())
            } else {
                parseCompare()
            }
        }

    private fun parseCompare(): OclExpression {
        val left = parseAdd()
        val op = peek()
        if (op is OclToken.Op && op.sym in setOf("=", "<>", "<", ">", "<=", ">=")) {
            consume()
            return OclExpression.BinaryOp(op.sym, left, parseAdd())
        }
        return left
    }

    private fun parseAdd(): OclExpression {
        var left = parseMul()
        while (peek() is OclToken.Op && (peek() as OclToken.Op).sym in setOf("+", "-")) {
            val op = (consume() as OclToken.Op).sym
            left = OclExpression.BinaryOp(op, left, parseMul())
        }
        return left
    }

    private fun parseMul(): OclExpression {
        var left = parseUnary()
        while (peek() is OclToken.Op && (peek() as OclToken.Op).sym in setOf("*", "/")) {
            val op = (consume() as OclToken.Op).sym
            left = OclExpression.BinaryOp(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): OclExpression =
        guardedRecursion {
            if (peek() is OclToken.Op && (peek() as OclToken.Op).sym == "-") {
                consume()
                OclExpression.UnaryOp("-", parseUnary())
            } else {
                parsePostfix()
            }
        }

    /** OCL type operations, callable via dot-navigation: `self.oclIsTypeOf(Order)`. */
    private val typeOpNames =
        setOf("oclIsTypeOf", "oclIsKindOf", "oclAsType", "oclIsUndefined", "oclIsInvalid")

    /** Type operations that take a single type-name argument (vs. zero-arg). */
    private val typeOpsWithArg = setOf("oclIsTypeOf", "oclIsKindOf", "oclAsType")

    private fun parsePostfix(): OclExpression {
        var expr = parsePrimary()
        while (true) {
            expr =
                when {
                    peek() == OclToken.Dot -> {
                        consume()
                        val propPos = currentPosition()
                        val name =
                            (consume() as? OclToken.Ident)?.name
                                ?: throw OclEvaluationException(
                                    "Expected property name after '.'",
                                    position = propPos,
                                )
                        when {
                            name in typeOpNames -> parseTypeOp(expr, name)
                            peek() == OclToken.LParen -> parseOperationCall(expr, name)
                            else -> OclExpression.Navigate(expr, name)
                        }
                    }
                    peek() == OclToken.Arrow -> {
                        consume()
                        val opPos = currentPosition()
                        val op =
                            (consume() as? OclToken.Ident)?.name
                                ?: throw OclEvaluationException(
                                    "Expected collection op name after '->'",
                                    position = opPos,
                                )
                        expect(OclToken.LParen)
                        parseCollectionOp(expr, op)
                    }
                    peek() == OclToken.AtPre -> {
                        consume()
                        OclExpression.AtPre(expr)
                    }
                    else -> return expr
                }
        }
    }

    /**
     * `receiver.name(arg1, arg2, ...)` — parses the parenthesized (possibly
     * empty) argument list of an [OclExpression.OperationCall].
     */
    private fun parseOperationCall(
        receiver: OclExpression,
        name: String,
    ): OclExpression {
        consume() // '('
        if (peek() == OclToken.RParen) {
            consume()
            return OclExpression.OperationCall(receiver, name)
        }
        val args = mutableListOf(parseExpr())
        while (peek() == OclToken.Comma) {
            consume()
            args += parseExpr()
        }
        expect(OclToken.RParen)
        return OclExpression.OperationCall(receiver, name, args)
    }

    private fun parseTypeOp(
        receiver: OclExpression,
        op: String,
    ): OclExpression {
        expect(OclToken.LParen)
        val typeName =
            if (op in typeOpsWithArg) {
                val typeNamePos = currentPosition()
                (consume() as? OclToken.Ident)?.name
                    ?: throw OclEvaluationException("Expected type name in '$op(...)'", position = typeNamePos)
            } else {
                null
            }
        expect(OclToken.RParen)
        return OclExpression.TypeOp(receiver, op, typeName)
    }

    private fun parseCollectionOp(
        receiver: OclExpression,
        op: String,
    ): OclExpression {
        // `iterate(iterVar; accVar = accInit | body)` — dedicated two-variable form.
        if (op == "iterate") return parseIterate(receiver)
        // Check for empty arg list
        if (peek() == OclToken.RParen) {
            consume()
            return OclExpression.CollectionOp(receiver, op)
        }
        // Check for lambda arg: IDENT '|' expr
        if (peek() is OclToken.Ident && tokens.getOrElse(pos + 1) { OclToken.Eof } == OclToken.Pipe) {
            val varName = (consume() as OclToken.Ident).name
            consume() // consume '|'
            val body = parseExpr()
            expect(OclToken.RParen)
            return OclExpression.CollectionOp(receiver, op, bindingVar = varName, body = body)
        }
        // Regular arg
        val arg = parseExpr()
        val args = mutableListOf(arg)
        while (peek() == OclToken.Comma) {
            consume()
            args += parseExpr()
        }
        expect(OclToken.RParen)
        return OclExpression.CollectionOp(receiver, op, args = args)
    }

    private fun parseIterate(receiver: OclExpression): OclExpression {
        val iterVarPos = currentPosition()
        val iterVar =
            (consume() as? OclToken.Ident)?.name
                ?: throw OclEvaluationException(
                    "Expected iterator variable in iterate(...)",
                    position = iterVarPos,
                )
        expect(OclToken.Semicolon)
        val accVarPos = currentPosition()
        val accVar =
            (consume() as? OclToken.Ident)?.name
                ?: throw OclEvaluationException(
                    "Expected accumulator variable in iterate(...)",
                    position = accVarPos,
                )
        val eqOp = peek()
        if (eqOp !is OclToken.Op || eqOp.sym != "=") {
            throw OclEvaluationException(
                "Expected '=' after accumulator variable in iterate(...) but got $eqOp",
                position = currentPosition(),
            )
        }
        consume()
        val accInit = parseExpr()
        expect(OclToken.Pipe)
        val body = parseExpr()
        expect(OclToken.RParen)
        return OclExpression.IterateExpr(receiver, iterVar, accVar, accInit, body)
    }

    private fun parsePrimary(): OclExpression =
        when (val t = peek()) {
            OclToken.Self -> {
                consume()
                OclExpression.Self
            }
            OclToken.NullLit -> {
                consume()
                OclExpression.NullLit
            }
            OclToken.TrueLit -> {
                consume()
                OclExpression.BoolLit(true)
            }
            OclToken.FalseLit -> {
                consume()
                OclExpression.BoolLit(false)
            }
            is OclToken.IntLit -> {
                consume()
                OclExpression.IntLit(t.value)
            }
            is OclToken.RealLit -> {
                consume()
                OclExpression.RealLit(t.value)
            }
            is OclToken.StrLit -> {
                consume()
                OclExpression.StrLit(t.value)
            }
            is OclToken.Ident -> {
                consume()
                OclExpression.VarRef(t.name)
            }
            OclToken.LParen -> {
                consume()
                val e = parseExpr()
                expect(OclToken.RParen)
                e
            }
            else -> throw OclEvaluationException("Unexpected token in primary: $t", position = currentPosition())
        }
}
