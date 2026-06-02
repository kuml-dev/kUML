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
            is OclExpression.BinaryOp -> evalBinaryOp(expr, env)
            is OclExpression.UnaryOp -> evalUnaryOp(expr, env)
        }

    @Suppress("UNCHECKED_CAST")
    private fun evalCollectionOp(
        expr: OclExpression.CollectionOp,
        env: Map<String, Any?>,
    ): Any? {
        val coll = eval(expr.receiver, env) as? List<*> ?: emptyList<Any?>()
        return when (expr.op) {
            "size" -> coll.size
            "isEmpty" -> coll.isEmpty()
            "notEmpty" -> coll.isNotEmpty()
            "includes" -> coll.contains(eval(expr.args.first(), env))
            "forAll" ->
                coll.all { item ->
                    val newEnv = env + mapOf(expr.bindingVar!! to item)
                    eval(expr.body!!, newEnv) as? Boolean ?: false
                }
            "exists" ->
                coll.any { item ->
                    val newEnv = env + mapOf(expr.bindingVar!! to item)
                    eval(expr.body!!, newEnv) as? Boolean ?: false
                }
            else -> throw OclEvaluationException("Unknown collection operation: ${expr.op}")
        }
    }

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
                    l is Int && r is Int -> l + r
                    l is String && r is String -> l + r
                    else -> throw OclEvaluationException("Cannot apply '+' to $l and $r")
                }
            "-" ->
                (l as? Int ?: throw OclEvaluationException("'-' requires Int")) -
                    (r as? Int ?: throw OclEvaluationException("'-' requires Int"))
            "*" ->
                (l as? Int ?: throw OclEvaluationException("'*' requires Int")) *
                    (r as? Int ?: throw OclEvaluationException("'*' requires Int"))
            "/" -> {
                val divisor = r as? Int ?: throw OclEvaluationException("'/' requires Int")
                if (divisor == 0) throw OclEvaluationException("Division by zero")
                (l as? Int ?: throw OclEvaluationException("'/' requires Int")) / divisor
            }
            else -> throw OclEvaluationException("Unknown binary op: ${expr.op}")
        }
    }

    private fun compareValues(
        l: Any?,
        r: Any?,
    ): Int {
        if (l is Int && r is Int) return l.compareTo(r)
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
            "-" -> -(operand as? Int ?: throw OclEvaluationException("unary '-' requires Int"))
            else -> throw OclEvaluationException("Unknown unary op: ${expr.op}")
        }
    }
}
