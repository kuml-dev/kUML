package dev.kuml.ai.tools.sysml2

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.internal.IdHelpers
import dev.kuml.sysml2.ActivityNodeKind

/**
 * Tools for editing SysML 2 models — covers all eight V2.0.3–V2.0.12 diagram kinds.
 */
@LLMDescription("Tools for editing SysML 2 models — covers all eight V2.0.3–V2.0.12 diagram kinds.")
public class Sysml2EditingTools(
    private val ctx: AgentEditingContext,
) : ToolSet {
    @Tool(customName = "add_part_def")
    @LLMDescription("Adds a SysML 2 part definition (block) to the model. Returns the assigned id. Used on BDD/IBD diagrams.")
    public suspend fun addPartDef(
        @LLMDescription("Block name in PascalCase, e.g. 'PowerTrain'.") name: String,
        @LLMDescription("Optional parent part definition id for nested blocks.") ownerIdOrName: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")
        val id = IdHelpers.uniqueId(name, Sysml2PatchOps.allIds(sysml))

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-bdd",
                elementKind = "sysml2.partdef",
                elementId = id,
                name = name,
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addPartDef(m as AnyKumlModel.Sysml2, id, name)
        }
    }

    @Tool(customName = "add_attribute_def")
    @LLMDescription("Adds a SysML 2 attribute definition. Targeted by an attribute_usage inside a part_def. Used on BDD diagrams.")
    public suspend fun addAttributeDef(
        @LLMDescription("Attribute name in camelCase.") name: String,
        @LLMDescription("Owning part definition id or name.") ownerIdOrName: String,
        @LLMDescription("Type name, e.g. 'Real', 'String', 'Mass'.") type: String,
        @LLMDescription("Optional SysML unit identifier, e.g. 'kg', 'm/s'.") unit: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")
        val owner =
            Sysml2PatchOps.resolveDefinition(sysml, ownerIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Owner '$ownerIdOrName' not found",
                    hint = "Use list_elements to discover available part definition ids",
                )

        val id = IdHelpers.uniqueId(name, Sysml2PatchOps.allIds(sysml), "attr")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-bdd",
                elementKind = "sysml2.attributedef",
                elementId = id,
                name = name,
                payload =
                    buildMap {
                        put("ownerId", owner.id)
                        put("type", type)
                        unit?.let { put("unit", it) }
                    },
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addAttributeDef(m as AnyKumlModel.Sysml2, id, name, owner.id, type, unit)
        }
    }

    @Tool(customName = "add_state")
    @LLMDescription("Adds a state to a state-transition diagram (STM).")
    public suspend fun addState(
        @LLMDescription("State name in PascalCase.") name: String,
        @LLMDescription("Owning state machine id or name; null = top-level.") parentIdOrName: String? = null,
        @LLMDescription("If true, marks this state as initial.") isInitial: Boolean = false,
        @LLMDescription("If true, marks this state as final.") isFinal: Boolean = false,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")
        val id = IdHelpers.uniqueId(name, Sysml2PatchOps.allIds(sysml), "state")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-stm",
                elementKind = "sysml2.statedef",
                elementId = id,
                name = name,
                payload =
                    buildMap {
                        if (isInitial) put("isInitial", "true")
                        if (isFinal) put("isFinal", "true")
                        parentIdOrName?.let { put("parentId", it) }
                    },
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addState(m as AnyKumlModel.Sysml2, id, name, isInitial, isFinal)
        }
    }

    @Tool(customName = "add_transition")
    @LLMDescription("Adds a transition between two states on a state-transition diagram.")
    public suspend fun addTransition(
        @LLMDescription("Source state id or name.") sourceIdOrName: String,
        @LLMDescription("Target state id or name.") targetIdOrName: String,
        @LLMDescription("Triggering event name, e.g. 'orderPlaced'.") trigger: String,
        @LLMDescription("Optional guard expression, e.g. 'amount > 0'.") guard: String? = null,
        @LLMDescription("Optional action expression executed on transition.") action: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")

        val source =
            Sysml2PatchOps.resolveDefinition(sysml, sourceIdOrName)
                ?: return PatchApplyResult.Failure(reason = "Source state '$sourceIdOrName' not found")
        val target =
            Sysml2PatchOps.resolveDefinition(sysml, targetIdOrName)
                ?: return PatchApplyResult.Failure(reason = "Target state '$targetIdOrName' not found")

        val id = IdHelpers.uniqueId("transition_${source.name}_${target.name}", Sysml2PatchOps.allIds(sysml))

        val patch =
            ModelPatch.AddRelationship(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-stm",
                relationshipKind = "sysml2.transition",
                relationshipId = id,
                sourceId = source.id,
                targetId = target.id,
                payload =
                    buildMap {
                        put("trigger", trigger)
                        guard?.let { put("guard", it) }
                        action?.let { put("action", it) }
                    },
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addTransition(m as AnyKumlModel.Sysml2, id, source.id, target.id, trigger, guard, action)
        }
    }

    @Tool(customName = "add_use_case")
    @LLMDescription("Adds a SysML 2 use case to a use-case diagram (UcDiagram).")
    public suspend fun addUseCase(
        @LLMDescription("Use case name in 'Verb + Noun' phrasing, e.g. 'Place Order'.") name: String,
        @LLMDescription("Optional actor name; created if absent.") actorName: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")
        val id = IdHelpers.uniqueId(name, Sysml2PatchOps.allIds(sysml), "uc")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-uc",
                elementKind = "sysml2.usecasedef",
                elementId = id,
                name = name,
                payload = actorName?.let { mapOf("actorName" to it) } ?: emptyMap(),
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addUseCase(m as AnyKumlModel.Sysml2, id, name, null, actorName)
        }
    }

    @Tool(customName = "add_requirement")
    @LLMDescription("Adds a requirement definition to a requirement diagram (ReqDiagram).")
    public suspend fun addRequirement(
        @LLMDescription("Requirement id label as it should appear on the diagram, e.g. 'REQ-001'.") reqId: String,
        @LLMDescription("Short requirement text.") text: String,
        @LLMDescription("Optional parent requirement id if this is a derived requirement.") parentReqId: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")
        val id = IdHelpers.uniqueId(reqId, Sysml2PatchOps.allIds(sysml), "req")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-req",
                elementKind = "sysml2.reqdef",
                elementId = id,
                name = reqId,
                payload =
                    buildMap {
                        put("text", text)
                        parentReqId?.let { put("parentReqId", it) }
                    },
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addRequirement(m as AnyKumlModel.Sysml2, id, reqId, text)
        }
    }

    @Tool(customName = "add_action")
    @LLMDescription("Adds an action to an activity diagram (ActDiagram).")
    public suspend fun addAction(
        @LLMDescription("Action name.") name: String,
        @LLMDescription("Parent activity id or name; null = top activity.") parentIdOrName: String? = null,
        @LLMDescription(
            "Action kind: 'opaque', 'send_signal', 'accept_event', 'call_behavior'. Default 'opaque'.",
        ) kind: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")
        val id = IdHelpers.uniqueId(name, Sysml2PatchOps.allIds(sysml), "action")

        val activityNodeKind =
            when (kind?.lowercase()) {
                null, "opaque", "" -> ActivityNodeKind.Action
                "send_signal" -> ActivityNodeKind.Action // SysML 2 uses ActionDefinition for send-signal
                "accept_event" -> ActivityNodeKind.Action
                "call_behavior" -> ActivityNodeKind.Action
                "initial" -> ActivityNodeKind.Initial
                "final" -> ActivityNodeKind.Final
                "flow_final" -> ActivityNodeKind.FlowFinal
                "decision" -> ActivityNodeKind.Decision
                "merge" -> ActivityNodeKind.Merge
                "fork" -> ActivityNodeKind.Fork
                "join" -> ActivityNodeKind.Join
                else -> {
                    val validKinds =
                        "opaque, send_signal, accept_event, call_behavior," +
                            " initial, final, flow_final, decision, merge, fork, join"
                    return PatchApplyResult.Failure(reason = "Unknown action kind '$kind'", hint = "Valid: $validKinds")
                }
            }

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-act",
                elementKind = "sysml2.actiondef",
                elementId = id,
                name = name,
                payload = buildMap { kind?.let { put("kind", it) } },
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addAction(m as AnyKumlModel.Sysml2, id, name, activityNodeKind)
        }
    }

    @Tool(customName = "add_constraint")
    @LLMDescription("Adds a SysML 2 constraint (typically used on parametric diagrams).")
    public suspend fun addConstraint(
        @LLMDescription("Constraint name.") name: String,
        @LLMDescription("Constraint expression in SysML 2 syntax, e.g. 'force == mass * acceleration'.") expression: String,
        @LLMDescription("Owning part definition or constraint definition id.") ownerIdOrName: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val sysml = model as? AnyKumlModel.Sysml2 ?: return PatchApplyResult.Failure("Context is not a SysML 2 model")
        val id = IdHelpers.uniqueId(name, Sysml2PatchOps.allIds(sysml), "constraint")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-par",
                elementKind = "sysml2.constraintdef",
                elementId = id,
                name = name,
                payload =
                    buildMap {
                        put("expression", expression)
                        ownerIdOrName?.let { put("ownerId", it) }
                    },
            )

        return ctx.applyPatch(patch) { m ->
            Sysml2PatchOps.addConstraint(m as AnyKumlModel.Sysml2, id, name, expression)
        }
    }
}
