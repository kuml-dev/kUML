package dev.kuml.runtime.tokenflow.adapter

import dev.kuml.runtime.activity.ActivityRuntimeSpec
import dev.kuml.runtime.tokenflow.GatewayFamily
import dev.kuml.runtime.tokenflow.GatewaySpec
import dev.kuml.runtime.tokenflow.TokenFlowEdge
import dev.kuml.runtime.tokenflow.TokenFlowNode
import dev.kuml.runtime.tokenflow.TokenFlowSpec
import dev.kuml.runtime.tokenflow.TokenNodeKind
import dev.kuml.sysml2.ActivityNodeKind

/**
 * Bridges a legacy [ActivityRuntimeSpec] (SysML 2 ACT, via
 * `dev.kuml.runtime.sysml2.Sysml2ActivityAdapter.toSpec`) into the ADR-0015
 * [TokenFlowSpec] shape.
 *
 * This is what makes SysML 2 ACT diagrams executable on [TokenFlowEngine]
 * without a separate `Sysml2Act`-specific mapping — it also doubles as the
 * bridge the engine-vs-`ActivityRuntime` parity test drives both engines
 * from the same source spec through.
 */
public fun ActivityRuntimeSpec.toTokenFlowSpec(
    id: String,
    name: String,
): TokenFlowSpec {
    val nodes =
        nodes.values.map { n ->
            when (n.kind) {
                ActivityNodeKind.Initial -> TokenFlowNode(id = n.id, kind = TokenNodeKind.INITIAL, actionBody = n.actionBody)
                ActivityNodeKind.Action -> TokenFlowNode(id = n.id, kind = TokenNodeKind.ACTION, actionBody = n.actionBody)
                ActivityNodeKind.Final -> TokenFlowNode(id = n.id, kind = TokenNodeKind.TERMINATE_FINAL, actionBody = n.actionBody)
                ActivityNodeKind.FlowFinal -> TokenFlowNode(id = n.id, kind = TokenNodeKind.FLOW_FINAL, actionBody = n.actionBody)
                ActivityNodeKind.Decision ->
                    TokenFlowNode(
                        id = n.id,
                        kind = TokenNodeKind.GATEWAY,
                        gateway = GatewaySpec(family = GatewayFamily.EXCLUSIVE, converging = false, diverging = true),
                    )
                ActivityNodeKind.Merge ->
                    TokenFlowNode(
                        id = n.id,
                        kind = TokenNodeKind.GATEWAY,
                        gateway = GatewaySpec(family = GatewayFamily.EXCLUSIVE, converging = true, diverging = false),
                    )
                ActivityNodeKind.Fork ->
                    TokenFlowNode(
                        id = n.id,
                        kind = TokenNodeKind.GATEWAY,
                        gateway = GatewaySpec(family = GatewayFamily.PARALLEL, converging = false, diverging = true),
                    )
                ActivityNodeKind.Join ->
                    TokenFlowNode(
                        id = n.id,
                        kind = TokenNodeKind.GATEWAY,
                        gateway = GatewaySpec(family = GatewayFamily.PARALLEL, converging = true, diverging = false),
                    )
            }
        }

    val edges =
        edges.map { e ->
            TokenFlowEdge(
                id = e.id,
                sourceNodeId = e.sourceNodeId,
                targetNodeId = e.targetNodeId,
                guard = e.guard,
                isObjectFlow = e.isObjectFlow,
                objectType = e.objectType,
            )
        }

    return TokenFlowSpec.of(id = id, name = name, nodes = nodes, edges = edges)
}
