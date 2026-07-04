package dev.kuml.core.script

import dev.kuml.core.script.interpreter.InterpreterScriptEvaluator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies the Welle-9 evaluation-strategy switch on [ScriptEvaluators].
 *
 * The interpreter is **opt-in**: only `KUML_MCP_SANDBOX_EVAL_STRATEGY=interpreter`
 * selects it. Everything else (unset, `compiler`, garbage) keeps the historical
 * compiler path — so an accidentally-mistyped value can never silently downgrade
 * to the narrow interpreter slice.
 */
class ScriptEvaluatorStrategyTest :
    StringSpec({
        "strategy=interpreter selects the interpreter evaluator" {
            val e = ScriptEvaluators.forStrategyAndMode(strategy = "interpreter", mode = null)
            e shouldBe InterpreterScriptEvaluator
        }

        "strategy=interpreter ignores the containment mode (interpreter is safe in-process)" {
            val e = ScriptEvaluators.forStrategyAndMode(strategy = "interpreter", mode = "pool")
            e shouldBe InterpreterScriptEvaluator
        }

        "strategy is case-insensitive and trims whitespace" {
            ScriptEvaluators.forStrategyAndMode(strategy = "  Interpreter ", mode = null) shouldBe InterpreterScriptEvaluator
        }

        "unset strategy → compiler path (default is NOT the interpreter)" {
            val e = ScriptEvaluators.forStrategyAndMode(strategy = null, mode = "in-process")
            e.shouldBeInstanceOf<ScriptEvaluator>()
            (e === InterpreterScriptEvaluator) shouldBe false
            e shouldBe InProcessScriptEvaluator
        }

        "strategy=compiler → compiler path" {
            val e = ScriptEvaluators.forStrategyAndMode(strategy = "compiler", mode = "in-process")
            (e === InterpreterScriptEvaluator) shouldBe false
        }

        "an unrecognised strategy value falls back to the compiler path, never the interpreter" {
            val e = ScriptEvaluators.forStrategyAndMode(strategy = "typo-here", mode = "in-process")
            (e === InterpreterScriptEvaluator) shouldBe false
        }
    })
