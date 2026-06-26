package dev.kuml.transform.bpmnuml

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformError
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram

/**
 * Transforms a [KumlDiagram] of type [DiagramType.ACTIVITY] into a kUML BPMN
 * process model script (`*-process.kuml.kts`).
 *
 * Transformer id: `"uml-activity-to-bpmn"` — usable via
 * `kuml transform --transformer uml-activity-to-bpmn` or
 * `kuml transform --from uml-activity --to bpmn`.
 *
 * Fails with [TransformResult.Failure] when the source diagram is not of type ACTIVITY.
 */
public class UmlActivityToBpmnTransformer : KumlTransformer<KumlDiagram, List<GeneratedFile>> {
    override val id: String = "uml-activity-to-bpmn"
    override val description: String =
        "UML Activity diagram → BPMN Process model script (.kuml.kts)"

    override fun transform(
        source: KumlDiagram,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> {
        if (source.type != DiagramType.ACTIVITY) {
            return TransformResult.Failure(
                listOf(
                    TransformError(
                        message = "Transformer '$id' requires a diagram of type ACTIVITY, got ${source.type}",
                        elementId = source.id,
                    ),
                ),
            )
        }

        val process =
            UmlActivityToBpmnMapper.map(source)
                ?: return TransformResult.Failure(
                    listOf(TransformError("Failed to map UML Activity diagram '${source.name}' to BpmnProcess")),
                )

        val content = BpmnProcessScriptRenderer.render(process)

        val diagramName =
            source.name
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .trim()
                .replace(" ", "-")
                .lowercase()
        val outputPath = "$diagramName-process.kuml.kts"

        var trace = TransformTrace()
        for (node in process.flowNodes) {
            trace = trace.plus(TraceabilityLink(node.id, outputPath, RULE_NODE))
        }
        for (flow in process.sequenceFlows) {
            trace = trace.plus(TraceabilityLink(flow.id, outputPath, RULE_FLOW))
        }

        val file = GeneratedFile(outputPath, content)
        return TransformResult.Success(listOf(file), trace)
    }

    private companion object {
        const val RULE_NODE = "uml-activity-node-to-bpmn-flow-node"
        const val RULE_FLOW = "uml-activity-edge-to-bpmn-sequence-flow"
    }
}

/** ServiceLoader provider for [UmlActivityToBpmnTransformer]. */
public class UmlActivityToBpmnTransformerProvider : KumlTransformerProvider {
    override fun transformer(): UmlActivityToBpmnTransformer = UmlActivityToBpmnTransformer()
}
