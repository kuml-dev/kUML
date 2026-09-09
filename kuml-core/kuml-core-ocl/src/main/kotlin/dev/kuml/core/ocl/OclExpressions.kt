package dev.kuml.core.ocl

/**
 * Public-API-Facade zum Auswerten von OCL-Ausdrücken aus anderen Modulen
 * (z.B. `kuml-runtime-core` für Guards).
 *
 * Beispiel:
 * ```
 * val result = OclExpressions.evaluate("event.amount > 100", self = instance, env = mapOf("event" to ...))
 * ```
 *
 * Wirft [OclEvaluationException] bei Parse- oder Evaluierungsfehlern.
 */
public object OclExpressions {
    /**
     * Parst und evaluiert den gegebenen OCL-Ausdruck.
     *
     * @param expression OCL-Ausdrucks-Text.
     * @param self Root-Navigationsobjekt (entspricht `self` im OCL-Kontext).
     * @param env Zusätzliche Variablen-Bindings (z.B. `event`, `vars`).
     * @return Ausgewerteter Wert (Int, String, Boolean, Map, List oder null).
     */
    public fun evaluate(
        expression: String,
        self: Any,
        env: Map<String, Any?> = emptyMap(),
    ): Any? {
        val tokens = OclLexer.tokenize(expression)
        val expr = OclParser(tokens = tokens).parse()
        val fullEnv: Map<String, Any?> = mapOf("self" to self) + env
        return OclEvaluator(self = self).eval(expr = expr, env = fullEnv)
    }

    /**
     * Result of [evaluateTracked]: the evaluated [value], plus whether
     * evaluation looked up a bound `env` variable name ([dev.kuml.core.ocl.ast.OclExpression.VarRef],
     * e.g. a bare identifier or `vars`/`event`) that was not present in [env]
     * at all, or navigated a dot-property ([dev.kuml.core.ocl.ast.OclExpression.Navigate]
     * / [dev.kuml.core.ocl.ast.OclExpression.OperationCall], e.g. `vars.foo`,
     * `event.foo`, `vars.getFoo()`) against a `Map` receiver that did not
     * contain that key — as opposed to a variable/key that is present and
     * genuinely `null`.
     */
    public data class OclTrackedResult(
        public val value: Any?,
        public val referencedMissingVariable: Boolean,
    )

    /**
     * Same evaluation as [evaluate], but additionally reports whether the
     * expression referenced a missing `env` variable (see [OclTrackedResult]).
     *
     * `env`-lookups are null-tolerant by design (`env[name]` returns `null`
     * both when [name] is absent and when it is present-but-`null`), and so
     * is a `Map`-receiver dot-navigation (`map[prop]` is exactly as
     * null-tolerant as `env[name]`), which makes an inequality comparison
     * against a *missing* variable — both the bare-identifier spelling
     * (`x <> 1`) and the dot-navigation spelling used throughout guards
     * (`vars.x <> 1`, `event.x <> 1`) — evaluate to a trusted `true` instead
     * of signalling "unknown" — the same fail-open class of bug that
     * `dev.kuml.runtime.activity.ActivityGuardEvaluator.referencesUnresolvedVariable`
     * already closes for the typed-AST (`!=`) dialect on the C-like side of
     * that same two-dialect guard evaluator. Callers that need fail-closed
     * semantics for a `true` result should use this instead of [evaluate] and
     * downgrade a `true` value built on [OclTrackedResult.referencedMissingVariable].
     */
    public fun evaluateTracked(
        expression: String,
        self: Any,
        env: Map<String, Any?> = emptyMap(),
    ): OclTrackedResult {
        val tokens = OclLexer.tokenize(expression)
        val expr = OclParser(tokens = tokens).parse()
        val fullEnv: Map<String, Any?> = mapOf("self" to self) + env
        val evaluator = OclEvaluator(self = self)
        val value = evaluator.eval(expr = expr, env = fullEnv)
        return OclTrackedResult(value = value, referencedMissingVariable = evaluator.referencedMissingVariable)
    }
}
