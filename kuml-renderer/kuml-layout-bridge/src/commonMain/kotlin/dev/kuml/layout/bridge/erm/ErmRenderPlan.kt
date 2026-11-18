package dev.kuml.layout.bridge.erm

import dev.kuml.erm.constraint.ConstraintViolation
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints

/**
 * Everything needed to lay out an ERM diagram, shared by the CLI and the desktop
 * so both pick the same notation-specific bridge, size provider and spacing hints.
 *
 * No engine access, no I/O, no rendering: the caller runs its own layout engine on
 * [graph] with [hints] and renders with [notation].
 *
 * @property graph the notation-specific layout graph (Chen and IDEF1X differ structurally from Martin/Bachman).
 * @property hints widened FK-hub spacing, see [ErmLayoutBridge.WIDENED_SPACING_HINTS].
 * @property notation the effective notation (override or the diagram's own).
 * @property violations constraint violations of [ErmConstraintChecker]; the caller decides how to report them.
 */
public data class ErmRenderPlan(
    val graph: LayoutGraph,
    val hints: LayoutHints,
    val notation: ErmNotation,
    val violations: List<ConstraintViolation>,
) {
    public companion object {
        /** Builds the plan for [model]/[diagram]; [notationOverride] wins over `diagram.notation`. */
        public fun of(
            model: ErmModel,
            diagram: ErmDiagram,
            notationOverride: ErmNotation? = null,
        ): ErmRenderPlan {
            val violations = ErmConstraintChecker().check(model)
            val notation = notationOverride ?: diagram.notation
            val hints = ErmLayoutBridge.WIDENED_SPACING_HINTS
            val graph =
                when (notation) {
                    ErmNotation.CHEN ->
                        ErmChenLayoutBridge.toChenLayoutGraph(
                            model = model,
                            diagram = diagram,
                            sizeProvider = ErmChenSizeProvider(model = model, diagram = diagram),
                        )
                    ErmNotation.IDEF1X ->
                        ErmIdef1xLayoutBridge.toLayoutGraph(
                            model = model,
                            diagram = diagram,
                            sizeProvider = ErmContentSizeProvider(model = model, diagram = diagram, layoutDirection = hints.direction),
                        )
                    else ->
                        ErmLayoutBridge.toLayoutGraph(
                            model = model,
                            diagram = diagram,
                            sizeProvider = ErmContentSizeProvider(model = model, diagram = diagram, layoutDirection = hints.direction),
                        )
                }
            return ErmRenderPlan(graph = graph, hints = hints, notation = notation, violations = violations)
        }
    }
}
