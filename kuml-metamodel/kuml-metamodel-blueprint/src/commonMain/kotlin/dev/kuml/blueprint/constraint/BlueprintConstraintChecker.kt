package dev.kuml.blueprint.constraint

import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel

/**
 * A single constraint violation found by [BlueprintConstraintChecker].
 *
 * @property elementId ID of the offending element, or null for model-level issues.
 * @property message Human-readable description.
 * @property severity Whether this blocks rendering (ERROR) or is advisory (WARNING).
 *
 * V3.1.25
 */
public data class ConstraintViolation(
    val elementId: String?,
    val message: String,
    val severity: ViolationSeverity,
)

/** Severity of a [ConstraintViolation]. */
public enum class ViolationSeverity {
    /** Structural error — the model is incomplete or internally inconsistent. */
    ERROR,

    /** Advisory warning — the model is valid but may not follow best practice. */
    WARNING,
}

/**
 * Checks a [BlueprintModel] for structural constraint violations.
 *
 * Rules:
 *  1. At least one actor — ERROR.
 *  2. At least one phase — ERROR.
 *  3. At least one journey step — ERROR.
 *  4. Each phase has at least one step — WARNING (empty column).
 *  5. `Phase.order` lückenlos (0..n-1) and unique — ERROR.
 *  6. `JourneyStep.phaseRef` resolves to an existing phase — ERROR.
 *  7. `JourneyStep.actorRef` resolves to an existing actor — ERROR.
 *  8. `JourneyStep.touchpointRefs` all resolve to existing touchpoints — ERROR.
 *  9. `Touchpoint.channelRef` resolves to an existing channel — ERROR.
 * 10. `StepConnection` source/target resolve to a step or touchpoint — ERROR.
 * 11. Sentiment set only on CUSTOMER_ACTIONS steps — WARNING otherwise.
 * 12. At least one customer step carries a sentiment — WARNING (emotion curve empty).
 *
 * An empty result means the model passes all checks.
 *
 * V3.1.25
 */
public class BlueprintConstraintChecker {
    public fun check(model: BlueprintModel): List<ConstraintViolation> =
        buildList {
            // 1–3. minimum content
            if (model.actors.isEmpty()) {
                add(ConstraintViolation(null, "Blueprint '${model.name}' has no actors", ViolationSeverity.ERROR))
            }
            if (model.phases.isEmpty()) {
                add(ConstraintViolation(null, "Blueprint '${model.name}' has no phases", ViolationSeverity.ERROR))
            }
            if (model.steps.isEmpty()) {
                add(ConstraintViolation(null, "Blueprint '${model.name}' has no steps", ViolationSeverity.ERROR))
            }

            // 5. phase order must be 0..n-1 unique
            val orders = model.phases.map { it.order }.sorted()
            val expected = model.phases.indices.toList()
            if (model.phases.isNotEmpty() && orders != expected) {
                add(
                    ConstraintViolation(
                        null,
                        "Phase orders must be a contiguous 0-based sequence; got ${model.phases.map { it.order }}",
                        ViolationSeverity.ERROR,
                    ),
                )
            }

            val phaseIds = model.phases.map { it.id }.toSet()
            val actorIds = model.actors.map { it.id }.toSet()
            val touchpointIds = model.touchpoints.map { it.id }.toSet()
            val channelIds = model.channels.map { it.id }.toSet()

            // 4. each phase has at least one step
            model.phases.forEach { phase ->
                if (model.steps.none { it.phaseRef == phase.id }) {
                    add(
                        ConstraintViolation(
                            phase.id,
                            "Phase '${phase.name ?: phase.id}' has no steps (empty column)",
                            ViolationSeverity.WARNING,
                        ),
                    )
                }
            }

            // 6–8, 11. per-step checks
            model.steps.forEach { step ->
                if (step.phaseRef !in phaseIds) {
                    add(
                        ConstraintViolation(
                            step.id,
                            "Step '${step.name ?: step.id}' phaseRef '${step.phaseRef}' not found",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
                if (step.actorRef != null && step.actorRef !in actorIds) {
                    add(
                        ConstraintViolation(
                            step.id,
                            "Step '${step.name ?: step.id}' actorRef '${step.actorRef}' not found",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
                step.touchpointRefs.forEach { tpRef ->
                    if (tpRef !in touchpointIds) {
                        add(
                            ConstraintViolation(
                                step.id,
                                "Step '${step.name ?: step.id}' touchpointRef '$tpRef' not found",
                                ViolationSeverity.ERROR,
                            ),
                        )
                    }
                }
                if (step.sentiment != null && step.layer != BlueprintLayer.CUSTOMER_ACTIONS) {
                    add(
                        ConstraintViolation(
                            step.id,
                            "Step '${step.name ?: step.id}' has a sentiment but is not in the Customer-Actions layer " +
                                "(sentiment only drives the emotion curve there)",
                            ViolationSeverity.WARNING,
                        ),
                    )
                }
            }

            // 9. touchpoint channel refs
            model.touchpoints.forEach { tp ->
                if (tp.channelRef != null && tp.channelRef !in channelIds) {
                    add(
                        ConstraintViolation(
                            tp.id,
                            "Touchpoint '${tp.name ?: tp.id}' channelRef '${tp.channelRef}' not found",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
            }

            // 10. connection endpoints
            val stepIds = model.steps.map { it.id }.toSet()
            val connectable = stepIds + touchpointIds
            model.connections.forEach { conn ->
                if (conn.sourceRef !in connectable) {
                    add(
                        ConstraintViolation(
                            conn.id,
                            "Connection '${conn.id}' sourceRef '${conn.sourceRef}' not a step or touchpoint",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
                if (conn.targetRef !in connectable) {
                    add(
                        ConstraintViolation(
                            conn.id,
                            "Connection '${conn.id}' targetRef '${conn.targetRef}' not a step or touchpoint",
                            ViolationSeverity.ERROR,
                        ),
                    )
                }
            }

            // 12. emotion curve needs at least one customer sentiment
            val hasCustomerSentiment =
                model.steps.any { it.layer == BlueprintLayer.CUSTOMER_ACTIONS && it.sentiment != null }
            val wantsEmotion =
                model.diagrams.any {
                    when (it) {
                        is dev.kuml.blueprint.model.JourneyDiagram -> it.showEmotionCurve
                        is dev.kuml.blueprint.model.BlueprintDiagramFull -> it.showEmotionCurve
                    }
                }
            if (wantsEmotion && !hasCustomerSentiment) {
                add(
                    ConstraintViolation(
                        null,
                        "A diagram requests the emotion curve but no Customer-Actions step carries a sentiment",
                        ViolationSeverity.WARNING,
                    ),
                )
            }
        }
}
