package dev.kuml.render.smil

import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Tests for [TraceFileLoader].
 *
 * V3.1.31 — STM + Activity SMIL Renderers
 */
class TraceFileLoaderTest :
    FunSpec({

        // ── (1) Loads a valid kuml.trace.v1 JSON resource into TraceFile with expected entry count ──

        test("loads valid kuml.trace.v1 JSON from file with expected entry count") {
            val url = TraceFileLoaderTest::class.java.getResource("/dev/kuml/render/smil/sample-trace.json")
            requireNotNull(url) { "sample-trace.json test resource not found" }
            val file = File(url.toURI())

            val result = TraceFileLoader.load(file)

            result.schema shouldBe TraceFile.SCHEMA
            result.modelId shouldBe "test-model"
            result.entries.size shouldBe 3
            val entered = result.entries.filterIsInstance<TraceEntry.StateEntered>()
            entered.size shouldBe 2
            entered[0].vertexId shouldBe "stateA"
            entered[1].vertexId shouldBe "stateB"
        }

        // ── (2) Rejects a file larger than maxBytes ──

        test("rejects a file larger than maxBytes with IllegalArgumentException") {
            val bigFile =
                File.createTempFile("kuml-trace-test", ".json").also {
                    it.writeText("{\"schema\":\"kuml.trace.v1\",\"entries\":[]}")
                    it.deleteOnExit()
                }

            shouldThrow<IllegalArgumentException> {
                TraceFileLoader.load(bigFile, maxBytes = 5L) // very small cap
            }
        }

        // ── (3) Rejects malformed JSON with a clean message that does NOT echo raw file bytes ──

        test("rejects malformed JSON with a clean message that does not echo raw file bytes") {
            val malformed =
                File.createTempFile("kuml-trace-malformed", ".json").also {
                    it.writeText("""{"schema":"kuml.trace.v1","entries": [INVALID_JSON_HERE]}""")
                    it.deleteOnExit()
                }

            val ex =
                shouldThrow<IllegalArgumentException> {
                    TraceFileLoader.load(malformed)
                }

            // Message must not echo raw file content (the invalid tokens)
            ex.message shouldNotContain "INVALID_JSON_HERE"
            // Message must give useful context
            ex.message shouldContain malformed.name
        }

        // ── (4) Rejects wrong schema string ──

        test("rejects wrong schema string with IllegalArgumentException") {
            val wrongSchema =
                File.createTempFile("kuml-trace-wrongschema", ".json").also {
                    it.writeText("""{"schema":"kuml.events.v1","entries":[]}""")
                    it.deleteOnExit()
                }

            val ex =
                shouldThrow<IllegalArgumentException> {
                    TraceFileLoader.load(wrongSchema)
                }

            ex.message shouldContain "kuml.events.v1"
            ex.message shouldContain TraceFile.SCHEMA
        }

        // ── (5) Empty entries list loads to TraceFile with 0 entries ──

        test("empty entries list loads to TraceFile with 0 entries") {
            val empty =
                File.createTempFile("kuml-trace-empty", ".json").also {
                    it.writeText("""{"schema":"kuml.trace.v1","entries":[]}""")
                    it.deleteOnExit()
                }

            val result = TraceFileLoader.load(empty)

            result.schema shouldBe TraceFile.SCHEMA
            result.entries.size shouldBe 0
        }
    })
