package dev.kuml.io.latex

import dev.kuml.bpmn.model.BpmnDiagram
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.erm.model.ErmNotation
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2PackageDiagram

/**
 * The single source of the rules and texts that decide whether a diagram has LaTeX output, shared by the CLI
 * (`LatexExportService`) and the web playground. It works on metamodel types only, so it carries no scripting
 * dependency.
 */
public object LatexExportSupport {
    /** Refusal text for BPMN choreography diagrams. */
    public const val BPMN_CHOREOGRAPHY_UNSUPPORTED: String = "BPMN choreography diagrams have no LaTeX output yet"

    /** Refusal text for BPMN conversation diagrams. */
    public const val BPMN_CONVERSATION_UNSUPPORTED: String = "BPMN conversation diagrams have no LaTeX output yet"

    /** `null` when ERM [notation] has LaTeX output, otherwise the reason it has none. */
    public fun ermReason(notation: ErmNotation): String? =
        notation
            .takeIf { !KumlLatexRenderer.ermLatexSupports(it) }
            ?.let(KumlLatexRenderer::ermUnsupportedNotationMessage)

    /** `null` when the SysML 2 [diagram] has LaTeX output, otherwise the reason it has none. */
    public fun sysml2Reason(diagram: Sysml2Diagram): String? =
        if (diagram is Sysml2PackageDiagram) KumlLatexRenderer.PACKAGE_DIAGRAM_UNSUPPORTED else null

    /** `null` when the BPMN [diagram] has LaTeX output, otherwise the reason it has none. */
    public fun bpmnReason(diagram: BpmnDiagram): String? =
        when (diagram) {
            is ChoreographyDiagram -> BPMN_CHOREOGRAPHY_UNSUPPORTED
            is ConversationDiagram -> BPMN_CONVERSATION_UNSUPPORTED
            else -> null
        }
}
