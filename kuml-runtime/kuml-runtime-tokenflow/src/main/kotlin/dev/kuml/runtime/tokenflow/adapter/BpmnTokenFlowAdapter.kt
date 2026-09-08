package dev.kuml.runtime.tokenflow.adapter

import dev.kuml.bpmn.model.BpmnActivity
import dev.kuml.bpmn.model.BpmnEvent
import dev.kuml.bpmn.model.BpmnGateway
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.BpmnProcess
import dev.kuml.bpmn.model.BpmnSubProcess
import dev.kuml.bpmn.model.EventDefinition
import dev.kuml.bpmn.model.EventPosition
import dev.kuml.bpmn.model.GatewayDirection
import dev.kuml.bpmn.model.GatewayType
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.bpmn.model.SequenceFlow
import dev.kuml.runtime.tokenflow.GatewayFamily
import dev.kuml.runtime.tokenflow.GatewaySpec
import dev.kuml.runtime.tokenflow.TokenFlowEdge
import dev.kuml.runtime.tokenflow.TokenFlowIssue
import dev.kuml.runtime.tokenflow.TokenFlowNode
import dev.kuml.runtime.tokenflow.TokenFlowSeverity
import dev.kuml.runtime.tokenflow.TokenFlowSpec
import dev.kuml.runtime.tokenflow.TokenNodeKind

/**
 * Builds a [TokenFlowSpec] directly from a [BpmnProcess] (ADR-0015).
 *
 * Unlike `dev.kuml.transform.bpmnuml.BpmnToUmlActivityMapper` (which splits a
 * `MIXED`-direction gateway into two UML nodes with synthetic ids like
 * `<id>_merge` / `<id>_decision`, and mangles sequence-flow ids into
 * `<flowId>_e<N>`), this adapter preserves every BPMN id **verbatim** as the
 * corresponding [TokenFlowNode.id] / [TokenFlowEdge.id]. That id stability is
 * a hard requirement: `dev.kuml.io.svg.bpmn.smil.BpmnTokenTimelineBuilder`
 * resolves trace `nodeId`s directly against `BpmnProcess.flowNodes` /
 * `sequenceFlows` — an id-mangling adapter would silently break the BPMN
 * token animation.
 */
public object BpmnTokenFlowAdapter {
    /** Resolves [diagram]'s `processId` in [model] and delegates to [toSpec]. */
    public fun toSpec(
        model: BpmnModel,
        diagram: ProcessDiagram,
    ): TokenFlowSpec {
        val process =
            model.processes.firstOrNull { it.id == diagram.processId }
                ?: return TokenFlowSpec.of(
                    id = diagram.processId,
                    name = diagram.name,
                    nodes = emptyList(),
                    edges = emptyList(),
                    extraIssues =
                        listOf(
                            TokenFlowIssue(
                                severity = TokenFlowSeverity.ERROR,
                                code = "PROCESS_NOT_FOUND",
                                message = "Diagram '${diagram.name}' references unknown processId '${diagram.processId}'.",
                            ),
                        ),
                )
        return toSpec(process)
    }

    /** Builds a [TokenFlowSpec] from a single [BpmnProcess]. */
    public fun toSpec(process: BpmnProcess): TokenFlowSpec {
        val issues = mutableListOf<TokenFlowIssue>()

        // Degree counts MUST come from sequenceFlows, not BpmnFlowNode.incoming/outgoing —
        // DSL-built models (ProcessBuilder) never populate those lists (ADR-0015 SF-9),
        // mirroring the same rule BpmnToUmlActivityMapper already follows.
        val incomingCount = mutableMapOf<String, Int>()
        val outgoingCount = mutableMapOf<String, Int>()
        val outgoingFlowsByNode = mutableMapOf<String, MutableList<SequenceFlow>>()
        for (flow in process.sequenceFlows) {
            outgoingCount[flow.sourceRef] = (outgoingCount[flow.sourceRef] ?: 0) + 1
            incomingCount[flow.targetRef] = (incomingCount[flow.targetRef] ?: 0) + 1
            outgoingFlowsByNode.getOrPut(flow.sourceRef) { mutableListOf() }.add(flow)
        }

        // hostActivityId -> boundary BpmnEvents attached to it (attachedToRef-based; see the
        // UNSUPPORTED_BOUNDARY_EVENT check below for why this, not BpmnActivity.boundaryEvents,
        // is the reliable signal).
        val attachedBoundaryEvents: Map<String, List<BpmnEvent>> =
            process.flowNodes
                .filterIsInstance<BpmnEvent>()
                .filter { it.attachedToRef != null }
                .groupBy { it.attachedToRef!! }

        val nodes = mutableListOf<TokenFlowNode>()

        for (node in process.flowNodes) {
            when (node) {
                is BpmnActivity -> {
                    // Security review (feature/tokenflow-execution-engine, finding "BPMN sub-process
                    // content / boundary events / loop characteristics silently dropped"): every
                    // BpmnActivity collapses to a single opaque ACTION node below, regardless of any
                    // of the three constructs checked here. Silently discarding them let a model whose
                    // real branching/exception/repetition logic lives in one of these constructs
                    // simulate as if that logic didn't exist — the happy path runs to completion,
                    // TokenFlowEngine reports Terminated, and `kuml simulate` exits 0 on a materially
                    // wrong model. These are therefore ERROR-severity (like UNSUPPORTED_GATEWAY below),
                    // not WARNING: the run must refuse to execute rather than certify a false result.
                    if (node is BpmnSubProcess && (node.flowElementNodes.isNotEmpty() || node.innerSequenceFlows.isNotEmpty())) {
                        issues +=
                            TokenFlowIssue(
                                severity = TokenFlowSeverity.ERROR,
                                code = "UNSUPPORTED_SUBPROCESS_CONTENT",
                                message =
                                    "Sub-process '${node.id}' has expanded content " +
                                        "(${node.flowElementNodes.size} flow elements, " +
                                        "${node.innerSequenceFlows.size} inner sequence flows) that " +
                                        "TokenFlowEngine does not execute — it is collapsed into a single " +
                                        "opaque action, silently discarding its inner branching logic.",
                                elementId = node.id,
                            )
                    }
                    if (node.loopCharacteristics != null) {
                        issues +=
                            TokenFlowIssue(
                                severity = TokenFlowSeverity.ERROR,
                                code = "UNSUPPORTED_LOOP_CHARACTERISTICS",
                                message =
                                    "Activity '${node.id}' declares loopCharacteristics " +
                                        "(${node.loopCharacteristics}), which TokenFlowEngine ignores — the " +
                                        "activity would fire exactly once, silently dropping the loop / " +
                                        "multi-instance semantics.",
                                elementId = node.id,
                            )
                    }
                    // `node.boundaryEvents` (host-side ids) is the DSL-only linkage set by
                    // `TaskBuilder.boundaryEvent(eventId)`; nothing populates it for an
                    // XML-imported model. `BpmnEvent.attachedToRef` (event-side, checked here via
                    // `attachedBoundaryEvents`) is what `BpmnXmlImporter` and
                    // `BpmnConstraintChecker`'s own "attachedToRef must exist" rule actually rely
                    // on — checking only `boundaryEvents` would make this ERROR unreachable for
                    // every real (non-DSL) model.
                    val attached = node.boundaryEvents + attachedBoundaryEvents[node.id].orEmpty().map { it.id }
                    if (attached.isNotEmpty()) {
                        issues +=
                            TokenFlowIssue(
                                severity = TokenFlowSeverity.ERROR,
                                code = "UNSUPPORTED_BOUNDARY_EVENT",
                                message =
                                    "Activity '${node.id}' has boundary event(s) attached " +
                                        "(${attached.distinct().joinToString()}) that TokenFlowEngine does not " +
                                        "execute — their exception/timer/escalation paths are structurally " +
                                        "unreachable, so only the happy path would ever be taken.",
                                elementId = node.id,
                            )
                    }
                    nodes += TokenFlowNode(id = node.id, kind = TokenNodeKind.ACTION, name = node.name, actionBody = node.name)
                }

                is BpmnEvent -> {
                    val kind =
                        when (node.position) {
                            EventPosition.START -> TokenNodeKind.INITIAL
                            EventPosition.END ->
                                if (node.definition ==
                                    EventDefinition.TERMINATE
                                ) {
                                    TokenNodeKind.TERMINATE_FINAL
                                } else {
                                    TokenNodeKind.END_EVENT
                                }
                            EventPosition.INTERMEDIATE -> TokenNodeKind.ACTION
                        }
                    nodes += TokenFlowNode(id = node.id, kind = kind, name = node.name)
                }

                is BpmnGateway -> {
                    val inDeg = incomingCount[node.id] ?: 0
                    val outDeg = outgoingCount[node.id] ?: 0
                    val converging = inDeg > 1 || node.direction == GatewayDirection.CONVERGING || node.direction == GatewayDirection.MIXED
                    val diverging = outDeg > 1 || node.direction == GatewayDirection.DIVERGING || node.direction == GatewayDirection.MIXED
                    val defaultEdgeId = node.defaultFlow ?: outgoingFlowsByNode[node.id]?.firstOrNull { it.isDefault }?.id

                    val family =
                        when (node.gatewayType) {
                            GatewayType.EXCLUSIVE -> GatewayFamily.EXCLUSIVE
                            GatewayType.PARALLEL -> GatewayFamily.PARALLEL
                            GatewayType.INCLUSIVE -> GatewayFamily.INCLUSIVE
                            GatewayType.EVENT_BASED, GatewayType.COMPLEX -> {
                                issues +=
                                    TokenFlowIssue(
                                        severity = TokenFlowSeverity.ERROR,
                                        code = "UNSUPPORTED_GATEWAY",
                                        message =
                                            "Gateway '${node.id}' has unsupported type ${node.gatewayType} — " +
                                                "only EXCLUSIVE, PARALLEL, and INCLUSIVE gateways can be executed.",
                                        elementId = node.id,
                                    )
                                GatewayFamily.EXCLUSIVE
                            }
                        }

                    nodes +=
                        TokenFlowNode(
                            id = node.id,
                            kind = TokenNodeKind.GATEWAY,
                            gateway =
                                GatewaySpec(
                                    family = family,
                                    converging = converging,
                                    diverging = diverging,
                                    defaultEdgeId = defaultEdgeId,
                                ),
                            name = node.name,
                        )
                }
            }
        }

        val edges =
            process.sequenceFlows.map { flow ->
                TokenFlowEdge(
                    id = flow.id,
                    sourceNodeId = flow.sourceRef,
                    targetNodeId = flow.targetRef,
                    guard = flow.conditionExpression,
                    isDefault = flow.isDefault,
                )
            }

        return TokenFlowSpec.of(id = process.id, name = process.name ?: process.id, nodes = nodes, edges = edges, extraIssues = issues)
    }
}
