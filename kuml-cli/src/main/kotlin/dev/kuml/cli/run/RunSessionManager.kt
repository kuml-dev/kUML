package dev.kuml.cli.run

import dev.kuml.cli.ExitCodes
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.runtime.Event
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.Snapshot
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.activity.ActivityDeadlockException
import dev.kuml.runtime.snapshot.ActivityInstanceSnapshot
import dev.kuml.runtime.snapshot.MigrationException
import dev.kuml.runtime.snapshot.MigrationPolicy
import dev.kuml.runtime.snapshot.StateMachineSnapshot
import dev.kuml.runtime.snapshot.fingerprintActivity
import dev.kuml.runtime.snapshot.readActivityInstanceSnapshot
import dev.kuml.runtime.snapshot.readStateMachineSnapshot
import dev.kuml.runtime.snapshot.writeActivityInstanceSnapshot
import dev.kuml.runtime.snapshot.writeStateMachineSnapshot
import dev.kuml.runtime.sysml2.Sysml2ActivityAdapter
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.runtime.writeTrace
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Path
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

// ── Session result types ─────────────────────────────────────────────────────

internal sealed class SessionResult {
    data class Ok(
        val activeStates: List<String>,
        val traceDelta: List<Any>,
        val message: String? = null,
    ) : SessionResult()

    data class Error(
        val message: String,
        val exitCode: Int = ExitCodes.SCRIPT_ERROR,
    ) : SessionResult()

    data class Terminated(
        val totalSteps: Long,
        val message: String = "Session terminated",
    ) : SessionResult()
}

// ── Session manager ──────────────────────────────────────────────────────────

/**
 * Manages the lifecycle of a single `kuml run` session.
 *
 * Supports both STM (UML / SysML 2 STM) and ACT (SysML 2 ACT) diagrams.
 * Thread-safe enough for the MCP HTTP adapter (one request at a time is
 * processed; all state mutations happen under synchronized blocks).
 */
internal class RunSessionManager {
    @Volatile
    private var session: RunSession? = null

    val currentSession: RunSession? get() = session

    val isTerminated: Boolean
        get() =
            when (val s = session) {
                is RunSession.Stm -> s.instance.isTerminated
                is RunSession.Act -> s.instance.isTerminated
                null -> true
            }

    // ── start ────────────────────────────────────────────────────────────────

    /**
     * Evaluates [scriptText] and starts an STM or ACT session.
     * If [restoreFrom] is non-null, restores from the snapshot file using [migrationPolicy].
     */
    fun start(
        scriptText: String,
        scriptName: String,
        restoreFrom: File?,
        migrationPolicy: MigrationPolicy,
    ): SessionResult {
        // Write script to temp file for KumlScriptHost
        val tempScript =
            File.createTempFile("kuml-run-", ".kuml.kts").also { f ->
                f.writeText(scriptText)
                f.deleteOnExit()
            }

        val evalResult = KumlScriptHost.eval(tempScript)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            return SessionResult.Error(
                message = "Script error:\n" + errors.joinToString("\n") { it.message },
                exitCode = ExitCodes.SCRIPT_ERROR,
            )
        }

        @Suppress("UNCHECKED_CAST")
        val success =
            evalResult as ResultWithDiagnostics.Success<kotlin.script.experimental.api.EvaluationResult>

        val extracted =
            try {
                dev.kuml.core.script.DiagramExtractor
                    .extractAny(success.value.returnValue, tempScript)
            } catch (_: Throwable) {
                val diagram = DiagramExtractor.extract(success.value.returnValue, tempScript)
                ExtractedDiagram.Uml(diagram)
            }

        // ACT path
        if (extracted is ExtractedDiagram.Sysml2) {
            val actDiagram =
                extracted.diagram as? ActDiagram
                    ?: extracted.model.diagrams
                        .filterIsInstance<ActDiagram>()
                        .firstOrNull()
            if (actDiagram != null) {
                return startActSession(extracted, actDiagram, restoreFrom, migrationPolicy)
            }
        }

        // STM / UML path
        val sm = resolveStateMachine(extracted) ?: return SessionResult.Error("No runnable state machine found")
        return startStmSession(sm, restoreFrom, migrationPolicy)
    }

    private fun startStmSession(
        sm: UmlStateMachine,
        restoreFrom: File?,
        policy: MigrationPolicy,
    ): SessionResult {
        val runtime = StateMachineRuntime(guards = OclGuardEvaluator())
        val instance =
            if (restoreFrom != null) {
                val snapshot: StateMachineSnapshot =
                    try {
                        readStateMachineSnapshot(restoreFrom)
                    } catch (e: Exception) {
                        return SessionResult.Error(
                            message = "Failed to read snapshot: ${e.message}",
                            exitCode = ExitCodes.IO_ERROR,
                        )
                    }
                try {
                    runtime.restoreFrom(sm, snapshot, policy)
                } catch (e: MigrationException) {
                    return SessionResult.Error(
                        message = "Migration rejected: ${e.message}",
                        exitCode = ExitCodes.RUN_MIGRATION_REJECTED,
                    )
                }
            } else {
                runtime.start(sm)
            }
        session = RunSession.Stm(runtime = runtime, instance = instance)
        return SessionResult.Ok(
            activeStates = instance.currentVertices.map { it.id },
            traceDelta = emptyList(),
            message = "Session started (STM): ${sm.name}",
        )
    }

    private fun startActSession(
        extracted: ExtractedDiagram.Sysml2,
        actDiagram: ActDiagram,
        restoreFrom: File?,
        policy: MigrationPolicy,
    ): SessionResult {
        val runtime =
            try {
                Sysml2ActivityAdapter.runtimeFor(extracted.model, actDiagram)
            } catch (e: IllegalArgumentException) {
                return SessionResult.Error("SysML 2 ACT adapter error: ${e.message}")
            }

        val spec = runtime.spec

        val actInstance =
            if (restoreFrom != null) {
                val snapshot: ActivityInstanceSnapshot =
                    try {
                        readActivityInstanceSnapshot(restoreFrom)
                    } catch (e: Exception) {
                        return SessionResult.Error(
                            message = "Failed to read activity snapshot: ${e.message}",
                            exitCode = ExitCodes.IO_ERROR,
                        )
                    }
                try {
                    runtime.restoreFrom(snapshot, policy)
                } catch (e: MigrationException) {
                    return SessionResult.Error(
                        message = "Migration rejected: ${e.message}",
                        exitCode = ExitCodes.RUN_MIGRATION_REJECTED,
                    )
                }
            } else {
                runtime.start().first
            }

        session = RunSession.Act(runtime = runtime, spec = spec, instance = actInstance)
        return SessionResult.Ok(
            activeStates = actInstance.tokenCounts.keys.toList(),
            traceDelta = emptyList(),
            message = "Session started (ACT): ${actDiagram.name}",
        )
    }

    // ── event ────────────────────────────────────────────────────────────────

    fun event(
        eventName: String,
        payload: Map<String, Any> = emptyMap(),
    ): SessionResult {
        val s = session ?: return SessionResult.Error("No active session")
        if (isTerminated) return SessionResult.Terminated(totalSteps = stepCount())

        return when (s) {
            is RunSession.Stm -> {
                val jsonPayload =
                    JsonObject(
                        payload.mapValues { (_, v) ->
                            when (v) {
                                is Boolean -> JsonPrimitive(v)
                                is Number -> JsonPrimitive(v)
                                else -> JsonPrimitive(v.toString())
                            }
                        },
                    )
                val ev = Event(name = eventName, payload = jsonPayload)
                val prevTraceSize = s.instance.trace.size
                val result = s.runtime.step(s.instance, ev)
                val traceDelta = s.instance.trace.drop(prevTraceSize)

                if (s.instance.isTerminated) {
                    SessionResult.Terminated(totalSteps = s.runtime.snapshotFull(s.instance).seqCounter)
                } else {
                    val message =
                        when (result) {
                            is StepResult.Transitioned -> "Transitioned: ${result.fromVertexIds} → ${result.toVertexIds}"
                            is StepResult.Stayed -> "Stayed: ${result.reason}"
                            is StepResult.GuardFailed -> "GuardFailed on ${result.transitionId}: ${result.message}"
                            is StepResult.Error -> "Error: ${result.cause.message}"
                            StepResult.Terminated -> "Terminated"
                        }
                    SessionResult.Ok(
                        activeStates = s.instance.currentVertices.map { it.id },
                        traceDelta = traceDelta,
                        message = message,
                    )
                }
            }

            is RunSession.Act -> {
                // ACT doesn't accept external events in the same way; step through
                val eventContext =
                    payload.mapValues { (_, v) -> v }
                val prevInstance = s.instance
                val (newInstance, stepTrace) =
                    try {
                        s.runtime.step(prevInstance, eventContext)
                    } catch (e: ActivityDeadlockException) {
                        return SessionResult.Error("Activity deadlock: ${e.message}")
                    }
                // Update the mutable session reference
                session = s.copy(instance = newInstance)
                if (newInstance.isTerminated) {
                    SessionResult.Terminated(totalSteps = newInstance.clock)
                } else {
                    SessionResult.Ok(
                        activeStates = newInstance.tokenCounts.keys.toList(),
                        traceDelta = stepTrace,
                        message = "Stepped ACT (event: $eventName)",
                    )
                }
            }
        }
    }

    // ── snapshot ─────────────────────────────────────────────────────────────

    fun snapshot(): SessionResult {
        val s = session ?: return SessionResult.Error("No active session")
        return when (s) {
            is RunSession.Stm ->
                SessionResult.Ok(
                    activeStates = s.instance.currentVertices.map { it.id },
                    traceDelta = s.instance.trace,
                    message = "stepCount=${s.runtime.snapshotFull(s.instance).seqCounter}",
                )

            is RunSession.Act ->
                SessionResult.Ok(
                    activeStates =
                        s.instance.tokenCounts.keys
                            .toList(),
                    traceDelta = emptyList(),
                    message = "clock=${s.instance.clock}",
                )
        }
    }

    // ── patch ────────────────────────────────────────────────────────────────

    fun patch(
        variables: Map<String, Any>,
        forceState: String? = null,
    ): SessionResult {
        val s = session ?: return SessionResult.Error("No active session")
        return when (s) {
            is RunSession.Stm -> {
                // Apply variable patches
                variables.forEach { (k, v) -> s.instance.variables[k] = v }

                if (forceState != null) {
                    // Force-state: rebuild instance via restore() with a modified snapshot
                    val currentSnap = s.runtime.snapshot(s.instance)
                    // Build new variables map (merging current + patched)
                    val newVars =
                        currentSnap.variables.toMutableMap().also {
                            variables.forEach { (k, v) ->
                                it[k] =
                                    when (v) {
                                        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                                        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                                        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                                    }
                            }
                        }
                    val newSnap =
                        Snapshot(
                            currentVertexIds = listOf(forceState),
                            variables = newVars,
                            traceSeqNo = currentSnap.traceSeqNo,
                        )
                    val newInstance =
                        try {
                            s.runtime.restore(s.instance.model, newSnap)
                        } catch (e: Exception) {
                            return SessionResult.Error("forceState failed: ${e.message}")
                        }
                    session = s.copy(instance = newInstance)
                    return SessionResult.Ok(
                        activeStates = newInstance.currentVertices.map { it.id },
                        traceDelta = emptyList(),
                        message = "Patched ${variables.size} variable(s), forced state to $forceState",
                    )
                }
                SessionResult.Ok(
                    activeStates = s.instance.currentVertices.map { it.id },
                    traceDelta = emptyList(),
                    message = "Patched ${variables.size} variable(s)",
                )
            }

            is RunSession.Act ->
                SessionResult.Error("patch not supported for ACT sessions")
        }
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    fun stop(): SessionResult {
        val s = session ?: return SessionResult.Terminated(0L)
        val steps = stepCount()
        session = null
        return SessionResult.Terminated(totalSteps = steps, message = "Session stopped after $steps steps")
    }

    // ── saveSnapshot ─────────────────────────────────────────────────────────

    fun saveSnapshot(out: Path) {
        val s = session ?: return
        when (s) {
            is RunSession.Stm -> {
                val snap = s.runtime.snapshotFull(s.instance)
                writeStateMachineSnapshot(snap, out.toFile())
            }

            is RunSession.Act -> {
                val fp =
                    fingerprintActivity(
                        nodeIds = s.spec.nodes.keys,
                        edgeIds =
                            s.spec.edges
                                .map { it.id }
                                .toSet(),
                    )
                val snap =
                    s.runtime.snapshotFull(
                        s.instance,
                        modelId =
                            s.spec.nodes.keys
                                .first(),
                        modelFingerprint = fp,
                    )
                writeActivityInstanceSnapshot(snap, out.toFile())
            }
        }
    }

    // ── writeSessionTrace ────────────────────────────────────────────────────

    fun writeSessionTrace(
        out: Path,
        modelId: String? = null,
    ) {
        val s = session ?: return
        when (s) {
            is RunSession.Stm ->
                writeTrace(s.instance.trace, out.toFile(), modelId = modelId ?: s.instance.model.id)

            is RunSession.Act ->
                writeTrace(emptyList(), out.toFile(), modelId = modelId)
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun stepCount(): Long =
        when (val s = session) {
            is RunSession.Stm -> s.runtime.snapshotFull(s.instance).seqCounter
            is RunSession.Act -> s.instance.clock
            null -> 0L
        }

    private fun resolveStateMachine(extracted: ExtractedDiagram): UmlStateMachine? =
        when (extracted) {
            is ExtractedDiagram.Uml ->
                extracted.diagram.elements.singleOrNull() as? UmlStateMachine

            is ExtractedDiagram.Sysml2 -> {
                val stm =
                    extracted.diagram as? StmDiagram
                        ?: extracted.model.diagrams
                            .filterIsInstance<StmDiagram>()
                            .firstOrNull()
                        ?: return null
                try {
                    Sysml2StateMachineAdapter.toUmlStateMachine(extracted.model, stm)
                } catch (_: Exception) {
                    null
                }
            }

            is ExtractedDiagram.C4 -> null
        }
}
