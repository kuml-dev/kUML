package dev.kuml.ai.tools.uml

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.internal.EnumCoercion
import dev.kuml.ai.tools.internal.IdHelpers
import dev.kuml.ai.tools.result.RemoveResult
import dev.kuml.ai.tools.result.RenameResult
import dev.kuml.uml.Visibility
import kotlinx.serialization.Serializable

/**
 * Tools for editing UML class, state-machine, and component diagrams in kUML.
 *
 * All mutations run through AgentEditingContext, which:
 *  - clones the model on first use,
 *  - serializes concurrent tool calls via a Mutex,
 *  - records an append-only audit log of ModelPatch entries.
 */
@LLMDescription("Tools for editing UML class, state-machine, and component diagrams in kUML.")
public class UmlEditingTools(
    private val ctx: AgentEditingContext,
) : ToolSet {
    @Tool(customName = "add_class")
    @LLMDescription(
        "Adds a UML class to the currently focused class diagram. " +
            "If no class diagram is focused yet, the first call also creates one. " +
            "Returns the assigned UML element id.",
    )
    public suspend fun addClass(
        @LLMDescription("The class name in PascalCase, e.g. 'OrderService'.") name: String,
        @LLMDescription(
            "Optional UML stereotype without guillemets, e.g. 'entity' or 'service'.",
        ) stereotype: String? = null,
        @LLMDescription(
            "Optional attribute definitions. Each attribute is added in declaration order.",
        ) attributes: List<AttributeSpec> = emptyList(),
        @LLMDescription(
            "If true, the class is rendered as abstract (italic name).",
        ) isAbstract: Boolean = false,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")
        val takenIds = uml.elements.map { it.id }.toSet()
        val classId = IdHelpers.uniqueId(name, takenIds)

        // Build attribute list
        val attrTaken = (takenIds + classId).toMutableSet()
        val umlAttrs =
            attributes.map { spec ->
                val attrId = IdHelpers.uniqueId(spec.name, attrTaken, "attr")
                attrTaken += attrId
                dev.kuml.uml.UmlProperty(
                    id = attrId,
                    name = spec.name,
                    type = dev.kuml.uml.UmlTypeRef(spec.type),
                    visibility = EnumCoercion.toVisibility(spec.visibility) ?: Visibility.PRIVATE,
                    defaultValue = spec.defaultValue,
                )
            }

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                elementKind = "uml.class",
                elementId = classId,
                name = name,
                payload =
                    buildMap {
                        stereotype?.let { put("stereotype", it) }
                        if (isAbstract) put("isAbstract", "true")
                    },
            )

        return ctx.applyPatch(patch) { m ->
            val u = m as AnyKumlModel.Uml
            UmlPatchOps.addClass(u, classId, name, stereotype, isAbstract, umlAttrs)
        }
    }

    @Tool(customName = "add_interface")
    @LLMDescription("Adds a UML interface to the current class diagram. Returns the assigned element id.")
    public suspend fun addInterface(
        @LLMDescription("Interface name in PascalCase, e.g. 'PaymentProcessor'.") name: String,
        @LLMDescription(
            "Optional operation signatures, e.g. ['process(amount: Decimal): Receipt'].",
        ) operations: List<String> = emptyList(),
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")
        val takenIds = uml.elements.map { it.id }.toSet()
        val ifaceId = IdHelpers.uniqueId(name, takenIds)

        val patch =
            ModelPatch.AddElement(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                elementKind = "uml.interface",
                elementId = ifaceId,
                name = name,
            )

        return ctx.applyPatch(patch) { m ->
            val u = m as AnyKumlModel.Uml
            UmlPatchOps.addInterface(u, ifaceId, name)
        }
    }

    @Tool(customName = "add_attribute")
    @LLMDescription("Adds an attribute to an existing classifier (UML class or interface).")
    public suspend fun addAttribute(
        @LLMDescription("The id or name of the owning classifier.") classifierIdOrName: String,
        @LLMDescription("Attribute name in camelCase.") name: String,
        @LLMDescription(
            "Attribute type — primitive (String, Int, …), referenced classifier name, or qualified id.",
        ) type: String,
        @LLMDescription(
            "UML visibility code: PUBLIC, PROTECTED, PRIVATE, PACKAGE. Default PRIVATE.",
        ) visibility: String? = null,
        @LLMDescription(
            "Optional default-value literal exactly as it should appear in code.",
        ) defaultValue: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")
        val classifier =
            UmlPatchOps.resolveClassifier(uml, classifierIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Classifier '$classifierIdOrName' not found",
                    hint = "Use list_elements to discover available classifier ids",
                )

        val vis: Visibility =
            try {
                EnumCoercion.toVisibility(visibility) ?: Visibility.PRIVATE
            } catch (e: IllegalArgumentException) {
                return PatchApplyResult.Failure(e.message ?: "Invalid visibility")
            }

        val takenIds = uml.elements.map { it.id }.toSet()
        val attrId = IdHelpers.uniqueId(name, takenIds, "attr")

        val patch =
            ModelPatch.UpdateAttribute(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                ownerId = classifier.id,
                attributeId = attrId,
                field = "add_attribute",
                newValue = "$name: $type",
            )

        return ctx.applyPatch(patch) { m ->
            val u = m as AnyKumlModel.Uml
            UmlPatchOps.addAttribute(u, classifier.id, attrId, name, type, vis, defaultValue)
                ?: throw IllegalArgumentException("Classifier '${classifier.id}' not found after patch")
        }
    }

    @Tool(customName = "add_operation")
    @LLMDescription("Adds an operation (method) to an existing classifier.")
    public suspend fun addOperation(
        @LLMDescription("Owner classifier id or name.") classifierIdOrName: String,
        @LLMDescription("Method name in camelCase, e.g. 'submitOrder'.") name: String,
        @LLMDescription("Parameter signatures as 'name: Type' strings.") parameters: List<String> = emptyList(),
        @LLMDescription("Return type — 'void' / 'Unit' for none.") returnType: String? = null,
        @LLMDescription(
            "UML visibility code: PUBLIC, PROTECTED, PRIVATE, PACKAGE. Default PUBLIC.",
        ) visibility: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")
        val classifier =
            UmlPatchOps.resolveClassifier(uml, classifierIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Classifier '$classifierIdOrName' not found",
                    hint = "Use list_elements to discover available classifier ids",
                )

        val vis: Visibility =
            try {
                EnumCoercion.toVisibility(visibility) ?: Visibility.PUBLIC
            } catch (e: IllegalArgumentException) {
                return PatchApplyResult.Failure(e.message ?: "Invalid visibility")
            }

        val takenIds = uml.elements.map { it.id }.toSet()
        val opId = IdHelpers.uniqueId(name, takenIds, "op")
        val umlParams = UmlPatchOps.parseParameters(parameters, takenIds + opId)
        val retType =
            when {
                returnType.isNullOrBlank() || returnType == "void" || returnType == "Unit" -> null
                else -> dev.kuml.uml.UmlTypeRef(returnType)
            }

        val patch =
            ModelPatch.UpdateAttribute(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                ownerId = classifier.id,
                attributeId = opId,
                field = "add_operation",
                newValue = name,
            )

        return ctx.applyPatch(patch) { m ->
            val u = m as AnyKumlModel.Uml
            UmlPatchOps.addOperation(u, classifier.id, opId, name, umlParams, retType, vis)
                ?: throw IllegalArgumentException("Classifier '${classifier.id}' not found after patch")
        }
    }

    @Tool(customName = "add_association")
    @LLMDescription("Adds an association between two classifiers in the current class diagram.")
    public suspend fun addAssociation(
        @LLMDescription("Source classifier id or name.") sourceIdOrName: String,
        @LLMDescription("Target classifier id or name.") targetIdOrName: String,
        @LLMDescription("Source-end multiplicity, e.g. '1', '0..1', '*'.") sourceMultiplicity: String? = null,
        @LLMDescription("Target-end multiplicity, e.g. '1', '0..1', '*'.") targetMultiplicity: String? = null,
        @LLMDescription("Optional association name shown on the edge.") name: String? = null,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")

        val source =
            UmlPatchOps.resolveClassifier(uml, sourceIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Source classifier '$sourceIdOrName' not found",
                )
        val target =
            UmlPatchOps.resolveClassifier(uml, targetIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Target classifier '$targetIdOrName' not found",
                )

        val takenIds = (uml.elements.map { it.id } + uml.relationships.map { it.id }).toSet()
        val assocId = IdHelpers.uniqueId("${source.name}_${target.name}", takenIds, "assoc")
        val srcMult = UmlPatchOps.parseMultiplicity(sourceMultiplicity)
        val tgtMult = UmlPatchOps.parseMultiplicity(targetMultiplicity)

        val patch =
            ModelPatch.AddRelationship(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                relationshipKind = "uml.association",
                relationshipId = assocId,
                sourceId = source.id,
                targetId = target.id,
            )

        return ctx.applyPatch(patch) { m ->
            val u = m as AnyKumlModel.Uml
            UmlPatchOps.addAssociation(u, assocId, source.id, target.id, name, srcMult, tgtMult)
        }
    }

    @Tool(customName = "add_generalization")
    @LLMDescription("Adds a generalization (inheritance) from child to parent classifier in the current class diagram.")
    public suspend fun addGeneralization(
        @LLMDescription("Child classifier id or name.") childIdOrName: String,
        @LLMDescription("Parent classifier id or name.") parentIdOrName: String,
    ): PatchApplyResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return PatchApplyResult.Failure("Context is not a UML model")

        val child =
            UmlPatchOps.resolveClassifier(uml, childIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Child classifier '$childIdOrName' not found",
                )
        val parent =
            UmlPatchOps.resolveClassifier(uml, parentIdOrName)
                ?: return PatchApplyResult.Failure(
                    reason = "Parent classifier '$parentIdOrName' not found",
                )
        if (child.id == parent.id) {
            return PatchApplyResult.Failure(
                reason = "Self-loop generalization is not allowed",
                hint = "Child and parent must be different classifiers",
            )
        }

        val takenIds = (uml.elements.map { it.id } + uml.relationships.map { it.id }).toSet()
        val genId = IdHelpers.uniqueId("${child.name}_extends_${parent.name}", takenIds, "gen")

        val patch =
            ModelPatch.AddRelationship(
                patchId = ModelPatch.newId(),
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                relationshipKind = "uml.generalization",
                relationshipId = genId,
                sourceId = child.id,
                targetId = parent.id,
            )

        return ctx.applyPatch(patch) { m ->
            val u = m as AnyKumlModel.Uml
            UmlPatchOps.addGeneralization(u, genId, child.id, parent.id)
        }
    }

    @Tool(customName = "remove_element")
    @LLMDescription("Removes the element with the given id from the current model. Idempotent: missing id returns Failure with hint.")
    public suspend fun removeElement(
        @LLMDescription("UML element id to remove. Use list_elements first to discover ids.") elementId: String,
    ): RemoveResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return RemoveResult.Failure("Context is not a UML model")

        if (uml.elements.none { it.id == elementId } && uml.relationships.none { it.id == elementId }) {
            return RemoveResult.Failure("Element '$elementId' not found")
        }

        val patchId = ModelPatch.newId()
        val patch =
            ModelPatch.RemoveElement(
                patchId = patchId,
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                elementId = elementId,
            )

        val result =
            ctx.applyPatch(patch) { m ->
                val u = m as AnyKumlModel.Uml
                UmlPatchOps.removeElement(u, elementId)
                    ?: throw IllegalArgumentException("Element '$elementId' disappeared during patch")
            }

        return when (result) {
            is PatchApplyResult.Success -> RemoveResult.Success(removedId = elementId, patchId = result.patchId)
            is PatchApplyResult.Failure -> RemoveResult.Failure(result.reason)
        }
    }

    @Tool(customName = "rename_element")
    @LLMDescription("Renames an existing element. Does not change the element id.")
    public suspend fun renameElement(
        @LLMDescription("Element id whose name should change.") elementId: String,
        @LLMDescription("New display name.") newName: String,
    ): RenameResult {
        val model = ctx.resolveModel()
        val uml = model as? AnyKumlModel.Uml ?: return RenameResult.Failure("Context is not a UML model")

        val element =
            uml.elements.firstOrNull { it.id == elementId }
                ?: return RenameResult.Failure("Element '$elementId' not found")
        val oldName = element.name

        val patchId = ModelPatch.newId()
        val patch =
            ModelPatch.RenameElement(
                patchId = patchId,
                appliedAt = ModelPatch.nowIso(),
                diagramId = ctx.currentDiagramId ?: uml.diagramId,
                elementId = elementId,
                oldName = oldName,
                newName = newName,
            )

        val result =
            ctx.applyPatch(patch) { m ->
                val u = m as AnyKumlModel.Uml
                val (renamed, _) =
                    UmlPatchOps.renameElement(u, elementId, newName)
                        ?: throw IllegalArgumentException("Element '$elementId' not found during rename")
                renamed
            }

        return when (result) {
            is PatchApplyResult.Success ->
                RenameResult.Success(
                    elementId = elementId,
                    oldName = oldName,
                    newName = newName,
                    patchId = result.patchId,
                )
            is PatchApplyResult.Failure -> RenameResult.Failure(result.reason)
        }
    }

    @Tool(customName = "set_current_diagram")
    @LLMDescription("Set which diagram subsequent edit-tool calls target. Required when the model has multiple diagrams.")
    public suspend fun setCurrentDiagram(
        @LLMDescription("The diagram id (use list_elements to discover).") diagramId: String,
    ): RenameResult {
        val prev = ctx.setCurrentDiagramId(diagramId)
        return RenameResult.Success(
            elementId = diagramId,
            oldName = prev ?: "(none)",
            newName = diagramId,
            patchId = ModelPatch.newId(),
        )
    }

    @Serializable
    public data class AttributeSpec(
        @property:LLMDescription("Attribute name in camelCase.") val name: String,
        @property:LLMDescription("Attribute type, e.g. String, Int, or a classifier name.") val type: String,
        @property:LLMDescription("UML visibility code: PUBLIC, PROTECTED, PRIVATE, PACKAGE.") val visibility: String? = null,
        @property:LLMDescription("Optional default-value literal.") val defaultValue: String? = null,
    )
}
