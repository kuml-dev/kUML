package dev.kuml.desktop.ai

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.ModelPatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes an LLM tool-call (name + JSON args) into a [ModelPatch], or null if unknown.
 *
 * Extracted from [AgentRunner] in V3.1.18 so that [KumlSpecialistAgent] and
 * [KumlAgentOrchestrator] can reuse the same decode map without duplication.
 *
 * Mapping is based on the `@Tool(customName = ...)` registrations in
 * [dev.kuml.ai.tools.uml.UmlEditingTools], [dev.kuml.ai.tools.c4.C4EditingTools],
 * and [dev.kuml.ai.tools.sysml2.Sysml2EditingTools].
 *
 * Note (V3.1.18): C4 and SysML2 tool names are recognised for allow-list filtering
 * but their decode paths return null because ModelPatch subtypes for those domains
 * are not yet implemented. This is called out in the CHANGELOG.
 */
internal class PatchDecoder(private val editingContext: AgentEditingContext?) {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decode(toolName: String, argsJson: String): ModelPatch? {
        val diagramId = editingContext?.currentDiagramId
        val args: JsonObject = runCatching {
            lenientJson.parseToJsonElement(argsJson) as? JsonObject
        }.getOrNull() ?: return null

        fun str(key: String): String? = runCatching { args[key]?.jsonPrimitive?.content }.getOrNull()

        return when (toolName) {
            "add_class" -> {
                val name = str("name") ?: return null
                ModelPatch.AddElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementKind = "uml.class",
                    elementId = name.toCandidateId(),
                    name = name,
                    payload = buildMap {
                        str("stereotype")?.let { put("stereotype", it) }
                        str("isAbstract")?.let { put("isAbstract", it) }
                    },
                )
            }
            "add_interface" -> {
                val name = str("name") ?: return null
                ModelPatch.AddElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementKind = "uml.interface",
                    elementId = name.toCandidateId(),
                    name = name,
                )
            }
            "add_attribute" -> {
                val ownerId = str("classifierIdOrName") ?: return null
                val attrName = str("name") ?: return null
                val attrType = str("type") ?: "Any"
                ModelPatch.UpdateAttribute(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    ownerId = ownerId,
                    attributeId = "${ownerId}_${attrName}",
                    field = "add_attribute",
                    newValue = "$attrName: $attrType",
                )
            }
            "add_operation" -> {
                val ownerId = str("classifierIdOrName") ?: return null
                val opName = str("name") ?: return null
                ModelPatch.UpdateAttribute(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    ownerId = ownerId,
                    attributeId = "${ownerId}_${opName}_op",
                    field = "add_operation",
                    newValue = opName,
                )
            }
            "add_association" -> {
                val sourceId = str("sourceIdOrName") ?: return null
                val targetId = str("targetIdOrName") ?: return null
                ModelPatch.AddRelationship(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    relationshipKind = "uml.association",
                    relationshipId = "${sourceId}_assoc_${targetId}",
                    sourceId = sourceId,
                    targetId = targetId,
                )
            }
            "add_generalization" -> {
                val specificId = str("specificIdOrName") ?: return null
                val generalId = str("generalIdOrName") ?: return null
                ModelPatch.AddRelationship(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    relationshipKind = "uml.generalization",
                    relationshipId = "${specificId}_gen_${generalId}",
                    sourceId = specificId,
                    targetId = generalId,
                )
            }
            "remove_element" -> {
                val elementId = str("elementId") ?: return null
                ModelPatch.RemoveElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementId = elementId,
                )
            }
            "rename_element" -> {
                val elementId = str("elementId") ?: return null
                val newName = str("newName") ?: return null
                val oldName = str("currentName") ?: elementId
                ModelPatch.RenameElement(
                    patchId = ModelPatch.newId(),
                    appliedAt = ModelPatch.nowIso(),
                    diagramId = diagramId,
                    elementId = elementId,
                    oldName = oldName,
                    newName = newName,
                )
            }
            // C4 and SysML2 tool names are known to the allow-list (see AgentDomain),
            // but ModelPatch subtypes for those domains are not yet implemented in V3.1.18.
            // Returning null here is intentional — tool calls are traced (ToolCallStart/End)
            // but no patch is buffered. Documented in CHANGELOG.
            else -> null
        }
    }

    companion object {
        fun String.toCandidateId(): String =
            this.lowercase().replace(Regex("[^a-z0-9]"), "_").trimEnd('_')
    }
}
