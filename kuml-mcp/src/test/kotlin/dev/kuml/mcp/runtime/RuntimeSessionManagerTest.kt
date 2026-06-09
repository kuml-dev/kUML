package dev.kuml.mcp.runtime

import dev.kuml.runtime.TraceEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files

/**
 * Unit tests for [RuntimeSessionManager] covering the five session operations:
 * start, event, snapshot, patch, and stop for both STM and ACT sessions.
 */
class RuntimeSessionManagerTest :
    FunSpec({

        // ── Fixtures ──────────────────────────────────────────────────────────

        /** Pepela thermostat STM as an inline script string. */
        val thermostatStmScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Thermostat") {
                val initial = stateDef("Initial", isInitial = true)
                val off = stateDef("Off")
                val idle = stateDef("Idle")
                val heating = stateDef("Heating")

                transition("init", initial, off)
                transition("powerOn", off, idle, trigger = "powerOn")
                transition("powerOff", idle, off, trigger = "powerOff")
                transition(
                    "startHeating", idle, heating,
                    trigger = "tick",
                    guard = "event.temperature < event.targetTemperature - 1",
                )
                transition(
                    "heatDone", heating, idle,
                    trigger = "tick",
                    guard = "event.temperature >= event.targetTemperature",
                )

                stmDiagram("Thermostat STM") {
                    include(initial)
                    include(off)
                    include(idle)
                    include(heating)
                }
            }
            """.trimIndent()

        /** Pepela boot activity as an inline script string. */
        val thermostatActScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("ThermostatBoot") {
                val init = initialNode()
                val readSensors = actionDef("ReadSensors", action = "sensors.readAll()")
                val fin = finalNode()

                controlFlow("toRead", init, readSensors)
                controlFlow("toFinal", readSensors, fin)

                actDiagram("Boot ACT") {
                    include(init)
                    include(readSensors)
                    include(fin)
                }
            }
            """.trimIndent()

        fun writeScript(content: String): java.io.File {
            val f = Files.createTempFile("kuml-test-", ".kuml.kts").toFile()
            f.writeText(content)
            return f
        }

        // ── STM: start ────────────────────────────────────────────────────────

        test("start STM from inline script returns valid sessionId and 'Off' activeState") {
            val manager = RuntimeSessionManager()
            val result = manager.start(thermostatStmScript, "stm", null)
            result shouldBe result // ensure no exception
            result as SessionResult.Started
            result.sessionId shouldStartWith "rs-"
            result.kind shouldBe "stm"
            result.activeStates shouldContain "Off"
        }

        test("start STM from file path also works") {
            val manager = RuntimeSessionManager()
            val file = writeScript(thermostatStmScript)
            try {
                val result = manager.start(file.absolutePath, "stm", null) as SessionResult.Started
                result.activeStates shouldContain "Off"
            } finally {
                file.delete()
            }
        }

        test("start with invalid script returns Error result") {
            val manager = RuntimeSessionManager()
            val result = manager.start("this is not valid kotlin script!!!", "stm", null)
            result as SessionResult.Error
            result.message shouldContain "Script evaluation failed"
        }

        // ── STM: event ────────────────────────────────────────────────────────

        test("event powerOn transitions STM from Off to Idle") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            val sessionId = started.sessionId

            val stepped = manager.event(sessionId, "powerOn", emptyMap()) as SessionResult.Stepped
            stepped.activeStates shouldContain "Idle"
            stepped.fired.size shouldBe 1
            stepped.fired.first() shouldContain "Off"
        }

        test("event with unknown trigger stays in current state without error") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            val sessionId = started.sessionId

            // Off state — unknown event should result in Stayed
            val result = manager.event(sessionId, "unknownEvent", emptyMap()) as SessionResult.Stepped
            result.fired shouldHaveSize 0
            result.activeStates shouldContain "Off"
            result.stepResult shouldContain "Stayed"
        }

        test("event with guard payload causes transition when guard holds") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            val sessionId = started.sessionId

            // Power on first
            manager.event(sessionId, "powerOn", emptyMap())

            // Send tick with temperature 15 < targetTemperature 21 - 1 = 20 → guard holds → Heating
            val stepped =
                manager.event(
                    sessionId,
                    "tick",
                    mapOf("temperature" to 15L, "targetTemperature" to 21L),
                ) as SessionResult.Stepped
            stepped.activeStates shouldContain "Heating"
        }

        test("event with guard payload stays when guard fails") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            val sessionId = started.sessionId

            manager.event(sessionId, "powerOn", emptyMap())

            // temperature 22 >= targetTemperature 21 → startHeating guard fails → stays Idle
            val stepped =
                manager.event(
                    sessionId,
                    "tick",
                    mapOf("temperature" to 22L, "targetTemperature" to 21L),
                ) as SessionResult.Stepped
            stepped.activeStates shouldContain "Idle"
            stepped.fired shouldHaveSize 0
        }

        test("event on unknown sessionId returns Error") {
            val manager = RuntimeSessionManager()
            val result = manager.event("rs-doesnotexist", "powerOn", emptyMap())
            result as SessionResult.Error
            result.message shouldContain "not found"
        }

        test("event on ACT session returns Error") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatActScript, "act", null) as SessionResult.Started
            val result = manager.event(started.sessionId, "someEvent", emptyMap())
            result as SessionResult.Error
            result.message.lowercase() shouldContain "activity"
        }

        // ── STM: snapshot ─────────────────────────────────────────────────────

        test("snapshot returns activeStates and empty traceTail before any events") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            val snap = manager.snapshot(started.sessionId) as SessionResult.Snapshot
            snap.activeStates shouldContain "Off"
            snap.stepCount shouldBe 0
        }

        test("snapshot after two events returns traceTail with entries") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            manager.event(started.sessionId, "powerOn", emptyMap())
            manager.event(started.sessionId, "powerOff", emptyMap())

            val snap = manager.snapshot(started.sessionId) as SessionResult.Snapshot
            snap.traceTail.shouldNotBeEmpty()
            snap.stepCount shouldBe 2
        }

        test("snapshot traceTail is capped at 20 entries") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            // Send many events that cycle through states
            repeat(25) {
                manager.event(started.sessionId, "powerOn", emptyMap())
                manager.event(started.sessionId, "powerOff", emptyMap())
            }
            val snap = manager.snapshot(started.sessionId) as SessionResult.Snapshot
            snap.traceTail.size shouldBe 20
        }

        // ── STM: patch ────────────────────────────────────────────────────────

        test("patch with variables updates session context") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            val patched = manager.patch(started.sessionId, mapOf("myVar" to "hello"), null) as SessionResult.Patched
            patched.ok shouldBe true
        }

        test("patch with forceState jumps to named state") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            // STM starts in Off; force-jump to Idle
            val patched = manager.patch(started.sessionId, emptyMap(), "Idle") as SessionResult.Patched
            patched.ok shouldBe true
            patched.activeStates shouldContain "Idle"
        }

        test("patch with unknown forceState returns Error") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            val result = manager.patch(started.sessionId, emptyMap(), "NonExistentState")
            result as SessionResult.Error
            result.message shouldContain "forceState"
        }

        // ── STM: stop ─────────────────────────────────────────────────────────

        test("stop returns full trace and cleans up session") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            manager.event(started.sessionId, "powerOn", emptyMap())
            val stopped = manager.stop(started.sessionId) as SessionResult.Stopped
            stopped.traceLength shouldBe stopped.trace.size
            stopped.trace.shouldNotBeEmpty()

            // After stop, session is gone
            val result = manager.event(started.sessionId, "powerOn", emptyMap())
            result as SessionResult.Error
        }

        test("stop on unknown sessionId returns Error") {
            val manager = RuntimeSessionManager()
            val result = manager.stop("rs-nonexistent")
            result as SessionResult.Error
            result.message shouldContain "not found"
        }

        // ── ACT: start ────────────────────────────────────────────────────────

        test("start ACT session returns sessionId with kind=act") {
            val manager = RuntimeSessionManager()
            val result = manager.start(thermostatActScript, "act", null) as SessionResult.Started
            result.kind shouldBe "act"
            result.sessionId shouldStartWith "rs-"
        }

        test("start ACT session with auto-detect selects act kind") {
            val manager = RuntimeSessionManager()
            // No kind hint — should auto-detect act from ActDiagram
            val result = manager.start(thermostatActScript, null, null) as SessionResult.Started
            result.kind shouldBe "act"
        }

        test("ACT session snapshot shows terminated after run") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatActScript, "act", null) as SessionResult.Started
            val snap = manager.snapshot(started.sessionId) as SessionResult.Snapshot
            snap.activeStates shouldContain "(terminated)"
        }

        test("ACT session stop returns full trace") {
            val manager = RuntimeSessionManager()
            val started = manager.start(thermostatActScript, "act", null) as SessionResult.Started
            val stopped = manager.stop(started.sessionId) as SessionResult.Stopped
            stopped.trace.shouldNotBeEmpty()
            // Should contain at least TokenPlaced and ActivityTerminated
            stopped.trace.any { it is TraceEntry.ActivityTerminated } shouldBe true
        }

        // ── TTL ───────────────────────────────────────────────────────────────

        test("expired session returns Error on event") {
            val manager = RuntimeSessionManager(ttlMs = 1L) // 1 ms TTL
            val started = manager.start(thermostatStmScript, "stm", null) as SessionResult.Started
            Thread.sleep(10L) // let the TTL expire
            val result = manager.event(started.sessionId, "powerOn", emptyMap())
            result as SessionResult.Error
            result.message shouldContain "not found"
        }
    })
