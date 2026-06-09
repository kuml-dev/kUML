package dev.kuml.runtime.sysml2

import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.activity.ActivityEdgeSpec
import dev.kuml.runtime.activity.ActivityNodeSpec
import dev.kuml.runtime.activity.ActivityRuntime
import dev.kuml.runtime.activity.ActivityRuntimeSpec
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.Sysml2Model

/**
 * V2.0.18 — adapter that builds an [ActivityRuntime] from a SysML 2 model
 * plus an [ActDiagram] selecting which [ActionDefinition]s participate.
 *
 * ## Translation rules
 *
 *  - Each visible [ActionDefinition] → [ActivityNodeSpec] (id, kind, actionBody = action).
 *  - Each [ControlFlowUsage] whose source AND target are both visible →
 *    [ActivityEdgeSpec](isObjectFlow = false, guard from usage).
 *  - Each [ObjectFlowUsage] whose source AND target are both visible →
 *    [ActivityEdgeSpec](isObjectFlow = true, objectType from usage).
 *  - Edges whose source or target is NOT in the visible set are silently
 *    dropped — same **Pattern A** projection rule used by the V2.0.17 STM
 *    adapter and the layout-bridge.
 *
 * ## Rationale
 *
 * Mirrors [Sysml2StateMachineAdapter] exactly: an adapter builds a
 * model-agnostic spec, the runtime runs unchanged. This decouples the
 * metamodel surface from the execution engine and lets both evolve
 * independently.
 */
public object Sysml2ActivityAdapter {
    /**
     * Build an [ActivityRuntime] from a SysML 2 model + ACT diagram.
     *
     * @param model the SysML 2 model containing [ActionDefinition]s and flow usages.
     * @param diagram the ACT diagram whose [ActDiagram.elementIds] select the
     *   participating action nodes.
     * @return a ready-to-use [ActivityRuntime] instance.
     */
    public fun runtimeFor(
        model: Sysml2Model,
        diagram: ActDiagram,
    ): ActivityRuntime {
        val spec = toSpec(model, diagram)
        return ActivityRuntime(spec = spec, guardEvaluator = OclGuardEvaluator())
    }

    /**
     * Build an [ActivityRuntimeSpec] from a SysML 2 model + ACT diagram.
     * Exposed for tests that want to inspect the translation shape.
     */
    public fun toSpec(
        model: Sysml2Model,
        diagram: ActDiagram,
    ): ActivityRuntimeSpec {
        val visibleIds = diagram.elementIds.toSet()

        // Translate all visible ActionDefinitions to ActivityNodeSpecs
        val nodes: Map<String, ActivityNodeSpec> =
            model.definitions
                .filterIsInstance<ActionDefinition>()
                .filter { it.id in visibleIds }
                .associate { def ->
                    def.id to
                        ActivityNodeSpec(
                            id = def.id,
                            kind = def.kind,
                            actionBody = def.action,
                        )
                }

        // Translate ControlFlowUsages whose both endpoints are visible
        val controlFlowEdges: List<ActivityEdgeSpec> =
            model.usages
                .filterIsInstance<ControlFlowUsage>()
                .filter { it.sourceNodeId in visibleIds && it.targetNodeId in visibleIds }
                .map { usage ->
                    ActivityEdgeSpec(
                        id = usage.id,
                        sourceNodeId = usage.sourceNodeId,
                        targetNodeId = usage.targetNodeId,
                        guard = usage.guard,
                        isObjectFlow = false,
                        objectType = null,
                    )
                }

        // Translate ObjectFlowUsages whose both endpoints are visible
        val objectFlowEdges: List<ActivityEdgeSpec> =
            model.usages
                .filterIsInstance<ObjectFlowUsage>()
                .filter { it.sourceNodeId in visibleIds && it.targetNodeId in visibleIds }
                .map { usage ->
                    ActivityEdgeSpec(
                        id = usage.id,
                        sourceNodeId = usage.sourceNodeId,
                        targetNodeId = usage.targetNodeId,
                        guard = null,
                        isObjectFlow = true,
                        objectType = usage.objectType,
                    )
                }

        return ActivityRuntimeSpec(
            nodes = nodes,
            edges = controlFlowEdges + objectFlowEdges,
        )
    }
}
