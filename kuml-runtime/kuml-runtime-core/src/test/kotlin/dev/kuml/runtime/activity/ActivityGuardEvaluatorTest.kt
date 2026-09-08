package dev.kuml.runtime.activity

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import dev.kuml.runtime.TraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [ActivityGuardEvaluator] (ADR-0015 / security fix B2).
 *
 * Before this class existed, `ActivityRuntime.evaluateGuard` called
 * `OclExpressions.evaluate` directly and never consulted the injected
 * `guardEvaluator` at all — a `TimeLimitedGuardEvaluator` wrapping it had no
 * effect. [ActivityRuntimeTest] ("wired guard evaluator" tests below) covers
 * the regression at the `ActivityRuntime` integration level; this file covers
 * the evaluator's own evaluation-environment contract in isolation.
 */
private class TestModelInstance(
    variables: Map<String, Any?>,
) : ModelInstance<Any> {
    override val model: Any get() = error("TestModelInstance.model should not be accessed")
    override val currentVertices: List<dev.kuml.uml.UmlVertex> = emptyList()
    override val variables: MutableMap<String, Any?> = variables.toMutableMap()
    override val isTerminated: Boolean = false
    override val trace: List<TraceEntry> = emptyList()
}

class ActivityGuardEvaluatorTest :
    FunSpec({

        val ev = ActivityGuardEvaluator()

        fun instance(vars: Map<String, Any?> = emptyMap()) = TestModelInstance(vars)

        test("null guard returns True") {
            ev.evaluate(guard = null, instance = instance(), event = Event.of("advance")) shouldBe GuardResult.True
        }

        test("blank guard returns True") {
            ev.evaluate(guard = "   ", instance = instance(), event = Event.of("advance")) shouldBe GuardResult.True
        }

        test("bare identifier resolves directly against instance variables (legacy semantics)") {
            ev.evaluate(guard = "allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "allow", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("'not <identifier>' negates a bare identifier (OCL spells negation 'not', not '!')") {
            ev.evaluate(guard = "not allow", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "not allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("square brackets are stripped before parsing") {
            ev.evaluate(guard = "[allow]", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.True
        }

        test("event.<key> and vars.<key> access the same instance variables") {
            val i = instance(mapOf("flag" to true))
            ev.evaluate(guard = "event.flag", instance = i, event = Event.of("advance")) shouldBe GuardResult.True
            ev.evaluate(guard = "vars.flag", instance = i, event = Event.of("advance")) shouldBe GuardResult.True
        }

        test("missing variable is treated as false, not an exception") {
            ev.evaluate(guard = "missing", instance = instance(), event = Event.of("advance")) shouldBe GuardResult.False
        }

        test("parse error is swallowed as False (never throws, per class contract)") {
            val result = ev.evaluate(guard = "!!! invalid !!!", instance = instance(), event = Event.of("advance"))
            result shouldBe GuardResult.False
        }

        test("non-boolean result is treated as Failed") {
            val result = ev.evaluate(guard = "42", instance = instance(), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }
    })
