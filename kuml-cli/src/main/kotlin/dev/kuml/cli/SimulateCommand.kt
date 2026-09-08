package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import dev.kuml.runtime.TraceDiff
import dev.kuml.runtime.activity.ActivityDeadlockException
import dev.kuml.runtime.activity.ActivityGuardEvaluator
import dev.kuml.runtime.loadEvents
import dev.kuml.runtime.loadTrace
import dev.kuml.runtime.sandbox.EffectExecutor
import dev.kuml.runtime.sandbox.SandboxEffectInvoker
import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator
import dev.kuml.runtime.sysml2.Sysml2ActivityAdapter
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.runtime.tokenflow.TokenFlowContext
import dev.kuml.runtime.tokenflow.TokenFlowEngine
import dev.kuml.runtime.tokenflow.TokenFlowLimits
import dev.kuml.runtime.tokenflow.TokenFlowOptions
import dev.kuml.runtime.tokenflow.TokenFlowOutcome
import dev.kuml.runtime.tokenflow.TokenFlowSeverity
import dev.kuml.runtime.tokenflow.TokenFlowSpec
import dev.kuml.runtime.tokenflow.adapter.BpmnTokenFlowAdapter
import dev.kuml.runtime.tokenflow.adapter.UmlActivityTokenFlowAdapter
import dev.kuml.runtime.writeTrace
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * The `simulate` subcommand — V1.1.5 (UML) + V2.0.17 (SysML 2 STM) + V2.0.18 (SysML 2 ACT).
 *
 * Two modes:
 *  - File mode: `kuml simulate <script> <events.json> --out <trace.json>`
 *  - Interactive mode: `kuml simulate <script> --interactive` (STM only)
 *
 * Optional `--expected <trace.json>` compares the produced trace to a goldfile.
 * `--epoch-clock` makes timestamps deterministic for reproducible Goldfile-Tests.
 * `--max-steps <N>` guards against infinite-loop ACT models (default 1000).
 *
 * ## Script flavours
 *
 *  * **UML scripts** — top-level expression is a `umlModel { … stateMachine { … } }`
 *    DSL that produces a [dev.kuml.uml.UmlStateMachine]. Loaded via
 *    [DiagramExtractor.extract] and passed directly to [StateMachineRuntime].
 *  * **SysML 2 STM scripts** — top-level expression is
 *    `sysml2Model("…") { … stmDiagram("…") { … } }`. V2.0.17 translates the
 *    selected [dev.kuml.sysml2.StateDefinition]s + [dev.kuml.sysml2.TransitionUsage]s
 *    to a [dev.kuml.uml.UmlStateMachine] via
 *    [Sysml2StateMachineAdapter.toUmlStateMachine] and runs them through the
 *    same [StateMachineRuntime].
 *  * **SysML 2 ACT scripts** — top-level expression is
 *    `sysml2Model("…") { … actDiagram("…") { … } }`. V2.0.18 builds an
 *    [dev.kuml.runtime.activity.ActivityRuntime] via [Sysml2ActivityAdapter]
 *    and runs the token-flow interpreter to completion. The events file
 *    provides the eventContext for guard evaluation; the first event's payload
 *    fields are used as the context map.
 *
 * If a SysML 2 script declares multiple diagrams, priority is:
 *  1. First [ActDiagram] (V2.0.18 — activity takes precedence over STM when both present)
 *  2. First [StmDiagram] (V2.0.17)
 *
 * The CLI's input / output contract (events file in, trace file out) is
 * identical across all flavours.
 */
internal class SimulateCommand : CliktCommand(name = "simulate") {
    private val script by argument(help = "Path to *.kuml.kts state-machine script")
        .file(mustExist = true, canBeDir = false)

    private val events by argument(help = "Path to events JSON (omit when using --interactive)")
        .file(mustExist = true, canBeDir = false)
        .optional()

    private val outputTrace by option("--out", help = "Path to write the generated trace JSON")
        .path()

    private val expectedTrace by option("--expected", help = "Path to expected trace JSON for diff")
        .file(mustExist = true, canBeDir = false)

    private val interactive by option("--interactive", help = "Run an interactive REPL").flag()

    private val epochClock by option(
        "--epoch-clock",
        help = "Use deterministic epoch clock for reproducible tests",
    ).flag()

    // Security review (feature/tokenflow-execution-engine, finding "unvalidated CLI limits"):
    // negative or zero values previously passed through unchecked, e.g. `--time-budget-ms
    // 10000000000000` overflowing the `* 1_000_000L` nanos conversion in TokenFlowEngine.run
    // into a negative deadline that aborted at step 0 with a nonsensical message. `restrictTo`
    // rejects non-positive values at CLI-parse time instead, before they ever reach the engine.
    private val maxSteps by option(
        "--max-steps",
        help = "Maximum steps for ACT activity execution (default 1000 — guard against infinite loops)",
    ).int().restrictTo(min = 1).default(1000)

    private val sandbox by option(
        "--sandbox",
        help = "Enable sandbox execution (EffectExecutor + TimeLimitedGuardEvaluator).",
    ).flag()

    private val guardTimeoutMs by option(
        "--guard-timeout-ms",
        help = "Guard evaluation timeout in milliseconds when --sandbox is active (default ${SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS}).",
    ).long().restrictTo(min = 1L).default(SandboxPolicy.DEFAULT_GUARD_TIMEOUT_MS)

    // ── TokenFlowEngine (ADR-0015) — BPMN process / UML Activity diagrams ────

    private val maxTokens by option(
        "--max-tokens",
        help =
            "Maximum concurrent tokens for BPMN/UML-Activity execution (protects against token " +
                "explosion in a cyclic Fork; default ${TokenFlowLimits.DEFAULT_MAX_TOKENS}).",
    ).int().restrictTo(min = 1).default(TokenFlowLimits.DEFAULT_MAX_TOKENS)

    private val timeBudgetMs by option(
        "--time-budget-ms",
        help = "Wall-clock budget in ms for BPMN/UML-Activity execution (default ${TokenFlowLimits.DEFAULT_WALL_CLOCK_MS}).",
    ).long().restrictTo(min = 1L).default(TokenFlowLimits.DEFAULT_WALL_CLOCK_MS)

    // Security review finding "INCLUSIVE-Diverge verwirft den Token still": strictGuardCoverage
    // existed on TokenFlowOptions but SimulateCommand never constructed one, so it was
    // unreachable from the CLI. Exposing it here lets a caller opt into fail-loud behaviour for
    // an EXCLUSIVE/INCLUSIVE split with no matching guard and no default edge, instead of the
    // token silently vanishing and the run reporting a false `Terminated`/exit-0 success.
    private val strictGuardCoverage by option(
        "--strict-guard-coverage",
        help =
            "Treat an EXCLUSIVE/INCLUSIVE diverge with no matching guard and no default edge as a " +
                "hard execution error instead of silently dropping the token (BPMN/UML-Activity only).",
    ).flag()

    private val bpmnProcessId by option(
        "--process",
        help = "ID of the BPMN process to execute (required when the model declares more than one process).",
    )

    override fun help(context: Context): String =
        "Execute a kUML or SysML 2 state machine / activity against an event sequence (file or REPL)."

    override fun run() {
        // Evaluate script and dispatch to the appropriate runtime
        val scriptResult = evalScript(script)
        val extracted = extractDiagram(result = scriptResult, file = script)

        // Check if this is an ACT diagram — route to activity runtime
        if (extracted is ExtractedDiagram.Sysml2) {
            val actDiagram =
                extracted.diagram as? ActDiagram
                    ?: extracted.model.diagrams
                        .filterIsInstance<ActDiagram>()
                        .firstOrNull()
            if (actDiagram != null) {
                runActivity(extracted = extracted, diagram = actDiagram)
                return
            }
        }

        // ADR-0015 — BPMN process diagrams route to the new TokenFlowEngine.
        if (extracted is ExtractedDiagram.Bpmn) {
            val processDiagram = resolveProcessDiagram(extracted)
            if (processDiagram != null) {
                val spec = BpmnTokenFlowAdapter.toSpec(model = extracted.model, diagram = processDiagram)
                runTokenFlow(spec = spec, modelId = processDiagram.name)
                return
            }
            System.err.println(
                "BPMN diagram '${extracted.diagram.name}' is not a process diagram (Collaboration/Choreography/" +
                    "Conversation diagrams have no single executable token flow) and cannot be simulated.",
            )
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }

        // ADR-0015 — UML Activity diagrams route to the new TokenFlowEngine.
        if (extracted is ExtractedDiagram.Uml && extracted.diagram.type == DiagramType.ACTIVITY) {
            val spec = UmlActivityTokenFlowAdapter.toSpec(extracted.diagram)
            runTokenFlow(spec = spec, modelId = extracted.diagram.name)
            return
        }

        // STM / UML path (existing)
        val sm = resolveStateMachine(extracted = extracted, file = script)
        val clock: () -> Instant =
            if (epochClock) {
                val counter =
                    java.util.concurrent.atomic
                        .AtomicLong(0L)
                val fn: () -> Instant = { Instant.ofEpochMilli(counter.getAndIncrement()) }
                fn
            } else {
                Instant::now
            }
        val sandboxPolicy = SandboxPolicy(guardTimeoutMs = guardTimeoutMs)
        val guardsEvaluator =
            if (sandbox) {
                TimeLimitedGuardEvaluator(delegate = OclGuardEvaluator(), policy = sandboxPolicy)
            } else {
                OclGuardEvaluator()
            }
        val effectInvoker =
            if (sandbox) {
                SandboxEffectInvoker(EffectExecutor(sandboxPolicy))
            } else {
                dev.kuml.runtime.EffectInvoker.NoOp
            }
        val runtime = StateMachineRuntime(guards = guardsEvaluator, clock = clock, effects = effectInvoker)
        val instance = runtime.start(sm)

        if (interactive) {
            runInteractive(runtime = runtime, instance = instance)
        } else {
            val eventFile =
                events ?: run {
                    System.err.println("EVENTS argument required when --interactive is not set.")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            val evs =
                try {
                    loadEvents(eventFile)
                } catch (e: Exception) {
                    System.err.println("Failed to load events: ${e.message}")
                    throw ProgramResult(ExitCodes.IO_ERROR)
                }
            for (ev in evs) {
                if (instance.isTerminated) break
                runtime.step(instance = instance, event = ev)
            }
            outputTrace?.let {
                try {
                    writeTrace(trace = instance.trace, file = it.toFile(), modelId = sm.id)
                    echo("Wrote ${instance.trace.size} trace entries to $it")
                } catch (e: IOException) {
                    System.err.println("I/O error: ${e.message}")
                    throw ProgramResult(ExitCodes.IO_ERROR)
                }
            }
            expectedTrace?.let { exp ->
                val expected = loadTrace(exp).entries
                val report = TraceDiff.compare(actual = instance.trace, expected = expected)
                if (!report.isMatch) {
                    System.err.println(report.toHumanReadable())
                    throw ProgramResult(ExitCodes.TRACE_DIFF)
                } else {
                    echo("Trace matches expected (${report.matched} entries).")
                }
            }
        }
    }

    // ── ACT execution path ────────────────────────────────────────────────────

    private fun runActivity(
        extracted: ExtractedDiagram.Sysml2,
        diagram: ActDiagram,
    ) {
        val eventFile =
            events ?: run {
                System.err.println("EVENTS argument required to run an ACT activity.")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val evs =
            try {
                loadEvents(eventFile)
            } catch (e: Exception) {
                System.err.println("Failed to load events: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }

        // Build event context from the first event's payload (flat map)
        val eventContext: Map<String, Any> =
            evs.firstOrNull()?.let { firstEvent ->
                firstEvent.payload.mapValues { (_, v) ->
                    when {
                        v is kotlinx.serialization.json.JsonPrimitive && v.isString -> v.content
                        v is kotlinx.serialization.json.JsonPrimitive ->
                            v.content.toBooleanStrictOrNull()
                                ?: v.content.toLongOrNull()
                                ?: v.content.toDoubleOrNull()
                                ?: v.content
                        else -> v.toString()
                    }
                }
            } ?: emptyMap()

        // ADR-0015 / security fix B2: `--sandbox` / `--guard-timeout-ms` previously had
        // no effect on the ACT path at all — Sysml2ActivityAdapter.runtimeFor() always
        // built an unsandboxed ActivityRuntime regardless of these flags. Wire the same
        // sandbox policy the STM path already honours.
        val actGuardEvaluator =
            if (sandbox) {
                TimeLimitedGuardEvaluator(
                    delegate = ActivityGuardEvaluator(),
                    policy = SandboxPolicy(guardTimeoutMs = guardTimeoutMs),
                )
            } else {
                ActivityGuardEvaluator()
            }
        val runtime =
            try {
                Sysml2ActivityAdapter.runtimeFor(model = extracted.model, diagram = diagram, guardEvaluator = actGuardEvaluator)
            } catch (ex: IllegalArgumentException) {
                System.err.println("SysML 2 ACT adapter error: ${ex.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val (initialInstance, startTrace) = runtime.start(eventContext)

        val (finalInstance, runTrace) =
            try {
                runtime.run(
                    initial = initialInstance,
                    eventContext = eventContext,
                    maxSteps = maxSteps,
                    failOnDeadlock = true,
                )
            } catch (ex: ActivityDeadlockException) {
                System.err.println("Activity error: ${ex.message}")
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }

        val allTrace = startTrace + runTrace
        val steps = finalInstance.clock

        echo("Activity terminated after $steps steps, ${allTrace.size} trace entries")

        outputTrace?.let {
            try {
                writeTrace(trace = allTrace, file = it.toFile(), modelId = diagram.name)
                echo("Wrote ${allTrace.size} trace entries to $it")
            } catch (e: IOException) {
                System.err.println("I/O error: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }
        }

        expectedTrace?.let { exp ->
            val expected = loadTrace(exp).entries
            val report = TraceDiff.compare(actual = allTrace, expected = expected)
            if (!report.isMatch) {
                System.err.println(report.toHumanReadable())
                throw ProgramResult(ExitCodes.TRACE_DIFF)
            } else {
                echo("Trace matches expected (${report.matched} entries).")
            }
        }
    }

    // ── TokenFlowEngine execution path (ADR-0015 — BPMN process / UML Activity) ─

    /**
     * Picks the [ProcessDiagram] to execute out of a BPMN model's diagrams.
     * If `--process` is given, it selects the process by id directly
     * (independent of which diagram(s) the script declared); otherwise the
     * single [ProcessDiagram] declared by the script is used, or — if the
     * script declared none but the model has exactly one process — a
     * synthetic diagram over the whole process.
     */
    private fun resolveProcessDiagram(extracted: ExtractedDiagram.Bpmn): ProcessDiagram? {
        bpmnProcessId?.let { pid ->
            val process =
                extracted.model.processes.firstOrNull { it.id == pid } ?: run {
                    val known = extracted.model.processes.joinToString(", ") { it.id }
                    System.err.println("No BPMN process with id '$pid'. Known process ids: $known")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            return ProcessDiagram(name = process.name ?: process.id, processId = process.id)
        }
        // Warn up front whenever more than one process-scoped diagram is declared and
        // --process wasn't given — regardless of which of the checks below ends up
        // actually selecting one. Previously this warning only fired in the narrower
        // case where `extracted.diagram` itself wasn't already a ProcessDiagram, so
        // the overwhelmingly common case (the script's own diagram IS a ProcessDiagram,
        // and a second one is also declared) silently picked the first one with no
        // indication a choice was even made.
        val declaredProcessDiagrams = extracted.model.diagrams.filterIsInstance<ProcessDiagram>()
        if (declaredProcessDiagrams.size > 1) {
            val implicitlySelected = (extracted.diagram as? ProcessDiagram) ?: declaredProcessDiagrams.first()
            System.err.println(
                "Warning: BPMN model '${extracted.model.name}' declares ${declaredProcessDiagrams.size} process " +
                    "diagrams (${declaredProcessDiagrams.joinToString(", ") { it.processId }}); simulating " +
                    "'${implicitlySelected.processId}'. Pass --process <id> to select a different one.",
            )
        }
        (extracted.diagram as? ProcessDiagram)?.let { return it }
        declaredProcessDiagrams.firstOrNull()?.let { return it }
        if (extracted.model.processes.size == 1) {
            val process = extracted.model.processes.single()
            return ProcessDiagram(name = process.name ?: process.id, processId = process.id)
        }
        if (extracted.model.processes.size > 1) {
            val known = extracted.model.processes.joinToString(", ") { it.id }
            System.err.println(
                "BPMN model '${extracted.model.name}' declares ${extracted.model.processes.size} processes " +
                    "and no process-scoped diagram — pass --process <id> to select one. Known process ids: $known",
            )
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        return null
    }

    /**
     * Runs [spec] to completion (or interactively) via [dev.kuml.runtime.tokenflow.TokenFlowEngine].
     * Guard evaluation is unconditionally sandboxed (ADR-0015 doctrine — see
     * `TokenFlowEngine.sandboxed`); `--guard-timeout-ms` still applies here,
     * `--sandbox` does not (that flag remains STM-only, matching its existing meaning).
     */
    private fun runTokenFlow(
        spec: TokenFlowSpec,
        modelId: String,
    ) {
        val issues = spec.validate()
        val errors = issues.filter { it.severity == TokenFlowSeverity.ERROR }
        for (issue in issues.filter { it.severity == TokenFlowSeverity.WARNING }) {
            System.err.println("Warning [${issue.code}]: ${issue.message}")
        }
        if (errors.isNotEmpty()) {
            for (issue in errors) System.err.println("Error [${issue.code}]: ${issue.message}")
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }

        val eventContext: Map<String, Any> =
            events?.let { eventFile ->
                val evs =
                    try {
                        loadEvents(eventFile)
                    } catch (e: Exception) {
                        System.err.println("Failed to load events: ${e.message}")
                        throw ProgramResult(ExitCodes.IO_ERROR)
                    }
                evs.firstOrNull()?.let { firstEvent ->
                    firstEvent.payload.mapValues { (_, v) ->
                        when {
                            v is kotlinx.serialization.json.JsonPrimitive && v.isString -> v.content
                            v is kotlinx.serialization.json.JsonPrimitive ->
                                v.content.toBooleanStrictOrNull()
                                    ?: v.content.toLongOrNull()
                                    ?: v.content.toDoubleOrNull()
                                    ?: v.content
                            else -> v.toString()
                        }
                    }
                } ?: emptyMap()
            } ?: emptyMap()
        val context = TokenFlowContext(eventContext)

        // Security review (feature/tokenflow-execution-engine, finding "Guard-Timeout und
        // Guard-Exception sind von 'Guard ist false' nicht unterscheidbar"): evaluateEdgeGuard
        // collapses GuardResult.Failed (sandbox timeout OR a genuine exception) to the same
        // boolean as a guard that legitimately evaluated to false — collected here via the
        // engine's guardResultListener side channel so the CLI can still surface it, without
        // adding a new TraceEntry variant that would risk `TraceFlavourDetector` reclassifying an
        // otherwise pure BPMN/ACT trace as MIXED (see the listener's KDoc on TokenFlowEngine).
        val guardFailures = mutableListOf<GuardResult.Failed>()

        TokenFlowEngine
            .sandboxed(
                spec = spec,
                policy = SandboxPolicy(guardTimeoutMs = guardTimeoutMs),
                limits = TokenFlowLimits(maxSteps = maxSteps, maxTokens = maxTokens, wallClockBudgetMs = timeBudgetMs),
                options = TokenFlowOptions(strictGuardCoverage = strictGuardCoverage),
                guardResultListener = { edge, result ->
                    if (result is GuardResult.Failed) {
                        System.err.println("Warning [GUARD_EVALUATION_FAILED]: edge '${edge.id}': ${result.message}")
                        guardFailures += result
                    }
                },
            ).use { sandboxed ->
                if (interactive) {
                    runTokenFlowInteractive(engine = sandboxed.engine, spec = spec, modelId = modelId)
                    return
                }

                val start = sandboxed.engine.start(context)
                (start.outcome as? TokenFlowOutcome.Failed)?.let { failed ->
                    System.err.println("Execution failed: ${failed.message}")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
                val result = sandboxed.engine.run(initial = start.instance, context = context)
                val allTrace = start.trace + result.trace

                echo("TokenFlow ${describeOutcome(result.outcome)} after ${result.instance.clock} steps, ${allTrace.size} trace entries")

                outputTrace?.let {
                    try {
                        writeTrace(trace = allTrace, file = it.toFile(), modelId = modelId)
                        echo("Wrote ${allTrace.size} trace entries to $it")
                    } catch (e: IOException) {
                        System.err.println("I/O error: ${e.message}")
                        throw ProgramResult(ExitCodes.IO_ERROR)
                    }
                }

                expectedTrace?.let { exp ->
                    val expected = loadTrace(exp).entries
                    val report = TraceDiff.compare(actual = allTrace, expected = expected)
                    if (!report.isMatch) {
                        System.err.println(report.toHumanReadable())
                        throw ProgramResult(ExitCodes.TRACE_DIFF)
                    } else {
                        echo("Trace matches expected (${report.matched} entries).")
                    }
                }

                when (val outcome = result.outcome) {
                    is TokenFlowOutcome.LimitExceeded -> {
                        System.err.println("Limit exceeded [${outcome.limit}]: ${outcome.detail}")
                        throw ProgramResult(ExitCodes.TOKEN_FLOW_LIMIT_EXCEEDED)
                    }
                    is TokenFlowOutcome.Blocked -> {
                        System.err.println("Deadlock: ${outcome.reason}")
                        throw ProgramResult(ExitCodes.TOKEN_FLOW_DEADLOCK)
                    }
                    is TokenFlowOutcome.Failed -> {
                        System.err.println("Execution failed: ${outcome.message}")
                        throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                    }
                    else -> Unit
                }

                // A LimitExceeded/Blocked/Failed outcome above already threw with a more specific
                // exit code; only escalate to SANDBOX_TIMEOUT for an otherwise-successful run so a
                // caller can tell "converged, but at least one guard was cancelled by the sandbox
                // timeout — the result may not reflect the model's real guard logic" apart from a
                // clean run and from every other failure mode.
                if (guardFailures.any { it.message.startsWith(TimeLimitedGuardEvaluator.TIMEOUT_MESSAGE_PREFIX) }) {
                    throw ProgramResult(ExitCodes.SANDBOX_TIMEOUT)
                }
            }
    }

    private fun describeOutcome(outcome: TokenFlowOutcome): String =
        when (outcome) {
            TokenFlowOutcome.Terminated -> "terminated"
            TokenFlowOutcome.Advanced -> "advanced"
            TokenFlowOutcome.Idle -> "idle"
            is TokenFlowOutcome.Blocked -> "deadlocked"
            is TokenFlowOutcome.LimitExceeded -> "hit a limit (${outcome.limit})"
            is TokenFlowOutcome.Failed -> "failed"
        }

    private fun runTokenFlowInteractive(
        engine: TokenFlowEngine,
        spec: TokenFlowSpec,
        modelId: String,
    ) {
        echo("Loaded token-flow model: '${spec.name}'")
        val initial = engine.start()
        var instance = initial.instance
        var trace = initial.trace
        val variables = mutableMapOf<String, Any?>()

        while (true) {
            echo("  Tokens: ${instance.tokenCounts}")
            val line =
                try {
                    readlnOrNull()?.trim() ?: break
                } catch (_: Exception) {
                    break
                }
            if (line.isEmpty()) continue
            when {
                line == "quit" || line == "exit" -> break
                line == "tokens" -> echo("Tokens: ${instance.tokenCounts}")
                line == "enabled" -> echo("Enabled: ${engine.enabledNodes(instance)}")
                line == "step" -> {
                    val stepResult = engine.step(instance = instance, context = TokenFlowContext(variables))
                    instance = stepResult.instance
                    trace = trace + stepResult.trace
                    echo("─ ${describeOutcome(stepResult.outcome)} (+${stepResult.trace.size} trace entries)")
                }
                line == "run" -> {
                    val runResult = engine.run(initial = instance, context = TokenFlowContext(variables))
                    instance = runResult.instance
                    trace = trace + runResult.trace
                    echo("─ ${describeOutcome(runResult.outcome)}")
                }
                line.startsWith("fire ") -> {
                    val nodeId = line.removePrefix("fire ").trim()
                    val stepResult = engine.fireNode(instance = instance, nodeId = nodeId, context = TokenFlowContext(variables))
                    instance = stepResult.instance
                    trace = trace + stepResult.trace
                    echo("─ ${describeOutcome(stepResult.outcome)} (+${stepResult.trace.size} trace entries)")
                }
                line.startsWith("set ") -> {
                    val assignment = line.removePrefix("set ").trim()
                    val eq = assignment.indexOf('=')
                    if (eq > 0) {
                        val key = assignment.substring(0, eq).trim()
                        val value = assignment.substring(eq + 1).trim()
                        variables[key] = value.toBooleanStrictOrNull() ?: value.toLongOrNull() ?: value
                        echo("Set $key = ${variables[key]}")
                    }
                }
                else -> echo("Unknown command: $line")
            }
            if (instance.isTerminated) {
                echo("Token flow terminated.")
                break
            }
        }
        outputTrace?.let {
            try {
                writeTrace(trace = trace, file = it.toFile(), modelId = modelId)
                echo("Wrote ${trace.size} trace entries to $it")
            } catch (e: IOException) {
                System.err.println("I/O error: ${e.message}")
                throw ProgramResult(ExitCodes.IO_ERROR)
            }
        }
    }

    // ── script evaluation helpers ─────────────────────────────────────────────

    private fun evalScript(file: java.io.File): ResultWithDiagnostics.Success<EvaluationResult> {
        val result = KumlScriptHost.eval(file = file)
        val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || result is ResultWithDiagnostics.Failure) {
            System.err.println("Script error:\n" + errors.joinToString("\n") { it.message })
            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
        }
        @Suppress("UNCHECKED_CAST")
        return result as ResultWithDiagnostics.Success<EvaluationResult>
    }

    private fun extractDiagram(
        result: ResultWithDiagnostics.Success<EvaluationResult>,
        file: java.io.File,
    ): ExtractedDiagram =
        try {
            dev.kuml.core.script.DiagramExtractor
                .extractAny(returnValue = result.value.returnValue, input = file)
        } catch (_: Throwable) {
            // Legacy UML path: older `stateMachine { … }` scripts that don't wrap in sysml2Model
            val diagram = DiagramExtractor.extract(returnValue = result.value.returnValue, input = file)
            ExtractedDiagram.Uml(diagram)
        }

    /**
     * Resolve a [UmlStateMachine] from an [ExtractedDiagram]. Used for STM/UML paths only.
     * ACT paths are handled separately by [runActivity].
     */
    private fun resolveStateMachine(
        extracted: ExtractedDiagram,
        file: java.io.File,
    ): UmlStateMachine =
        when (extracted) {
            is ExtractedDiagram.Uml -> {
                val diagram = extracted.diagram
                diagram.elements.singleOrNull() as? UmlStateMachine ?: run {
                    System.err.println(
                        "Script must produce exactly one UmlStateMachine in its diagram. " +
                            "Got: ${diagram.elements.map { it::class.simpleName }}",
                    )
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }
            is ExtractedDiagram.Sysml2 -> {
                // ACT diagrams were already handled in run() before reaching here.
                // Here we only handle STM diagrams.
                val stm =
                    extracted.diagram as? StmDiagram
                        ?: extracted.model.diagrams
                            .filterIsInstance<StmDiagram>()
                            .firstOrNull()
                        ?: run {
                            System.err.println(
                                "SysML 2 script '${file.name}' declares no StmDiagram or ActDiagram. " +
                                    "`kuml simulate` requires a `stmDiagram(\"…\") { … }` or " +
                                    "`actDiagram(\"…\") { … }` block to identify which diagram to simulate.",
                            )
                            throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                        }
                try {
                    Sysml2StateMachineAdapter.toUmlStateMachine(model = extracted.model, diagram = stm)
                } catch (ex: IllegalStateException) {
                    System.err.println("SysML 2 STM adapter error: ${ex.message}")
                    throw ProgramResult(ExitCodes.SCRIPT_ERROR)
                }
            }
            is ExtractedDiagram.C4 -> {
                System.err.println(
                    "C4 diagrams have no executable behaviour and cannot be simulated. " +
                        "Use a UML or SysML 2 STM/ACT script instead.",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            is ExtractedDiagram.Bpmn -> {
                System.err.println(
                    "BPMN diagrams are not supported by `kuml simulate`. " +
                        "Use a UML or SysML 2 STM/ACT script instead.",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            is ExtractedDiagram.Blueprint -> {
                System.err.println(
                    "Blueprint/Journey-Map diagrams are not supported by `kuml simulate`. " +
                        "Use a UML or SysML 2 STM/ACT script instead.",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
            is ExtractedDiagram.Erm -> {
                System.err.println(
                    "ERM diagrams have no executable behaviour and cannot be simulated. " +
                        "Use a UML or SysML 2 STM/ACT script instead.",
                )
                throw ProgramResult(ExitCodes.SCRIPT_ERROR)
            }
        }

    private fun runInteractive(
        runtime: StateMachineRuntime,
        instance: StateMachineInstance,
    ) {
        echo("Loaded state machine: '${instance.model.name}'")
        echo("Currently in: ${instance.currentVertices.map { it.id }}")
        while (true) {
            val line =
                try {
                    readlnOrNull()?.trim() ?: break
                } catch (_: Exception) {
                    break
                }
            if (line.isEmpty()) continue
            if (line == "quit" || line == "exit") break
            val (name, payload) = parseInteractive(line)
            val result = runtime.step(instance = instance, event = Event(name = name, payload = payload))
            when (result) {
                is StepResult.Transitioned ->
                    echo("─ Transitioned: ${result.fromVertexIds} → ${result.toVertexIds}")
                is StepResult.Stayed -> echo("─ Stayed: ${result.reason}")
                is StepResult.GuardFailed -> echo("─ GuardFailed on ${result.transitionId}: ${result.message}")
                is StepResult.Error -> echo("─ Error: ${result.cause.message}")
                StepResult.Terminated -> echo("─ Terminated.")
            }
            echo("  Currently in: ${instance.currentVertices.map { it.id }}")
            if (instance.isTerminated) {
                echo("State machine terminated.")
                break
            }
        }
        outputTrace?.let {
            writeTrace(trace = instance.trace, file = it.toFile(), modelId = instance.model.id)
            echo("Wrote ${instance.trace.size} trace entries to $it")
        }
    }

    private fun parseInteractive(line: String): Pair<String, JsonObject> {
        val ws = line.indexOf(' ')
        if (ws < 0) return line to JsonObject(emptyMap())
        val name = line.substring(0, ws).trim()
        val rest = line.substring(ws).trim()
        val payload =
            try {
                KumlRuntimeJson.parseToJsonElement(rest).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
        return name to payload
    }
}
