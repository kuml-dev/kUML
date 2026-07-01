package dev.kuml.blueprint.model

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Top-level container for a User Journey / Service Blueprint.
 *
 * The model is the single source of truth; [diagrams] are projections that
 * select which layers/lines/emotion-curve are visible.
 *
 * V3.1.21
 */
@Serializable
data class BlueprintModel(
    val name: String,
    val actors: List<Actor> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val touchpoints: List<Touchpoint> = emptyList(),
    val phases: List<Phase> = emptyList(),
    val steps: List<JourneyStep> = emptyList(),
    val connections: List<StepConnection> = emptyList(),
    val diagrams: List<BlueprintDiagram> = emptyList(),
    val metadata: Map<String, KumlMetaValue> = emptyMap(),
) {
    /** Looks up any blueprint element by id across all element lists. */
    fun elementById(id: String): BlueprintElement? =
        actors.firstOrNull { it.id == id }
            ?: channels.firstOrNull { it.id == id }
            ?: touchpoints.firstOrNull { it.id == id }
            ?: phases.firstOrNull { it.id == id }
            ?: steps.firstOrNull { it.id == id }
            ?: connections.firstOrNull { it.id == id }

    /** Steps of a phase in a given layer — used for renderer cell occupancy. */
    fun stepsIn(
        phaseId: String,
        layer: BlueprintLayer,
    ): List<JourneyStep> = steps.filter { it.phaseRef == phaseId && it.layer == layer }

    /** Which layers are actually occupied (empty ones can be hidden by the renderer). */
    fun activeLayers(): Set<BlueprintLayer> = steps.map { it.layer }.toSet()

    /** Phases sorted by their declared column [Phase.order]. */
    fun orderedPhases(): List<Phase> = phases.sortedBy { it.order }

    /**
     * Emotion curve: one (phase, sentiment?) pair per phase, with the sentiment
     * averaged over the phase's Customer-Actions steps. A phase with no
     * sentiment-bearing customer step yields `null` (rendered as a gap).
     */
    fun emotionCurve(): List<Pair<Phase, Sentiment?>> =
        orderedPhases().map { phase ->
            val sentiments =
                stepsIn(phase.id, BlueprintLayer.CUSTOMER_ACTIONS).mapNotNull { it.sentiment }
            val avg =
                if (sentiments.isEmpty()) {
                    null
                } else {
                    Sentiment.of(sentiments.map { it.value }.average().roundToInt())
                }
            phase to avg
        }
}
