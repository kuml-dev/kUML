package dev.kuml.runtime

import dev.kuml.core.ocl.OclEvaluationException
import dev.kuml.core.ocl.OclExpressions
import dev.kuml.runtime.internal.toEvalMap

/**
 * Default-Implementierung von [GuardEvaluator], die das OCL-Subset aus
 * `kuml-core-ocl` zum Auswerten von Transitions-Guards verwendet.
 *
 * Konventionen:
 *  - `null` oder leerer Guard → [GuardResult.True] (UML 2.5 §15.3.13).
 *  - Eckige Klammern an Anfang/Ende des Guard-Strings werden vor dem
 *    Parse entfernt: `"[isValid]"` → `"isValid"`.
 *  - `self` im OCL-Kontext ist die [ModelInstance].
 *  - `env["event"]` ist eine flache Map-View über das aktuelle [Event]
 *    (Name + Payload-Felder flach).
 *  - `env["vars"]` ist die `variables`-Map der Instanz.
 */
public class OclGuardEvaluator : GuardEvaluator {
    override fun evaluate(
        guard: String?,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult {
        if (guard.isNullOrBlank()) return GuardResult.True
        val cleaned = stripBrackets(guard)
        return try {
            val env: Map<String, Any?> =
                mapOf(
                    "event" to event.toEvalMap(),
                    "vars" to instance.variables,
                )
            val raw = OclExpressions.evaluate(cleaned, self = instance, env = env)
            when (raw) {
                true -> GuardResult.True
                false -> GuardResult.False
                null -> GuardResult.False
                else -> GuardResult.Failed("Guard expression did not evaluate to Boolean (got $raw)")
            }
        } catch (ex: OclEvaluationException) {
            GuardResult.Failed(ex.message ?: ex.javaClass.simpleName)
        } catch (ex: IllegalArgumentException) {
            GuardResult.Failed("Guard parse error: ${ex.message ?: ex.javaClass.simpleName}")
        } catch (ex: IllegalStateException) {
            GuardResult.Failed("Guard error: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    private fun stripBrackets(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("[") && t.endsWith("]")) {
            t.substring(1, t.length - 1).trim()
        } else {
            t
        }
    }
}
