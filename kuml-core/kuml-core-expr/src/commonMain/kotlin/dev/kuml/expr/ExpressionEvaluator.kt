package dev.kuml.expr

/**
 * Tree-walking evaluator for the kUML typed expression AST (V2.0.20a).
 *
 * Context keys are plain attribute names. Multi-segment [AttributeRef] paths
 * (`a.b`) are not resolved in V2.0.20a — single-level context lookups only.
 * `a.b` with a Map in context["a"] returns null (documented behaviour; V2.0.20b
 * adds nested map traversal).
 */
public object ExpressionEvaluator {
    /**
     * Evaluates [expr] against [context] (attribute-name → Any? value).
     *
     * Returns `null` for [LiteralNull] and for [AttributeRef]s whose first
     * path segment is not in [context], or for multi-segment paths.
     *
     * @throws EvaluationException on type mismatch at runtime.
     */
    public fun evaluate(
        expr: KumlExpression,
        context: Map<String, Any?> = emptyMap(),
    ): Any? =
        when (expr) {
            is LiteralBool -> expr.value
            is LiteralInt -> expr.value
            is LiteralReal -> expr.value
            is LiteralString -> expr.value
            is LiteralNull -> null

            is AttributeRef -> resolveAttrRef(expr, context)

            is FunctionCall -> null // V2.0.20b adds function resolution

            is UnaryOp -> evalUnary(expr, context)

            is BinaryOp -> evalBinary(expr, context)
        }

    // ── AttributeRef resolution ───────────────────────────────────────────────

    private fun resolveAttrRef(
        ref: AttributeRef,
        context: Map<String, Any?>,
    ): Any? {
        if (ref.path.isEmpty()) return null
        var current: Any? = context[ref.path[0]]
        for (i in 1 until ref.path.size) {
            // Multi-segment: try to navigate into maps (best-effort; null if not a map)
            current =
                when (val c = current) {
                    is Map<*, *> -> c[ref.path[i]]
                    else -> null
                }
        }
        return current
    }

    // ── Unary operations ──────────────────────────────────────────────────────

    private fun evalUnary(
        expr: UnaryOp,
        context: Map<String, Any?>,
    ): Any? {
        val v = evaluate(expr.operand, context)
        return when (expr.op) {
            UnaryOperator.NOT -> {
                val b =
                    v as? Boolean
                        ?: throw EvaluationException("Operator '!' requires Boolean, got ${v?.let { it::class.simpleName } ?: "null"}")
                !b
            }
            UnaryOperator.NEG ->
                when (v) {
                    is Long -> -v
                    is Int -> -v.toLong()
                    is Double -> -v
                    is Float -> -v.toDouble()
                    null -> throw EvaluationException("Unary '-' applied to null")
                    else -> throw EvaluationException("Unary '-' requires numeric value, got ${v::class.simpleName}")
                }
        }
    }

    // ── Binary operations ─────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    private fun evalBinary(
        expr: BinaryOp,
        context: Map<String, Any?>,
    ): Any? {
        // Short-circuit evaluation for logical operators
        if (expr.op == BinaryOperator.OR) {
            val l = evaluate(expr.left, context)
            val lb =
                l as? Boolean
                    ?: throw EvaluationException(
                        "Operator '||' requires Boolean left operand, got ${l?.let { it::class.simpleName } ?: "null"}",
                    )
            if (lb) return true
            val r = evaluate(expr.right, context)
            return r as? Boolean
                ?: throw EvaluationException(
                    "Operator '||' requires Boolean right operand, got ${r?.let { it::class.simpleName } ?: "null"}",
                )
        }
        if (expr.op == BinaryOperator.AND) {
            val l = evaluate(expr.left, context)
            val lb =
                l as? Boolean
                    ?: throw EvaluationException(
                        "Operator '&&' requires Boolean left operand, got ${l?.let { it::class.simpleName } ?: "null"}",
                    )
            if (!lb) return false
            val r = evaluate(expr.right, context)
            return r as? Boolean
                ?: throw EvaluationException(
                    "Operator '&&' requires Boolean right operand, got ${r?.let { it::class.simpleName } ?: "null"}",
                )
        }

        val left = evaluate(expr.left, context)
        val right = evaluate(expr.right, context)

        return when (expr.op) {
            BinaryOperator.EQ -> evalEquals(left, right)
            BinaryOperator.NEQ -> !evalEquals(left, right)

            BinaryOperator.LT, BinaryOperator.LTE, BinaryOperator.GT, BinaryOperator.GTE ->
                evalCompare(expr.op, left, right)

            BinaryOperator.ADD -> evalAdd(left, right)

            BinaryOperator.SUB, BinaryOperator.MUL, BinaryOperator.DIV ->
                evalArith(expr.op, left, right)

            BinaryOperator.OR, BinaryOperator.AND ->
                error("Unreachable — handled above")
        }
    }

    private fun evalEquals(
        left: Any?,
        right: Any?,
    ): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false
        // Numeric cross-type equality
        if (left is Number && right is Number) return left.toDouble() == right.toDouble()
        return left == right
    }

    private fun evalCompare(
        op: BinaryOperator,
        left: Any?,
        right: Any?,
    ): Boolean {
        val l = toComparable(left) ?: throw EvaluationException("Comparison requires numeric values, got ${typeName(left)}")
        val r = toComparable(right) ?: throw EvaluationException("Comparison requires numeric values, got ${typeName(right)}")
        return when (op) {
            BinaryOperator.LT -> l < r
            BinaryOperator.LTE -> l <= r
            BinaryOperator.GT -> l > r
            BinaryOperator.GTE -> l >= r
            else -> error("Unreachable")
        }
    }

    private fun evalAdd(
        left: Any?,
        right: Any?,
    ): Any {
        // String concatenation
        if (left is String || right is String) {
            if (left is String && right is String) return left + right
            throw EvaluationException("Operator '+' cannot mix String and ${typeName(if (left !is String) left else right)}")
        }
        if (left is Number && right is Number) return addNumbers(left, right)
        throw EvaluationException("Operator '+' cannot be applied to ${typeName(left)} and ${typeName(right)}")
    }

    private fun evalArith(
        op: BinaryOperator,
        left: Any?,
        right: Any?,
    ): Any {
        val l =
            left as? Number
                ?: throw EvaluationException("Operator '${op.name}' requires numeric left operand, got ${typeName(left)}")
        val r =
            right as? Number
                ?: throw EvaluationException("Operator '${op.name}' requires numeric right operand, got ${typeName(right)}")
        return when (op) {
            BinaryOperator.SUB -> subNumbers(l, r)
            BinaryOperator.MUL -> mulNumbers(l, r)
            BinaryOperator.DIV -> divNumbers(l, r)
            else -> error("Unreachable")
        }
    }

    // ── Number helpers ────────────────────────────────────────────────────────

    private fun toComparable(v: Any?): Double? =
        when (v) {
            is Double -> v
            is Float -> v.toDouble()
            is Long -> v.toDouble()
            is Int -> v.toDouble()
            else -> null
        }

    private fun addNumbers(
        a: Number,
        b: Number,
    ): Number = if (a is Double || b is Double || a is Float || b is Float) a.toDouble() + b.toDouble() else a.toLong() + b.toLong()

    private fun subNumbers(
        a: Number,
        b: Number,
    ): Number = if (a is Double || b is Double || a is Float || b is Float) a.toDouble() - b.toDouble() else a.toLong() - b.toLong()

    private fun mulNumbers(
        a: Number,
        b: Number,
    ): Number = if (a is Double || b is Double || a is Float || b is Float) a.toDouble() * b.toDouble() else a.toLong() * b.toLong()

    private fun divNumbers(
        a: Number,
        b: Number,
    ): Number {
        if ((b is Long || b is Int) && b.toLong() == 0L) throw EvaluationException("Division by zero")
        return if (a is Double || b is Double || a is Float || b is Float) {
            a.toDouble() / b.toDouble()
        } else {
            a.toLong() / b.toLong()
        }
    }

    private fun typeName(v: Any?): String = v?.let { it::class.simpleName } ?: "null"
}

public class EvaluationException(
    message: String,
) : RuntimeException(message)
