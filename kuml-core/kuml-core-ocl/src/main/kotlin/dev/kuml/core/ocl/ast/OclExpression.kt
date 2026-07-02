package dev.kuml.core.ocl.ast

internal sealed interface OclExpression {
    data object Self : OclExpression

    data object NullLit : OclExpression

    data class IntLit(
        val v: Int,
    ) : OclExpression

    data class RealLit(
        val v: Double,
    ) : OclExpression

    data class StrLit(
        val v: String,
    ) : OclExpression

    data class BoolLit(
        val v: Boolean,
    ) : OclExpression

    data class VarRef(
        val name: String,
    ) : OclExpression

    data class Navigate(
        val receiver: OclExpression,
        val prop: String,
    ) : OclExpression

    data class CollectionOp(
        val receiver: OclExpression,
        val op: String,
        val args: List<OclExpression> = emptyList(),
        val bindingVar: String? = null,
        val body: OclExpression? = null,
    ) : OclExpression

    /**
     * `iterate(iterVar; accVar = accInit | body)` — the general OCL accumulator
     * iterator. All other collection iterators can conceptually be expressed in
     * terms of `iterate`, but are implemented directly in the evaluator for
     * clarity and performance; `iterate` itself is kept as an explicit AST node
     * because its two-variable binding shape does not fit [CollectionOp].
     */
    data class IterateExpr(
        val receiver: OclExpression,
        val iterVar: String,
        val accVar: String,
        val accInit: OclExpression,
        val body: OclExpression,
    ) : OclExpression

    data class LetExpr(
        val name: String,
        val initExpr: OclExpression,
        val body: OclExpression,
    ) : OclExpression

    data class IfExpr(
        val cond: OclExpression,
        val thenExpr: OclExpression,
        val elseExpr: OclExpression,
    ) : OclExpression

    data class BinaryOp(
        val op: String,
        val left: OclExpression,
        val right: OclExpression,
    ) : OclExpression

    data class UnaryOp(
        val op: String,
        val operand: OclExpression,
    ) : OclExpression

    /**
     * OCL type operations — `oclIsTypeOf(T)`, `oclIsKindOf(T)`, `oclAsType(T)`,
     * `oclIsUndefined()`, `oclIsInvalid()`.
     *
     * [typeName] is the referenced classifier name (e.g. `Order`); `null` for
     * the two zero-arg operations ([op] `"oclIsUndefined"` / `"oclIsInvalid"`),
     * which do not take a type argument.
     */
    data class TypeOp(
        val receiver: OclExpression,
        val op: String,
        val typeName: String? = null,
    ) : OclExpression

    /**
     * OCL `expr@pre` — references the value of [receiver] as it was at
     * operation entry. Only meaningful inside a `post:` constraint body; see
     * [dev.kuml.core.ocl.OclEvaluator] for snapshot-resolution semantics.
     */
    data class AtPre(
        val receiver: OclExpression,
    ) : OclExpression
}
