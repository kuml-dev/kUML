package dev.kuml.expr

/**
 * Type inference for the kUML typed expression AST (V2.0.20a).
 *
 * V2.0.20a scope: numeric arithmetic, boolean logic, comparison operators.
 * [AttributeRef] and [FunctionCall] against unknown names return
 * [KumlType.Unknown] — not an error here.  Full scope resolution is V2.0.20b.
 */
public object ExpressionTypeChecker {
    /**
     * Infers the type of [expr] given [env] (known attribute-name → type mappings).
     *
     * Returns a [KumlType.TypeError] if the expression is ill-typed; otherwise
     * a concrete [KumlType].
     */
    public fun infer(
        expr: KumlExpression,
        env: Map<String, KumlType> = emptyMap(),
    ): KumlType =
        when (expr) {
            is LiteralBool -> KumlType.Bool
            is LiteralInt -> KumlType.Int
            is LiteralReal -> KumlType.Real
            is LiteralString -> KumlType.Str
            is LiteralNull -> KumlType.Null

            is AttributeRef -> {
                // Single-segment lookup in env; multi-segment paths are Unknown in V2.0.20a.
                if (expr.path.size == 1) {
                    env[expr.path[0]] ?: KumlType.Unknown
                } else {
                    KumlType.Unknown
                }
            }

            is FunctionCall -> KumlType.Unknown // V2.0.20b adds function resolution

            is UnaryOp -> inferUnary(expr, env)

            is BinaryOp -> inferBinary(expr, env)
        }

    private fun inferUnary(
        expr: UnaryOp,
        env: Map<String, KumlType>,
    ): KumlType {
        val t = infer(expr.operand, env)
        return when (expr.op) {
            UnaryOperator.NOT ->
                when (t) {
                    is KumlType.Bool, KumlType.Unknown -> KumlType.Bool
                    else -> KumlType.TypeError("Operator '!' requires Bool, got $t")
                }
            UnaryOperator.NEG ->
                when (t) {
                    is KumlType.Int -> KumlType.Int
                    is KumlType.Real -> KumlType.Real
                    KumlType.Unknown -> KumlType.Unknown
                    else -> KumlType.TypeError("Unary '-' requires Int or Real, got $t")
                }
        }
    }

    private fun inferBinary(
        expr: BinaryOp,
        env: Map<String, KumlType>,
    ): KumlType {
        val lt = infer(expr.left, env)
        val rt = infer(expr.right, env)

        // Propagate existing type errors
        if (lt is KumlType.TypeError) return lt
        if (rt is KumlType.TypeError) return rt

        return when (expr.op) {
            BinaryOperator.OR, BinaryOperator.AND -> {
                when {
                    lt == KumlType.Unknown || rt == KumlType.Unknown -> KumlType.Bool
                    lt == KumlType.Bool && rt == KumlType.Bool -> KumlType.Bool
                    else ->
                        KumlType.TypeError(
                            "Operator '${expr.op.symbol}' requires Bool operands, got $lt and $rt",
                        )
                }
            }

            BinaryOperator.EQ, BinaryOperator.NEQ -> {
                // Equality is permissive — any comparable pair is fine.
                // Unknown operands are allowed.
                KumlType.Bool
            }

            BinaryOperator.LT, BinaryOperator.LTE, BinaryOperator.GT, BinaryOperator.GTE -> {
                when {
                    lt == KumlType.Unknown || rt == KumlType.Unknown -> KumlType.Bool
                    isNumeric(lt) && isNumeric(rt) -> KumlType.Bool
                    else ->
                        KumlType.TypeError(
                            "Comparison '${expr.op.symbol}' requires numeric operands, got $lt and $rt",
                        )
                }
            }

            BinaryOperator.ADD -> {
                when {
                    lt == KumlType.Unknown || rt == KumlType.Unknown -> KumlType.Unknown
                    lt == KumlType.Int && rt == KumlType.Int -> KumlType.Int
                    isNumeric(lt) && isNumeric(rt) -> KumlType.Real
                    lt == KumlType.Str && rt == KumlType.Str -> KumlType.Str
                    else -> KumlType.TypeError("Operator '+' cannot be applied to $lt and $rt")
                }
            }

            BinaryOperator.SUB, BinaryOperator.MUL, BinaryOperator.DIV -> {
                when {
                    lt == KumlType.Unknown || rt == KumlType.Unknown -> KumlType.Unknown
                    lt == KumlType.Int && rt == KumlType.Int -> KumlType.Int
                    isNumeric(lt) && isNumeric(rt) -> KumlType.Real
                    else ->
                        KumlType.TypeError(
                            "Operator '${expr.op.symbol}' requires numeric operands, got $lt and $rt",
                        )
                }
            }
        }
    }

    private fun isNumeric(t: KumlType): Boolean = t == KumlType.Int || t == KumlType.Real

    private val BinaryOperator.symbol: String
        get() =
            when (this) {
                BinaryOperator.OR -> "||"
                BinaryOperator.AND -> "&&"
                BinaryOperator.EQ -> "=="
                BinaryOperator.NEQ -> "!="
                BinaryOperator.LT -> "<"
                BinaryOperator.LTE -> "<="
                BinaryOperator.GT -> ">"
                BinaryOperator.GTE -> ">="
                BinaryOperator.ADD -> "+"
                BinaryOperator.SUB -> "-"
                BinaryOperator.MUL -> "*"
                BinaryOperator.DIV -> "/"
            }
}
