package dev.kuml.ai.tools.c4

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.internal.IdHelpers

/**
 * Tools for editing C4-architecture diagrams (Context / Container / Component) in kUML.
 */
@LLMDescription("Tools for editing C4-architecture diagrams (Context / Container / Component) in kUML.")
public class C4EditingTools(
    private val ctx: AgentEditingContext,
) : ToolSet {
    @Tool(customName = "add_person")
    @LLMDescription("Adds a Person actor to the current C4 model (typically appears on the Context diagram).")
    public suspend fun addPerson(
        @LLMDescription("Person name, e.g. 'Customer'.") name: String,
        @LLMDescription("Short description of the role, max ~80 chars.") description: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val c4 = model as? AnyKumlModel.C4 ?: return PatchApplyResult.Failure("Context is not a C4 model")
        val id = IdHelpers.uniqueId(name, C4PatchOps.allIds(c4), "person")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-context-diagram",
                elementKind = "c4.person",
                elementId = id,
                name = name,
            )

        return ctx.applyPatch(patch) { m ->
            C4PatchOps.addPerson(m as AnyKumlModel.C4, id, name, description)
        }
    }

    @Tool(customName = "add_software_system")
    @LLMDescription("Adds a Software System (top-level C4 element).")
    public suspend fun addSoftwareSystem(
        @LLMDescription("System name.") name: String,
        @LLMDescription("Short description.") description: String? = null,
        @LLMDescription("If true, the system is marked external (rendered with dashed border).") isExternal: Boolean = false,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val c4 = model as? AnyKumlModel.C4 ?: return PatchApplyResult.Failure("Context is not a C4 model")
        val id = IdHelpers.uniqueId(name, C4PatchOps.allIds(c4), "system")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-context-diagram",
                elementKind = "c4.software_system",
                elementId = id,
                name = name,
                payload = if (isExternal) mapOf("external" to "true") else emptyMap(),
            )

        return ctx.applyPatch(patch) { m ->
            C4PatchOps.addSoftwareSystem(m as AnyKumlModel.C4, id, name, description, isExternal)
        }
    }

    @Tool(customName = "add_container")
    @LLMDescription("Adds a Container inside the given Software System.")
    public suspend fun addContainer(
        @LLMDescription("Parent Software System id or name.") systemIdOrName: String,
        @LLMDescription("Container name, e.g. 'Web Application'.") name: String,
        @LLMDescription("Optional technology label, e.g. 'Spring Boot 3'.") technology: String? = null,
        @LLMDescription("Short description.") description: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val c4 = model as? AnyKumlModel.C4 ?: return PatchApplyResult.Failure("Context is not a C4 model")
        val system =
            C4PatchOps.resolveElement(c4, systemIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Software System '$systemIdOrName' not found",
                    hint = "Use list_elements to discover available system ids",
                )

        val id = IdHelpers.uniqueId(name, C4PatchOps.allIds(c4), "container")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-container-diagram",
                elementKind = "c4.container",
                elementId = id,
                name = name,
                payload = mapOf("systemId" to system.id),
            )

        return ctx.applyPatch(patch) { m ->
            C4PatchOps.addContainer(m as AnyKumlModel.C4, id, system.id, name, technology, description)
        }
    }

    @Tool(customName = "add_component")
    @LLMDescription("Adds a Component inside the given Container.")
    public suspend fun addComponent(
        @LLMDescription("Parent Container id or name.") containerIdOrName: String,
        @LLMDescription("Component name, e.g. 'OrderController'.") name: String,
        @LLMDescription("Optional technology label.") technology: String? = null,
        @LLMDescription("Short description.") description: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val c4 = model as? AnyKumlModel.C4 ?: return PatchApplyResult.Failure("Context is not a C4 model")
        val container =
            C4PatchOps.resolveElement(c4, containerIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Container '$containerIdOrName' not found",
                    hint = "Use list_elements to discover available container ids",
                )

        val id = IdHelpers.uniqueId(name, C4PatchOps.allIds(c4), "component")

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-component-diagram",
                elementKind = "c4.component",
                elementId = id,
                name = name,
                payload = mapOf("containerId" to container.id),
            )

        return ctx.applyPatch(patch) { m ->
            C4PatchOps.addComponent(m as AnyKumlModel.C4, id, container.id, name, technology, description)
        }
    }

    @Tool(customName = "add_relationship")
    @LLMDescription("Adds a relationship (arrow) between two C4 elements.")
    public suspend fun addRelationship(
        @LLMDescription("Source element id or name.") sourceIdOrName: String,
        @LLMDescription("Target element id or name.") targetIdOrName: String,
        @LLMDescription("Short verb-phrase shown on the edge, e.g. 'reads from'.") label: String,
        @LLMDescription("Optional technology label, e.g. 'HTTPS' or 'JDBC'.") technology: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val c4 = model as? AnyKumlModel.C4 ?: return PatchApplyResult.Failure("Context is not a C4 model")

        val source =
            C4PatchOps.resolveElement(c4, sourceIdOrName)
                ?: return PatchApplyResult.Failure(reason = "Source '$sourceIdOrName' not found")
        val target =
            C4PatchOps.resolveElement(c4, targetIdOrName)
                ?: return PatchApplyResult.Failure(reason = "Target '$targetIdOrName' not found")

        val id = IdHelpers.uniqueId("${source.name}_to_${target.name}", C4PatchOps.allIds(c4), "rel")

        val patch =
            ModelPatch.AddRelationship(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: "agent-default-context-diagram",
                relationshipKind = "c4.relationship",
                relationshipId = id,
                sourceId = source.id,
                targetId = target.id,
                payload = mapOf("label" to label),
            )

        return ctx.applyPatch(patch) { m ->
            C4PatchOps.addRelationship(m as AnyKumlModel.C4, id, source.id, target.id, label, technology)
        }
    }
}
