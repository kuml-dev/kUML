package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.KumlTransformerProvider
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace

/**
 * Transforms a [BpmnProcess] into a kUML Activity diagram script (`*-activity.kuml.kts`).
 *
 * Delegates structural mapping to [BpmnToUmlActivityMapper] and script rendering
 * to [UmlActivityScriptRenderer].
 *
 * Transformer id: `"bpmn-to-uml-activity"` — usable via
 * `kuml transform --transformer bpmn-to-uml-activity` or
 * `kuml transform --from bpmn --to uml-activity`.
 *
 * Limitation: Pool / Lane → ActivityPartition is best-effort only.
 * Lane names are recorded in node metadata (`"uml.partition"`) and emitted
 * as script comments because the kUML UML Activity metamodel has no
 * PARTITION node kind.
 */
public class BpmnToUmlActivityTransformer : KumlTransformer<BpmnProcess, List<GeneratedFile>> {
    override val id: String = "bpmn-to-uml-activity"
    override val description: String =
        "BPMN Process → UML Activity diagram script (.kuml.kts)"

    override fun transform(
        source: BpmnProcess,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> {
        val model = BpmnToUmlActivityMapper.map(source)
        val content = UmlActivityScriptRenderer.render(model)

        val processName =
            (source.name ?: source.id)
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .trim()
                .replace(" ", "-")
                .lowercase()
        val outputPath = "$processName-activity.kuml.kts"

        var trace = TransformTrace()
        for (node in model.nodes) {
            trace = trace.plus(TraceabilityLink(node.id, outputPath, RULE_NODE))
        }
        for (edge in model.edges) {
            trace = trace.plus(TraceabilityLink(edge.id, outputPath, RULE_EDGE))
        }

        val file = GeneratedFile(outputPath, content)
        return TransformResult.Success(listOf(file), trace)
    }

    private companion object {
        const val RULE_NODE = "bpmn-flow-node-to-uml-activity-node"
        const val RULE_EDGE = "bpmn-sequence-flow-to-uml-activity-edge"
    }
}

/** ServiceLoader provider for [BpmnToUmlActivityTransformer]. */
public class BpmnToUmlActivityTransformerProvider : KumlTransformerProvider {
    override fun transformer(): BpmnToUmlActivityTransformer = BpmnToUmlActivityTransformer()
}
