package dev.kuml.runtime.trace

import dev.kuml.runtime.trace.otlp.OtlpIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch

class OtlpIdsTest :
    FunSpec({

        val hexPattern = Regex("[0-9a-f]+")

        test("traceId is exactly 32 hex chars") {
            val id = OtlpIds.traceId("MyModel")
            id shouldHaveLength 32
            id shouldMatch hexPattern
        }

        test("spanId(seed) is exactly 16 hex chars") {
            val id = OtlpIds.spanId("root:MyModel")
            id shouldHaveLength 16
            id shouldMatch hexPattern
        }

        test("spanId(modelId, vertexId, seqNo) is exactly 16 hex chars") {
            val id = OtlpIds.spanId("MyModel", "StateA", 42L)
            id shouldHaveLength 16
            id shouldMatch hexPattern
        }

        test("IDs are deterministic — same input always produces same output") {
            val t1 = OtlpIds.traceId("MyModel")
            val t2 = OtlpIds.traceId("MyModel")
            t1 shouldBe t2

            val s1 = OtlpIds.spanId("MyModel", "A", 0L)
            val s2 = OtlpIds.spanId("MyModel", "A", 0L)
            s1 shouldBe s2
        }

        test("different inputs produce different IDs (no collisions for basic cases)") {
            OtlpIds.traceId("ModelA") shouldNotBe OtlpIds.traceId("ModelB")
            OtlpIds.spanId("M", "A", 0L) shouldNotBe OtlpIds.spanId("M", "B", 0L)
            OtlpIds.spanId("M", "A", 0L) shouldNotBe OtlpIds.spanId("M", "A", 1L)
        }

        test("fnv1a64 never returns zero") {
            // Regression: fnv1a64 maps 0 → 1 to avoid all-zero span IDs
            for (i in 0..100) {
                val hash = OtlpIds.fnv1a64("seed$i")
                (hash != 0L) shouldBe true
            }
        }
    })
