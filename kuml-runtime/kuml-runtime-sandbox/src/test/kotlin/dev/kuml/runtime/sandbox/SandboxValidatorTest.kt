package dev.kuml.runtime.sandbox

import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlFinalState
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SandboxValidatorTest :
    FunSpec({

        fun buildSm(
            entry: String? = null,
            guard: String? = null,
            effect: String? = null,
        ): UmlStateMachine {
            val vertices =
                listOf(
                    UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL),
                    UmlState(id = "A", name = "A", exit = null),
                    UmlState(id = "B", name = "B", entry = entry),
                    UmlFinalState(id = "end", name = "end"),
                )
            val transitions =
                listOf(
                    UmlTransition(id = "t0", sourceId = "init", targetId = "A"),
                    UmlTransition(id = "t1", sourceId = "A", targetId = "B", trigger = "go", guard = guard, effect = effect),
                )
            return UmlStateMachine(id = "sm", name = "sm", vertices = vertices, transitions = transitions)
        }

        test("clean model passes validation") {
            val sm = buildSm(entry = "x = 1", guard = "x > 0")
            val report = SandboxValidator(SandboxPolicy()).validate(sm)
            report.isClean shouldBe true
            report.violations.shouldBeEmpty()
        }

        test("disallowed function produces DISALLOWED_FUNCTION violation") {
            val sm = buildSm(effect = "log.info('hello')")
            val report = SandboxValidator(SandboxPolicy.Strict).validate(sm)
            // Strict policy has no allowed functions
            report.violations.any { it.kind == ViolationKind.DISALLOWED_FUNCTION } shouldBe true
        }

        test("parse error in guard produces PARSE_ERROR violation") {
            val sm = buildSm(guard = "@@@invalid###")
            val report = SandboxValidator(SandboxPolicy()).validate(sm)
            report.violations.any { it.kind == ViolationKind.PARSE_ERROR } shouldBe true
        }

        test("too-deep expression produces EXPRESSION_TOO_DEEP violation") {
            val policy = SandboxPolicy(maxExpressionDepth = 1)
            val sm = buildSm(guard = "a + b + c")
            val report = SandboxValidator(policy).validate(sm)
            report.violations.any { it.kind == ViolationKind.EXPRESSION_TOO_DEEP } shouldBe true
        }

        test("reserved assignment produces RESERVED_VARIABLE_NAME violation") {
            val sm = buildSm(entry = "self = 1")
            val report = SandboxValidator(SandboxPolicy()).validate(sm)
            report.violations.any { it.kind == ViolationKind.RESERVED_VARIABLE_NAME } shouldBe true
        }

        test("unknown function in guard produces violation on strict policy") {
            val sm = buildSm(guard = "unknownFn(x)")
            val report = SandboxValidator(SandboxPolicy.Strict).validate(sm)
            report.violations.any { it.kind == ViolationKind.DISALLOWED_FUNCTION } shouldBe true
        }
    })
