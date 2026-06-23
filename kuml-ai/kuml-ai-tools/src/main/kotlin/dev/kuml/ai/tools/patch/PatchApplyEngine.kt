package dev.kuml.ai.tools.patch

import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.context.PatchApplyResult
import dev.kuml.ai.tools.context.Snapshot
import dev.kuml.ai.tools.patch.aitrace.AiTraceSink
import dev.kuml.ai.tools.patch.aitrace.NoopAiTraceSink
import dev.kuml.ai.tools.patch.apply.ModelMutationRouter
import dev.kuml.ai.tools.patch.apply.ModelSnippet
import dev.kuml.ai.tools.patch.store.InsertResult
import dev.kuml.ai.tools.patch.store.PatchStatus
import dev.kuml.ai.tools.patch.store.PersistentPatchStore
import dev.kuml.ai.tools.patch.validation.PatchValidationResult
import dev.kuml.runtime.AiTraceEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock

/**
 * Orchestrates the Accept/Reject lifecycle for AI-generated patches.
 *
 * ## Architecture
 * The engine maintains its own pending patch buffer. AI tool calls produce
 * [ModelPatch] instances that are submitted to the engine via [buffer].
 * The engine validates each patch BEFORE applying it to the [AgentEditingContext]
 * working model — inserting a validation gate between tool-call and mutation.
 *
 * Construction emits an [AiTraceEntry.SessionStarted] and freezes a
 * [Snapshot] for `rejectAll()` rollback. The engine holds a stable
 * [PatchSessionId] (ULID) for the lifetime of the instance — V3.0.24-UI
 * uses it as the conversation key.
 *
 * ## Concurrency
 * `buffer` / `applyOne` / `rejectOne` / `rejectAll` are sequenced by [engineMutex].
 * The engine always acquires [engineMutex] BEFORE any AgentEditingContext
 * operation — this ordering prevents lock-order inversion with the context's
 * own internal mutex.
 *
 * ## TODO (V3.x)
 * At models with > 500 elements the pre-session snapshot consumes several MB
 * of heap (JSON-roundtrip via DeepCopy). An `IncrementalSnapshot` strategy
 * (dirty-tracked elements only) would reduce this. For V3.0.25 the blowup is
 * acceptable: realistic AI sessions target < 100-element models.
 */
public class PatchApplyEngine(
    private val context: AgentEditingContext,
    private val validator: PatchValidator = PatchValidator(),
    private val traceSink: AiTraceSink = NoopAiTraceSink,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Owner identifier for this engine instance.
     *
     * Used for multi-user ownership validation: a patch buffered with a different
     * owner id will be rejected at apply time. Defaults to the OS username so
     * single-user callers need not pass this explicitly.
     */
    ownerId: String? = null,
    /**
     * Optional persistent patch store. When non-null, every applied/rejected patch is
     * recorded. Conflict detection runs against the store's 5-second window.
     * Defaults to null (in-memory only) for backward compatibility.
     */
    private val store: PersistentPatchStore? = null,
    /**
     * Optional in-process pub/sub broker for cross-session notifications.
     * Defaults to null (standalone engine).
     */
    private val broker: SharedPatchBroker? = null,
) : AutoCloseable {
    private val engineMutex = Mutex()

    /** Stable session id for this engine instance — set once in init. */
    public val sessionId: PatchSessionId = PatchSessionId.newSession()

    /** Effective owner id for this session (OS username by default). */
    public val ownerId: String = ownerId ?: System.getProperty("user.name") ?: "unknown"

    /** Pre-session snapshot; captured synchronously in the init block. */
    private lateinit var preSessionSnapshot: Snapshot

    /** Engine-internal buffer of patches waiting for accept/reject. */
    private val pendingBuffer: MutableList<ModelPatch> = mutableListOf()
    private val rejectedPatchIds: MutableSet<String> = mutableSetOf()
    private val appliedPatchIds: MutableSet<String> = mutableSetOf()

    /**
     * Maps patchId → ownerId for multi-user ownership validation.
     * Populated by [buffer(patch, ownerId)]; cleared alongside the buffer on rejectAll.
     */
    private val patchOwners: MutableMap<String, String> = mutableMapOf()

    private var seqCounter = 0L

    init {
        // Pre-session snapshot + initial trace entry.
        // Snapshot is captured via runBlocking because init blocks are synchronous
        // but AgentEditingContext.snapshot() is a suspend function.
        // This is intentional: the snapshot MUST be captured before any tool call
        // can race with us. The overhead is a single JSON deep-copy.
        val (snapshot, fingerprint) =
            kotlinx.coroutines.runBlocking {
                val snap = context.snapshot()
                val fp = modelFingerprint(snap.model)
                snap to fp
            }
        preSessionSnapshot = snapshot
        kotlinx.coroutines.runBlocking {
            traceSink.emit(
                AiTraceEntry.SessionStarted(
                    seqNo = nextSeq(),
                    timestamp = nowIso(),
                    sessionId = sessionId.value,
                    baseModelFingerprint = fingerprint,
                ),
            )
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Adds [patch] to the pending buffer attributed to this engine's [ownerId].
     * Does NOT apply it to the context yet. Call [applyOne] to validate + apply,
     * or [rejectOne] to discard.
     *
     * Backward-compatible single-argument form; the patch is owned by [ownerId].
     */
    public suspend fun buffer(patch: ModelPatch): Unit = buffer(patch, ownerId)

    /**
     * Adds [patch] to the pending buffer attributed to [patchOwnerId].
     *
     * Use this overload in multi-user scenarios where a patch originates from a
     * different user than the engine owner. Ownership validation happens at
     * [applyOne] time: if [patchOwnerId] != [ownerId], the apply is rejected.
     */
    public suspend fun buffer(
        patch: ModelPatch,
        patchOwnerId: String,
    ): Unit =
        engineMutex.withLock {
            pendingBuffer.add(patch)
            patchOwners[patch.patchId] = patchOwnerId
        }

    /**
     * Validate + apply a buffered patch to the [AgentEditingContext] working model.
     *
     * Emits [AiTraceEntry.Validated] and (on success) [AiTraceEntry.Applied].
     */
    public suspend fun applyOne(patchId: String): PatchApplyOutcome =
        engineMutex.withLock {
            if (patchId in rejectedPatchIds) {
                return@withLock PatchApplyOutcome.ApplyFailed(
                    patchId = patchId,
                    reason = "Patch $patchId was already rejected.",
                )
            }
            if (patchId in appliedPatchIds) {
                return@withLock PatchApplyOutcome.ApplyFailed(
                    patchId = patchId,
                    reason = "Patch $patchId was already applied.",
                )
            }

            val patch =
                pendingBuffer.firstOrNull { it.patchId == patchId }
                    ?: return@withLock PatchApplyOutcome.ApplyFailed(
                        patchId = patchId,
                        reason = "Patch $patchId not found in engine buffer.",
                    )

            // Multi-user ownership gate: reject if patch was buffered by a different owner.
            val patchOwner = patchOwners[patchId]
            if (patchOwner != null && patchOwner != ownerId) {
                val ownershipReason =
                    "${PatchReasonPrefix.OWNERSHIP_MISMATCH}: Patch $patchId owned by '$patchOwner'," +
                        " cannot be applied by '$ownerId'."
                traceSink.emit(
                    AiTraceEntry.Rejected(
                        seqNo = nextSeq(),
                        timestamp = nowIso(),
                        sessionId = sessionId.value,
                        patchId = patchId,
                        reason = ownershipReason,
                    ),
                )
                rejectedPatchIds += patchId
                patchOwners.remove(patchId)
                return@withLock PatchApplyOutcome.ApplyFailed(
                    patchId = patchId,
                    reason = ownershipReason,
                )
            }

            val baseModel = context.resolveModel()
            val mutate = ModelMutationRouter.mutateFor(patch)
            val validationResult = validator.validate(baseModel, patch, mutate)

            val patchKind = patchKindOf(patch)

            traceSink.emit(
                AiTraceEntry.Validated(
                    seqNo = nextSeq(),
                    timestamp = nowIso(),
                    sessionId = sessionId.value,
                    patchId = patchId,
                    patchKind = patchKind,
                    phase =
                        when (validationResult) {
                            is PatchValidationResult.Valid -> "OK"
                            is PatchValidationResult.Invalid -> validationResult.phase.name
                        },
                    errorCount =
                        when (validationResult) {
                            is PatchValidationResult.Valid -> 0
                            is PatchValidationResult.Invalid -> validationResult.errors.size
                        },
                ),
            )

            if (validationResult is PatchValidationResult.Invalid) {
                return@withLock PatchApplyOutcome.ValidationFailed(
                    patchId = patchId,
                    validation = validationResult,
                )
            }

            val validOutcome = validationResult as PatchValidationResult.Valid

            // Apply to the real working model
            // Persistence conflict check (when store is wired).
            if (store != null) {
                val storeResult = store.insert(patch, ownerId, PatchStatus.PENDING)
                if (storeResult is InsertResult.ConflictDetected) {
                    val conflictReason =
                        "${PatchReasonPrefix.CONFLICT}: element touched within ${storeResult.windowMs}ms" +
                            " by patch ${storeResult.conflictingPatchId}"
                    rejectedPatchIds += patchId
                    patchOwners.remove(patchId)
                    traceSink.emit(
                        AiTraceEntry.Rejected(
                            seqNo = nextSeq(),
                            timestamp = nowIso(),
                            sessionId = sessionId.value,
                            patchId = patchId,
                            reason = conflictReason,
                        ),
                    )
                    val conflictElementId =
                        when (patch) {
                            is ModelPatch.AddElement -> patch.elementId
                            is ModelPatch.RemoveElement -> patch.elementId
                            is ModelPatch.UpdateAttribute -> patch.ownerId
                            is ModelPatch.RenameElement -> patch.elementId
                            is ModelPatch.AddRelationship -> patch.relationshipId
                        }
                    broker?.publish(
                        PatchBrokerEvent.ConflictDetected(
                            sessionId = sessionId.value,
                            elementId = conflictElementId,
                            conflictingPatchId = storeResult.conflictingPatchId,
                        ),
                    )
                    return@withLock PatchApplyOutcome.ApplyFailed(
                        patchId = patchId,
                        reason = conflictReason,
                    )
                }
            }

            val applyResult = context.applyPatch(patch, mutate)
            return@withLock when (applyResult) {
                is PatchApplyResult.Success -> {
                    appliedPatchIds += patchId
                    patchOwners.remove(patchId)
                    traceSink.emit(
                        AiTraceEntry.Applied(
                            seqNo = nextSeq(),
                            timestamp = nowIso(),
                            sessionId = sessionId.value,
                            patchId = patchId,
                            patchKind = patchKind,
                            elementId = applyResult.elementId,
                        ),
                    )
                    store?.updateStatus(patchId, PatchStatus.APPLIED)
                    broker?.publish(
                        PatchBrokerEvent.PatchAppliedBySession(
                            sessionId = sessionId.value,
                            ownerId = ownerId,
                            patchId = patchId,
                            elementId = applyResult.elementId,
                        ),
                    )
                    PatchApplyOutcome.Applied(
                        patchId = patchId,
                        validation = validOutcome,
                        applyResult = applyResult,
                    )
                }
                is PatchApplyResult.Failure -> {
                    store?.updateStatus(patchId, PatchStatus.REJECTED)
                    patchOwners.remove(patchId)
                    PatchApplyOutcome.ApplyFailed(patchId = patchId, reason = applyResult.reason)
                }
            }
        }

    /**
     * Marks a buffered patch as rejected — does NOT mutate the working model.
     * Idempotent: a second call on the same patchId is a no-op.
     * No-op if patchId was already applied (see §11.10).
     */
    public suspend fun rejectOne(
        patchId: String,
        reason: String? = null,
    ): Unit =
        engineMutex.withLock {
            if (patchId in appliedPatchIds || patchId in rejectedPatchIds) return@withLock
            rejectedPatchIds += patchId
            patchOwners.remove(patchId)
            traceSink.emit(
                AiTraceEntry.Rejected(
                    seqNo = nextSeq(),
                    timestamp = nowIso(),
                    sessionId = sessionId.value,
                    patchId = patchId,
                    reason = reason,
                ),
            )
            store?.updateStatus(patchId, PatchStatus.REJECTED)
        }

    /**
     * Restore the pre-session snapshot and emit [AiTraceEntry.SessionAborted]
     * with all currently-pending patch ids.
     *
     * All patches buffered in the context — including any that were previously
     * applied — are reverted (the snapshot replaces the entire working state).
     */
    public suspend fun rejectAll(reason: String? = null): Unit =
        engineMutex.withLock {
            context.resetTo(preSessionSnapshot)
            val pending = pendingBuffer.map { it.patchId }.filter { it !in rejectedPatchIds && it !in appliedPatchIds }
            val allRejected = rejectedPatchIds.toMutableSet().also { it.addAll(pending) }
            rejectedPatchIds.addAll(allRejected)
            patchOwners.clear()

            traceSink.emit(
                AiTraceEntry.SessionAborted(
                    seqNo = nextSeq(),
                    timestamp = nowIso(),
                    sessionId = sessionId.value,
                    rejectedPatchIds = allRejected.toList(),
                    reason = reason,
                ),
            )
        }

    /**
     * Compute the structured diff for the UI preview (V3.0.24 consumer).
     * Does NOT require the engine mutex — reads are safe without it.
     */
    public suspend fun diff(patchId: String): PatchDiff {
        val patch =
            pendingBuffer.firstOrNull { it.patchId == patchId }
                ?: return PatchDiff(
                    patchId = patchId,
                    before = ModelSnippet(emptyList(), "(patch not found)"),
                    after = ModelSnippet(emptyList(), "(patch not found)"),
                    elementChanges = emptyList(),
                )

        val baseModel = context.resolveModel()
        val touchedId = touchedElementId(patch)
        val contextIds = nearbyElementIds(baseModel, touchedId, contextWindow = 2)

        val beforeSnippet =
            ModelSnippet(
                elementIds = contextIds,
                text = buildSnippetText(baseModel, contextIds),
            )

        val mutate = ModelMutationRouter.mutateFor(patch)
        val patchedModel =
            try {
                mutate(baseModel)
            } catch (_: Exception) {
                baseModel
            }

        val afterSnippet =
            ModelSnippet(
                elementIds = contextIds + listOfNotNull(touchedId).filter { it !in contextIds },
                text = buildSnippetText(patchedModel, contextIds + listOfNotNull(touchedId)),
            )

        val changes = buildElementChanges(patch, baseModel, patchedModel)

        return PatchDiff(
            patchId = patchId,
            before = beforeSnippet,
            after = afterSnippet,
            elementChanges = changes,
        )
    }

    /** Read-only access: buffered patches not yet applied or rejected. */
    public suspend fun pendingPatchIds(): List<String> =
        engineMutex.withLock {
            pendingBuffer
                .map { it.patchId }
                .filter { it !in appliedPatchIds && it !in rejectedPatchIds }
        }

    /** Read-only access: patch ids that have been explicitly rejected. */
    public fun rejectedIds(): Set<String> = rejectedPatchIds.toSet()

    /**
     * Closes the underlying [PersistentPatchStore] (if any), releasing the JDBC connection
     * and SQLite WAL/SHM file handles.
     *
     * Callers must wrap engine instances in `use {}` or call `close()` explicitly when the
     * session ends to avoid leaking file descriptors in long-running or multi-session processes.
     */
    override fun close() {
        store?.close()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    @Synchronized
    private fun nextSeq(): Long = seqCounter++

    private fun nowIso(): String = clock.instant().toString()

    private fun patchKindOf(patch: ModelPatch): String =
        when (patch) {
            is ModelPatch.AddElement -> patch.elementKind
            is ModelPatch.RemoveElement -> "remove"
            is ModelPatch.UpdateAttribute -> "update.${patch.field}"
            is ModelPatch.RenameElement -> "rename"
            is ModelPatch.AddRelationship -> patch.relationshipKind
        }

    private fun touchedElementId(patch: ModelPatch): String? =
        when (patch) {
            is ModelPatch.AddElement -> patch.elementId
            is ModelPatch.RemoveElement -> patch.elementId
            is ModelPatch.UpdateAttribute -> patch.ownerId
            is ModelPatch.RenameElement -> patch.elementId
            is ModelPatch.AddRelationship -> patch.relationshipId
        }

    private fun nearbyElementIds(
        model: AnyKumlModel,
        targetId: String?,
        contextWindow: Int,
    ): List<String> {
        if (targetId == null) return emptyList()
        val allIds: List<String> =
            when (model) {
                is AnyKumlModel.Uml ->
                    model.elements.map { it.id } + model.relationships.map { it.id }
                is AnyKumlModel.C4 ->
                    model.model.elements.map { it.id } + model.model.relationships.map { it.id }
                is AnyKumlModel.Sysml2 ->
                    model.model.definitions.map { it.id } + model.model.usages.map { it.id }
            }
        val idx = allIds.indexOf(targetId)
        if (idx < 0) return emptyList()
        val from = (idx - contextWindow).coerceAtLeast(0)
        val to = (idx + contextWindow + 1).coerceAtMost(allIds.size)
        return allIds.subList(from, to)
    }

    private fun buildSnippetText(
        model: AnyKumlModel,
        elementIds: List<String>,
    ): String {
        if (elementIds.isEmpty()) return "(empty)"
        val lines = mutableListOf<String>()
        when (model) {
            is AnyKumlModel.Uml -> {
                val elementById = model.elements.associateBy { it.id }
                val relById = model.relationships.associateBy { it.id }
                for (id in elementIds) {
                    val el = elementById[id]
                    val rel = relById[id]
                    when {
                        el != null -> lines.add("  ${el.javaClass.simpleName}(id=$id, name=${el.name})")
                        rel != null -> lines.add("  ${rel.javaClass.simpleName}(id=$id)")
                    }
                }
            }
            else -> lines.add("  (${model.javaClass.simpleName} — ${elementIds.size} elements)")
        }
        return lines.joinToString("\n")
    }

    private fun buildElementChanges(
        patch: ModelPatch,
        before: AnyKumlModel,
        after: AnyKumlModel,
    ): List<ElementChange> =
        when (patch) {
            is ModelPatch.AddElement ->
                listOf(
                    ElementChange(
                        elementId = patch.elementId,
                        kind = "added",
                        before = null,
                        after = "${patch.elementKind}(${patch.name})",
                    ),
                )
            is ModelPatch.RemoveElement ->
                listOf(
                    ElementChange(
                        elementId = patch.elementId,
                        kind = "removed",
                        before = patch.elementId,
                        after = null,
                    ),
                )
            is ModelPatch.UpdateAttribute ->
                listOf(
                    ElementChange(
                        elementId = patch.ownerId,
                        kind = "modified",
                        before = "${patch.field}=?",
                        after = "${patch.field}=${patch.newValue}",
                    ),
                )
            is ModelPatch.RenameElement ->
                listOf(
                    ElementChange(
                        elementId = patch.elementId,
                        kind = "modified",
                        before = patch.oldName,
                        after = patch.newName,
                    ),
                )
            is ModelPatch.AddRelationship ->
                listOf(
                    ElementChange(
                        elementId = patch.relationshipId,
                        kind = "added",
                        before = null,
                        after = "${patch.relationshipKind}(${patch.sourceId}→${patch.targetId})",
                    ),
                )
        }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

/**
 * Computes a short SHA-256 hex fingerprint of [model] using JSON serialization.
 * Deterministic for the same model content regardless of JVM run order.
 */
private fun modelFingerprint(model: AnyKumlModel): String {
    val json = Json { encodeDefaults = false }
    val bytes =
        try {
            json.encodeToString(AnyKumlModel.serializer(), model).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            model.javaClass.name.toByteArray()
        }
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(bytes)
    return hash.take(16).joinToString("") { "%02x".format(it) }
}
