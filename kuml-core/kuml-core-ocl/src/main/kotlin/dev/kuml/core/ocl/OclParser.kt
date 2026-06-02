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
        parseImplies().also {
            if (peek() != OclToken.Eof) throw OclEvaluationException("Unexpected token after expression: ${peek()}")
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
        // Check for empty arg list
        if (peek() == OclToken.RParen) {
            consume()
            return OclExpression.CollectionOp(receiver, op)
        }
        // Check for lambda arg: IDENT '|' expr
        if (peek() is OclToken.Ident && tokens.getOrElse(pos + 1) { OclToken.Eof } == OclToken.Pipe) {
            val varName = (consume() as OclToken.Ident).name
            consume() // consume '|'
            val body = parseImplies()
            expect(OclToken.RParen)
            return OclExpression.CollectionOp(receiver, op, bindingVar = varName, body = body)
        }
        // Regular arg
        val arg = parseImplies()
        val args = mutableListOf(arg)
        while (peek() == OclToken.Comma) {
            consume()
            args += parseImplies()
        }
        expect(OclToken.RParen)
        return OclExpression.CollectionOp(receiver, op, args = args)
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
                val e = parseImplies()
                expect(OclToken.RParen)
                e
            }
            else -> throw OclEvaluationException("Unexpected token in primary: $t")
        }
}
