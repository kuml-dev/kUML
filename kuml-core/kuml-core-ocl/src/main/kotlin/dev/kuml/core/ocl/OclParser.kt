package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression

internal class OclParser(
    private val tokens: List<OclToken>,
) {
    private var pos = 0

    private fun peek(): OclToken = tokens.getOrElse(pos) { OclToken.Eof }

    private fun consume(): OclToken = tokens.getOrElse(pos++) { OclToken.Eof }

    private fun expect(token: OclToken) {
        val t = consume()
        if (t != token) throw OclEvaluationException("Expected $token but got $t at position ${pos - 1}")
    }

    private fun matchIdent(name: String): Boolean = peek() is OclToken.Ident && (peek() as OclToken.Ident).name == name

    internal fun parse(): OclExpression =
        parseExpr().also {
            if (peek() != OclToken.Eof) throw OclEvaluationException("Unexpected token after expression: ${peek()}")
        }

    /**
     * Top-level expression entry point. `let ... in ...` binds weaker than
     * `implies` (it wraps the whole rest of the expression), so it is handled
     * above [parseImplies] in the precedence chain.
     */
    private fun parseExpr(): OclExpression =
        when {
            matchIdent("let") -> parseLet()
            matchIdent("if") -> parseIf()
            else -> parseImplies()
        }

    private fun parseLet(): OclExpression {
        consume() // 'let'
        val name =
            (consume() as? OclToken.Ident)?.name
                ?: throw OclEvaluationException("Expected identifier after 'let'")
        val op = peek()
        if (op !is OclToken.Op || op.sym != "=") {
            throw OclEvaluationException("Expected '=' in let-expression but got $op")
        }
        consume()
        val initExpr = parseExpr()
        if (!matchIdent("in")) throw OclEvaluationException("Expected 'in' in let-expression but got ${peek()}")
        consume()
        val body = parseExpr()
        return OclExpression.LetExpr(name, initExpr, body)
    }

    private fun parseIf(): OclExpression {
        consume() // 'if'
        val cond = parseExpr()
        if (!matchIdent("then")) throw OclEvaluationException("Expected 'then' but got ${peek()}")
        consume()
        val thenExpr = parseExpr()
        if (!matchIdent("else")) throw OclEvaluationException("Expected 'else' but got ${peek()}")
        consume()
        val elseExpr = parseExpr()
        if (!matchIdent("endif")) throw OclEvaluationException("Expected 'endif' but got ${peek()}")
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

    private fun parseNot(): OclExpression {
        if (matchIdent("not")) {
            consume()
            return OclExpression.UnaryOp("not", parseNot())
        }
        return parseCompare()
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

    private fun parseUnary(): OclExpression {
        if (peek() is OclToken.Op && (peek() as OclToken.Op).sym == "-") {
            consume()
            return OclExpression.UnaryOp("-", parseUnary())
        }
        return parsePostfix()
    }

    private fun parsePostfix(): OclExpression {
        var expr = parsePrimary()
        while (true) {
            expr =
                when {
                    peek() == OclToken.Dot -> {
                        consume()
                        val name =
                            (consume() as? OclToken.Ident)?.name
                                ?: throw OclEvaluationException("Expected property name after '.'")
                        OclExpression.Navigate(expr, name)
                    }
                    peek() == OclToken.Arrow -> {
                        consume()
                        val op =
                            (consume() as? OclToken.Ident)?.name
                                ?: throw OclEvaluationException("Expected collection op name after '->'")
                        expect(OclToken.LParen)
                        parseCollectionOp(expr, op)
                    }
                    else -> return expr
                }
        }
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
        val iterVar =
            (consume() as? OclToken.Ident)?.name
                ?: throw OclEvaluationException("Expected iterator variable in iterate(...)")
        expect(OclToken.Semicolon)
        val accVar =
            (consume() as? OclToken.Ident)?.name
                ?: throw OclEvaluationException("Expected accumulator variable in iterate(...)")
        val eqOp = peek()
        if (eqOp !is OclToken.Op || eqOp.sym != "=") {
            throw OclEvaluationException("Expected '=' after accumulator variable in iterate(...) but got $eqOp")
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
            else -> throw OclEvaluationException("Unexpected token in primary: $t")
        }
}
