package dev.kuml.core.ocl

import dev.kuml.core.ocl.ast.OclExpression
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlGeneralization

/**
 * Evaluates parsed OCL expressions against a receiver object.
 *
 * @property self The OCL `self` — the root navigation object.
 * @property model The enclosing model's elements (e.g. `KumlDiagram.elements`),
 *   used to resolve association-end navigation (`self.assocEnd`) and `closure()`
 *   in [UmlPropertyAccessor], as well as classifier-name resolution and
 *   [UmlGeneralization] chain walking for the `oclIsKindOf`/`oclIsTypeOf`/
 *   `oclAsType` type operations. Defaults to empty for callers that only
 *   navigate direct/structural properties (e.g. runtime guards evaluating over
 *   a [dev.kuml.core.model.KumlEvalContext] or `Map`, which have no
 *   surrounding model) — association navigation and type-name resolution
 *   simply find no match in that case.
 * @property preSnapshot The `self`-relative environment as it was at operation
 *   entry, used to resolve `expr@pre` inside `post:` constraint bodies (V3.2.22).
 *   Only [OclExpression.Self] and [OclExpression.Navigate] receivers directly
 *   composed of `self`-navigations are meaningfully "pre-state" — this subset
 *   has no mutable runtime object model, so the snapshot is simply the *same*
 *   `self`/`model` re-evaluated: `@pre` is effectively a no-op here and exists
 *   for OCL-source compatibility with contracts written against a stateful
 *   runtime (see the `OclEvaluator` KDoc / `V3.2.22` daily-note Stolperfalle:
 *   this evaluator has no operation-call runtime, so there is no pre-state
 *   distinct from the current state to snapshot). Callers that *do* have a
 *   genuine pre-state (e.g. a future runtime-guard operation executor) can
 *   pass a differing [preSnapshot] map (`self` -> pre-call receiver) to get
 *   real snapshot semantics; `eval`'s recursive calls thread it through.
 */
internal class OclEvaluator(
    private val self: Any,
    private val model: List<Any> = emptyList(),
    private val preSnapshot: Map<String, Any?>? = null,
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
                PropertyAccessor.get(recv, expr.prop, model)
            }
            is OclExpression.OperationCall -> evalOperationCall(expr, env)
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
            is OclExpression.TypeOp -> evalTypeOp(expr, env)
            is OclExpression.AtPre -> eval(expr.receiver, preSnapshot ?: env)
        }

    // ── OCL standard-library String/Real/Integer operations (V3.2.24) ──────

    /**
     * Dispatches `receiver.name(args)` calls parsed as [OclExpression.OperationCall].
     *
     * Resolution order:
     * 1. `String` standard-library operations, if the receiver evaluates to a [String].
     * 2. `Real`/`Integer` standard-library operations, if the receiver is numeric.
     * 3. Fallback: no matching standard-library operation — this subset has no
     *    operation-invocation runtime for arbitrary model operations (see class
     *    KDoc), so a bare zero-arg call on a non-primitive receiver (e.g. a
     *    metamodel accessor exposed as a method-shaped name) resolves via
     *    [PropertyAccessor] exactly like [OclExpression.Navigate] — this keeps
     *    `self.someOperation()`-style call syntax from the OCL spec parseable
     *    without requiring every model accessor to be re-exposed as a property.
     */
    private fun evalOperationCall(
        expr: OclExpression.OperationCall,
        env: Map<String, Any?>,
    ): Any? {
        val receiverValue =
            eval(expr.receiver, env)
                ?: throw OclEvaluationException("Cannot call '${expr.name}' on null")

        fun arg(i: Int): Any? = eval(expr.args[i], env)

        if (receiverValue is String) {
            evalStringOp(receiverValue, expr.name, expr.args, ::arg)?.let { return it.value }
        }
        if (isNumeric(receiverValue)) {
            evalNumberOp(receiverValue, expr.name, expr.args, ::arg)?.let { return it.value }
        }
        return PropertyAccessor.get(receiverValue, expr.name, model)
    }

    /** Wraps a possibly-`null` standard-library result so "no matching op" can be told apart from a `null` result. */
    private class OpResult(
        val value: Any?,
    )

    /**
     * OCL `String` standard-library operations (V3.2.24 completion). All
     * indices are 1-based per the OCL specification (`substring(1, size())`
     * returns the whole string), converted to Kotlin's 0-based indices here.
     */
    private fun evalStringOp(
        receiver: String,
        name: String,
        args: List<OclExpression>,
        arg: (Int) -> Any?,
    ): OpResult? =
        when (name) {
            "size" -> OpResult(receiver.length)
            "toUpper" -> OpResult(receiver.uppercase())
            "toLower" -> OpResult(receiver.lowercase())
            "concat" -> OpResult(receiver + requireString(arg(0), "concat"))
            "substring" -> {
                val from = requireInt(arg(0), "substring")
                val to = requireInt(arg(1), "substring")
                if (from < 1 || to > receiver.length || from > to + 1) {
                    throw OclEvaluationException(
                        "'substring($from, $to)' out of bounds for a string of length ${receiver.length}",
                    )
                }
                OpResult(receiver.substring(from - 1, to))
            }
            "indexOf" -> OpResult(receiver.indexOf(requireString(arg(0), "indexOf")) + 1)
            "equalsIgnoreCase" -> OpResult(receiver.equals(requireString(arg(0), "equalsIgnoreCase"), ignoreCase = true))
            "isEmpty" -> OpResult(receiver.isEmpty())
            "notEmpty" -> OpResult(receiver.isNotEmpty())
            "at" -> {
                val i = requireInt(arg(0), "at")
                if (i < 1 || i > receiver.length) {
                    throw OclEvaluationException("'at($i)' out of bounds for a string of length ${receiver.length}")
                }
                OpResult(receiver[i - 1].toString())
            }
            else -> null
        }

    /**
     * OCL `Real`/`Integer` standard-library operations (V3.2.24 completion).
     * `mod`/`div` are Integer-only per the OCL spec; `abs`/`floor`/`round`
     * preserve the OCL `Integer op -> Integer` / `Real op -> Real` promotion
     * rule used elsewhere in this evaluator (see [arithResult]).
     */
    private fun evalNumberOp(
        receiver: Any,
        name: String,
        args: List<OclExpression>,
        arg: (Int) -> Any?,
    ): OpResult? =
        when (name) {
            "abs" -> OpResult(if (receiver is Int) kotlin.math.abs(receiver) else kotlin.math.abs(toNumeric(receiver)))
            "floor" -> OpResult(kotlin.math.floor(toNumeric(receiver)).toInt())
            // OCL `round()` rounds half *up* ("if there are two nearest integers,
            // the larger is selected" — OMG OCL 2.4 §7.5.2), unlike
            // `kotlin.math.round`'s round-half-to-even ("banker's rounding"),
            // which would incorrectly return 2 for `2.5.round()`.
            "round" -> OpResult(kotlin.math.floor(toNumeric(receiver) + 0.5).toInt())
            "max" -> {
                val other = arg(0)
                OpResult(arithResult(receiver, other, kotlin.math.max(toNumeric(receiver), toNumeric(other))))
            }
            "min" -> {
                val other = arg(0)
                OpResult(arithResult(receiver, other, kotlin.math.min(toNumeric(receiver), toNumeric(other))))
            }
            "mod" -> {
                val divisor = requireInt(arg(0), "mod")
                if (divisor == 0) throw OclEvaluationException("'mod' by zero")
                OpResult(requireInt(receiver, "mod") % divisor)
            }
            "div" -> {
                val divisor = requireInt(arg(0), "div")
                if (divisor == 0) throw OclEvaluationException("'div' by zero")
                OpResult(Math.floorDiv(requireInt(receiver, "div"), divisor))
            }
            else -> null
        }

    private fun requireString(
        v: Any?,
        op: String,
    ): String = v as? String ?: throw OclEvaluationException("'$op' requires a String argument, got $v")

    private fun requireInt(
        v: Any?,
        op: String,
    ): Int = v as? Int ?: throw OclEvaluationException("'$op' requires an Integer argument, got $v")

    // ── OCL type operations (V3.2.22) ───────────────────────────────────────

    private fun evalTypeOp(
        expr: OclExpression.TypeOp,
        env: Map<String, Any?>,
    ): Any? {
        val receiverValue = eval(expr.receiver, env)
        return when (expr.op) {
            "oclIsUndefined" -> receiverValue == null
            "oclIsInvalid" -> false // this subset has no distinct "invalid" (error) value from "undefined" (null)
            "oclIsTypeOf" -> receiverValue != null && classifierNameOf(receiverValue) == requireTypeName(expr)
            "oclIsKindOf" -> receiverValue != null && isKindOf(receiverValue, requireTypeName(expr))
            "oclAsType" -> {
                val typeName = requireTypeName(expr)
                if (receiverValue != null && isKindOf(receiverValue, typeName)) {
                    receiverValue
                } else {
                    throw OclEvaluationException(
                        "'oclAsType($typeName)' failed: receiver is not a kind of '$typeName'",
                    )
                }
            }
            else -> throw OclEvaluationException("Unknown type operation: ${expr.op}")
        }
    }

    private fun requireTypeName(expr: OclExpression.TypeOp): String =
        expr.typeName ?: throw OclEvaluationException("'${expr.op}' requires a type name argument")

    /** The declared classifier name of [value] — its own metamodel type name, not a Kotlin class name. */
    private fun classifierNameOf(value: Any?): String? = (value as? UmlClassifier)?.name

    /**
     * `oclIsKindOf(T)` — `true` if [value]'s classifier is `T` itself or a
     * (transitive) specialization of `T`, walking [UmlGeneralization] edges
     * in [model] from the value's classifier up to its ancestors.
     */
    private fun isKindOf(
        value: Any?,
        typeName: String,
    ): Boolean {
        val classifier = value as? UmlClassifier ?: return false
        if (classifier.name == typeName) return true
        val generalizations = model.filterIsInstance<UmlGeneralization>()
        val classifiersById = model.filterIsInstance<UmlClassifier>().associateBy { it.id }
        val visited = mutableSetOf(classifier.id)
        val frontier = ArrayDeque(listOf(classifier.id))
        while (frontier.isNotEmpty()) {
            val currentId = frontier.removeFirst()
            for (gen in generalizations) {
                if (gen.specificId != currentId) continue
                val general = classifiersById[gen.generalId] ?: continue
                if (general.name == typeName) return true
                if (visited.add(general.id)) frontier.addLast(general.id)
            }
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalCollectionOp(
        expr: OclExpression.CollectionOp,
        env: Map<String, Any?>,
    ): Any? {
        val receiverValue = eval(expr.receiver, env)
        // `closure` is defined on Collection in the OCL standard library, but
        // this subset has no collection-literal syntax (see OclEvaluatorTest),
        // so a single non-collection receiver (e.g. bare `self`) is treated as
        // an implicit singleton — matching the common `self->closure(...)`
        // idiom for starting a transitive-closure navigation from one element.
        val coll =
            when (receiverValue) {
                is List<*> -> receiverValue
                null -> emptyList<Any?>()
                else -> if (expr.op == "closure") listOf(receiverValue) else emptyList()
            }

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
            "closure" -> evalClosure(coll, expr)
            else -> throw OclEvaluationException("Unknown collection operation: ${expr.op}")
        }
    }

    /**
     * OCL `closure(v | expr)` — the transitive closure of the navigation
     * expressed by `expr` over the receiver collection's elements.
     *
     * Per the OCL standard library, the result contains every element
     * *reachable* via repeated application of `expr` — the original source
     * elements are only included if they are re-reached through the relation
     * (e.g. a cycle). A visited-set (covering both the source elements, to
     * prevent re-navigating them, and the result) guards against infinite
     * loops in cyclic association graphs.
     *
     * Leaf elements (e.g. a classifier with no outgoing association matching
     * the navigation) simply terminate that branch: [UmlPropertyAccessor]
     * throws [OclEvaluationException] for a property with no structural or
     * association-end match, which is treated here as "no further elements"
     * rather than propagated — the alternative (`self.next` on every model
     * classifier being reachable) is not something `closure()` callers can
     * pre-guarantee for an arbitrary association graph.
     */
    private fun evalClosure(
        coll: List<*>,
        expr: OclExpression.CollectionOp,
    ): List<Any?> {
        val body = expr.body ?: throw OclEvaluationException("'closure' requires a navigation body")
        val bindingVar = expr.bindingVar ?: throw OclEvaluationException("'closure' requires a binding variable")
        val visited = LinkedHashSet<Any?>(coll)
        val result = LinkedHashSet<Any?>()
        val frontier = ArrayDeque(coll)
        while (frontier.isNotEmpty()) {
            val item = frontier.removeFirst()
            val next =
                try {
                    eval(body, mapOf(bindingVar to item, "self" to self))
                } catch (_: OclEvaluationException) {
                    null
                }
            val newItems =
                when (next) {
                    null -> emptyList()
                    is List<*> -> next
                    else -> listOf(next)
                }
            for (n in newItems) {
                result += n
                if (visited.add(n)) frontier.addLast(n)
            }
        }
        return result.toList()
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
