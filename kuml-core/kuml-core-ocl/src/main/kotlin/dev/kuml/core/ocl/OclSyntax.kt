package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression

/**
 * Public syntactic classification of one OCL lexeme, for editor highlighting.
 */
public enum class OclTokenKind {
    KEYWORD,
    IDENT,
    LITERAL,
    OPERATOR,
    PAREN,
    ERROR,
}

/**
 * One highlightable span of an OCL expression.
 *
 * @property start 0-based char offset, inclusive.
 * @property end 0-based char offset, exclusive (so `expr.substring(start, end)` is the lexeme text).
 * @property kind syntactic classification of the span.
 */
public data class HighlightToken(
    val start: Int,
    val end: Int,
    val kind: OclTokenKind,
)

/** Coarse scalar type of a scope variable, used by [OclSyntax.typeCheck]. */
public enum class OclType {
    BOOLEAN,
    INTEGER,
    REAL,
    STRING,
    OBJECT,
    COLLECTION,
    UNKNOWN,
}

/**
 * Type environment for [OclSyntax.typeCheck]: names visible to the expression
 * besides `self` (which is always implicitly in scope).
 */
public class OclScope(
    public val variables: Map<String, OclType>,
)

/** Structured result of a static OCL check — never thrown, always returned. */
public sealed interface OclCheckResult {
    /** The expression passed every static check. */
    public object Ok : OclCheckResult

    /**
     * The expression failed a static check.
     *
     * @property message human-readable description of the failure.
     * @property range 0-based char range of the offending region, or `null` if not localizable.
     */
    public data class Error(
        public val message: String,
        public val range: IntRange?,
    ) : OclCheckResult
}

/**
 * Public facade over the OCL lexer/parser/AST for editor-facing static
 * analysis — syntax highlighting and non-throwing type/scope checking.
 *
 * Both entry points are pure and never throw: [highlight] tolerates scan
 * errors by emitting [OclTokenKind.ERROR] spans instead of failing, and
 * [typeCheck] converts every [OclEvaluationException] into a structured
 * [OclCheckResult.Error].
 */
public object OclSyntax {
    /** Reserved words lexed as plain [OclToken.Ident] by [OclLexer]. */
    private val KEYWORDS =
        setOf("let", "in", "if", "then", "else", "endif", "and", "or", "not", "implies", "iterate")

    /**
     * Maximum accepted length (in chars) of an expression passed to
     * [typeCheck] — the static, pre-parse half of the complexity/size guard.
     *
     * Shared sandbox limit: Wave 5's runtime `applyPatch` path must invoke
     * the same guard before applying a `ChangeGuard`, so this is the single
     * source of truth for the cap.
     */
    internal const val MAX_EXPRESSION_LENGTH: Int = 4096

    /**
     * Maximum accepted AST node count for an expression accepted by
     * [typeCheck] — the post-parse half of the complexity/size guard. See
     * [MAX_EXPRESSION_LENGTH] for the shared-limit rationale.
     */
    internal const val MAX_AST_NODES: Int = 500

    /**
     * Maximum accepted AST nesting depth for an expression accepted by
     * [typeCheck]. See [MAX_EXPRESSION_LENGTH] for the shared-limit
     * rationale.
     */
    internal const val MAX_NESTING_DEPTH: Int = 64

    /** Operators [typeCheck] applies its sound literal type-mismatch pass to. */
    private val CHECKED_BINARY_OPS = setOf("<", ">", "<=", ">=", "+", "-", "*", "/", "=", "<>")

    private val NUMERIC_TYPES = setOf(OclType.INTEGER, OclType.REAL)

    /**
     * Classifies every lexeme of [expr] for editor syntax highlighting.
     *
     * Tolerant of scan errors (unterminated strings, unexpected characters):
     * bad spans are reported as [OclTokenKind.ERROR] rather than throwing, so
     * a still-being-typed expression can be highlighted incrementally. Never
     * throws. Returns an empty list for blank input.
     */
    public fun highlight(expr: String): List<HighlightToken> =
        OclLexer
            .scan(expr, tolerant = true)
            .filter { it.isError || it.token != OclToken.Eof }
            .map { HighlightToken(it.start, it.end, kindOf(it)) }

    private fun kindOf(lexeme: OclLexeme): OclTokenKind {
        if (lexeme.isError) return OclTokenKind.ERROR
        return when (val token = lexeme.token) {
            is OclToken.TrueLit, is OclToken.FalseLit, is OclToken.NullLit,
            is OclToken.IntLit, is OclToken.RealLit, is OclToken.StrLit,
            -> OclTokenKind.LITERAL
            is OclToken.Self, is OclToken.AtPre -> OclTokenKind.KEYWORD
            is OclToken.LParen, is OclToken.RParen -> OclTokenKind.PAREN
            is OclToken.Op, is OclToken.Dot, is OclToken.Arrow, is OclToken.Pipe,
            is OclToken.Comma, is OclToken.Semicolon,
            -> OclTokenKind.OPERATOR
            is OclToken.Ident -> if (token.name in KEYWORDS) OclTokenKind.KEYWORD else OclTokenKind.IDENT
            OclToken.Eof -> OclTokenKind.ERROR // unreachable: filtered out above unless isError
        }
    }

    /**
     * Statically checks [expr] against [scope] without ever evaluating it.
     *
     * Runs, in order, a size guard, a syntax parse, an AST complexity guard, a
     * free-variable/scope check, and a conservative literal type-mismatch
     * pass — returning the first failure. [OclCheckResult.Ok] means the
     * expression passed every check, not that it is guaranteed to evaluate
     * successfully (e.g. runtime navigation failures are out of scope here).
     * Never throws; deterministic for a given `(expr, scope)` pair.
     *
     * Belt-and-braces against recursive-descent stack exhaustion: parsing and
     * measuring both recurse over the expression, and although [OclParser]
     * enforces [MAX_NESTING_DEPTH] itself (see `OclParser.guardedRecursion`),
     * a caught [StackOverflowError] here is a second line of defense so this
     * function's "never throws" contract holds even if a future change to
     * the parser or AST walk reintroduces an unguarded recursion path.
     */
    public fun typeCheck(
        expr: String,
        scope: OclScope,
    ): OclCheckResult {
        if (expr.length > MAX_EXPRESSION_LENGTH) {
            return OclCheckResult.Error(
                "expression too long (max $MAX_EXPRESSION_LENGTH chars)",
                0 until expr.length,
            )
        }

        return try {
            val ast =
                try {
                    val (tokens, positions) = OclLexer.tokenizeWithPositions(expr)
                    OclParser(tokens, positions).parse()
                } catch (e: OclEvaluationException) {
                    return OclCheckResult.Error(e.message ?: "syntax error", rangeFrom(e.position, expr))
                }

            val (nodeCount, depth) = measure(ast)
            if (nodeCount > MAX_AST_NODES || depth > MAX_NESTING_DEPTH) {
                return OclCheckResult.Error("expression too complex", null)
            }

            val unknownVar = firstUnknownFreeVar(ast, bound = emptySet(), scope = scope)
            if (unknownVar != null) {
                return OclCheckResult.Error("unknown variable '$unknownVar'", rangeOfIdent(unknownVar, expr))
            }

            if (ast is OclExpression.BinaryOp && ast.op in CHECKED_BINARY_OPS) {
                val leftType = staticType(ast.left, scope)
                val rightType = staticType(ast.right, scope)
                if (typesIncompatible(leftType, rightType)) {
                    return OclCheckResult.Error(
                        "type mismatch: cannot apply '${ast.op}' to $leftType and $rightType",
                        null,
                    )
                }
            }

            OclCheckResult.Ok
        } catch (e: StackOverflowError) {
            OclCheckResult.Error("expression too complex", null)
        }
    }

    /** `(nodeCount, maxDepth)` of [expr], a leaf counting as depth 1. */
    private fun measure(expr: OclExpression): Pair<Int, Int> {
        fun combine(children: List<Pair<Int, Int>>): Pair<Int, Int> {
            val nodeCount = children.sumOf { it.first } + 1
            val depth = (children.maxOfOrNull { it.second } ?: 0) + 1
            return nodeCount to depth
        }
        return when (expr) {
            OclExpression.Self, OclExpression.NullLit -> 1 to 1
            is OclExpression.IntLit, is OclExpression.RealLit, is OclExpression.StrLit,
            is OclExpression.BoolLit, is OclExpression.VarRef,
            -> 1 to 1
            is OclExpression.Navigate -> combine(listOf(measure(expr.receiver)))
            is OclExpression.OperationCall ->
                combine(listOf(measure(expr.receiver)) + expr.args.map(::measure))
            is OclExpression.CollectionOp ->
                combine(
                    listOf(measure(expr.receiver)) +
                        expr.args.map(::measure) +
                        listOfNotNull(expr.body?.let(::measure)),
                )
            is OclExpression.IterateExpr ->
                combine(listOf(measure(expr.receiver), measure(expr.accInit), measure(expr.body)))
            is OclExpression.LetExpr -> combine(listOf(measure(expr.initExpr), measure(expr.body)))
            is OclExpression.IfExpr ->
                combine(listOf(measure(expr.cond), measure(expr.thenExpr), measure(expr.elseExpr)))
            is OclExpression.BinaryOp -> combine(listOf(measure(expr.left), measure(expr.right)))
            is OclExpression.UnaryOp -> combine(listOf(measure(expr.operand)))
            is OclExpression.TypeOp -> combine(listOf(measure(expr.receiver)))
            is OclExpression.AtPre -> combine(listOf(measure(expr.receiver)))
        }
    }

    /**
     * First free (unbound, non-`self`) variable name referenced by [expr]
     * that is also absent from [scope], or `null` if every free
     * [OclExpression.VarRef] is either `self`, bound by an enclosing `let`,
     * collection-op lambda, or `iterate`, or declared in [scope].
     *
     * Free-but-known variables are skipped rather than short-circuiting the
     * walk, so an unknown variable referenced *after* a known one (e.g. `a >
     * b` with only `a` in scope) is still found.
     */
    private fun firstUnknownFreeVar(
        expr: OclExpression,
        bound: Set<String>,
        scope: OclScope,
    ): String? =
        when (expr) {
            OclExpression.Self, OclExpression.NullLit -> null
            is OclExpression.IntLit, is OclExpression.RealLit,
            is OclExpression.StrLit, is OclExpression.BoolLit,
            -> null
            is OclExpression.VarRef ->
                if (expr.name == "self" || expr.name in bound || expr.name in scope.variables) {
                    null
                } else {
                    expr.name
                }
            is OclExpression.Navigate -> firstUnknownFreeVar(expr.receiver, bound, scope)
            is OclExpression.OperationCall ->
                firstUnknownFreeVar(expr.receiver, bound, scope)
                    ?: expr.args.firstNotNullOfOrNull { firstUnknownFreeVar(it, bound, scope) }
            is OclExpression.CollectionOp -> {
                val innerBound = expr.bindingVar?.let { bound + it } ?: bound
                firstUnknownFreeVar(expr.receiver, bound, scope)
                    ?: expr.args.firstNotNullOfOrNull { firstUnknownFreeVar(it, bound, scope) }
                    ?: expr.body?.let { firstUnknownFreeVar(it, innerBound, scope) }
            }
            is OclExpression.IterateExpr -> {
                val innerBound = bound + expr.iterVar + expr.accVar
                firstUnknownFreeVar(expr.receiver, bound, scope)
                    ?: firstUnknownFreeVar(expr.accInit, bound, scope)
                    ?: firstUnknownFreeVar(expr.body, innerBound, scope)
            }
            is OclExpression.LetExpr ->
                firstUnknownFreeVar(expr.initExpr, bound, scope)
                    ?: firstUnknownFreeVar(expr.body, bound + expr.name, scope)
            is OclExpression.IfExpr ->
                firstUnknownFreeVar(expr.cond, bound, scope)
                    ?: firstUnknownFreeVar(expr.thenExpr, bound, scope)
                    ?: firstUnknownFreeVar(expr.elseExpr, bound, scope)
            is OclExpression.BinaryOp ->
                firstUnknownFreeVar(expr.left, bound, scope) ?: firstUnknownFreeVar(expr.right, bound, scope)
            is OclExpression.UnaryOp -> firstUnknownFreeVar(expr.operand, bound, scope)
            is OclExpression.TypeOp -> firstUnknownFreeVar(expr.receiver, bound, scope)
            is OclExpression.AtPre -> firstUnknownFreeVar(expr.receiver, bound, scope)
        }

    /**
     * Best-effort static type of [expr] — only literals and bare scope
     * variables are certain; everything else (navigation, operation calls,
     * etc.) is [OclType.UNKNOWN] so [typesIncompatible] never false-positives
     * on it.
     */
    private fun staticType(
        expr: OclExpression,
        scope: OclScope,
    ): OclType =
        when (expr) {
            is OclExpression.IntLit -> OclType.INTEGER
            is OclExpression.RealLit -> OclType.REAL
            is OclExpression.StrLit -> OclType.STRING
            is OclExpression.BoolLit -> OclType.BOOLEAN
            is OclExpression.VarRef -> scope.variables[expr.name] ?: OclType.UNKNOWN
            else -> OclType.UNKNOWN
        }

    /** `true` only when both types are certain and provably incompatible. */
    private fun typesIncompatible(
        a: OclType,
        b: OclType,
    ): Boolean {
        if (a == OclType.UNKNOWN || b == OclType.UNKNOWN) return false
        if (a == b) return false
        if (a in NUMERIC_TYPES && b in NUMERIC_TYPES) return false
        return true
    }

    /** 0-based offset of the first char of each source line, `lineStarts[0] == 0`. */
    private fun lineStarts(expr: String): List<Int> {
        val starts = mutableListOf(0)
        expr.forEachIndexed { idx, c -> if (c == '\n') starts += idx + 1 }
        return starts
    }

    /**
     * Converts a 1-based [OclPosition] to a 0-based char [IntRange] within
     * [expr], widened to the full lexeme starting there when one can be
     * found. `null` when [position] is `null` or out of bounds.
     */
    private fun rangeFrom(
        position: OclPosition?,
        expr: String,
    ): IntRange? {
        if (position == null) return null
        val starts = lineStarts(expr)
        val lineIndex = position.line - 1
        if (lineIndex !in starts.indices) return null
        val offset = starts[lineIndex] + position.col - 1
        if (offset < 0 || offset > expr.length) return null
        val lexeme = OclLexer.scan(expr, tolerant = true).firstOrNull { it.start == offset && it.end > it.start }
        return when {
            lexeme != null -> lexeme.start until lexeme.end
            offset < expr.length -> offset until (offset + 1)
            else -> (offset - 1).coerceAtLeast(0) until offset.coerceAtLeast(1)
        }
    }

    /** Span of the first [OclToken.Ident] lexeme named [name] in [expr], or `null`. */
    private fun rangeOfIdent(
        name: String,
        expr: String,
    ): IntRange? {
        val lexeme =
            OclLexer.scan(expr, tolerant = true).firstOrNull {
                val token = it.token
                token is OclToken.Ident && token.name == name
            }
        return lexeme?.let { it.start until it.end }
    }
}
