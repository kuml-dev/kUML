package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.loadTrace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * V2.0.17 — CLI smoke test that proves `kuml simulate` accepts SysML 2
 * scripts and runs them through the [dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter].
 *
 * The traffic-light fixture drives the canonical Red → Green → Yellow → Red
 * cycle plus a terminal `powerOff` event into the `Off` final state. The
 * test asserts on the *state-sequence shape* of the produced trace so the
 * expected behaviour is end-to-end visible without coupling to wall-clock
 * timestamps.
 */
class SimulateCommandSysml2Test :
    FunSpec({

        val script = File("src/test/resources/simulate/sysml2/traffic-light.kuml.kts")
        val events = File("src/test/resources/simulate/sysml2/traffic-light.events.json")

        test("kuml simulate accepts a SysML 2 STM script and produces a trace") {
            val out = Files.createTempFile("kuml-simulate-sysml2-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${script.absolutePath} ${events.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0

                val trace = loadTrace(out).entries
                val stateSequence =
                    trace
                        .filterIsInstance<TraceEntry.StateEntered>()
                        .map { it.vertexId }

                // The INITIAL pseudostate fires its outgoing transition during
                // start() before any state-entered entry is emitted (the
                // runtime treats the initial vertex as transient — see V1.1.5
                // operational semantics). The visible state sequence is
                // therefore Red → Green → Yellow → Red → Off.
                stateSequence shouldContainInOrder
                    listOf("Red", "Green", "Yellow", "Red", "Off")

                val text = out.readText()
                text shouldContain "Terminated"
                text shouldContain "modelId"
            } finally {
                out.delete()
            }
        }
    })
