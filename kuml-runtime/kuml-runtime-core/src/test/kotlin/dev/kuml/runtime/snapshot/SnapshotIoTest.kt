package dev.kuml.runtime.snapshot

import dev.kuml.runtime.Event
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.activity.ActivityInstance
import dev.kuml.runtime.finalState
import dev.kuml.runtime.initial
import dev.kuml.runtime.smOf
import dev.kuml.runtime.state
import dev.kuml.runtime.trans
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * Tests für [SnapshotIo] — Datei-basierte Serialisierung von Snapshots.
 */
class SnapshotIoTest :
    FunSpec({
        val model =
            smOf(
                name = "IoSM",
                vertices =
                    listOf(
                        initial("init"),
                        state("A"),
                        state("B"),
                        finalState("end"),
                    ),
                transitions =
                    listOf(
                        trans("t0", "init", "A"),
                        trans("t1", "A", "B", trigger = "go"),
                        trans("t2", "B", "end", trigger = "done"),
                    ),
            )

        val runtime = StateMachineRuntime()

        fun tempFile(name: String): File = Files.createTempFile("kuml-snap-test-", "-$name").toFile().also { it.deleteOnExit() }

        test("writeSnapshot then readSnapshot is identity") {
            val instance = runtime.start(model)
            runtime.step(instance, Event.of("go"))
            instance.variables["x"] = 99L

            val snap = runtime.snapshotFull(instance)
            val file = tempFile("identity.json")
            writeStateMachineSnapshot(snap, file)

            val restored = readStateMachineSnapshot(file)
            restored shouldBe snap
        }

        test("JSON output is pretty-printed") {
            val instance = runtime.start(model)
            val snap = runtime.snapshotFull(instance)
            val file = tempFile("pretty.json")
            writeStateMachineSnapshot(snap, file)

            val content = file.readText()
            // Pretty-printed JSON contains newlines and indentation
            content shouldContain "\n"
            content shouldContain "  "
        }

        test("schemaVersion=1 is the default on roundtrip") {
            val instance = runtime.start(model)
            val snap = runtime.snapshotFull(instance)
            snap.schemaVersion shouldBe 1
            val file = tempFile("version.json")
            writeStateMachineSnapshot(snap, file)

            // schemaVersion has a default value — readback still yields 1 (via default)
            val restored = readStateMachineSnapshot(file)
            restored.schemaVersion shouldBe 1
        }

        test("ActivityInstanceSnapshot roundtrip via file") {
            val activitySnap =
                ActivityInstanceSnapshot(
                    modelId = "act1",
                    modelFingerprint = "abc123",
                    instance = ActivityInstance(tokenCounts = mapOf("node1" to 2), clock = 5L),
                )
            val file = tempFile("activity-snapshot.json")
            writeActivityInstanceSnapshot(activitySnap, file)

            val restored = readActivityInstanceSnapshot(file)
            restored shouldBe activitySnap
        }
    })
