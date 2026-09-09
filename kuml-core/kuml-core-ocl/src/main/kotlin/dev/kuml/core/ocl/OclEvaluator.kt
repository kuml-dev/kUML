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
    /**
     * Set by [eval] whenever a [OclExpression.VarRef] is looked up against an
     * `env` map that does not contain that name as a key, or a
     * [OclExpression.Navigate]/[OclExpression.OperationCall] dot-navigation
     * (`vars.x`, `event.x`, `vars.getX()`) resolves against a `Map` receiver
     * that does not contain that key — i.e. the variable is *missing*, not
     * merely bound to a `null` value. `env[expr.name]`/`map[prop]` alone
     * cannot distinguish the two (a `Map` returns `null` for both), so
     * without this side channel a fail-open comparison against a missing
     * variable (`x <> 1`, or the dot-spelling `vars.x <> 1`) cannot be told
     * apart from a genuine `x == null` comparison. Read by
     * [OclExpressions.evaluateTracked]; unused by the plain
     * [OclExpressions.evaluate] entry point.
     *
     * From the outside — the two callers that consume it
     * ([OclExpressions.evaluateTracked] runs one evaluation per call, so
     * there is nothing to reset between) — this flag only ever moves from
     * `false` to `true`, never back. [evalBinaryOp] keeps it scoped to the
     * operand(s) that actually determine the result: `and`/`or`/`implies`
     * are short-circuited instead of eagerly evaluating both sides, and
     * `or`/`implies` additionally use [evalIsolated] to check each operand's
     * *own* taint in isolation before folding it in — so a missing variable
     * on a branch a real evaluation never consults, or on a branch whose
     * `true` is subsumed by the *other* operand's independently-trustworthy
     * `true` (`X or true` / `X implies true`, true for any `X`), does not
     * spuriously taint an otherwise-trustworthy `true` result.
     *
     * ## Broader meaning: "this result is not fully determined by data that
     * was actually provided *and* correctly typed"
     *
     * Despite the name, this flag is the evaluator's only "unknown" channel,
     * and is therefore also set wherever a *silent type narrowing* would
     * otherwise hand back a definite-looking value derived from a value the
     * expression could not really use:
     *
     *  - a Boolean-valued iterator body (`forAll`/`exists`/`select`/`reject`/
     *    `any`/`one`) that does not evaluate to a `Boolean` (see `boolBodyOf`
     *    in [evalCollectionOp]),
     *  - a `->`-receiver, an `iterate` receiver, or a `union`/`intersection`
     *    argument that is present but is not a collection (see [asCollection]),
     *  - an `and`/`or`/`implies` where at least one operand's truth value
     *    stays unresolved (missing, present-but-non-Boolean, or present-null)
     *    and the *other* operand does not determine the result on its own —
     *    the full three-valued (Kleene/OCL-"invalid") truth table is spelled
     *    out per operator in [evalBinaryOp], since the taint decision hinges
     *    on that table, not on any single `as?`-narrowing call site.
     *
     * From the consumer's point of view these are the same failure shape as a
     * missing variable: the returned Boolean cannot be trusted, so
     * `dev.kuml.runtime.activity.ActivityGuardEvaluator` must fail the guard
     * closed instead of firing the edge. Constructs that instead *throw* on a
     * wrong type (`not`, the `if` condition, `sortedBy`, the String/Integer
     * standard-library `require*` helpers) need no flag — the exception is
     * already fail-closed.
     */
    internal var referencedMissingVariable: Boolean = false
        private set

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
            is OclExpression.VarRef -> {
                if (!env.containsKey(expr.name)) referencedMissingVariable = true
                env[expr.name]
            }
            is OclExpression.Navigate -> {
                val recv =
                    eval(expr = expr.receiver, env = env)
                        ?: throw OclEvaluationException(message = "Cannot navigate '${expr.prop}' on null")
                trackIfMissingMapKey(receiver = recv, key = expr.prop)
                PropertyAccessor.get(self = recv, prop = expr.prop, model = model)
            }
            is OclExpression.OperationCall -> evalOperationCall(expr = expr, env = env)
            is OclExpression.CollectionOp -> evalCollectionOp(expr = expr, env = env)
            is OclExpression.IterateExpr -> evalIterate(expr = expr, env = env)
            is OclExpression.LetExpr -> {
                val value = eval(expr = expr.initExpr, env = env)
                eval(expr = expr.body, env = env + mapOf(expr.name to value))
            }
            is OclExpression.IfExpr -> {
                val cond =
                    eval(expr = expr.cond, env = env) as? Boolean
                        ?: throw OclEvaluationException(message = "'if' condition requires Boolean")
                if (cond) eval(expr = expr.thenExpr, env = env) else eval(expr = expr.elseExpr, env = env)
            }
            is OclExpression.BinaryOp -> evalBinaryOp(expr = expr, env = env)
            is OclExpression.UnaryOp -> evalUnaryOp(expr = expr, env = env)
            is OclExpression.TypeOp -> evalTypeOp(expr = expr, env = env)
            is OclExpression.AtPre -> eval(expr = expr.receiver, env = preSnapshot ?: env)
        }

    /**
     * Sets [referencedMissingVariable] when [receiver] is a `Map` (the
     * `vars`/`event` payload views) that does not contain [key] — the
     * dot-navigation counterpart of the [OclExpression.VarRef] check in
     * [eval]. [UmlPropertyAccessor.getKnown]'s `Map` branch resolves
     * `map[prop]` directly and returns a silent `null` for an absent key,
     * indistinguishable from a key that is present and genuinely `null`,
     * without this check.
     */
    private fun trackIfMissingMapKey(
        receiver: Any?,
        key: String,
    ) {
        if (receiver is Map<*, *> && !receiver.containsKey(key)) referencedMissingVariable = true
    }

    /**
     * Runs [block] with [referencedMissingVariable] temporarily isolated:
     * saves the current flag, clears it to `false`, runs [block], captures
     * whether the flag ended up `true` (i.e. [block] itself — including
     * anything it recursively evaluates — referenced a missing variable),
     * then restores the flag to exactly its pre-call value regardless of
     * what [block] did. The caller receives [block]'s result alongside that
     * captured taint bit and decides how — or whether — to fold it back in.
     *
     * This is what lets [evalBinaryOp]'s `or`/`implies` arms tell "did this
     * *particular* operand's evaluation reference a missing variable" apart
     * from whatever taint the *other* operand (or an outer, already-running
     * evaluation) already accumulated — necessary because
     * [referencedMissingVariable] is a single monotonic flag on this
     * evaluator instance, not scoped per sub-expression.
     */
    private fun <T> evalIsolated(block: () -> T): Pair<T, Boolean> {
        val savedFlag = referencedMissingVariable
        referencedMissingVariable = false
        val value =
            try {
                block()
            } catch (e: Throwable) {
                // block() can throw (e.g. an [OclEvaluationException] from a
                // dot-navigation type mismatch inside a `closure(...)` body,
                // which [evalClosure] catches and treats as "no successor" —
                // the exception never reaches the caller of this evaluator).
                // Without this catch, an exception here would skip the
                // restoration below entirely, permanently discarding
                // [savedFlag] (the caller's pre-existing taint from before
                // this isolated operand started) and leaving the flag stuck
                // at the `false` this function set two lines up — silently
                // untainting an outer, already-tainted evaluation
                // (fail-open). Merge whatever taint the aborted block already
                // recorded back into the caller's taint before propagating,
                // so a swallowed exception still leaves the flag exactly as
                // tainted as it was before this call, plus anything the
                // aborted operand itself flagged before it threw.
                referencedMissingVariable = savedFlag || referencedMissingVariable
                throw e
            }
        val tainted = referencedMissingVariable
        referencedMissingVariable = savedFlag
        return value to tainted
    }

    /**
     * Narrows [value] to the `List` this evaluator represents OCL collections
     * with, recording taint whenever the narrowing silently swallows a
     * *present* value that simply is not a collection.
     *
     * `x as? List<*> ?: emptyList()` is the collection-shaped sibling of the
     * `x as? Boolean` defect fixed for `and`/`or`/`implies`: a present,
     * wrongly-typed value (a String, an Int, a Map) becomes an *empty*
     * collection, and an empty collection makes `forAll` vacuously `true`,
     * `isEmpty()` `true`, `excludes(x)` `true`, and `iterate` return its
     * accumulator's initial value untouched — all trustworthy-*looking*
     * (untainted) answers derived from a swallowed type error, so a guard
     * built on them fires instead of failing closed.
     *
     * A genuinely `null` value keeps its existing untainted "empty collection"
     * reading: `null` here means either "absent", in which case the
     * [OclExpression.VarRef] / [trackIfMissingMapKey] checks have *already*
     * set the flag, or "present and explicitly null", which reads naturally as
     * an empty collection (`null->isEmpty()` is `true`, `null->notEmpty()` is
     * `false`) rather than as a type error.
     */
    private fun asCollection(value: Any?): List<*> =
        when (value) {
            is List<*> -> value
            null -> emptyList<Any?>()
            else -> {
                referencedMissingVariable = true
                emptyList<Any?>()
            }
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
            eval(expr = expr.receiver, env = env)
                ?: throw OclEvaluationException(message = "Cannot call '${expr.name}' on null")

        fun arg(i: Int): Any? = eval(expr = expr.args[i], env = env)

        if (receiverValue is String) {
            evalStringOp(receiver = receiverValue, name = expr.name, args = expr.args, arg = ::arg)?.let { return it.value }
        }
        if (isNumeric(receiverValue)) {
            evalNumberOp(receiver = receiverValue, name = expr.name, args = expr.args, arg = ::arg)?.let { return it.value }
        }
        trackIfMissingMapKey(receiver = receiverValue, key = expr.name)
        return PropertyAccessor.get(self = receiverValue, prop = expr.name, model = model)
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
            "concat" -> OpResult(receiver + requireString(v = arg(0), op = "concat"))
            "substring" -> {
                val from = requireInt(v = arg(0), op = "substring")
                val to = requireInt(v = arg(1), op = "substring")
                if (from < 1 || to > receiver.length || from > to + 1) {
                    throw OclEvaluationException(
                        message = "'substring($from, $to)' out of bounds for a string of length ${receiver.length}",
                    )
                }
                OpResult(receiver.substring(from - 1, to))
            }
            "indexOf" -> OpResult(receiver.indexOf(requireString(v = arg(0), op = "indexOf")) + 1)
            "equalsIgnoreCase" -> OpResult(receiver.equals(requireString(v = arg(0), op = "equalsIgnoreCase"), ignoreCase = true))
            "isEmpty" -> OpResult(receiver.isEmpty())
            "notEmpty" -> OpResult(receiver.isNotEmpty())
            "at" -> {
                val i = requireInt(v = arg(0), op = "at")
                if (i < 1 || i > receiver.length) {
                    throw OclEvaluationException(message = "'at($i)' out of bounds for a string of length ${receiver.length}")
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
                OpResult(arithResult(l = receiver, r = other, result = kotlin.math.max(toNumeric(receiver), toNumeric(other))))
            }
            "min" -> {
                val other = arg(0)
                OpResult(arithResult(l = receiver, r = other, result = kotlin.math.min(toNumeric(receiver), toNumeric(other))))
            }
            "mod" -> {
                val divisor = requireInt(v = arg(0), op = "mod")
                if (divisor == 0) throw OclEvaluationException(message = "'mod' by zero")
                OpResult(requireInt(v = receiver, op = "mod") % divisor)
            }
            "div" -> {
                val divisor = requireInt(v = arg(0), op = "div")
                if (divisor == 0) throw OclEvaluationException(message = "'div' by zero")
                OpResult(Math.floorDiv(requireInt(v = receiver, op = "div"), divisor))
            }
            else -> null
        }

    private fun requireString(
        v: Any?,
        op: String,
    ): String = v as? String ?: throw OclEvaluationException(message = "'$op' requires a String argument, got $v")

    private fun requireInt(
        v: Any?,
        op: String,
    ): Int = v as? Int ?: throw OclEvaluationException(message = "'$op' requires an Integer argument, got $v")

    // ── OCL type operations (V3.2.22) ───────────────────────────────────────

    private fun evalTypeOp(
        expr: OclExpression.TypeOp,
        env: Map<String, Any?>,
    ): Any? {
        // The receiver is evaluated via [evalIsolated] rather than a plain
        // `eval()` because `oclIsUndefined`/`oclIsInvalid` are the one place
        // in this evaluator where "the value is missing" *is* the trustworthy
        // answer, not a sign the result can't be trusted — but only when the
        // receiver is a *plain lookup chain* ([isPlainLookupChain]: `self`, a
        // bare variable reference, or a `.`-navigation built up from one).
        // For a lookup chain, `oclIsUndefined()` exists specifically to ask
        // "was this ever provided?", so a receiver that resolves to `null`
        // because it touched a missing variable is not tainted data
        // corrupting an otherwise-good result — it is *exactly* the case the
        // operation is asking about. Without discarding taint for this case,
        // `vars.cancelReason.oclIsUndefined()` over a `vars` map that simply
        // never carried `cancelReason` came back as an untainted-looking
        // `true` computation that [referencedMissingVariable] nonetheless
        // marked untrustworthy, so a guard like
        // `vars.cancelReason.oclIsUndefined()` failed closed instead of
        // firing — exactly backwards for the operator whose entire purpose is
        // detecting that condition.
        //
        // A *computed* receiver (a collection operation, an arithmetic/
        // Boolean expression, a closure, …) is different: there, whether the
        // result is `null` genuinely depends on the missing data the
        // computation touched along the way, so discarding the taint
        // unconditionally would turn a fail-closed result into a
        // trustworthy-looking `true` for data that was never actually
        // resolved — e.g. `vars.items->any(i | i = vars.target).oclIsUndefined()`
        // must stay tainted when `vars.target` was never provided, because
        // the `oclIsUndefined()` result flips depending on it. The other type
        // operations (`oclIsTypeOf`/`oclIsKindOf`/`oclAsType`) genuinely do
        // depend on the receiver's real value/type regardless of its shape,
        // so they always fold the isolated taint back in to keep their
        // previous (leaking) behaviour.
        val (receiverValue, receiverTainted) = evalIsolated { eval(expr = expr.receiver, env = env) }
        val isLookupChain = isPlainLookupChain(expr.receiver)
        return when (expr.op) {
            "oclIsUndefined" -> {
                if (receiverTainted && !isLookupChain) referencedMissingVariable = true
                receiverValue == null
            }
            "oclIsInvalid" -> {
                if (receiverTainted && !isLookupChain) referencedMissingVariable = true
                false // this subset has no distinct "invalid" (error) value from "undefined" (null)
            }
            "oclIsTypeOf" -> {
                if (receiverTainted) referencedMissingVariable = true
                receiverValue != null && classifierNameOf(receiverValue) == requireTypeName(expr)
            }
            "oclIsKindOf" -> {
                if (receiverTainted) referencedMissingVariable = true
                receiverValue != null && isKindOf(value = receiverValue, typeName = requireTypeName(expr))
            }
            "oclAsType" -> {
                if (receiverTainted) referencedMissingVariable = true
                val typeName = requireTypeName(expr)
                if (receiverValue != null && isKindOf(value = receiverValue, typeName = typeName)) {
                    receiverValue
                } else {
                    throw OclEvaluationException(
                        message = "'oclAsType($typeName)' failed: receiver is not a kind of '$typeName'",
                    )
                }
            }
            else -> throw OclEvaluationException(message = "Unknown type operation: ${expr.op}")
        }
    }

    private fun requireTypeName(expr: OclExpression.TypeOp): String =
        expr.typeName ?: throw OclEvaluationException(message = "'${expr.op}' requires a type name argument")

    /**
     * `true` when [expr] is a *plain lookup chain* — `self`, a bare variable
     * reference, or a `.`-navigation (property or zero/multi-arg operation
     * call) built up recursively from one — as opposed to a *computed*
     * expression (a collection operation, a Boolean/arithmetic combinator, a
     * closure, a literal, …) whose result depends on more than simply "was
     * this path ever provided".
     *
     * Used by [evalTypeOp]'s `oclIsUndefined`/`oclIsInvalid` arms to decide
     * whether a tainted receiver's "missing" answer is itself the trustworthy
     * answer (a lookup chain) or whether the taint has to propagate because
     * the receiver's null-ness genuinely depends on the missing data (a
     * computed receiver) — see the comment on [evalTypeOp] for the concrete
     * scenario this distinction closes.
     */
    private fun isPlainLookupChain(expr: OclExpression): Boolean =
        when (expr) {
            is OclExpression.Self -> true
            is OclExpression.VarRef -> true
            is OclExpression.Navigate -> isPlainLookupChain(expr.receiver)
            is OclExpression.OperationCall -> isPlainLookupChain(expr.receiver)
            else -> false
        }

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
        val receiverValue = eval(expr = expr.receiver, env = env)
        // `closure` is defined on Collection in the OCL standard library, but
        // this subset has no collection-literal syntax (see OclEvaluatorTest),
        // so a single non-collection receiver (e.g. bare `self`) is treated as
        // an implicit singleton — matching the common `self->closure(...)`
        // idiom for starting a transitive-closure navigation from one element.
        // Every *other* operation keeps the historical "not a collection ->
        // empty collection" reading, but routes through [asCollection] so a
        // present-but-wrongly-typed receiver is recorded as taint instead of
        // silently producing a vacuously-`true` `forAll`/`isEmpty`/`excludes`.
        val coll =
            if (expr.op == "closure" && receiverValue != null && receiverValue !is List<*>) {
                listOf(receiverValue)
            } else {
                asCollection(receiverValue)
            }

        fun bodyOf(item: Any?): Any? {
            val newEnv = env + mapOf((expr.bindingVar ?: "") to item)
            return eval(expr = expr.body!!, env = newEnv)
        }

        /**
         * Evaluates an iterator body that OCL requires to be Boolean-valued
         * (`forAll`, `exists`, `select`, `reject`, `any`, `one`).
         *
         * This used to be `bodyOf(item) as? Boolean ?: false` — the same
         * `as? Boolean` defect already fixed for the `and`/`implies` arms of
         * [evalBinaryOp]: the cast silently turns *any* non-Boolean body result
         * (a String, an Int, a present `null`) into `null`, and the elvis then
         * reads that as a definite, trustworthy `false`. A `false` from that
         * path is not the safe direction it looks like — `forAll` reports an
         * untainted `false` (which negates to an untainted, firing `true`),
         * `reject` *keeps* the element (so `->notEmpty()` is an untainted
         * `true`), and `one` counts it as non-matching. Record taint so any
         * result built on such a body fails closed, while keeping the `false`
         * reading itself unchanged for the non-guard callers
         * ([OclValidator]/[StereotypeValidator]) that never read the flag.
         */
        fun boolBodyOf(item: Any?): Boolean {
            val raw = bodyOf(item)
            if (raw is Boolean) return raw
            referencedMissingVariable = true
            return false
        }

        return when (expr.op) {
            "size" -> coll.size
            "isEmpty" -> coll.isEmpty()
            "notEmpty" -> coll.isNotEmpty()
            "includes" -> coll.contains(eval(expr = expr.args.first(), env = env))
            "excludes" -> !coll.contains(eval(expr = expr.args.first(), env = env))
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
                            ?: throw OclEvaluationException(message = "'sortedBy' body must evaluate to a Comparable, got $v")
                    },
                )
            "sum" -> {
                val values = coll.map { if (expr.body != null) bodyOf(it) else it }
                val total = values.fold(0.0) { acc, v -> acc + toNumeric(v) }
                if (values.all { numericIsInt(it) }) total.toInt() else total
            }
            "count" -> coll.count { it == eval(expr = expr.args.first(), env = env) }
            "including" -> coll + eval(expr = expr.args.first(), env = env)
            "excluding" -> coll.filterNot { it == eval(expr = expr.args.first(), env = env) }
            "union" -> coll + asCollection(eval(expr = expr.args.first(), env = env))
            "intersection" -> {
                val other = asCollection(eval(expr = expr.args.first(), env = env))
                coll.filter { other.contains(it) }
            }
            "first" -> coll.firstOrNull() ?: throw OclEvaluationException(message = "'first' called on empty collection")
            "last" -> coll.lastOrNull() ?: throw OclEvaluationException(message = "'last' called on empty collection")
            "asSet" -> coll.distinct()
            "asSequence" -> coll
            "closure" -> evalClosure(coll = coll, expr = expr, env = env)
            else -> throw OclEvaluationException(message = "Unknown collection operation: ${expr.op}")
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
     *
     * The body is evaluated against the *enclosing* [env] with only
     * [bindingVar] added/overridden (`env + mapOf(bindingVar to item)`), not
     * a fresh `mapOf(bindingVar to item, "self" to self)` — the latter used
     * to drop every other outer binding (e.g. a `vars` payload the body
     * references) on the floor. Losing `vars` this way was itself
     * fail-closed (a dropped `vars.x` reference is a missing [VarRef], which
     * taints the result and — via the `catch` below — is read as "no
     * successor" for that branch), so this was over-tainting/over-pruning,
     * not a security regression; fixing it lets the body see the caller's
     * real environment. It also fixes a narrower, untainted divergence: the
     * hardcoded `"self" to self` bound the constructor's `self` rather than
     * whatever `env["self"]` the caller had in scope, so a `self`-named
     * instance variable in [env] would silently shadow the true root object
     * for the duration of the closure body with no taint recorded — folding
     * [bindingVar] into [env] instead preserves the caller's own `self`.
     */
    private fun evalClosure(
        coll: List<*>,
        expr: OclExpression.CollectionOp,
        env: Map<String, Any?>,
    ): List<Any?> {
        val body = expr.body ?: throw OclEvaluationException(message = "'closure' requires a navigation body")
        val bindingVar = expr.bindingVar ?: throw OclEvaluationException(message = "'closure' requires a binding variable")
        val visited = LinkedHashSet<Any?>(coll)
        val result = LinkedHashSet<Any?>()
        val frontier = ArrayDeque(coll)
        while (frontier.isNotEmpty()) {
            val item = frontier.removeFirst()
            val next =
                try {
                    eval(expr = body, env = env + mapOf(bindingVar to item))
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
        val coll = asCollection(eval(expr = expr.receiver, env = env))
        var acc = eval(expr = expr.accInit, env = env)
        for (item in coll) {
            val newEnv = env + mapOf(expr.iterVar to item, expr.accVar to acc)
            acc = eval(expr = expr.body, env = newEnv)
        }
        return acc
    }

    private fun numericIsInt(v: Any?): Boolean = v is Int

    private fun evalBinaryOp(
        expr: OclExpression.BinaryOp,
        env: Map<String, Any?>,
    ): Any? {
        // "and"/"or"/"implies" are evaluated lazily (short-circuit) instead of
        // eagerly computing both sides up front: this matches the OCL 2.4
        // standard library's non-strict semantics for these operators (e.g.
        // "false and invalid = false", "true or invalid = true" — the result
        // is already determined by one operand regardless of the other), and
        // — critically — keeps [referencedMissingVariable] scoped to the
        // operand(s) that actually determine the result. Eagerly evaluating
        // both sides (as this used to) made a legitimately-`true`
        // "urgent or vip" guard fail closed whenever the *other*,
        // never-actually-consulted operand happened to reference a variable
        // that was missing — the OCL-front-end counterpart of the bug
        // [dev.kuml.runtime.activity.ActivityGuardEvaluator]'s
        // `referencesUnresolvedVariableInBinary` already avoids on the AST
        // dialect by re-deriving the short-circuit branch instead of
        // statically walking both operands.
        //
        // "or"/"implies" additionally need *per-operand* taint isolation, not
        // just short-circuiting: "or"'s `l != true` branch — and "implies"'s
        // `l != true` branch when `l` is merely *missing* rather than
        // concretely `false` — still has to consult the right operand to
        // decide the final Boolean, so evaluating `left` first can leave
        // [referencedMissingVariable] set even though `right` alone ends up
        // determining a legitimately-trustworthy `true` result ("X or true"
        // and "X implies true" are both `true` for *any* X, missing or not).
        // [evalIsolated] lets each arm check whether an operand's own
        // evaluation introduced new taint without that taint automatically
        // leaking into a result the operand never actually decided.
        //
        // The taint decision itself follows the explicit three-valued
        // (Kleene/OCL-"invalid") truth table for each operator, not a sweep
        // over individual `as? Boolean` narrowing call sites: an earlier
        // round of this fix audited the *cast* locations and closed the gap
        // for one operand of 'and'/'implies', but left the mirror-image gap
        // open on the operand it hadn't looked at — the defect lives in the
        // *conditions that consume* a narrowed `null`, not in the cast
        // itself, so every arm below spells out all three truth values
        // (concrete `true`, concrete `false`, unresolved) for both operands
        // rather than special-casing just the one operand a previous review
        // happened to focus on.
        when (expr.op) {
            "and" -> {
                // Explicit three-valued (Kleene/OCL-"invalid") truth table
                // instead of narrowing each operand and reasoning about the
                // narrowed values separately: the previous shape fixed the
                // *left*-operand instance of "a present-but-non-Boolean
                // operand silently reads as untainted `false`" but left the
                // mirror-image gap open on the *right* operand (`true and
                // "yes"` returned an untainted `false`, escalating to an
                // untainted `not (...)  == true` fail-open exactly like the
                // left-operand case this file already treats as a MAJOR
                // finding). A concrete Boolean `false` on *either* side is
                // trustworthy and decides the result on its own — matches the
                // OCL non-strict "false and invalid = false" /
                // "invalid and false = false" rules, including the DoS-
                // relevant short-circuit: `l == false` returns before the
                // right operand is evaluated (or even navigated into) at all.
                // Only when both operands are concretely resolved Booleans is
                // `true` trustworthy; anything else (missing, present-but-
                // wrongly-typed, present-null) that isn't already covered by
                // one of the `false` shortcuts leaves the truth value
                // genuinely unknown, so the `false` fallback below is tainted.
                //
                // The left operand is evaluated via [evalIsolated] — unlike
                // the eager, unisolated `eval()` this used to be — because
                // `l == false` is not the only branch that can decide the
                // result: when the *right* operand alone turns out concretely
                // `false`, that shortcut is trustworthy regardless of what the
                // left operand's own (possibly missing-data-derived) value
                // was, exactly like the mirror-image case `or`/`implies`
                // already isolate for. Without isolation, a left operand that
                // merely touched a missing variable while still resolving to
                // a non-`false` value (e.g. a present-but-wrong-type value
                // that reads as `null`, or a nested expression whose own
                // *value* is fine but which navigated through a missing map
                // key on the way) left [referencedMissingVariable] set
                // regardless of which side actually decided the result —
                // so `invalid and false = false` came back tainted even
                // though the trustworthy right-hand `false` alone justifies
                // it, and a guard like `not (missing and false)` failed
                // closed instead of returning the trustworthy `True` OCL's
                // non-strict semantics dictate.
                val (lVal, lTainted) = evalIsolated { eval(expr = expr.left, env = env) as? Boolean }
                if (lVal == false) {
                    // Left alone decides ("false and invalid = false"). But a
                    // left operand that is `false` only because it *itself*
                    // touched a missing variable is not trustworthy on its
                    // own — the missing data could have made it `true`,
                    // which would hand the decision to the right operand
                    // instead — so that uncertainty still has to be folded
                    // back in here.
                    if (lTainted) referencedMissingVariable = true
                    return false
                }
                // Wrapped in try/catch for the same reason as the "or"/
                // "implies" arms above: a throwing right operand (e.g. a
                // dot-navigation type mismatch inside a `closure(...)` body
                // that [evalClosure] swallows and treats as "no successor")
                // must not silently drop `lTainted`, which lives solely in
                // this local variable and is never merged into
                // [referencedMissingVariable] except in the branches below —
                // without this, an aborted right-hand evaluation would leave
                // the flag exactly as it was before the left operand ran,
                // discarding a real taint the left operand already recorded.
                val r =
                    try {
                        eval(expr = expr.right, env = env) as? Boolean
                    } catch (e: Throwable) {
                        if (lTainted) referencedMissingVariable = true
                        throw e
                    }
                if (r == false) {
                    // Right alone decides ("invalid and false = false" /
                    // "true and false = false") regardless of what the left
                    // operand's own (isolated, still-uncertain) value really
                    // was — discard the left operand's taint here, mirroring
                    // how the 'or' arm discards the left operand's taint once
                    // the right operand alone establishes a trustworthy
                    // `true`.
                    return false
                }
                if (lVal == true && r == true) {
                    // Both operands genuinely have to be `true` for this
                    // branch — unlike the shortcuts above, the left operand's
                    // own truth value is exactly what the result depends on,
                    // so a left operand that only reached `true` via data
                    // that also touched a missing variable keeps the result
                    // tainted.
                    if (lTainted) referencedMissingVariable = true
                    return true
                }
                referencedMissingVariable = true
                return false
            }
            "or" -> {
                val (lVal, lTainted) = evalIsolated { eval(expr = expr.left, env = env) as? Boolean }
                if (lVal == true) {
                    if (lTainted) referencedMissingVariable = true
                    return true
                }
                // Wrapped in try/catch so that a throwing right operand
                // (e.g. a dot-navigation type mismatch inside a `closure(...)`
                // body that [evalClosure] swallows and treats as "no
                // successor") doesn't silently drop the left operand's own
                // taint: [evalIsolated]'s internal catch only restores its
                // *own* `savedFlag` (the taint from before this "or" node),
                // not `lTainted`, which lives solely in this local variable.
                // Without re-merging it here, an aborted right-hand
                // evaluation would leave [referencedMissingVariable] exactly
                // as it was before the left operand ran — silently
                // discarding a real taint the left operand already recorded.
                val (rVal, rTainted) =
                    try {
                        evalIsolated { eval(expr = expr.right, env = env) as? Boolean }
                    } catch (e: Throwable) {
                        if (lTainted) referencedMissingVariable = true
                        throw e
                    }
                if (rVal == true) {
                    // Right alone establishes the true result regardless of
                    // what the left operand's real (uncertain) value was —
                    // discard the left operand's taint.
                    if (rTainted) referencedMissingVariable = true
                    return true
                }
                // False result: trustworthy only when BOTH operands are
                // concretely `false` ("false or false = false"). A present-
                // but-non-Boolean/`null` operand on either side (narrowed to
                // `null` by the `as? Boolean` cast above, so `!= false`) is
                // not itself tainted the way [evalIsolated] tracks — that
                // only catches a *missing* variable inside the operand's own
                // evaluation, not a value that was present but wrongly typed
                // — yet its truth value is just as unknown, and the mirror-
                // image of this gap was already closed for 'and'/'implies'.
                // "invalid or false = invalid", so either side being
                // non-concretely-false taints the result independently of
                // [lTainted]/[rTainted].
                if (lVal != false || rVal != false) referencedMissingVariable = true
                if (lTainted || rTainted) referencedMissingVariable = true
                return false
            }
            "implies" -> {
                val (lVal, lTainted) = evalIsolated { eval(expr = expr.left, env = env) as? Boolean }
                if (lVal == false && !lTainted) {
                    // Left is concretely `false` (not merely missing, and not
                    // merely a non-Boolean/`null` present value that the `as?
                    // Boolean` cast above silently turned into a `null` that
                    // is indistinguishable from "missing") — matches the OCL
                    // non-strict "false implies invalid = true" rule: skip
                    // the right operand entirely, exactly as before. A
                    // present-but-non-Boolean or present-but-`null` left
                    // operand instead falls through to the unresolved branch
                    // below, so its unknown truth value still forces the
                    // right operand to be consulted and — absent a
                    // trustworthy `true` there — taints the result, rather
                    // than being silently treated as `false` and returning an
                    // untainted `true` without ever evaluating (or even
                    // navigating into) the right operand.
                    return true
                }
                if (lVal == true) {
                    val (rVal, rTainted) =
                        try {
                            evalIsolated { eval(expr = expr.right, env = env) as? Boolean }
                        } catch (e: Throwable) {
                            if (lTainted) referencedMissingVariable = true
                            throw e
                        }
                    if (rVal == true) {
                        // "X implies true" is true for *any* X, so the left
                        // operand's own taint genuinely cannot change this
                        // result — drop it, exactly like the "or" arm does for
                        // "X or true".
                        if (rTainted) referencedMissingVariable = true
                        return true
                    }
                    if (rVal == false) {
                        // The result is `false`, and that depends on the left
                        // operand really being `true`. If the left operand's
                        // evaluation touched a missing variable, its `true` is
                        // not trustworthy: had the missing data been present,
                        // the left operand could have been `false`, and OCL's
                        // "false implies X = true" rule would have produced
                        // `true` instead. Dropping `lTainted` here (as this arm
                        // did before) handed back an untainted `false`, which
                        // escalates to an untainted, fired `True` guard the
                        // moment it is negated, compared, or used as an `if`
                        // condition — the same fail-open escalation already
                        // closed for the "and" arm.
                        if (lTainted || rTainted) referencedMissingVariable = true
                        return false
                    }
                    // Right operand is present-but-non-Boolean/`null` (not a
                    // concrete `true` or `false`) — the mirror-image of the
                    // gap already closed for 'and': "true implies invalid =
                    // invalid" per OCL, so this cannot be a trustworthy
                    // `false` regardless of `lTainted`/`rTainted`.
                    referencedMissingVariable = true
                    return false
                }
                // Left is a missing/unresolved variable, or a present value
                // that isn't concretely `false` or `true` (not a concrete
                // `false`), so its real truth value is unknown. "X implies
                // true" is true regardless of X, so check whether the right
                // operand alone is a trustworthy `true` before deciding
                // whether the left operand's taint still matters. Wrapped in
                // try/catch for the same reason as the "or" arm above: a
                // throwing right operand must not silently drop `lTainted`.
                val (rVal, rTainted) =
                    try {
                        evalIsolated { eval(expr = expr.right, env = env) as? Boolean }
                    } catch (e: Throwable) {
                        if (lTainted) referencedMissingVariable = true
                        throw e
                    }
                if (rVal == true) {
                    if (rTainted) referencedMissingVariable = true
                    return true
                }
                referencedMissingVariable = true // result still depends on left's unresolved truth value
                return true
            }
        }
        val l = eval(expr = expr.left, env = env)
        val r = eval(expr = expr.right, env = env)
        return when (expr.op) {
            "=" -> l == r
            "<>" -> l != r
            "<" -> compareValues(l = l, r = r) < 0
            ">" -> compareValues(l = l, r = r) > 0
            "<=" -> compareValues(l = l, r = r) <= 0
            ">=" -> compareValues(l = l, r = r) >= 0
            "+" ->
                when {
                    l is String && r is String -> l + r
                    isNumeric(l) && isNumeric(r) -> arithResult(l = l, r = r, result = toNumeric(l) + toNumeric(r))
                    else -> throw OclEvaluationException(message = "Cannot apply '+' to $l and $r")
                }
            "-" ->
                when {
                    isNumeric(l) && isNumeric(r) -> arithResult(l = l, r = r, result = toNumeric(l) - toNumeric(r))
                    else -> throw OclEvaluationException(message = "Cannot apply '-' to $l and $r")
                }
            "*" ->
                when {
                    isNumeric(l) && isNumeric(r) -> arithResult(l = l, r = r, result = toNumeric(l) * toNumeric(r))
                    else -> throw OclEvaluationException(message = "Cannot apply '*' to $l and $r")
                }
            "/" -> {
                if (!isNumeric(l) || !isNumeric(r)) {
                    throw OclEvaluationException(message = "'/' requires numeric operands, got $l and $r")
                }
                val divisor = toNumeric(r)
                if (divisor == 0.0) throw OclEvaluationException(message = "Division by zero")
                // OCL '/' is always real division, regardless of operand types.
                toNumeric(l) / divisor
            }
            else -> throw OclEvaluationException(message = "Unknown binary op: ${expr.op}")
        }
    }

    private fun isNumeric(v: Any?): Boolean = v is Int || v is Double

    private fun toNumeric(v: Any?): Double =
        when (v) {
            is Int -> v.toDouble()
            is Double -> v
            else -> throw OclEvaluationException(message = "Expected numeric value, got $v")
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
        throw OclEvaluationException(message = "Cannot compare $l and $r")
    }

    private fun evalUnaryOp(
        expr: OclExpression.UnaryOp,
        env: Map<String, Any?>,
    ): Any? {
        val operand = eval(expr = expr.operand, env = env)
        return when (expr.op) {
            "not" -> !(operand as? Boolean ?: throw OclEvaluationException(message = "'not' requires Boolean"))
            "-" ->
                when (operand) {
                    is Int -> -operand
                    is Double -> -operand
                    else -> throw OclEvaluationException(message = "unary '-' requires a numeric operand")
                }
            else -> throw OclEvaluationException(message = "Unknown unary op: ${expr.op}")
        }
    }
}
