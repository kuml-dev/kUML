package dev.kuml.ai.tools.inspection

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.result.ElementDetails
import dev.kuml.ai.tools.result.ElementSummary
import dev.kuml.ai.tools.result.UnusedReport
import dev.kuml.c4.model.C4Component
import dev.kuml.c4.model.C4Container
import dev.kuml.c4.model.C4Person
import dev.kuml.c4.model.C4Relationship
import dev.kuml.c4.model.C4SoftwareSystem
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface

/**
 * Read-only inspection tools — query the model without mutating it.
 */
@LLMDescription("Read-only inspection tools — query the model without mutating it.")
public class ModelInspectionTools(
    private val ctx: AgentEditingContext,
) : ToolSet {
    @Tool(customName = "list_elements")
    @LLMDescription(
        "Lists every element in the current model, returning id, name, and kind " +
            "for each. The agent should call this first when it needs to reference " +
            "an existing element by id.",
    )
    public suspend fun listElements(): List<ElementSummary> {
        val model = ctx.resolveModel()
        return when (model) {
            is AnyKumlModel.Uml -> {
                val elementSummaries =
                    model.elements.map { el ->
                        ElementSummary(
                            id = el.id,
                            name = el.name,
                            kind =
                                when (el) {
                                    is UmlClass -> "uml.class"
                                    is UmlInterface -> "uml.interface"
                                    else -> "uml.${el::class.simpleName?.lowercase() ?: "element"}"
                                },
                        )
                    }
                val relSummaries =
                    model.relationships.map { rel ->
                        ElementSummary(
                            id = rel.id,
                            name =
                                when (rel) {
                                    is UmlAssociation -> rel.name ?: "(association)"
                                    is UmlGeneralization -> "(generalization)"
                                    else -> "(${rel::class.simpleName?.lowercase() ?: "relationship"})"
                                },
                            kind =
                                when (rel) {
                                    is UmlAssociation -> "uml.association"
                                    is UmlGeneralization -> "uml.generalization"
                                    else -> "uml.${rel::class.simpleName?.lowercase() ?: "relationship"}"
                                },
                        )
                    }
                elementSummaries + relSummaries
            }
            is AnyKumlModel.C4 -> {
                val elementSummaries =
                    model.model.elements.map { el ->
                        ElementSummary(
                            id = el.id,
                            name = el.name,
                            kind =
                                when (el) {
                                    is C4Person -> "c4.person"
                                    is C4SoftwareSystem -> "c4.software_system"
                                    is C4Container -> "c4.container"
                                    is C4Component -> "c4.component"
                                    is C4Relationship -> "c4.relationship"
                                    else -> "c4.${el::class.simpleName?.lowercase() ?: "element"}"
                                },
                        )
                    }
                val relSummaries =
                    model.model.relationships.map { rel ->
                        ElementSummary(
                            id = rel.id,
                            name = rel.label,
                            kind = "c4.relationship",
                        )
                    }
                elementSummaries + relSummaries
            }
            is AnyKumlModel.Sysml2 -> {
                val defSummaries =
                    model.model.definitions.map { def ->
                        ElementSummary(
                            id = def.id,
                            name = def.name,
                            kind = "sysml2.${def::class.simpleName?.lowercase() ?: "definition"}",
                        )
                    }
                val usageSummaries =
                    model.model.usages.map { usage ->
                        ElementSummary(
                            id = usage.id,
                            name = usage.name,
                            kind = "sysml2.${usage::class.simpleName?.lowercase() ?: "usage"}",
                        )
                    }
                defSummaries + usageSummaries
            }
        }
    }

    @Tool(customName = "get_element_details")
    @LLMDescription("Returns full details of one element — attributes, operations, relationships, stereotypes.")
    public suspend fun getElementDetails(
        @LLMDescription("Element id from list_elements.") elementId: String,
    ): ElementDetails {
        val model = ctx.resolveModel()

        return when (model) {
            is AnyKumlModel.Uml -> {
                val element =
                    model.elements.firstOrNull { it.id == elementId }
                        ?: return ElementDetails(
                            id = elementId,
                            name = "(not found)",
                            kind = "unknown",
                        )

                val incomingRels =
                    model.relationships
                        .filter { rel ->
                            when (rel) {
                                is UmlAssociation -> rel.ends.any { it.typeId == elementId }
                                is UmlGeneralization -> rel.generalId == elementId
                                else -> false
                            }
                        }.map { it.id }

                val outgoingRels =
                    model.relationships
                        .filter { rel ->
                            when (rel) {
                                is UmlAssociation -> rel.ends.first().typeId == elementId
                                is UmlGeneralization -> rel.specificId == elementId
                                else -> false
                            }
                        }.map { it.id }

                when (element) {
                    is UmlClass ->
                        ElementDetails(
                            id = element.id,
                            name = element.name,
                            kind = "uml.class",
                            attributes =
                                element.attributes.map { attr ->
                                    ElementDetails.AttributeView(
                                        id = attr.id,
                                        name = attr.name,
                                        type = attr.type.name,
                                        visibility = attr.visibility.name,
                                    )
                                },
                            operations =
                                element.operations.map { op ->
                                    ElementDetails.OperationView(
                                        id = op.id,
                                        signature = buildSignature(op),
                                        visibility = op.visibility.name,
                                    )
                                },
                            stereotypes = element.stereotypes,
                            incomingRelationshipIds = incomingRels,
                            outgoingRelationshipIds = outgoingRels,
                        )
                    is UmlInterface ->
                        ElementDetails(
                            id = element.id,
                            name = element.name,
                            kind = "uml.interface",
                            attributes =
                                element.attributes.map { attr ->
                                    ElementDetails.AttributeView(
                                        id = attr.id,
                                        name = attr.name,
                                        type = attr.type.name,
                                        visibility = attr.visibility.name,
                                    )
                                },
                            operations =
                                element.operations.map { op ->
                                    ElementDetails.OperationView(
                                        id = op.id,
                                        signature = buildSignature(op),
                                        visibility = op.visibility.name,
                                    )
                                },
                            stereotypes = element.stereotypes,
                            incomingRelationshipIds = incomingRels,
                            outgoingRelationshipIds = outgoingRels,
                        )
                    else ->
                        ElementDetails(
                            id = element.id,
                            name = element.name,
                            kind = "uml.${element::class.simpleName?.lowercase() ?: "element"}",
                            stereotypes = element.stereotypes,
                            incomingRelationshipIds = incomingRels,
                            outgoingRelationshipIds = outgoingRels,
                        )
                }
            }
            is AnyKumlModel.C4 -> {
                val element =
                    model.model.elements.firstOrNull { it.id == elementId }
                        ?: return ElementDetails(id = elementId, name = "(not found)", kind = "unknown")
                ElementDetails(
                    id = element.id,
                    name = element.name,
                    kind = "c4.${element::class.simpleName?.lowercase() ?: "element"}",
                )
            }
            is AnyKumlModel.Sysml2 -> {
                val def =
                    model.model.definitions.firstOrNull { it.id == elementId }
                        ?: model.model.usages.firstOrNull { it.id == elementId }
                        ?: return ElementDetails(id = elementId, name = "(not found)", kind = "unknown")
                ElementDetails(
                    id = def.id,
                    name = def.name,
                    kind = "sysml2.${def::class.simpleName?.lowercase() ?: "element"}",
                )
            }
        }
    }

    @Tool(customName = "find_unused_elements")
    @LLMDescription(
        "Finds classifiers with no incoming/outgoing relationships. Useful for " +
            "the agent to suggest cleanups during model refactoring.",
    )
    public suspend fun findUnusedElements(): UnusedReport {
        val model = ctx.resolveModel()

        return when (model) {
            is AnyKumlModel.Uml -> {
                // Collect all element ids referenced by relationships
                val referencedIds = mutableSetOf<String>()
                model.relationships.forEach { rel ->
                    when (rel) {
                        is UmlAssociation -> rel.ends.forEach { referencedIds += it.typeId }
                        is UmlGeneralization -> {
                            referencedIds += rel.specificId
                            referencedIds += rel.generalId
                        }
                        else -> Unit
                    }
                }

                val unused = model.elements.filter { it.id !in referencedIds }
                val unusedNames = unused.map { it.name }
                UnusedReport(
                    unusedElementIds = unused.map { it.id },
                    rationale =
                        if (unused.isEmpty()) {
                            "All elements are connected via relationships."
                        } else {
                            "Elements with no relationships: ${unusedNames.joinToString(", ")}"
                        },
                )
            }
            is AnyKumlModel.C4 -> {
                val referencedIds = mutableSetOf<String>()
                model.model.relationships.forEach { rel ->
                    referencedIds += rel.source
                    referencedIds += rel.target
                }
                val unused = model.model.elements.filter { it.id !in referencedIds && it !is C4Relationship }
                UnusedReport(
                    unusedElementIds = unused.map { it.id },
                    rationale =
                        if (unused.isEmpty()) {
                            "All C4 elements participate in relationships."
                        } else {
                            "Isolated elements: ${unused.map { it.name }.joinToString(", ")}"
                        },
                )
            }
            is AnyKumlModel.Sysml2 -> {
                // In SysML2, check for definitions with no usages referencing them
                val usedDefIds =
                    model.model.usages
                        .map { it.definitionId }
                        .toSet()
                val unused = model.model.definitions.filter { it.id !in usedDefIds }
                UnusedReport(
                    unusedElementIds = unused.map { it.id },
                    rationale =
                        if (unused.isEmpty()) {
                            "All definitions have corresponding usages."
                        } else {
                            "Definitions with no usages: ${unused.map { it.name }.joinToString(", ")}"
                        },
                )
            }
        }
    }

    private fun buildSignature(op: dev.kuml.uml.UmlOperation): String {
        val params = op.parameters.joinToString(", ") { "${it.name}: ${it.type.name}" }
        val ret = op.returnType?.let { ": ${it.name}" } ?: ""
        return "${op.name}($params)$ret"
    }
}
