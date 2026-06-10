package dev.kuml.cli.run

import dev.kuml.runtime.snapshot.MigrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

private val STM_SCRIPT =
    """
    stateDiagram(name = "OrderLifecycle") {
        val init = initialState()
        val draft = state(name = "Draft")
        val confirmed = state(name = "Confirmed")
        val done = finalState(name = "Done")
        transition(source = init, target = draft)
        transition(source = draft, target = confirmed) { trigger = "confirm" }
        transition(source = confirmed, target = done) { trigger = "deliver" }
    }
    """.trimIndent()

private fun makeManager(script: String = STM_SCRIPT): RunSessionManager {
    val manager = RunSessionManager()
    manager.start(
        scriptText = script,
        scriptName = "test.kuml.kts",
        restoreFrom = null,
        migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
    )
    return manager
}

private fun runStdin(
    manager: RunSessionManager,
    input: String,
): Pair<Int, String> {
    val inStream = ByteArrayInputStream(input.toByteArray())
    val outBuffer = ByteArrayOutputStream()
    val out = PrintStream(outBuffer)
    val adapter = StdinAdapter(manager, inStream, out)
    val exitCode = adapter.run()
    return exitCode to outBuffer.toString()
}

class StdinAdapterTest :
    FunSpec({

        // ── 1. Quit command returns 0 ─────────────────────────────────────────

        test("processes quit command and returns 0") {
            val manager = makeManager()
            val (exitCode, _) = runStdin(manager, "quit\n")
            exitCode shouldBe 0
        }

        // ── 2. Event processing by name ───────────────────────────────────────

        test("processes event by name") {
            val manager = makeManager()
            val (exitCode, output) = runStdin(manager, "confirm\nquit\n")
            exitCode shouldBe 0
            output shouldContain "Confirmed"
        }

        // ── 3. Snapshot command outputs JSON ──────────────────────────────────

        test("snapshot command outputs JSON") {
            val manager = makeManager()
            val (exitCode, output) = runStdin(manager, "snapshot\nquit\n")
            exitCode shouldBe 0
            output shouldContain "activeStates"
        }

        // ── 4. Status command outputs active states ───────────────────────────

        test("status command outputs active states") {
            val manager = makeManager()
            val (exitCode, output) = runStdin(manager, "status\nquit\n")
            exitCode shouldBe 0
            output shouldContain "Active states:"
        }
    })
