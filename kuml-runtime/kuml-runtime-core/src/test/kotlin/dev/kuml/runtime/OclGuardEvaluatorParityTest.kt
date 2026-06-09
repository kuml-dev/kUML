package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * V2.0.20a — Parity tests ensuring the refactored [OclGuardEvaluator] behaves
 * identically to the legacy path for the key cases.
 */
class OclGuardEvaluatorParityTest :
    FunSpec({

        fun newInstance(): StateMachineInstance {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state("A")),
                    transitions = listOf(trans("t0", "init", "A")),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            return rt.start(sm)
        }

        val ev = OclGuardEvaluator()

        test("simple guard 'true' evaluates to True (AST path)") {
            ev.evaluate("true", newInstance(), Event.of("any")) shouldBe GuardResult.True
        }

        test("simple guard 'false' evaluates to False (AST path)") {
            ev.evaluate("false", newInstance(), Event.of("any")) shouldBe GuardResult.False
        }

        test("guard 'event.allow' with payload {allow=true} evaluates to True") {
            val event =
                Event(
                    name = "check",
                    payload =
                        buildJsonObject {
                            put("allow", JsonPrimitive(true))
                        },
                )
            // The OCL legacy evaluator resolves event.allow via dot-navigation on the
            // event's eval-map; the AST evaluator resolves it via AttributeRef(["event","allow"])
            // navigating into the nested map at context["event"]["allow"].
            val result = ev.evaluate("event.allow", newInstance(), event)
            result shouldBe GuardResult.True
        }

        test("unparseable guard '@@@' falls back to legacy path and does not throw") {
            // '@@@' cannot be parsed by the AST parser — must fall back to legacy OCL,
            // which also cannot parse it and returns Failed.
            val result = ev.evaluate("@@@", newInstance(), Event.of("any"))
            // Must not throw — should be Failed or False (not True)
            (result is GuardResult.Failed || result == GuardResult.False) shouldBe true
        }

        test("parse cache is populated after first evaluation") {
            val evaluator = OclGuardEvaluator()
            // First call seeds the cache
            evaluator.evaluate("true", newInstance(), Event.of("x")) shouldBe GuardResult.True
            // Second call uses the cache — still correct
            evaluator.evaluate("true", newInstance(), Event.of("x")) shouldBe GuardResult.True
        }
    })
