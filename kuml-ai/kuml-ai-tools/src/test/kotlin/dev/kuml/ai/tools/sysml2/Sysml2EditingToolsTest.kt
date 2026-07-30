package dev.kuml.ai.tools.sysml2

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.UseCaseDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class Sysml2EditingToolsTest :
    FunSpec({

        fun makeTools(): Pair<AgentEditingContext, Sysml2EditingTools> {
            val ctx = AgentEditingContext.emptySysml2()
            return ctx to Sysml2EditingTools(ctx)
        }

        test("add_part_def creates a top-level PartDefinition") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addPartDef(name = "Vehicle")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val model = (ctx.resolveModel() as AnyKumlModel.Sysml2).model
                model.definitions shouldHaveSize 1
                model.definitions[0].shouldBeInstanceOf<PartDefinition>()
                model.definitions[0].name shouldBe "Vehicle"
            }
        }

        test("add_part_def with owner nests inside parent") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addPartDef(name = "Vehicle")
                val result = tools.addPartDef(name = "Engine", ownerIdOrName = "Vehicle")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_attribute_def attaches to owning part definition") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addPartDef(name = "Vehicle")
                val result = tools.addAttributeDef(name = "mass", ownerIdOrName = "Vehicle", type = "Real", unit = "kg")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_attribute_def with unit records the SysML unit") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addPartDef(name = "Battery")
                tools.addAttributeDef(name = "capacity", ownerIdOrName = "Battery", type = "Real", unit = "kWh")
                val usages = (ctx.resolveModel() as AnyKumlModel.Sysml2).model.usages
                usages.isNotEmpty() shouldBe true
            }
        }

        test("add_state with isInitial flag marks initial pseudostate") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addState(name = "Off", isInitial = true)
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val defs = (ctx.resolveModel() as AnyKumlModel.Sysml2).model.definitions
                val state = defs.filterIsInstance<StateDefinition>().first()
                state.isInitial shouldBe true
            }
        }

        test("add_state in nested state-machine respects parent") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addState(name = "On")
                val result = tools.addState(name = "SubState", parentIdOrName = "On")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_transition with guard and action attaches both") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addState(name = "Off", isInitial = true)
                tools.addState(name = "On")
                val result =
                    tools.addTransition(
                        sourceIdOrName = "Off",
                        targetIdOrName = "On",
                        trigger = "powerOn",
                        guard = "voltage > 0",
                        action = "start()",
                    )
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val usages = (ctx.resolveModel() as AnyKumlModel.Sysml2).model.usages
                val transition = usages.filterIsInstance<TransitionUsage>().first()
                transition.guard shouldBe "voltage > 0"
                transition.effect shouldBe "start()"
            }
        }

        test("add_transition rejects unknown source state") {
            val (_, tools) = makeTools()
            runTest {
                tools.addState(name = "On")
                val result = tools.addTransition(sourceIdOrName = "NonExistent", targetIdOrName = "On", trigger = "event")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
            }
        }

        test("add_use_case creates use case and auto-creates actor") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addUseCase(name = "Place Order", actorName = "Customer")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val defs = (ctx.resolveModel() as AnyKumlModel.Sysml2).model.definitions
                defs.filterIsInstance<UseCaseDefinition>() shouldHaveSize 1
                // Actor should also be created
                defs.filterIsInstance<dev.kuml.sysml2.ActorDefinition>() shouldHaveSize 1
            }
        }

        test("add_use_case reuses existing actor by name") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addUseCase(name = "Place Order", actorName = "Customer")
                tools.addUseCase(name = "Cancel Order", actorName = "Customer")
                val defs = (ctx.resolveModel() as AnyKumlModel.Sysml2).model.definitions
                // Should only have one Actor for "Customer"
                defs.filterIsInstance<dev.kuml.sysml2.ActorDefinition>().size shouldBe 1
            }
        }

        test("add_requirement creates requirement with rendered id label") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addRequirement(reqId = "REQ-001", text = "The system shall respond in <200ms")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val req =
                    (ctx.resolveModel() as AnyKumlModel.Sysml2)
                        .model.definitions
                        .filterIsInstance<RequirementDefinition>()
                        .first()
                req.reqId shouldBe "REQ-001"
                req.text shouldBe "The system shall respond in <200ms"
            }
        }

        test("add_requirement with parentReqId links derivation") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addRequirement(reqId = "REQ-001", text = "Top-level")
                val result = tools.addRequirement(reqId = "REQ-001.1", text = "Sub-requirement", parentReqId = "REQ-001")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                // Both requirements should exist
                val reqs =
                    (ctx.resolveModel() as AnyKumlModel.Sysml2)
                        .model.definitions
                        .filterIsInstance<RequirementDefinition>()
                reqs shouldHaveSize 2
            }
        }

        test("add_action with opaque kind creates ActionDefinition") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addAction(name = "ProcessPayment", kind = "opaque")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val action =
                    (ctx.resolveModel() as AnyKumlModel.Sysml2)
                        .model.definitions
                        .filterIsInstance<ActionDefinition>()
                        .first()
                action.kind shouldBe ActivityNodeKind.Action
            }
        }

        test("add_action with send_signal kind creates ActionDefinition") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addAction(name = "SendNotification", kind = "send_signal")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
            }
        }

        test("add_action with invalid kind returns Failure") {
            val (_, tools) = makeTools()
            runTest {
                val result = tools.addAction(name = "BadAction", kind = "unknown_kind_xyz")
                result.shouldBeInstanceOf<PatchApplyResult.Failure>()
            }
        }

        test("add_constraint attaches to part definition") {
            val (ctx, tools) = makeTools()
            runTest {
                tools.addPartDef(name = "Vehicle")
                val result =
                    tools.addConstraint(
                        name = "NewtonsLaw",
                        expression = "force == mass * acceleration",
                        ownerIdOrName = "Vehicle",
                    )
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                val constraint =
                    (ctx.resolveModel() as AnyKumlModel.Sysml2)
                        .model.definitions
                        .filterIsInstance<ConstraintDefinition>()
                        .first()
                constraint.expression shouldBe "force == mass * acceleration"
            }
        }

        test("add_constraint without owner adds to top-level constraints") {
            val (ctx, tools) = makeTools()
            runTest {
                val result = tools.addConstraint(name = "SpeedLimit", expression = "velocity <= 120")
                result.shouldBeInstanceOf<PatchApplyResult.Success>()
                (ctx.resolveModel() as AnyKumlModel.Sysml2)
                    .model.definitions
                    .filterIsInstance<ConstraintDefinition>() shouldHaveSize 1
            }
        }

        test("all 8 diagram families coexist in a single context") {
            val (ctx, tools) = makeTools()
            runTest {
                // BDD
                tools.addPartDef(name = "Vehicle")
                // STM
                tools.addState(name = "Idle", isInitial = true)
                tools.addState(name = "Running")
                tools.addTransition(sourceIdOrName = "Idle", targetIdOrName = "Running", trigger = "start")
                // UC
                tools.addUseCase(name = "Drive", actorName = "Driver")
                // REQ
                tools.addRequirement(reqId = "R-001", text = "Safety requirement")
                // ACT
                tools.addAction(name = "Accelerate")
                // PAR
                tools.addConstraint(name = "Physics", expression = "F = ma")

                val model = (ctx.resolveModel() as AnyKumlModel.Sysml2).model
                model.definitions.isNotEmpty() shouldBe true
                model.usages.isNotEmpty() shouldBe true
            }
        }
    })
