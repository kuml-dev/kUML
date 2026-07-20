package dev.kuml.cli.workspace

import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.c4.model.ComponentDiagram
import dev.kuml.c4.model.ContainerDiagram
import dev.kuml.c4.model.DeploymentDiagram
import dev.kuml.c4.model.DynamicDiagram
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.c4.model.SystemLandscapeDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.UcDiagram
import dev.kuml.workspace.OkfType

/**
 * Maps an [ExtractedDiagram] (an evaluated `.kuml.kts` script's diagram) to the
 * OKF `type:` vocabulary ([OkfType]) — the reverse direction of
 * [OkfType.fromId].
 *
 * Used only by the `--to okf` direction of `kuml workspace convert` (FT-7):
 * knowing the *concrete* diagram kind requires script evaluation
 * ([dev.kuml.core.script.KumlScriptHost]), which `kuml-docs:kuml-workspace`
 * deliberately does not depend on (ADR-0011) — so this mapping lives in
 * `kuml-cli`, the module that already evaluates scripts for `kuml render`.
 */
internal object OkfTypeMapper {
    /**
     * Resolves [extracted] to an [OkfType.id], or `null` when the diagram kind
     * has no OKF vocabulary entry yet. Callers surface a `null` result as
     * `OKF-C-003` (custom `type:` fallback — see [customTypeIdFallback] —
     * plus a warning, or an error under `--strict`).
     *
     * Un-mappable cases (deliberately, not oversights — the OKF vocabulary
     * simply has no corresponding entry yet):
     * - UML: everything except [DiagramType.CLASS]/[SEQUENCE]/[STATE]/[ACTIVITY]/
     *   [COMPONENT]/[USE_CASE] — i.e. `OBJECT`, `COMPOSITE_STRUCTURE`, `PACKAGE`,
     *   `DEPLOYMENT`, `PROFILE`, `COMMUNICATION`, `TIMING`, `INTERACTION_OVERVIEW`.
     *   (The `BPMN_*`/`JOURNEY`/`BLUEPRINT` values of [DiagramType] are never
     *   produced for an [ExtractedDiagram.Uml] — those diagram kinds extract as
     *   [ExtractedDiagram.Bpmn]/[ExtractedDiagram.Blueprint] instead.)
     * - C4: [SystemLandscapeDiagram], [DeploymentDiagram], [DynamicDiagram] have
     *   no OKF vocabulary entry (only Context/Container/Component do).
     */
    internal fun toOkfTypeId(extracted: ExtractedDiagram): String? =
        when (extracted) {
            is ExtractedDiagram.Uml ->
                when (extracted.diagram.type) {
                    DiagramType.CLASS -> OkfType.UML_CLASS_DIAGRAM.id
                    DiagramType.SEQUENCE -> OkfType.UML_SEQUENCE_DIAGRAM.id
                    DiagramType.STATE -> OkfType.UML_STATE_MACHINE.id
                    DiagramType.ACTIVITY -> OkfType.UML_ACTIVITY_DIAGRAM.id
                    DiagramType.COMPONENT -> OkfType.UML_COMPONENT_DIAGRAM.id
                    DiagramType.USE_CASE -> OkfType.UML_USE_CASE_DIAGRAM.id
                    else -> null
                }
            is ExtractedDiagram.C4 ->
                when (extracted.diagram) {
                    is SystemContextDiagram -> OkfType.C4_CONTEXT_DIAGRAM.id
                    is ContainerDiagram -> OkfType.C4_CONTAINER_DIAGRAM.id
                    is ComponentDiagram -> OkfType.C4_COMPONENT_DIAGRAM.id
                    is SystemLandscapeDiagram, is DeploymentDiagram, is DynamicDiagram -> null
                }
            is ExtractedDiagram.Sysml2 ->
                when (extracted.diagram) {
                    is BdDiagram -> OkfType.SYSML2_BLOCK_DEFINITION.id
                    is IbdDiagram -> OkfType.SYSML2_INTERNAL_BLOCK.id
                    is StmDiagram -> OkfType.SYSML2_STATE_MACHINE.id
                    is ActDiagram -> OkfType.SYSML2_ACTIVITY.id
                    is SeqDiagram -> OkfType.SYSML2_SEQUENCE.id
                    is UcDiagram -> OkfType.SYSML2_USE_CASE.id
                    is ReqDiagram -> OkfType.SYSML2_REQUIREMENT.id
                    is ParDiagram -> OkfType.SYSML2_PARAMETRIC.id
                }
            is ExtractedDiagram.Bpmn ->
                when (extracted.diagram) {
                    is ProcessDiagram -> OkfType.BPMN_PROCESS.id
                    is CollaborationDiagram -> OkfType.BPMN_COLLABORATION.id
                    is ChoreographyDiagram -> OkfType.BPMN_CHOREOGRAPHY.id
                    is ConversationDiagram -> OkfType.BPMN_CONVERSATION.id
                }
            is ExtractedDiagram.Blueprint -> OkfType.SERVICE_BLUEPRINT.id
            is ExtractedDiagram.Erm -> OkfType.ERM_DIAGRAM.id
        }

    /**
     * A best-effort custom `type:` value for a diagram kind [toOkfTypeId]
     * returned `null` for. Deterministic and derived from the diagram's own
     * Kotlin type name, so re-running `convert` on the same script always
     * produces the same custom type instead of inventing a new label each time.
     *
     * Only ever called for the two [ExtractedDiagram] branches that have
     * `null`-returning cases in [toOkfTypeId] (`Uml`, `C4`) — every other
     * branch maps exhaustively, so the `else` arm below is unreachable in
     * practice and exists only so the `when` does not need to be partial.
     */
    internal fun customTypeIdFallback(extracted: ExtractedDiagram): String =
        when (extracted) {
            is ExtractedDiagram.Uml -> "Uml${extracted.diagram.type.toPascalCase()}Diagram"
            is ExtractedDiagram.C4 -> "C4${extracted.diagram::class.simpleName}"
            else -> extracted::class.simpleName ?: "CustomDiagram"
        }

    /**
     * A human-readable title for the wrapped note's frontmatter `title:` and H1
     * heading: the diagram's own `name`, falling back to [fallback] (typically
     * the source script's file stem) when the diagram's name is blank.
     */
    internal fun titleOf(
        extracted: ExtractedDiagram,
        fallback: String,
    ): String {
        val name =
            when (extracted) {
                is ExtractedDiagram.Uml -> extracted.diagram.name
                is ExtractedDiagram.C4 -> extracted.diagram.name
                is ExtractedDiagram.Sysml2 -> extracted.diagram.name
                is ExtractedDiagram.Bpmn -> extracted.diagram.name
                is ExtractedDiagram.Blueprint -> extracted.diagram.name
                is ExtractedDiagram.Erm -> extracted.diagram.name
            }
        return name.ifBlank { fallback }
    }

    /** `"COMPOSITE_STRUCTURE"` → `"CompositeStructure"`. */
    private fun DiagramType.toPascalCase(): String =
        name.split('_').joinToString("") { part ->
            part.lowercase().replaceFirstChar(Char::uppercaseChar)
        }
}
