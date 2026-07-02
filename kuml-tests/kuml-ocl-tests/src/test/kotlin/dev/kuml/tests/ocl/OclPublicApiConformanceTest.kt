package dev.kuml.tests.ocl

import dev.kuml.core.ocl.OclExpressions
import dev.kuml.core.ocl.OclValidator
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlConstraint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Black-box OCL conformance smoke test (V3.2.24), exercised purely against
 * `kuml-core-ocl`'s **public** API (`OclExpressions`, `OclValidator`) rather
 * than its `internal` parser/evaluator types.
 *
 * The detailed OMG-example conformance suite and feature-coverage matrix live
 * in `kuml-core-ocl`'s own test sourceset
 * (`OclConformanceTest.kt`/`OclBenchmarkTest.kt`) because [dev.kuml.core.ocl.OclParser]
 * and [dev.kuml.core.ocl.OclEvaluator] are `internal` to that module — this
 * module only sees the public surface, matching how downstream consumers
 * (`kuml-cli`, `kuml-mcp`, `kuml-runtime-core`) actually use OCL. This test
 * exists to prove the *published* API surface stays conformant end-to-end,
 * complementing rather than duplicating the internal suite.
 */
class OclPublicApiConformanceTest :
    FunSpec({

        test("OclExpressions.evaluate resolves String/Integer standard-library operation calls") {
            OclExpressions.evaluate("'shampoo'.substring(2, 4)", self = Unit) shouldBe "ham"
            OclExpressions.evaluate("(-7).abs()", self = Unit) shouldBe 7
            OclExpressions.evaluate("7.mod(3)", self = Unit) shouldBe 1
        }

        test("OclExpressions.evaluate resolves collection iterators over model navigation") {
            val cls =
                UmlClass(
                    id = "Order",
                    name = "Order",
                    constraints =
                        listOf(
                            UmlConstraint(id = "Order::c1", name = "NameLooksReasonable", body = "self.name.size() > 0"),
                        ),
                )
            OclExpressions.evaluate("self.name.size() > 0", self = cls) shouldBe true
        }

        test("OclValidator.validateWithExpressions reports a violation for a failing standard-library constraint") {
            val cls = UmlClass(id = "Order", name = "")
            val result =
                OclValidator.validateWithExpressions(
                    self = cls,
                    elementId = cls.id,
                    elementName = cls.name,
                    constraintBodies = mapOf("NonEmptyName" to "self.name.notEmpty()"),
                )
            result.valid shouldBe false
            result.violations.single().constraintName shouldBe "NonEmptyName"
        }

        test("OclValidator.validateWithExpressions passes when the standard-library constraint holds") {
            val cls = UmlClass(id = "Order", name = "Order")
            val result =
                OclValidator.validateWithExpressions(
                    self = cls,
                    elementId = cls.id,
                    elementName = cls.name,
                    constraintBodies = mapOf("NonEmptyName" to "self.name.notEmpty()"),
                )
            result.valid shouldBe true
        }

        test("OclValidator.parseOclSyntax accepts operation-call syntax without evaluating it") {
            OclValidator.parseOclSyntax("self.name.toUpper().concat('!')")
        }
    })
