package dev.kuml.sysml2

import kotlinx.serialization.Serializable

/**
 * Discriminator for the shapes of a state-machine vertex (wave A1).
 *
 * A single [StateDefinition] class carries every kind (same design as
 * [ActivityNodeKind] for ACT). The legacy boolean flags
 * [StateDefinition.isInitial] / [StateDefinition.isFinal] stay supported;
 * [StateDefinition.effectiveKind] folds them into this enum.
 */
@Serializable
enum class StateKind {
    /** Regular (leaf or composite) state. */
    State,

    /** Initial pseudo-state (filled circle). */
    Initial,

    /** Final pseudo-state (donut). */
    Final,

    /** Choice pseudo-state (hollow diamond); guards are evaluated on arrival. */
    Choice,

    /** Junction pseudo-state (filled dot); static merge/split of transitions. */
    Junction,

    /** Fork pseudo-state (bar); splits into concurrent regions. */
    Fork,

    /** Join pseudo-state (bar); synchronises concurrent regions. */
    Join,

    /** Shallow history pseudo-state (`H`). */
    ShallowHistory,

    /** Deep history pseudo-state (`H*`). */
    DeepHistory,
}

/**
 * An orthogonal region of a composite [StateDefinition]. Children refer to it
 * through [StateDefinition.regionId].
 */
@Serializable
data class StateRegion(
    val id: String,
    val name: String? = null,
)
