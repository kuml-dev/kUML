package dev.kuml.core.ocl.ast

internal sealed interface OclExpression {
    data object Self : OclExpression

    data object NullLit : OclExpression

    data class IntLit(
        val v: Int,
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

    data class BinaryOp(
        val op: String,
        val left: OclExpression,
        val right: OclExpression,
    ) : OclExpression

    data class UnaryOp(
        val op: String,
        val operand: OclExpression,
    ) : OclExpression
}
