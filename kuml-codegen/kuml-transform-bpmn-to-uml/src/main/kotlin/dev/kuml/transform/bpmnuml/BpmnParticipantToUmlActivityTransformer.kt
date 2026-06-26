package dev.kuml.transform.bpmnuml

import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnParticipant
import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.TraceabilityLink
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformError
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.TransformTrace

/**
 * Transforms a [BpmnParticipant] (Pool) into a kUML Activity diagram script
 * (`*-activity.kuml.kts`), fully preserving Lane → `uml.partition` metadata.
 *
 * This transformer supersedes [BpmnToUmlActivityTransformer] for collaboration
 * models where lane information must be preserved:
 *
 * - Transformer id: `"bpmn-participant-to-uml-activity"` — usable via
 *   `kuml transform --transformer bpmn-participant-to-uml-activity` or
 *   `kuml transform --from bpmn-participant --to uml-activity`.
 * - The source [BpmnParticipant] carries both a [BpmnParticipant.processRef]
 *   (resolved from [TransformContext.options] key `"bpmn.model"` is *not*
 *   required — the participant's process is resolved externally and embedded
 *   in the variant below; see [BpmnParticipantBundle]) and a
 *   [BpmnParticipant.lanes] list that is forwarded directly to
 *   [BpmnToUmlActivityMapper.map].
 *
 * For callers that have both the [BpmnModel] and need to resolve
 * `processRef → BpmnProcess`, use the CLI path or [transformBundle] below.
 *
 * Lane membership for each flow node is recorded as `"uml.partition"` in the
 * mapped [dev.kuml.uml.UmlActivityNode.metadata] and emitted as a script comment.
 * No structural partition node is created (the kUML UML Activity metamodel has no
 * PARTITION node kind).
 */
public class BpmnParticipantToUmlActivityTransformer : KumlTransformer<BpmnParticipantBundle, List<GeneratedFile>> {
    override val id: String = "bpmn-participant-to-uml-activity"
    override val description: String =
        "BPMN Participant (Pool + Lanes) → UML Activity diagram script (.kuml.kts)"

    override fun transform(
        source: BpmnParticipantBundle,
        ctx: TransformContext,
    ): TransformResult<List<GeneratedFile>> {
        val process =
            source.process
                ?: return TransformResult.Failure(
                    listOf(
                        TransformError(
                            message =
                                "Participant '${source.participant.id}' has no processRef or the " +
                                    "referenced process could not be resolved from the BpmnModel.",
                            elementId = source.participant.id,
                        ),
                    ),
                )

        val model = BpmnToUmlActivityMapper.map(process, source.participant.lanes)
        val content = UmlActivityScriptRenderer.render(model)

        val participantName =
            (source.participant.name ?: source.participant.id)
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .trim()
                .replace(" ", "-")
                .lowercase()
        val outputPath = "$participantName-activity.kuml.kts"

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

/**
 * Bundle pairing a [BpmnParticipant] with its resolved [dev.kuml.bpmn.model.BpmnProcess].
 *
 * The [process] is resolved from the enclosing [BpmnModel] by matching
 * [BpmnParticipant.processRef] against [BpmnModel.processes]. If the participant
 * is a black-box pool (no processRef), [process] is `null` and the transformer
 * will emit a [TransformResult.Failure].
 *
 * @property participant The pool (participant) carrying lane information.
 * @property process     The resolved process, or `null` for black-box pools.
 */
public data class BpmnParticipantBundle(
    val participant: BpmnParticipant,
    val process: dev.kuml.bpmn.model.BpmnProcess?,
) {
    public companion object {
        /**
         * Resolves a [BpmnParticipantBundle] from a [BpmnModel] and a participant ID.
         *
         * Returns `null` if the participant does not exist in any collaboration.
         */
        public fun from(
            model: BpmnModel,
            participantId: String,
        ): BpmnParticipantBundle? {
            val participant =
                model.collaborations
                    .flatMap { it.participants }
                    .firstOrNull { it.id == participantId }
                    ?: return null
            val process =
                participant.processRef?.let { ref -> model.processes.firstOrNull { it.id == ref } }
            return BpmnParticipantBundle(participant, process)
        }

        /**
         * Resolves bundles for **all** participants in [model] that have a non-null processRef
         * pointing to a known process.
         *
         * Black-box pools (no processRef) are included with [BpmnParticipantBundle.process] = `null`.
         */
        public fun allFrom(model: BpmnModel): List<BpmnParticipantBundle> =
            model.collaborations
                .flatMap { it.participants }
                .map { participant ->
                    val process =
                        participant.processRef?.let { ref ->
                            model.processes.firstOrNull { it.id == ref }
                        }
                    BpmnParticipantBundle(participant, process)
                }
    }
}
