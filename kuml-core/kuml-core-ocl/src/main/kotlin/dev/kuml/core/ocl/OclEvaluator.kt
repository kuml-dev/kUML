package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression

internal class OclEvaluator(
    private val self: Any,
) {
    internal fun eval(
        expr: OclExpression,
        env: Map<String, Any?> = mapOf("self" to self),
    ): Any? =
        when (expr) {
            is OclExpression.Self -> env["self"]
            is OclExpression.NullLit -> null
            is OclExpression.IntLit -> expr.v
            is OclExpression.RealLit -> expr.v
            is OclExpression.StrLit -> expr.v
            is OclExpression.BoolLit -> expr.v
            is OclExpression.VarRef -> env[expr.name]
            is OclExpression.Navigate -> {
                val recv =
                    eval(expr.receiver, env)
                        ?: throw OclEvaluationException("Cannot navigate '${expr.prop}' on null")
                UmlPropertyAccessor.get(recv, expr.prop)
            }
            is OclExpression.CollectionOp -> evalCollectionOp(expr, env)
            is OclExpression.IterateExpr -> evalIterate(expr, env)
            is OclExpression.LetExpr -> {
                val value = eval(expr.initExpr, env)
                eval(expr.body, env + mapOf(expr.name to value))
            }
            is OclExpression.IfExpr -> {
                val cond =
                    eval(expr.cond, env) as? Boolean
                        ?: throw OclEvaluationException("'if' condition requires Boolean")
                if (cond) eval(expr.thenExpr, env) else eval(expr.elseExpr, env)
            }
            is OclExpression.BinaryOp -> evalBinaryOp(expr, env)
            is OclExpression.UnaryOp -> evalUnaryOp(expr, env)
        }

    @Suppress("UNCHECKED_CAST")
    private fun evalCollectionOp(
        expr: OclExpression.CollectionOp,
        env: Map<String, Any?>,
    ): Any? {
        val coll = eval(expr.receiver, env) as? List<*> ?: emptyList<Any?>()

        fun bodyOf(item: Any?): Any? {
            val newEnv = env + mapOf((expr.bindingVar ?: "") to item)
            return eval(expr.body!!, newEnv)
        }

        fun boolBodyOf(item: Any?): Boolean = bodyOf(item) as? Boolean ?: false

        return when (expr.op) {
            "size" -> coll.size
            "isEmpty" -> coll.isEmpty()
            "notEmpty" -> coll.isNotEmpty()
            "includes" -> coll.contains(eval(expr.args.first(), env))
            "excludes" -> !coll.contains(eval(expr.args.first(), env))
            "forAll" -> coll.all { boolBodyOf(it) }
            "exists" -> coll.any { boolBodyOf(it) }
            "select" -> if (expr.body != null) coll.filter { boolBodyOf(it) } else coll
            "reject" -> if (expr.body != null) coll.filterNot { boolBodyOf(it) } else coll
            // OCL `collect` conceptually yields a Bag (duplicates preserved) — a flat
            // List already models that; nested List results are flattened one level.
            "collect" ->
                coll.flatMap { item ->
                    val r = if (expr.body != null) bodyOf(item) else item
                    if (r is List<*>) r else listOf(r)
                }
            "any" -> coll.firstOrNull { boolBodyOf(it) }
            "one" -> coll.count { boolBodyOf(it) } == 1
            "isUnique" -> {
                val mapped = coll.map { if (expr.body != null) bodyOf(it) else it }
                mapped.size == mapped.toSet().size
            }
            "sortedBy" ->
                coll.sortedWith(
                    compareBy(nullsFirst()) { item ->
                        val v = bodyOf(item)
                        @Suppress("UNCHECKED_CAST")
                        (v as? Comparable<Any?>)
                            ?: throw OclEvaluationException("'sortedBy' body must evaluate to a Comparable, got $v")
                    },
                )
            "sum" -> {
                val values = coll.map { if (expr.body != null) bodyOf(it) else it }
                val total = values.fold(0.0) { acc, v -> acc + toNumeric(v) }
                if (values.all { numericIsInt(it) }) total.toInt() else total
            }
            "count" -> coll.count { it == eval(expr.args.first(), env) }
            "including" -> coll + eval(expr.args.first(), env)
            "excluding" -> coll.filterNot { it == eval(expr.args.first(), env) }
            "union" -> coll + ((eval(expr.args.first(), env) as? List<*>) ?: emptyList<Any?>())
            "intersection" -> {
                val other = (eval(expr.args.first(), env) as? List<*>) ?: emptyList<Any?>()
                coll.filter { other.contains(it) }
            }
            "first" -> coll.firstOrNull() ?: throw OclEvaluationException("'first' called on empty collection")
            "last" -> coll.lastOrNull() ?: throw OclEvaluationException("'last' called on empty collection")
            "asSet" -> coll.distinct()
            "asSequence" -> coll
            else -> throw OclEvaluationException("Unknown collection operation: ${expr.op}")
        }
    }

    private fun evalIterate(
        expr: OclExpression.IterateExpr,
        env: Map<String, Any?>,
    ): Any? {
        val coll = eval(expr.receiver, env) as? List<*> ?: emptyList<Any?>()
        var acc = eval(expr.accInit, env)
        for (item in coll) {
            val newEnv = env + mapOf(expr.iterVar to item, expr.accVar to acc)
            acc = eval(expr.body, newEnv)
        }
        return acc
    }

    private fun numericIsInt(v: Any?): Boolean = v is Int

    private fun evalBinaryOp(
        expr: OclExpression.BinaryOp,
        env: Map<String, Any?>,
    ): Any? {
        val l = eval(expr.left, env)
        val r = eval(expr.right, env)
        return when (expr.op) {
            "=" -> l == r
            "<>" -> l != r
            "<" -> compareValues(l, r) < 0
            ">" -> compareValues(l, r) > 0
            "<=" -> compareValues(l, r) <= 0
            ">=" -> compareValues(l, r) >= 0
            "and" -> (l as? Boolean == true) && (r as? Boolean == true)
            "or" -> (l as? Boolean == true) || (r as? Boolean == true)
            "implies" -> !(l as? Boolean == true) || (r as? Boolean == true)
            "+" ->
                when {
                    l is String && r is String -> l + r
                    isNumeric(l) && isNumeric(r) -> arithResult(l, r, toNumeric(l) + toNumeric(r))
                    else -> throw OclEvaluationException("Cannot apply '+' to $l and $r")
                }
            "-" ->
                when {
                    isNumeric(l) && isNumeric(r) -> arithResult(l, r, toNumeric(l) - toNumeric(r))
                    else -> throw OclEvaluationException("Cannot apply '-' to $l and $r")
                }
            "*" ->
                when {
                    isNumeric(l) && isNumeric(r) -> arithResult(l, r, toNumeric(l) * toNumeric(r))
                    else -> throw OclEvaluationException("Cannot apply '*' to $l and $r")
                }
            "/" -> {
                if (!isNumeric(l) || !isNumeric(r)) {
                    throw OclEvaluationException("'/' requires numeric operands, got $l and $r")
                }
                val divisor = toNumeric(r)
                if (divisor == 0.0) throw OclEvaluationException("Division by zero")
                // OCL '/' is always real division, regardless of operand types.
                toNumeric(l) / divisor
            }
            else -> throw OclEvaluationException("Unknown binary op: ${expr.op}")
        }
    }

    private fun isNumeric(v: Any?): Boolean = v is Int || v is Double

    private fun toNumeric(v: Any?): Double =
        when (v) {
            is Int -> v.toDouble()
            is Double -> v
            else -> throw OclEvaluationException("Expected numeric value, got $v")
        }

    /**
     * Arithmetic result type follows OCL promotion rules: `Integer op Integer`
     * stays `Integer`, any `Real` operand promotes the result to `Real`.
     */
    private fun arithResult(
        l: Any?,
        r: Any?,
        result: Double,
    ): Any = if (l is Int && r is Int) result.toInt() else result

    private fun compareValues(
        l: Any?,
        r: Any?,
    ): Int {
        if (isNumeric(l) && isNumeric(r)) return toNumeric(l).compareTo(toNumeric(r))
        if (l is String && r is String) return l.compareTo(r)
        throw OclEvaluationException("Cannot compare $l and $r")
    }

    private fun evalUnaryOp(
        expr: OclExpression.UnaryOp,
        env: Map<String, Any?>,
    ): Any? {
        val operand = eval(expr.operand, env)
        return when (expr.op) {
            "not" -> !(operand as? Boolean ?: throw OclEvaluationException("'not' requires Boolean"))
            "-" ->
                when (operand) {
                    is Int -> -operand
                    is Double -> -operand
                    else -> throw OclEvaluationException("unary '-' requires a numeric operand")
                }
            else -> throw OclEvaluationException("Unknown unary op: ${expr.op}")
        }
    }
}
