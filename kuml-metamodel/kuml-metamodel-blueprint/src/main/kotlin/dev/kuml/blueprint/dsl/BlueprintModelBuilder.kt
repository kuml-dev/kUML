package dev.kuml.blueprint.dsl

import dev.kuml.blueprint.model.Actor
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.Channel
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.ConnectionStyle
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Phase
import dev.kuml.blueprint.model.StepConnection
import dev.kuml.blueprint.model.Touchpoint
import dev.kuml.blueprint.model.TouchpointSymbol

/**
 * Root builder for a [BlueprintModel].
 *
 * Auto-ids are deterministic (`actor_0`, `phase_1`, `step_3`, …) — never UUIDs —
 * so snapshot/diff tests stay stable. Phase order is taken from declaration
 * order via [phaseCounter], guaranteeing it is gap-free and unique.
 *
 * V3.1.22
 */
@BlueprintDsl
class BlueprintModelBuilder(
    private val name: String,
) {
    private val actors = mutableListOf<Actor>()
    private val channels = mutableListOf<Channel>()
    private val touchpoints = mutableListOf<Touchpoint>()
    private val phases = mutableListOf<Phase>()
    private val steps = mutableListOf<JourneyStep>()
    private val connections = mutableListOf<StepConnection>()
    private val diagrams = mutableListOf<BlueprintDiagram>()
    private var phaseCounter = 0

    fun actor(
        name: String,
        role: ActorRole = ActorRole.CUSTOMER,
        description: String? = null,
    ): String {
        val id = autoId("actor", actors.size)
        actors += Actor(id, name, role, description)
        return id
    }

    fun channel(
        name: String,
        kind: ChannelKind = ChannelKind.OTHER,
    ): String {
        val id = autoId("channel", channels.size)
        channels += Channel(id, name, kind)
        return id
    }

    fun touchpoint(
        name: String,
        channel: String? = null,
        symbol: TouchpointSymbol = TouchpointSymbol.CIRCLE,
    ): String {
        val id = autoId("tp", touchpoints.size)
        touchpoints += Touchpoint(id, name, channelRef = channel, symbol = symbol)
        return id
    }

    /** A phase block — every step declared inside lands in this phase. */
    fun phase(
        name: String,
        block: PhaseBuilder.() -> Unit,
    ): String {
        val id = autoId("phase", phaseCounter)
        phases += Phase(id, name, order = phaseCounter)
        phaseCounter++
        PhaseBuilder(id, this).apply(block)
        return id
    }

    fun connection(
        from: String,
        to: String,
        style: ConnectionStyle = ConnectionStyle.SOLID,
    ) {
        connections += StepConnection(autoId("conn", connections.size), null, from, to, style)
    }

    /** Infix flow operator: `stepA flowsTo stepB`. Returns the target id for chaining. */
    infix fun String.flowsTo(target: String): String {
        connection(this, target)
        return target
    }

    fun journeyDiagram(
        name: String,
        layers: Set<BlueprintLayer> = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
        emotionCurve: Boolean = true,
    ) {
        diagrams += JourneyDiagram(name, layers, emotionCurve)
    }

    /**
     * Full Service-Blueprint view. By default all four layers and all three
     * separator lines are shown; pass [layers] / [lines] subsets to restrict
     * the projection, and [emotionCurve] = true to overlay the emotion curve.
     */
    fun blueprintDiagram(
        name: String,
        layers: Set<BlueprintLayer> = BlueprintLayer.entries.toSet(),
        lines: Set<BlueprintLine> = BlueprintLine.entries.toSet(),
        emotionCurve: Boolean = false,
    ) {
        diagrams += BlueprintDiagramFull(name, layers, lines, emotionCurve)
    }

    // ── internal API used by PhaseBuilder / StepBuilder ──
    internal fun addStep(step: JourneyStep) {
        steps += step
    }

    internal fun nextStepId(): String = autoId("step", steps.size)

    private fun autoId(
        prefix: String,
        n: Int,
    ) = "${prefix}_$n"

    fun build(): BlueprintModel =
        BlueprintModel(
            name = name,
            actors = actors.toList(),
            channels = channels.toList(),
            touchpoints = touchpoints.toList(),
            phases = phases.sortedBy { it.order },
            steps = steps.toList(),
            connections = connections.toList(),
            diagrams = diagrams.toList(),
        )
}

/**
 * DSL entry point — analogous to `bpmnModel { }`, `sysml2Model { }`.
 *
 * `blueprint(name) { … }` covers both views: a Journey Map is simply a
 * blueprint with only the Customer layer plus the emotion curve.
 *
 * V3.1.22
 */
fun blueprint(
    name: String,
    block: BlueprintModelBuilder.() -> Unit,
): BlueprintModel = BlueprintModelBuilder(name).apply(block).build()
