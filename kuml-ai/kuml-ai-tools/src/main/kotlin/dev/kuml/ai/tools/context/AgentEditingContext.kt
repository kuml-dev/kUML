package dev.kuml.ai.tools.context

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sandbox-friendly editing surface that ALL @Tool-driven mutations target.
 *
 * Semantics:
 *  - Construction takes an immutable seed model; the context immediately clones it
 *    (see DeepCopy). The seed is never touched.
 *  - Every applyPatch() updates an internal mutable model + appends to patches.
 *  - currentDiagramId is set explicitly via setCurrentDiagramId or implicitly
 *    by the first AddElement patch that names a diagram id.
 *  - snapshot() captures the working state for V3.0.25 rollback.
 *  - All public methods are coroutine-safe — a Mutex serializes mutations so
 *    parallel Koog @Tool invocations on the same context cannot interleave.
 *
 * NOT thread-safe across context INSTANCES — caller owns one context per
 * agent session. The session id is the natural isolation key.
 */
public class AgentEditingContext(
    initialModel: AnyKumlModel,
    private val onPatch: (ModelPatch) -> Unit = {},
) {
    /** Mutex serializes mutations within this context. */
    private val mutex: Mutex = Mutex()

    private var working: AnyKumlModel = DeepCopy.copy(initialModel)
    private val mutPatches: MutableList<ModelPatch> = mutableListOf()
    private var _currentDiagramId: String? = null

    /** Read-only view of the working model. */
    public suspend fun resolveModel(): AnyKumlModel = mutex.withLock { working }

    /** Currently focused diagram id — settable by tools or set implicitly on first edit. */
    public val currentDiagramId: String? get() = _currentDiagramId

    /** Append-only patch log (defensive copy on read). */
    public suspend fun patches(): List<ModelPatch> = mutex.withLock { mutPatches.toList() }

    /**
     * Set the active diagram id used by all subsequent edit-tool calls when the
     * caller does not name a diagram explicitly. Returns the previously-active id.
     */
    public suspend fun setCurrentDiagramId(id: String?): String? =
        mutex.withLock {
            val prev = _currentDiagramId
            _currentDiagramId = id
            prev
        }

    /**
     * Apply a patch to the working clone.
     *
     * The [mutate] function receives the current working model and returns a new
     * model instance with the mutation applied. If [mutate] throws, the patch is
     * NOT recorded and the exception propagates to the caller (which wraps it in
     * PatchApplyResult.Failure). The Mutex ensures atomicity.
     */
    public suspend fun applyPatch(
        patch: ModelPatch,
        mutate: (AnyKumlModel) -> AnyKumlModel,
    ): PatchApplyResult =
        mutex.withLock {
            return@withLock try {
                val newModel = mutate(working)
                working = newModel
                mutPatches += patch
                onPatch(patch)
                // Auto-set currentDiagramId on first AddElement if not yet set
                if (_currentDiagramId == null && patch is ModelPatch.AddElement && patch.diagramId != null) {
                    _currentDiagramId = patch.diagramId
                }
                val elementId =
                    when (patch) {
                        is ModelPatch.AddElement -> patch.elementId
                        is ModelPatch.AddRelationship -> patch.relationshipId
                        is ModelPatch.UpdateAttribute -> patch.ownerId
                        is ModelPatch.RenameElement -> patch.elementId
                        is ModelPatch.RemoveElement -> patch.elementId
                    }
                PatchApplyResult.Success(elementId = elementId, patchId = patch.patchId)
            } catch (e: Exception) {
                PatchApplyResult.Failure(
                    reason = e.message ?: e.javaClass.simpleName,
                    hint = "Check the element ids and try again",
                )
            }
        }

    /** Freeze the current working state — V3.0.25 rollback hook. */
    public suspend fun snapshot(): Snapshot =
        mutex.withLock {
            Snapshot(
                snapshotId = ModelPatch.newId(),
                capturedAt = ModelPatch.nowIso(),
                model = DeepCopy.copy(working),
                currentDiagramId = _currentDiagramId,
                patches = mutPatches.toList(),
            )
        }

    /** Restore working state from a snapshot — discards patches after snapshot. */
    public suspend fun resetTo(snapshot: Snapshot): Unit =
        mutex.withLock {
            working = DeepCopy.copy(snapshot.model)
            _currentDiagramId = snapshot.currentDiagramId
            mutPatches.clear()
            mutPatches.addAll(snapshot.patches)
        }

    public companion object {
        /** Convenience: fresh context seeded with an empty UML model. */
        public fun emptyUml(modelName: String = "AgentModel"): AgentEditingContext = AgentEditingContext(AnyKumlModel.emptyUml(modelName))

        public fun emptyC4(modelName: String = "AgentC4"): AgentEditingContext = AgentEditingContext(AnyKumlModel.emptyC4(modelName))

        public fun emptySysml2(modelName: String = "AgentSysml2"): AgentEditingContext =
            AgentEditingContext(AnyKumlModel.emptySysml2(modelName))
    }
}
