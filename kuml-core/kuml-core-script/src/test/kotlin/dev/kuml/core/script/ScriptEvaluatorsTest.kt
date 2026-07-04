package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Config resolution for [ScriptEvaluators] — verifies the fail-closed default
 * (warm pool, Welle 3), the Welle-2 cold-start opt-in, and the in-process
 * opt-out.
 *
 * The pool-producing cases immediately close the returned evaluator so the test
 * does not leak the warm worker JVMs it spins up.
 *
 * V0.23.3.
 */
class ScriptEvaluatorsTest :
    FunSpec({

        /** Resolves the evaluator and, if it is a closeable pool, closes it right away. */
        fun resolve(mode: String?): ScriptEvaluator = ScriptEvaluators.forMode(mode).also { (it as? AutoCloseable)?.close() }

        test("default (unset) resolves to the warm pool") {
            resolve(null).shouldBeInstanceOf<PooledScriptEvaluator>()
        }

        test("explicit pool resolves to the warm pool") {
            resolve("pool").shouldBeInstanceOf<PooledScriptEvaluator>()
        }

        test("explicit child-process resolves to the cold-start sandbox") {
            resolve("child-process").shouldBeInstanceOf<ChildProcessScriptEvaluator>()
        }

        test("explicit in-process resolves to the in-process evaluator") {
            resolve("in-process").shouldBeInstanceOf<InProcessScriptEvaluator>()
        }

        test("unrecognised value falls back to the secure default (warm pool)") {
            resolve("banana").shouldBeInstanceOf<PooledScriptEvaluator>()
        }

        test("mode is case- and whitespace-insensitive") {
            resolve("  IN-PROCESS  ").shouldBeInstanceOf<InProcessScriptEvaluator>()
        }
    })
