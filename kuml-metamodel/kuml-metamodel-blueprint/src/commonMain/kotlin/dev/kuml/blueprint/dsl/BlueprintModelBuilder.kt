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
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink

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
    private val notes = mutableListOf<KumlNote>()
    private val noteLinks = mutableListOf<KumlNoteLink>()
    private var phaseCounter = 0

    fun actor(
        name: String,
        role: ActorRole = ActorRole.CUSTOMER,
        description: String? = null,
    ): String {
        val id = autoId(prefix = "actor", n = actors.size)
        actors += Actor(id = id, name = name, role = role, description = description)
        return id
    }

    fun channel(
        name: String,
        kind: ChannelKind = ChannelKind.OTHER,
    ): String {
        val id = autoId(prefix = "channel", n = channels.size)
        channels += Channel(id = id, name = name, kind = kind)
        return id
    }

    fun touchpoint(
        name: String,
        channel: String? = null,
        symbol: TouchpointSymbol = TouchpointSymbol.CIRCLE,
    ): String {
        val id = autoId(prefix = "tp", n = touchpoints.size)
        touchpoints += Touchpoint(id = id, name = name, channelRef = channel, symbol = symbol)
        return id
    }

    /** A phase block — every step declared inside lands in this phase. */
    fun phase(
        name: String,
        block: PhaseBuilder.() -> Unit,
    ): String {
        val id = autoId(prefix = "phase", n = phaseCounter)
        phases += Phase(id = id, name = name, order = phaseCounter)
        phaseCounter++
        PhaseBuilder(phaseId = id, model = this).apply(block)
        return id
    }

    fun connection(
        from: String,
        to: String,
        style: ConnectionStyle = ConnectionStyle.SOLID,
    ) {
        connections +=
            StepConnection(id = autoId(prefix = "conn", n = connections.size), name = null, sourceRef = from, targetRef = to, style = style)
    }

    /** Infix flow operator: `stepA flowsTo stepB`. Returns the target id for chaining. */
    infix fun String.flowsTo(target: String): String {
        connection(from = this, to = target)
        return target
    }

    fun journeyDiagram(
        name: String,
        layers: Set<BlueprintLayer> = setOf(BlueprintLayer.CUSTOMER_ACTIONS),
        emotionCurve: Boolean = true,
        block: (dev.kuml.core.model.layout.LayoutBlockScope.() -> Unit)? = null,
    ) {
        diagrams +=
            JourneyDiagram(
                name = name,
                visibleLayers = layers,
                showEmotionCurve = emotionCurve,
                layout =
                    dev.kuml.core.model.layout.LayoutBlockScope
                        .specOf(block),
            )
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
        block: (dev.kuml.core.model.layout.LayoutBlockScope.() -> Unit)? = null,
    ) {
        diagrams +=
            BlueprintDiagramFull(
                name = name,
                visibleLayers = layers,
                showLines = lines,
                showEmotionCurve = emotionCurve,
                layout =
                    dev.kuml.core.model.layout.LayoutBlockScope
                        .specOf(block),
            )
    }

    // ── internal API used by PhaseBuilder / StepBuilder ──
    internal fun addStep(step: JourneyStep) {
        steps += step
    }

    /** Adds a note anchored to the steps with the given ids (the values `customer(...)`, `backstage(...)` return); none = free-standing. */
    fun note(
        text: String,
        vararg stepIds: String,
        id: String? = null,
    ): String {
        val noteId = id ?: "note-${notes.size + 1}"
        require(notes.none { it.id == noteId }) { "duplicate note id '$noteId'" }
        notes += KumlNote(id = noteId, body = text)
        for (sid in stepIds) noteLinks += KumlNoteLink(id = "note-link:$noteId::$sid", noteId = noteId, targetId = sid)
        return noteId
    }

    internal fun nextStepId(): String = autoId(prefix = "step", n = steps.size)

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
            notes = notes.toList(),
            noteLinks = noteLinks.toList(),
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
