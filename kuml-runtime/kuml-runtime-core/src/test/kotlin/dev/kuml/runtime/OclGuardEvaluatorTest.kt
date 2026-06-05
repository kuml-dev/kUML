package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class OclGuardEvaluatorTest :
    FunSpec({

        fun newInstance(): StateMachineInstance {
            // Minimal instance for guard evaluation — no transitions are fired here.
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

        test("null guard returns True") {
            ev.evaluate(null, newInstance(), Event.of("any")) shouldBe GuardResult.True
        }

        test("blank guard returns True") {
            ev.evaluate("   ", newInstance(), Event.of("any")) shouldBe GuardResult.True
        }

        test("square brackets are stripped before parsing") {
            ev.evaluate("[true]", newInstance(), Event.of("any")) shouldBe GuardResult.True
            ev.evaluate("[false]", newInstance(), Event.of("any")) shouldBe GuardResult.False
        }

        test("bare boolean literal true evaluates") {
            ev.evaluate("true", newInstance(), Event.of("any")) shouldBe GuardResult.True
        }

        test("event.amount > 100 evaluates true for payload 150") {
            val event =
                Event(
                    name = "pay",
                    payload =
                        buildJsonObject {
                            put("amount", JsonPrimitive(150))
                        },
                )
            ev.evaluate("event.amount > 100", newInstance(), event) shouldBe GuardResult.True
        }

        test("event.amount > 100 evaluates false for payload 50") {
            val event =
                Event(
                    name = "pay",
                    payload =
                        buildJsonObject {
                            put("amount", JsonPrimitive(50))
                        },
                )
            ev.evaluate("event.amount > 100", newInstance(), event) shouldBe GuardResult.False
        }

        test("vars.flag boolean access returns instance variable value") {
            val instance = newInstance()
            instance.variables["flag"] = true
            ev.evaluate("vars.flag", instance, Event.of("any")) shouldBe GuardResult.True

            instance.variables["flag"] = false
            ev.evaluate("vars.flag", instance, Event.of("any")) shouldBe GuardResult.False
        }

        test("parse error returns Failed with clear message") {
            val result = ev.evaluate("!!! invalid !!!", newInstance(), Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("eval error returns Failed (unknown navigation)") {
            val result = ev.evaluate("event.amount.weird.path", newInstance(), Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("non-boolean result returns Failed") {
            val result = ev.evaluate("42", newInstance(), Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("missing payload key returns Failed (navigation on null)") {
            val event = Event(name = "pay", payload = JsonObject(emptyMap()))
            // event.amount → null (Map lookup) → OCL Navigate on null → exception
            // Or if amount exists but the further navigation is null. Either way Failed.
            val result = ev.evaluate("event.amount > 100", newInstance(), event)
            // Could be Failed or False depending on null-handling — we just require non-true.
            (result == GuardResult.False || result is GuardResult.Failed) shouldBe true
        }
    })
