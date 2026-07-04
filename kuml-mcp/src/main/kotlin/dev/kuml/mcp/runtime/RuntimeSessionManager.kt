package dev.kuml.mcp.runtime

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptSecurityException
import dev.kuml.runtime.Event
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.Snapshot
import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.activity.ActivityInstance
import dev.kuml.runtime.activity.ActivityRuntime
import dev.kuml.runtime.sysml2.Sysml2ActivityAdapter
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * In-memory session manager for the `kuml.run.*` MCP tools.
 *
 * A [RuntimeSession] wraps either a [StateMachineRuntime]+[StateMachineInstance]
 * or an [ActivityRuntime]+[ActivityInstance], accumulates trace entries, and
 * expires after [ttlMs] milliseconds of inactivity.  Each successful call to
 * any session tool resets the TTL.
 *
 * Sessions are keyed by a short UUID prefixed `rs-`.  Cleanup runs every
 * minute in a daemon thread so leaked/idle sessions don't accumulate.
 */
internal class RuntimeSessionManager(
    val ttlMs: Long = 30 * 60 * 1000L,
) {
    // ── session storage ───────────────────────────────────────────────────────

    private val sessions = ConcurrentHashMap<String, RuntimeSession>()

    // Background cleanup: check for expired sessions every 60 s
    private val cleaner =
        Executors
            .newSingleThreadScheduledExecutor { r ->
                Thread(r, "kuml-session-cleaner").also { it.isDaemon = true }
            }.also { executor ->
                executor.scheduleAtFixedRate(::evictExpired, 60L, 60L, TimeUnit.SECONDS)
            }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Start a new session from a script source.
     *
     * @param source File path to a `.kuml.kts` script, or an inline script
     *   string (detected by leading `sysml2Model` or `import`).
     * @param kind `"stm"` or `"act"`.  If null, auto-detected from the first
     *   diagram found in the model.
     * @param elementName Optional diagram name to select from a multi-diagram script.
     */
    internal fun start(
        source: String,
        kind: String?,
        elementName: String?,
    ): SessionResult {
        val (file, tempCreated) = resolveScriptFile(source)

        return try {
            try {
                KumlScriptGuard.validate(file.readText())
            } catch (e: ScriptSecurityException) {
                return SessionResult.Error(e.message ?: "kUML script rejected by security guard.")
            }

            val evalResult = KumlScriptHost.eval(file)

            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                return SessionResult.Error("Script evaluation failed:\n$msg")
            }

            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: return SessionResult.Error("Script evaluation produced no result")

            val extracted =
                try {
                    DiagramExtractor.extractAny(success.value.returnValue, file)
                } catch (_: Throwable) {
                    val diagram = DiagramExtractor.extract(success.value.returnValue, file)
                    ExtractedDiagram.Uml(diagram)
                }

            val sessionId = "rs-${UUID.randomUUID().toString().take(8)}"
            val effectiveKind = resolveKind(extracted, kind, elementName)

            val session =
                when (effectiveKind) {
                    "stm" -> buildStmSession(sessionId, extracted, elementName)
                    "act" -> buildActSession(sessionId, extracted, elementName)
                    else -> return SessionResult.Error("Unknown kind '$effectiveKind'; expected 'stm' or 'act'")
                }

            sessions[sessionId] = session
            SessionResult.Started(
                sessionId = sessionId,
                kind = effectiveKind,
                activeStates = session.activeStates(),
                trace = emptyList(),
            )
        } finally {
            if (tempCreated) file.delete()
        }
    }

    /** Send an event to an STM session; not supported for ACT sessions. */
    internal fun event(
        sessionId: String,
        eventName: String,
        payload: Map<String, Any>,
    ): SessionResult {
        val session = getSession(sessionId) ?: return SessionResult.Error("Session not found or expired: $sessionId")
        session.lastAccessMs = System.currentTimeMillis()

        return when (session) {
            is RuntimeSession.Stm -> {
                val traceBefore = session.instance.trace.size
                val jsonPayload =
                    JsonObject(
                        payload.entries.associate { (k, v) -> k to v.anyToJsonElement() },
                    )
                val ev = Event(name = eventName, payload = jsonPayload)
                val result = session.runtime.step(session.instance, ev)
                session.stepCount++

                val fullTrace = session.instance.trace
                val newEntries = if (traceBefore < fullTrace.size) fullTrace.drop(traceBefore) else emptyList()
                val fired =
                    newEntries
                        .filterIsInstance<TraceEntry.TransitionFired>()
                        .map { "${it.fromVertexId}→${it.toVertexId}" }

                SessionResult.Stepped(
                    fired = fired,
                    activeStates = session.activeStates(),
                    traceDelta = newEntries,
                    stepResult = result.toLabel(),
                )
            }

            is RuntimeSession.Act ->
                SessionResult.Error(
                    "Activity sessions (kind=act) run to completion on start. " +
                        "Use kuml.run.snapshot to inspect the final state.",
                )
        }
    }

    /** Return a snapshot of the current session state. */
    internal fun snapshot(sessionId: String): SessionResult {
        val session = getSession(sessionId) ?: return SessionResult.Error("Session not found or expired: $sessionId")
        session.lastAccessMs = System.currentTimeMillis()

        return when (session) {
            is RuntimeSession.Stm -> {
                val fullTrace = session.instance.trace
                val traceTail = fullTrace.takeLast(20)
                val variablesJson =
                    session.instance.variables.entries
                        .associate { (k, v) -> k to v.anyToJsonElement() }
                SessionResult.Snapshot(
                    activeStates = session.activeStates(),
                    variables = variablesJson,
                    traceTail = traceTail,
                    stepCount = session.stepCount,
                )
            }

            is RuntimeSession.Act ->
                SessionResult.Snapshot(
                    activeStates = session.activeStates(),
                    variables = emptyMap(),
                    traceTail = session.trace.takeLast(20),
                    stepCount = session.actInstance.clock.toInt(),
                )
        }
    }

    /** Patch variables and/or force-jump to a named state in an STM session. */
    internal fun patch(
        sessionId: String,
        variables: Map<String, Any>,
        forceState: String?,
    ): SessionResult {
        val session = getSession(sessionId) ?: return SessionResult.Error("Session not found or expired: $sessionId")
        session.lastAccessMs = System.currentTimeMillis()

        return when (session) {
            is RuntimeSession.Stm -> {
                // Update variables (MutableMap is public)
                variables.forEach { (k, v) -> session.instance.variables[k] = v }

                // Force state jump if requested — rebuild instance via restore with patched snapshot
                if (forceState != null) {
                    val model = session.instance.model
                    val targetVertex =
                        model.vertices.firstOrNull { v ->
                            v.id == forceState ||
                                (v is UmlState && v.name == forceState)
                        } ?: return SessionResult.Error(
                            "forceState '$forceState' not found. " +
                                "Known vertices: ${model.vertices.map { it.id }.joinToString()}",
                        )

                    // Build a patched snapshot that places the machine in forceState
                    val patchedVariables =
                        session.instance.variables.entries.associate { (k, v) ->
                            k to v.anyToJsonElement()
                        }
                    val patchedSnapshot =
                        Snapshot(
                            currentVertexIds = listOf(targetVertex.id),
                            variables = patchedVariables,
                            traceSeqNo =
                                session.instance.trace.size
                                    .toLong(),
                        )
                    val newInstance = session.runtime.restore(model, patchedSnapshot)
                    // Re-apply patched variables (restore uses the snapshot vars)
                    variables.forEach { (k, v) -> newInstance.variables[k] = v }
                    session.replaceInstance(newInstance)
                }

                SessionResult.Patched(
                    ok = true,
                    activeStates = session.activeStates(),
                )
            }

            is RuntimeSession.Act ->
                SessionResult.Error("Patch is not supported for activity sessions (kind=act).")
        }
    }

    /** Stop and remove a session, returning the full trace. */
    internal fun stop(sessionId: String): SessionResult {
        val session = sessions.remove(sessionId) ?: return SessionResult.Error("Session not found: $sessionId")

        return when (session) {
            is RuntimeSession.Stm -> {
                val trace = session.instance.trace
                SessionResult.Stopped(
                    totalSteps = session.stepCount,
                    traceLength = trace.size,
                    trace = trace,
                )
            }

            is RuntimeSession.Act ->
                SessionResult.Stopped(
                    totalSteps = session.actInstance.clock.toInt(),
                    traceLength = session.trace.size,
                    trace = session.trace,
                )
        }
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    private fun getSession(sessionId: String): RuntimeSession? {
        val session = sessions[sessionId] ?: return null
        val now = System.currentTimeMillis()
        if (now - session.lastAccessMs > ttlMs) {
            sessions.remove(sessionId)
            return null
        }
        return session
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) -> now - session.lastAccessMs > ttlMs }
    }

    /**
     * Resolves the script file.  Returns (file, wasTemporary).
     *
     * If [source] is an existing file path, use it directly.
     * Otherwise treat it as inline script content and write to a temp file.
     */
    private fun resolveScriptFile(source: String): Pair<File, Boolean> {
        val asFile = File(source)
        if (asFile.exists() && asFile.isFile) {
            return asFile to false
        }
        val tmp = Files.createTempFile("kuml-mcp-run-", ".kuml.kts").toFile()
        tmp.writeText(source)
        return tmp to true
    }

    private fun resolveKind(
        extracted: ExtractedDiagram,
        hint: String?,
        elementName: String?,
    ): String {
        if (hint != null) return hint.lowercase()
        if (extracted is ExtractedDiagram.Sysml2) {
            val diagrams = extracted.model.diagrams
            val target =
                if (elementName != null) {
                    diagrams.firstOrNull { it.name == elementName }
                } else {
                    diagrams.firstOrNull()
                }
            return when (target) {
                is ActDiagram -> "act"
                else -> "stm"
            }
        }
        return "stm"
    }

    private fun buildStmSession(
        sessionId: String,
        extracted: ExtractedDiagram,
        elementName: String?,
    ): RuntimeSession.Stm {
        val sm =
            when (extracted) {
                is ExtractedDiagram.Uml -> {
                    extracted.diagram.elements.singleOrNull() as? dev.kuml.uml.UmlStateMachine
                        ?: error(
                            "UML script must contain exactly one UmlStateMachine; " +
                                "got: ${extracted.diagram.elements.map { it::class.simpleName }}",
                        )
                }
                is ExtractedDiagram.Sysml2 -> {
                    val diagram =
                        if (elementName != null) {
                            extracted.model.diagrams
                                .filterIsInstance<StmDiagram>()
                                .firstOrNull { it.name == elementName }
                                ?: error("No StmDiagram named '$elementName' found in script")
                        } else {
                            extracted.model.diagrams
                                .filterIsInstance<StmDiagram>()
                                .firstOrNull()
                                ?: error("No StmDiagram found in script")
                        }
                    Sysml2StateMachineAdapter.toUmlStateMachine(extracted.model, diagram)
                }
                is ExtractedDiagram.C4 -> error("C4 diagrams cannot be simulated as STM")
                is ExtractedDiagram.Bpmn -> error("BPMN diagrams cannot be simulated as STM")
                is ExtractedDiagram.Blueprint -> error("Blueprint/Journey-Map diagrams cannot be simulated as STM")
            }

        val runtime = StateMachineRuntime(guards = OclGuardEvaluator())
        val instance = runtime.start(sm)
        return RuntimeSession.Stm(
            id = sessionId,
            runtime = runtime,
            currentInstance = instance,
            lastAccessMs = System.currentTimeMillis(),
        )
    }

    private fun buildActSession(
        sessionId: String,
        extracted: ExtractedDiagram,
        elementName: String?,
    ): RuntimeSession.Act {
        require(extracted is ExtractedDiagram.Sysml2) {
            "Activity sessions require a SysML 2 script with an actDiagram block"
        }
        val diagram =
            if (elementName != null) {
                extracted.model.diagrams
                    .filterIsInstance<ActDiagram>()
                    .firstOrNull { it.name == elementName }
                    ?: error("No ActDiagram named '$elementName' found in script")
            } else {
                extracted.model.diagrams
                    .filterIsInstance<ActDiagram>()
                    .firstOrNull()
                    ?: error("No ActDiagram found in script")
            }

        val actRuntime = Sysml2ActivityAdapter.runtimeFor(extracted.model, diagram)
        val (initialInstance, startTrace) = actRuntime.start()

        val (finalInstance, runTrace) =
            try {
                actRuntime.run(initial = initialInstance, failOnDeadlock = false)
            } catch (_: dev.kuml.runtime.activity.ActivityDeadlockException) {
                initialInstance to startTrace
            }

        val fullTrace = startTrace + runTrace
        return RuntimeSession.Act(
            id = sessionId,
            actRuntime = actRuntime,
            actInstance = finalInstance,
            trace = fullTrace,
            lastAccessMs = System.currentTimeMillis(),
        )
    }

    // ── shutdown ──────────────────────────────────────────────────────────────

    internal fun shutdown() {
        cleaner.shutdown()
    }
}

// ── Session sealed class ──────────────────────────────────────────────────────

internal sealed class RuntimeSession {
    abstract val id: String
    abstract var lastAccessMs: Long
    abstract var stepCount: Int

    abstract fun activeStates(): List<String>

    internal class Stm(
        override val id: String,
        val runtime: StateMachineRuntime,
        currentInstance: StateMachineInstance,
        override var lastAccessMs: Long,
        override var stepCount: Int = 0,
    ) : RuntimeSession() {
        // Var so patch/restore can swap it out
        var instance: StateMachineInstance = currentInstance
            private set

        fun replaceInstance(newInstance: StateMachineInstance) {
            instance = newInstance
        }

        override fun activeStates(): List<String> =
            instance.currentVertices.map { v ->
                if (v is dev.kuml.uml.UmlState) v.name else v.id
            }
    }

    internal class Act(
        override val id: String,
        val actRuntime: ActivityRuntime,
        val actInstance: ActivityInstance,
        val trace: List<TraceEntry>,
        override var lastAccessMs: Long,
        override var stepCount: Int = 0,
    ) : RuntimeSession() {
        override fun activeStates(): List<String> =
            if (actInstance.isTerminated) listOf("(terminated)") else actInstance.tokenCounts.keys.toList()
    }
}

// ── Result sealed class ───────────────────────────────────────────────────────

internal sealed class SessionResult {
    internal data class Started(
        val sessionId: String,
        val kind: String,
        val activeStates: List<String>,
        val trace: List<TraceEntry>,
    ) : SessionResult()

    internal data class Stepped(
        val fired: List<String>,
        val activeStates: List<String>,
        val traceDelta: List<TraceEntry>,
        val stepResult: String,
    ) : SessionResult()

    internal data class Snapshot(
        val activeStates: List<String>,
        val variables: Map<String, JsonElement>,
        val traceTail: List<TraceEntry>,
        val stepCount: Int,
    ) : SessionResult()

    internal data class Patched(
        val ok: Boolean,
        val activeStates: List<String>,
    ) : SessionResult()

    internal data class Stopped(
        val totalSteps: Int,
        val traceLength: Int,
        val trace: List<TraceEntry>,
    ) : SessionResult()

    internal data class Error(
        val message: String,
    ) : SessionResult()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Convert any Kotlin value to a [JsonElement] (shallow). */
internal fun Any?.anyToJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(this.entries.associate { (k, v) -> k.toString() to v.anyToJsonElement() })
        is List<*> -> JsonArray(this.map { it.anyToJsonElement() })
        else -> JsonPrimitive(this.toString())
    }

private fun StepResult.toLabel(): String =
    when (this) {
        is StepResult.Transitioned -> "Transitioned"
        is StepResult.Stayed -> "Stayed(${this.reason})"
        is StepResult.GuardFailed -> "GuardFailed"
        is StepResult.Error -> "Error"
        StepResult.Terminated -> "Terminated"
    }
