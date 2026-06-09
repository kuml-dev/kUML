package dev.kuml.expr

/**
 * Sealed hierarchy for side-effecting statements in kUML models.
 *
 * Distinct from [KumlExpression] because effects mutate state or emit
 * calls, whereas expressions are pure. A sequence of effects is parsed
 * from raw action-body / entry-action / effect strings using `;` as
 * separator.
 *
 * V2.0.20b grammar (informal):
 *   effect    ::= call | assign
 *   call      ::= attrPath '(' argList ')'       // e.g. relay.heat(true)
 *   assign    ::= attrPath '=' expr               // e.g. targetTemp = 21.0
 *   attrPath  ::= IDENT ('.' IDENT)*
 *   argList   ::= (expr (',' expr)*)?
 *
 * Multiple effects in one string are separated by `;`.
 * A bare expression (without assignment or call syntax) is wrapped in
 * [ExpressionEffect] — treats raw boolean guards that are also valid
 * expressions as effects (defensive parse).
 */
public sealed class KumlEffect

/**
 * A method/function call on a (possibly dotted) receiver.
 *
 * `relay.heat(true)` → `CallEffect(["relay", "heat"], [LiteralBool(true)])`
 * `log.info('done')` → `CallEffect(["log", "info"], [LiteralString("done")])`
 */
public data class CallEffect(
    val receiver: List<String>,
    val args: List<KumlExpression>,
) : KumlEffect()

/**
 * An assignment to a (possibly dotted) target path.
 *
 * `targetTemp = 21.0` → `AssignEffect(["targetTemp"], LiteralReal(21.0))`
 * `a.b = x + 1`       → `AssignEffect(["a", "b"], BinaryOp(ADD, …, …))`
 */
public data class AssignEffect(
    val target: List<String>,
    val value: KumlExpression,
) : KumlEffect()

/**
 * A bare expression used as a statement (defensive fallback).
 *
 * `42`    → `ExpressionEffect(LiteralInt(42))`
 * `valid` → `ExpressionEffect(AttributeRef(["valid"]))`
 */
public data class ExpressionEffect(
    val expr: KumlExpression,
) : KumlEffect()
