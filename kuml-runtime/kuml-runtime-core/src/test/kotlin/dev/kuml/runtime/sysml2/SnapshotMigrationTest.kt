package dev.kuml.runtime.sysml2

import dev.kuml.runtime.KumlRuntimeJson
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.snapshot.MigrationException
import dev.kuml.runtime.snapshot.StateMachineSnapshot
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class SnapshotMigrationTest :
    FunSpec({
        fun timed() =
            sysml2Model(name = "S") {
                val init = stateDef(name = "Init", isInitial = true)
                val on = stateDef(name = "On")
                val oi = stateDef(name = "OI", isInitial = true, parent = on)
                val a = stateDef(name = "A", parent = on)
                val h = history(name = "H", parent = on)
                val off = stateDef(name = "Off")
                transition(name = "t0", source = init, target = on)
                transition(name = "oi", source = oi, target = a)
                transition(name = "a2off", source = on, target = off, timer = after(5.seconds), id = "a2off")
                transition(name = "back", source = off, target = h, trigger = "back")
                stmDiagram(name = "d") {
                    include(init)
                    include(on)
                    include(off)
                }
            }

        test("a v1 snapshot (no A2 fields) restores as a plain one-leaf configuration") {
            val m =
                sysml2Model(name = "F") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "i", source = init, target = a)
                    transition(name = "ab", source = a, target = b, trigger = "go")
                    stmDiagram(name = "d") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val h = m.start()
            val v1 = h.runtime.snapshotFull(h.instance).copy(schemaVersion = 1)
            val text = KumlRuntimeJson.encodeToString(StateMachineSnapshot.serializer(), v1)
            text shouldNotContain "historyMemory"
            text shouldNotContain "armedTimers"
            text shouldNotContain "schemaVersion"
            val decoded = KumlRuntimeJson.decodeFromString(StateMachineSnapshot.serializer(), text)
            decoded.schemaVersion shouldBe 1
            val restored = StateMachineRuntime().restoreFrom(model = h.stateMachine, snapshot = decoded)
            restored.currentVertexIds shouldContainExactly h.instance.currentVertexIds
            StateMachineRuntime().step(
                instance = restored,
                event =
                    dev.kuml.runtime.Event
                        .of("go"),
            )
            restored.currentVertexIds shouldContain "B"
        }

        test("a v2 round trip keeps timers, history and the virtual clock") {
            val h = timed().start(startTime = Instant.parse("2026-03-01T10:00:00Z"))
            h.runtime.advance(instance = h.instance, by = 2.seconds)
            val snap = h.runtime.snapshotFull(h.instance)
            snap.schemaVersion shouldBe 2
            snap.armedTimers.size shouldBe 1
            val text = KumlRuntimeJson.encodeToString(StateMachineSnapshot.serializer(), snap)
            val back = KumlRuntimeJson.decodeFromString(StateMachineSnapshot.serializer(), text)

            val rt = StateMachineRuntime(virtualStart = Instant.parse("2026-03-01T10:00:00Z"))
            val restored = rt.restoreFrom(model = h.stateMachine, snapshot = back)
            rt.nextTimerDue(restored) shouldBe Instant.parse("2026-03-01T10:00:05Z")
            rt.advance(instance = restored, by = 3.seconds).firedTransitionIds shouldContainExactly listOf("a2off")
            restored.currentVertexIds shouldContain "Off"
            rt.step(
                instance = restored,
                event =
                    dev.kuml.runtime.Event
                        .of("back"),
            )
            restored.currentVertexIds shouldContain "A" // history remembered A
        }

        test("a snapshot of a newer schema version is rejected with a clear message") {
            val h = timed().start()
            val snap = h.runtime.snapshotFull(h.instance).copy(schemaVersion = 3)
            val ex = shouldThrow<MigrationException> { StateMachineRuntime().restoreFrom(model = h.stateMachine, snapshot = snap) }
            ex.message!!.contains("schema version 3") shouldBe true
        }
    })
