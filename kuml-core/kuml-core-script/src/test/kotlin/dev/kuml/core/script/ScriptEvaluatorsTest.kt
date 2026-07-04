package dev.kuml.core.script

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Config resolution for [ScriptEvaluators] — verifies the fail-closed default
 * (child-process) and the explicit in-process opt-out.
 *
 * V0.23.3.
 */
class ScriptEvaluatorsTest :
    FunSpec({

        test("default (unset) resolves to the child-process sandbox") {
            ScriptEvaluators.forMode(null).shouldBeInstanceOf<ChildProcessScriptEvaluator>()
        }

        test("explicit child-process resolves to the sandbox") {
            ScriptEvaluators.forMode("child-process").shouldBeInstanceOf<ChildProcessScriptEvaluator>()
        }

        test("explicit in-process resolves to the in-process evaluator") {
            ScriptEvaluators.forMode("in-process").shouldBeInstanceOf<InProcessScriptEvaluator>()
        }

        test("unrecognised value falls back to the secure default (child-process)") {
            ScriptEvaluators.forMode("banana").shouldBeInstanceOf<ChildProcessScriptEvaluator>()
        }

        test("mode is case- and whitespace-insensitive") {
            ScriptEvaluators.forMode("  IN-PROCESS  ").shouldBeInstanceOf<InProcessScriptEvaluator>()
        }
    })
