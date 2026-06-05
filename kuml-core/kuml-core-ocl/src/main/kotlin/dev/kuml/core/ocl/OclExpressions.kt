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
        val expr = OclParser(tokens).parse()
        val fullEnv: Map<String, Any?> = mapOf("self" to self) + env
        return OclEvaluator(self).eval(expr, fullEnv)
    }
}
