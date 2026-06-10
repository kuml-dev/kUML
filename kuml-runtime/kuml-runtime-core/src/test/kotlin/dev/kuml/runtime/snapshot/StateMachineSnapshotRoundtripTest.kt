package dev.kuml.runtime.snapshot

import dev.kuml.runtime.Event
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.finalState
import dev.kuml.runtime.initial
import dev.kuml.runtime.smOf
import dev.kuml.runtime.state
import dev.kuml.runtime.trans
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Roundtrip-Tests für StateMachineSnapshot: snapshotFull → restoreFrom muss
 * den Zustand verlustfrei wiederherstellen.
 */
class StateMachineSnapshotRoundtripTest :
    FunSpec({
        val model =
            smOf(
                name = "TestSM",
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

        test("roundtrip preserves currentVertexIds") {
            val instance = runtime.start(model)
            runtime.step(instance, Event.of("go"))
            val snap = runtime.snapshotFull(instance)
            val restored = runtime.restoreFrom(model, snap)
            restored.currentVertices.map { it.id } shouldBe instance.currentVertices.map { it.id }
        }

        test("roundtrip preserves variables") {
            val instance = runtime.start(model)
            instance.variables["score"] = 42L
            instance.variables["active"] = true
            val snap = runtime.snapshotFull(instance)
            val restored = runtime.restoreFrom(model, snap)
            restored.variables["score"] shouldBe 42L
            restored.variables["active"] shouldBe true
        }

        test("roundtrip preserves internal queue") {
            val instance = runtime.start(model)
            // Directly add to internal queue to test preservation
            instance.mutInternalQueue.add(Event.of("queued-event"))
            val snap = runtime.snapshotFull(instance)
            val restored = runtime.restoreFrom(model, snap)
            restored.mutInternalQueue shouldHaveSize 1
            restored.mutInternalQueue.first().name shouldBe "queued-event"
        }

        test("roundtrip preserves trace") {
            val instance = runtime.start(model)
            runtime.step(instance, Event.of("go"))
            val traceSize = instance.mutTrace.size
            val snap = runtime.snapshotFull(instance)
            val restored = runtime.restoreFrom(model, snap)
            restored.mutTrace shouldHaveSize traceSize
        }

        test("roundtrip preserves seqCounter") {
            val instance = runtime.start(model)
            runtime.step(instance, Event.of("go"))
            val snap = runtime.snapshotFull(instance)
            val restored = runtime.restoreFrom(model, snap)
            restored.seqCounter shouldBe snap.seqCounter
        }

        test("roundtrip preserves isTerminated flag") {
            val instance = runtime.start(model)
            runtime.step(instance, Event.of("go"))
            runtime.step(instance, Event.of("done"))
            instance.isTerminated.shouldBeTrue()
            val snap = runtime.snapshotFull(instance)
            val restored = runtime.restoreFrom(model, snap)
            restored.isTerminated.shouldBeTrue()
        }

        test("snapshot of fresh instance") {
            val instance = runtime.start(model)
            val snap = runtime.snapshotFull(instance)
            snap.modelId shouldBe model.id
            snap.schemaVersion shouldBe 1
            snap.isTerminated.shouldBeFalse()
            snap.currentVertexIds.shouldNotBeEmpty()
        }

        test("snapshot of terminated instance") {
            val instance = runtime.start(model)
            runtime.step(instance, Event.of("go"))
            runtime.step(instance, Event.of("done"))
            val snap = runtime.snapshotFull(instance)
            snap.isTerminated.shouldBeTrue()
            snap.modelId shouldBe model.id
        }
    })
