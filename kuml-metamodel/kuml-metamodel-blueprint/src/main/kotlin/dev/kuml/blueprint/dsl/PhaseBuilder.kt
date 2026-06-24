package dev.kuml.blueprint.dsl

import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Sentiment

/**
 * Builder scoped to a single phase. All steps declared here are bound to that
 * phase via [phaseId].
 *
 * V3.1.22 (layer-shortcut convenience builders `frontstage`/`backstage`/`support`
 * are added in V3.1.24).
 */
@BlueprintDsl
class PhaseBuilder(
    private val phaseId: String,
    private val model: BlueprintModelBuilder,
) {
    /** Generic step in an arbitrary layer (defaults to Customer Actions). */
    fun step(
        name: String,
        layer: BlueprintLayer = BlueprintLayer.CUSTOMER_ACTIONS,
        touchpoints: List<String> = emptyList(),
        actor: String? = null,
        sentiment: Sentiment? = null,
        pain: String? = null,
        opportunity: String? = null,
    ): String {
        val id = model.nextStepId()
        model.addStep(
            JourneyStep(
                id = id,
                name = name,
                phaseRef = phaseId,
                layer = layer,
                touchpointRefs = touchpoints,
                actorRef = actor,
                sentiment = sentiment,
                painPoint = pain,
                opportunity = opportunity,
            ),
        )
        return id
    }

    /** Convenience: a Customer-Actions step that carries an emotion. */
    fun customer(
        name: String,
        sentiment: Sentiment,
        touchpoints: List<String> = emptyList(),
        actor: String? = null,
        pain: String? = null,
        opportunity: String? = null,
    ): String =
        step(
            name = name,
            layer = BlueprintLayer.CUSTOMER_ACTIONS,
            touchpoints = touchpoints,
            actor = actor,
            sentiment = sentiment,
            pain = pain,
            opportunity = opportunity,
        )
}
