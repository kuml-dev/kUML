package dev.kuml.core.ocl

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.uml.UmlConstraint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * V3.2.23 — OCL over BPMN + SysML 2 models.
 *
 * Covers [OclValidator.validateBpmn] / [OclValidator.validateSysml2] and the
 * [KumlViolation.sourcePosition] field populated by [OclLexer.tokenizeWithPositions].
 */
class OclBpmnSysml2Test :
    FunSpec({

        // ── BPMN ─────────────────────────────────────────────────────────────

        test("BPMN process with satisfied invariant is valid") {
            val model: BpmnModel =
                bpmnModel("Order Management") {
                    process(id = "orderProcess", name = "Order Process") {
                        startEvent("Start")
                        endEvent("End")
                        constraint(name = "hasFlowNodes", body = "self.flowNodes->size() > 0")
                    }
                }
            val result = OclValidator.validateBpmn(model)
            result.valid shouldBe true
            result.violations.size shouldBe 0
        }

        test("BPMN process with violated invariant reports a KumlViolation") {
            val model: BpmnModel =
                bpmnModel("Empty Process") {
                    process(id = "emptyProcess", name = "Empty Process") {
                        constraint(name = "hasFlowNodes", body = "self.flowNodes->size() > 0")
                    }
                }
            val result = OclValidator.validateBpmn(model)
            result.valid shouldBe false
            result.violations.size shouldBe 1
            result.violations.first().classifierId shouldBe "emptyProcess"
            result.violations.first().sourcePosition shouldBe OclPosition(line = 1, col = 1)
        }

        test("BPMN process constraint parse error carries the failing token's source position") {
            val model: BpmnModel =
                bpmnModel("Broken") {
                    process(id = "brokenProcess", name = "Broken Process") {
                        // Missing closing paren — a syntax error at a known column.
                        constraint(name = "broken", body = "self.flowNodes->size(")
                    }
                }
            val result = OclValidator.validateBpmn(model)
            result.valid shouldBe false
            val violation = result.violations.first()
            (violation.sourcePosition != null) shouldBe true
        }

        test("BPMN process without constraints validates trivially") {
            val model: BpmnModel =
                bpmnModel("Plain") {
                    process(id = "plainProcess", name = "Plain Process") {
                        startEvent("Start")
                        endEvent("End")
                    }
                }
            val result = OclValidator.validateBpmn(model)
            result.valid shouldBe true
            result.violations.size shouldBe 0
        }

        // ── SysML 2 ──────────────────────────────────────────────────────────

        test("SysML 2 PartDefinition with satisfied invariant is valid") {
            val model: Sysml2Model =
                sysml2Model("Vehicle") {
                    partDef(name = "Engine") {
                        attribute(name = "mass", typeId = "Mass")
                        constraint(name = "hasFeatures", body = "self.features->size() > 0")
                    }
                }
            val result = OclValidator.validateSysml2(model)
            result.valid shouldBe true
            result.violations.size shouldBe 0
        }

        test("SysML 2 PartDefinition invariant can navigate to a named feature") {
            val model: Sysml2Model =
                sysml2Model("Vehicle") {
                    partDef(name = "Engine") {
                        attribute(name = "mass", typeId = "Mass")
                        constraint(name = "massNamedCorrectly", body = "self.mass.name = 'mass'")
                    }
                }
            val result = OclValidator.validateSysml2(model)
            result.valid shouldBe true
            result.violations.size shouldBe 0
        }

        test("SysML 2 PartDefinition with violated invariant reports a KumlViolation") {
            val model: Sysml2Model =
                sysml2Model("Vehicle") {
                    partDef(name = "Engine") {
                        constraint(name = "hasFeatures", body = "self.features->size() > 0")
                    }
                }
            val result = OclValidator.validateSysml2(model)
            result.valid shouldBe false
            result.violations.size shouldBe 1
            val part = model.definitions.filterIsInstance<PartDefinition>().first()
            result.violations.first().classifierId shouldBe part.id
        }

        test("SysML 2 PartDefinition without constraints validates trivially") {
            val model: Sysml2Model =
                sysml2Model("Vehicle") {
                    partDef(name = "Engine") {
                        attribute(name = "mass", typeId = "Mass")
                    }
                }
            val result = OclValidator.validateSysml2(model)
            result.valid shouldBe true
            result.violations.size shouldBe 0
        }

        // ── Source positions (V3.2.23) ──────────────────────────────────────────

        test("tokenizeWithPositions tracks 1-based line/col for a single-line expression") {
            val (tokens, positions) = OclLexer.tokenizeWithPositions("self.name")
            tokens.size shouldBe positions.size
            // 'self' at col 1, '.' at col 5, 'name' at col 6
            positions[0] shouldBe OclPosition(line = 1, col = 1)
            positions[1] shouldBe OclPosition(line = 1, col = 5)
            positions[2] shouldBe OclPosition(line = 1, col = 6)
        }

        test("tokenizeWithPositions tracks line breaks across a multi-line expression") {
            val (_, positions) = OclLexer.tokenizeWithPositions("self.name\n= 'x'")
            // Token after the newline should be on line 2.
            val afterNewline = positions.first { it.line == 2 }
            afterNewline.col shouldBe 1
        }

        test("evaluation-failure violation falls back to constraint-body-start position") {
            val model: Sysml2Model =
                sysml2Model("Vehicle") {
                    partDef(name = "Engine") {
                        constraint(name = "alwaysFalse", body = "1 = 2")
                    }
                }
            val result = OclValidator.validateSysml2(model)
            result.violations.first().sourcePosition shouldBe OclPosition(line = 1, col = 1)
        }

        test("KumlViolation is additive-serializable with sourcePosition") {
            val v =
                KumlViolation(
                    constraintId = "c1",
                    constraintName = "c",
                    classifierId = "C",
                    classifierName = "C",
                    oclExpression = "self.x",
                    message = "msg",
                    sourcePosition = OclPosition(line = 2, col = 3),
                )
            v.sourcePosition shouldBe OclPosition(line = 2, col = 3)
        }

        test("parser reports the offending token's own position, not the following token's") {
            // Regression test (V3.2.23 review fix): the parser used to capture
            // currentPosition() *after* consume() at all `(consume() as? OclToken.Ident)`
            // call sites, so the reported position pointed one token past the
            // actual failure. `let 5 = x in x` fails because '5' (col 5) is not
            // an identifier after 'let' — the exception must point at col 5,
            // not at '=' (col 7).
            val (tokens, positions) = OclLexer.tokenizeWithPositions("let 5 = x in x")
            val result = kotlin.runCatching { OclParser(tokens, positions).parse() }
            val exception = result.exceptionOrNull() as? OclEvaluationException
            exception.shouldNotBeNull()
            exception.position shouldBe OclPosition(line = 1, col = 5)
        }

        test("iterate() parser reports the iterator variable's own position on error") {
            // Same off-by-one class of bug, for the iterate(...) two-variable form.
            val (tokens, positions) = OclLexer.tokenizeWithPositions("self->iterate(1; acc = 0 | acc)")
            val result = kotlin.runCatching { OclParser(tokens, positions).parse() }
            val exception = result.exceptionOrNull() as? OclEvaluationException
            exception.shouldNotBeNull()
            // 'self'=1..4, '->'=5..6, 'iterate'=7..13, '('=14, '1'=15
            exception.position shouldBe OclPosition(line = 1, col = 15)
        }

        test("UmlConstraint reused across BPMN and SysML2 keeps a single constraint shape") {
            // Sanity check that both metamodels reuse the same UmlConstraint type
            // (V3.2.23 design decision — no parallel constraint AST).
            val c = UmlConstraint(id = "x", name = "x", body = "true")
            c.language shouldBe "OCL"
        }
    })
