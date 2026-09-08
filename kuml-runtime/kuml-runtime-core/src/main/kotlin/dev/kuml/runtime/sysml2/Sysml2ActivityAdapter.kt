package dev.kuml.runtime.sysml2

import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.activity.ActivityEdgeSpec
import dev.kuml.runtime.activity.ActivityGuardEvaluator
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
     * @param guardEvaluator Guard-evaluation strategy. Defaults to
     *   [ActivityGuardEvaluator] (bare-identifier-friendly, unsandboxed — matches
     *   historical behaviour). Callers that need a bounded evaluation time (e.g.
     *   `kuml simulate --sandbox`, `kuml run`, `kuml trace replay`, the MCP
     *   `kuml.run.*` tools) should pass a
     *   `dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator` wrapping
     *   [ActivityGuardEvaluator] instead (ADR-0015 / security fix B2 — this
     *   parameter previously existed on [ActivityRuntime] but was never
     *   consulted at evaluation time).
     * @return a ready-to-use [ActivityRuntime] instance.
     */
    public fun runtimeFor(
        model: Sysml2Model,
        diagram: ActDiagram,
        guardEvaluator: GuardEvaluator = ActivityGuardEvaluator(),
    ): ActivityRuntime {
        val spec = toSpec(model = model, diagram = diagram)

        // V2.0.20b — pre-parse ACT ControlFlow guards at construction time.
        // Mirrors what Sysml2StateMachineAdapter.toUmlStateMachine does for STM
        // guards in V2.0.20a.  Parse failures are silently ignored: the
        // OclGuardEvaluator already has a transparent legacy fallback, and
        // errors here would surface as false-negative guard results at runtime.
        spec.edges.forEach { edge ->
            if (!edge.guard.isNullOrBlank()) {
                val errors = mutableListOf<dev.kuml.expr.ParseError>()
                OclLikeExpressionParser.tryParse(input = edge.guard, errors = errors)
                // Parse failures are intentionally not logged to avoid noise;
                // the legacy evaluator will handle them at runtime.
            }
        }

        return ActivityRuntime(spec = spec, guardEvaluator = guardEvaluator)
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
