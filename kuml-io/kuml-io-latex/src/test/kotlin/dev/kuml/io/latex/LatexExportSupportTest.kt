package dev.kuml.io.latex

import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.erm.model.ErmNotation
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.Sysml2PackageDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class LatexExportSupportTest :
    FunSpec({
        test("ERM: Martin, Bachman and IDEF1X have LaTeX output") {
            listOf(ErmNotation.MARTIN, ErmNotation.BACHMAN, ErmNotation.IDEF1X).forEach {
                LatexExportSupport.ermReason(notation = it) shouldBe null
            }
        }

        test("ERM: Chen is refused with the shared message") {
            val reason = LatexExportSupport.ermReason(notation = ErmNotation.CHEN)
            reason shouldBe KumlLatexRenderer.ermUnsupportedNotationMessage(ErmNotation.CHEN)
            reason!! shouldContain "CHEN"
        }

        test("the BPMN texts are the ones the CLI always printed") {
            LatexExportSupport.BPMN_CHOREOGRAPHY_UNSUPPORTED shouldBe "BPMN choreography diagrams have no LaTeX output yet"
            LatexExportSupport.BPMN_CONVERSATION_UNSUPPORTED shouldBe "BPMN conversation diagrams have no LaTeX output yet"
        }

        test("BPMN: choreography and conversation are refused, process is not") {
            LatexExportSupport.bpmnReason(
                diagram = ChoreographyDiagram(name = "C", choreographyId = "c"),
            ) shouldBe LatexExportSupport.BPMN_CHOREOGRAPHY_UNSUPPORTED
            LatexExportSupport.bpmnReason(
                diagram = ConversationDiagram(name = "V", conversationId = "v"),
            ) shouldBe LatexExportSupport.BPMN_CONVERSATION_UNSUPPORTED
            LatexExportSupport.bpmnReason(diagram = ProcessDiagram(name = "P", processId = "p")) shouldBe null
        }

        test("SysML 2: package diagrams are refused, others are not") {
            LatexExportSupport.sysml2Reason(
                diagram = Sysml2PackageDiagram(name = "P"),
            ) shouldBe KumlLatexRenderer.PACKAGE_DIAGRAM_UNSUPPORTED
            LatexExportSupport.sysml2Reason(diagram = BdDiagram(name = "B")) shouldBe null
        }
    })
