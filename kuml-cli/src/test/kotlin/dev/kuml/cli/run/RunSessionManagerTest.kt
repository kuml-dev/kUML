package dev.kuml.cli.run

import dev.kuml.cli.ExitCodes
import dev.kuml.runtime.snapshot.MigrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

// Shared script text constants

private val UML_STM_SCRIPT =
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

private val SYSML2_STM_SCRIPT =
    """
    @file:Suppress("unused")
    import dev.kuml.sysml2.dsl.sysml2Model
    sysml2Model("TrafficLight") {
        val init = stateDef("Initial", isInitial = true)
        val red = stateDef("Red")
        val green = stateDef("Green")
        val off = stateDef("Off", isFinal = true)
        transition("init", init, red)
        transition("redToGreen", red, green, trigger = "timer60s")
        transition("greenToOff", green, off, trigger = "powerOff")
        stmDiagram("TrafficLight") {
            include(init)
            include(red)
            include(green)
            include(off)
        }
    }
    """.trimIndent()

private val SYSML2_ACT_SCRIPT =
    """
    @file:Suppress("unused")
    import dev.kuml.sysml2.dsl.sysml2Model
    sysml2Model("SimpleAct") {
        val init = initialNode()
        val work = actionDef("DoWork", action = "execute()")
        val fin = finalNode()
        controlFlow("start", init, work)
        controlFlow("done", work, fin)
        actDiagram("Simple Activity") {
            include(init)
            include(work)
            include(fin)
        }
    }
    """.trimIndent()

class RunSessionManagerTest :
    FunSpec({

        // ── 1. Start STM session from UML script ──────────────────────────────

        test("start STM session from UML script") {
            val manager = RunSessionManager()
            val result =
                manager.start(
                    scriptText = UML_STM_SCRIPT,
                    scriptName = "test.kuml.kts",
                    restoreFrom = null,
                    migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
                )
            val ok = result.shouldBeInstanceOf<SessionResult.Ok>()
            ok.activeStates.shouldNotBeEmpty()
            manager.isTerminated shouldBe false
        }

        // ── 2. Start ACT session from SysML2 ACT script ───────────────────────

        test("start ACT session from SysML2 ACT script") {
            val manager = RunSessionManager()
            val result =
                manager.start(
                    scriptText = SYSML2_ACT_SCRIPT,
                    scriptName = "test.kuml.kts",
                    restoreFrom = null,
                    migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
                )
            // ACT session should have active nodes (tokens)
            result.shouldBeInstanceOf<SessionResult.Ok>().activeStates.shouldNotBeEmpty()
        }

        // ── 3. Event triggers transition ──────────────────────────────────────

        test("event triggers transition") {
            val manager = RunSessionManager()
            manager.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            // After start, machine is in Draft
            val result = manager.event("confirm")
            // Vertex IDs have format "ModelName::StateName"
            result.shouldBeInstanceOf<SessionResult.Ok>().activeStates.joinToString(",") shouldContain "Confirmed"
        }

        // ── 4. Event on terminated session returns terminated ──────────────────

        test("event on terminated session returns Terminated") {
            val manager = RunSessionManager()
            manager.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            manager.event("confirm")
            manager.event("deliver") // → Done (final)
            // Now terminated — another event should return Terminated or Error
            val result = manager.event("anything")
            result.shouldBeInstanceOf<SessionResult.Terminated>()
        }

        // ── 5. Patch updates variable ─────────────────────────────────────────

        test("patch updates variable") {
            val manager = RunSessionManager()
            manager.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            val result = manager.patch(mapOf("priority" to 42))
            result.shouldBeInstanceOf<SessionResult.Ok>()
        }

        // ── 6. Snapshot returns current active states ─────────────────────────

        test("snapshot returns current active states") {
            val manager = RunSessionManager()
            manager.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            manager
                .snapshot()
                .shouldBeInstanceOf<SessionResult.Ok>()
                .activeStates
                .shouldNotBeEmpty()
        }

        // ── 7. saveSnapshot writes valid JSON ─────────────────────────────────

        test("saveSnapshot writes valid JSON") {
            val manager = RunSessionManager()
            manager.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            val out = Files.createTempFile("kuml-run-snap-", ".json")
            try {
                manager.saveSnapshot(out)
                val content = out.toFile().readText()
                content shouldContain "modelId"
                content shouldContain "currentVertexIds"
            } finally {
                out.toFile().delete()
            }
        }

        // ── 8. Restore from snapshot with AcceptIfFingerprintMatches succeeds ─

        test("restore from snapshot with AcceptIfFingerprintMatches succeeds") {
            // First, create a snapshot
            val manager1 = RunSessionManager()
            manager1.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            manager1.event("confirm")
            val snapFile = Files.createTempFile("kuml-run-restore-", ".json")
            try {
                manager1.saveSnapshot(snapFile)

                // Restore from that snapshot
                val manager2 = RunSessionManager()
                val result =
                    manager2.start(
                        scriptText = UML_STM_SCRIPT,
                        scriptName = "test.kuml.kts",
                        restoreFrom = snapFile.toFile(),
                        migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
                    )
                // Vertex IDs have format "ModelName::StateName"
                result.shouldBeInstanceOf<SessionResult.Ok>().activeStates.joinToString(",") shouldContain "Confirmed"
            } finally {
                snapFile.toFile().delete()
            }
        }

        // ── 9. Restore with Reject policy and modified model returns error ─────

        test("restore with Reject policy and mismatched snapshot returns migration error") {
            // Create snapshot from a different model (simulate mismatch by creating
            // a snapshot from one model and restoring with a different one)
            val manager1 = RunSessionManager()
            manager1.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            val snapFile = Files.createTempFile("kuml-run-reject-", ".json")
            try {
                manager1.saveSnapshot(snapFile)

                // Attempt to restore with a different model and Reject policy
                val differentScript =
                    """
                    stateDiagram(name = "DifferentMachine") {
                        val init = initialState()
                        val s1 = state(name = "S1")
                        val done = finalState(name = "Done")
                        transition(source = init, target = s1)
                        transition(source = s1, target = done) { trigger = "go" }
                    }
                    """.trimIndent()
                val manager2 = RunSessionManager()
                val result =
                    manager2.start(
                        scriptText = differentScript,
                        scriptName = "test.kuml.kts",
                        restoreFrom = snapFile.toFile(),
                        migrationPolicy = MigrationPolicy.Reject,
                    )
                result.shouldBeInstanceOf<SessionResult.Error>().exitCode shouldBe ExitCodes.RUN_MIGRATION_REJECTED
            } finally {
                snapFile.toFile().delete()
            }
        }

        // ── 10. Stop returns terminated result ────────────────────────────────

        test("stop returns terminated result") {
            val manager = RunSessionManager()
            manager.start(
                scriptText = UML_STM_SCRIPT,
                scriptName = "test.kuml.kts",
                restoreFrom = null,
                migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
            )
            val result = manager.stop()
            result.shouldBeInstanceOf<SessionResult.Terminated>()
            manager.currentSession shouldBe null
        }
    })
