package dev.kuml.expr

/**
 * Sealed hierarchy for the kUML typed expression AST.
 *
 * Covers the OCL-like subset used in SysML 2 guards, PAR constraints, and
 * V2.0.20a scope: guard expressions only (effect/action bodies are V2.0.20b).
 *
 * Grammar (informal, top-to-bottom precedence):
 *   expr     ::= or
 *   or       ::= and ('||' and)*
 *   and      ::= not ('&&' not)*
 *   not      ::= '!' not | compare
 *   compare  ::= add (('==' | '!=' | '<' | '<=' | '>' | '>=') add)?
 *   add      ::= mul (('+' | '-') mul)*
 *   mul      ::= unary (('*' | '/') unary)*
 *   unary    ::= '-' unary | primary
 *   primary  ::= literal | attrRef | funcCall | '(' expr ')'
 *   attrRef  ::= IDENT ('.' IDENT)*
 *   funcCall ::= IDENT '(' (expr (',' expr)*)? ')'
 *   literal  ::= INT | REAL | STRING | 'true' | 'false' | 'null'
 */
public sealed class KumlExpression

public data class BinaryOp(
    val op: BinaryOperator,
    val left: KumlExpression,
    val right: KumlExpression,
) : KumlExpression()

public data class UnaryOp(
    val op: UnaryOperator,
    val operand: KumlExpression,
) : KumlExpression()

public data class LiteralBool(
    val value: Boolean,
) : KumlExpression()

public data class LiteralInt(
    val value: Long,
) : KumlExpression()

public data class LiteralReal(
    val value: Double,
) : KumlExpression()

public data class LiteralString(
    val value: String,
) : KumlExpression()

public object LiteralNull : KumlExpression()

/** Attribute path: `self.x.y` → `path = ["self", "x", "y"]` */
public data class AttributeRef(
    val path: List<String>,
) : KumlExpression()

public data class FunctionCall(
    val name: String,
    val args: List<KumlExpression>,
) : KumlExpression()

public enum class BinaryOperator { OR, AND, EQ, NEQ, LT, LTE, GT, GTE, ADD, SUB, MUL, DIV }

public enum class UnaryOperator { NOT, NEG }
