package dev.kuml.ai.tools.context

import kotlinx.serialization.Serializable

/**
 * Frozen, point-in-time snapshot of the editing context. Used by V3.0.25 to
 * "Reject all" the patches an agent has accumulated and roll back to the
 * pre-AI-session state.
 *
 * The snapshot is structural: it captures (model, currentDiagramId, patches).
 * Re-applying the snapshot replaces the context's working state atomically.
 */
@Serializable
public data class Snapshot(
    val snapshotId: String,
    val capturedAt: String,
    val model: AnyKumlModel,
    val currentDiagramId: String?,
    val patches: List<ModelPatch>,
)
